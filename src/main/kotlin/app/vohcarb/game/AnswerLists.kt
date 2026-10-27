package app.vohcarb.game

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/**
 * The lists Name and Place answers are checked against, and what checks Animal, Thing and sprint answers:
 * the word catalog's categories.
 */
@Service
@Transactional(readOnly = true)
class AnswerLists(private val jdbc: NamedParameterJdbcTemplate) {

    /** Which of these normalized answers are known first names. */
    fun knownNames(normalized: Collection<String>): Set<String> = known("game_name", normalized)

    fun knownPlaces(normalized: Collection<String>): Set<String> = known("game_place", normalized)

    private fun known(table: String, normalized: Collection<String>): Set<String> {
        val keys = normalized.filter { it.isNotEmpty() }.toSet()
        if (keys.isEmpty()) return emptySet()
        return jdbc.queryForList("SELECT normalized FROM $table WHERE normalized IN (:keys)", mapOf("keys" to keys), String::class.java)
            .filterNotNull().toSet()
    }

    /**
     * Catalog words in a category, with their level (null = unranked), among [lemmas]. Sensitive words never count.
     * Catalog lemmas are single lower-case words, so a normalized answer is a lemma.
     */
    fun wordsInCategory(category: String, lemmas: Collection<String>): Map<String, Int?> {
        val keys = lemmas.filter { it.isNotEmpty() }.toSet()
        if (keys.isEmpty()) return emptyMap()
        val result = HashMap<String, Int?>()
        jdbc.query(
            """
            SELECT DISTINCT w.lemma, w.difficulty FROM word w
            JOIN word_category wc ON wc.word_id = w.id
            JOIN category c ON c.id = wc.category_id
            WHERE c.slug = :category AND w.lemma IN (:lemmas) AND w.is_sensitive = FALSE
            """.trimIndent(),
            mapOf("category" to category, "lemmas" to keys),
        ) { rs -> result[rs.getString(1)] = rs.getObject(2)?.let { (it as Number).toInt() } }
        return result
    }

    companion object {
        /** Catalog categories that check Animal and Thing answers. */
        const val ANIMALS = "animals"
        const val THINGS = "objects"
    }
}

/**
 * Loads the name and place lists, run with the `import` profile (see ImportRunner). Each run replaces the list
 * it's given.
 *
 * - Places: GeoNames `countryInfo.txt` and `cities15000.zip` (or the .txt inside), CC BY 4.0.
 * - Names: a CSV with a `name` column (e.g. the SSA baby names) or a plain list, one name per line.
 */
@Service
class AnswerListImporter(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun importPlaces(countriesFile: Path?, citiesFile: Path?) {
        val places = LinkedHashMap<String, Place>()
        countriesFile?.let { file -> Files.newInputStream(file).use { readCountries(it).forEach { p -> places.putIfAbsent(p.normalized, p) } } }
        citiesFile?.let { file -> openText(file).use { readCities(it).forEach { p -> places.putIfAbsent(p.normalized, p) } } }
        jdbc.update("DELETE FROM game_place")
        places.values.chunked(BATCH_SIZE).forEach { batch ->
            jdbc.batchUpdate(
                "INSERT INTO game_place (normalized, display, place_type, country) VALUES (?, ?, ?, ?)",
                batch.map { arrayOf(it.normalized, it.display, it.type, it.country) },
            )
        }
        log.info("Imported {} places", places.size)
    }

    @Transactional
    fun importNames(files: List<Path>) {
        val names = LinkedHashMap<String, String>()
        files.forEach { file -> openText(file).use { readNames(it).forEach { name -> names.putIfAbsent(AnswerText.normalize(name), name) } } }
        names.remove("")
        jdbc.update("DELETE FROM game_name")
        names.entries.chunked(BATCH_SIZE).forEach { batch ->
            jdbc.batchUpdate("INSERT INTO game_name (normalized, display) VALUES (?, ?)", batch.map { arrayOf(it.key, it.value) })
        }
        log.info("Imported {} names", names.size)
    }

    data class Place(val normalized: String, val display: String, val type: String, val country: String?)

    companion object {
        private const val BATCH_SIZE = 1_000
        private const val MAX_LENGTH = 64

        /** countryInfo.txt: tab-separated, `#` comments; ISO code in column 0, name in column 4. */
        fun readCountries(input: InputStream): List<Place> = input.bufferedReader().lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .filter { it.size > 4 }
            .mapNotNull { place(it[4], "COUNTRY", it[0]) }
            .toList()

        /** GeoNames city dump: tab-separated; name in column 1, ASCII name in column 2, country code in column 8. */
        fun readCities(input: InputStream): Sequence<Place> = input.bufferedReader().lineSequence()
            .map { it.split('\t') }
            .filter { it.size > 8 }
            .flatMap { listOfNotNull(place(it[1], "CITY", it[8]), place(it[2], "CITY", it[8])) }

        /** A CSV whose header has a `name` column, or a plain list with one name per line. */
        fun readNames(input: InputStream): Sequence<String> {
            val lines = input.bufferedReader().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.iterator()
            if (!lines.hasNext()) return emptySequence()
            val first = lines.next()
            val header = first.split(',').map { it.trim('"').lowercase() }
            val column = header.indexOf("name")
            return if (column >= 0) {
                lines.asSequence().mapNotNull { line -> line.split(',').getOrNull(column)?.trim('"') }
            } else {
                sequenceOf(first) + lines.asSequence()
            }.filter { it.length <= MAX_LENGTH }
        }

        private fun place(name: String, type: String, country: String?): Place? {
            val normalized = AnswerText.normalize(name)
            if (normalized.isEmpty() || name.length > MAX_LENGTH) return null
            return Place(normalized, name, type, country?.takeIf { it.length == 2 })
        }

        /** A text file, or the first .txt inside a .zip. */
        private fun openText(file: Path): InputStream {
            if (!file.toString().endsWith(".zip")) return Files.newInputStream(file)
            val zip = ZipInputStream(Files.newInputStream(file))
            while (true) {
                val entry = zip.nextEntry ?: error("No .txt file in $file")
                if (!entry.isDirectory && (entry.name.endsWith(".txt") || entry.name.endsWith(".csv"))) return zip
            }
        }
    }
}
