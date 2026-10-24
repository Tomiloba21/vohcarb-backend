package app.vohcarb.catalog.importer

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

data class ImportStats(val words: Int, val senses: Int, val examples: Int, val pronunciations: Int)

/**
 * Loads Open English WordNet into the catalog tables.
 *
 * Only single, lowercase words are kept (no proper nouns, no multi-word phrases) since those make poor
 * "word of the day" material. IDs are assigned here, in alphabetical order, so the whole catalog is
 * written with plain batch inserts.
 */
@Service
class WordNetImporter(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun importFile(file: Path, replace: Boolean): ImportStats {
        val raw = BufferedInputStream(Files.newInputStream(file))
        val input = if (file.fileName.toString().endsWith(".gz")) GZIPInputStream(raw) else raw
        return input.use { importStream(it, replace) }
    }

    @Transactional
    fun importStream(input: InputStream, replace: Boolean): ImportStats {
        val existing = jdbc.queryForObject("SELECT COUNT(*) FROM word", Long::class.java) ?: 0
        if (existing > 0) {
            check(replace) { "Catalog already has $existing words; run with vohcarb.import.replace=true to replace it" }
            // TODO: once user progress references words, switch to upserting by slug instead of wiping.
            for (table in listOf("example", "sense", "pronunciation", "word_category", "word")) {
                jdbc.update("DELETE FROM $table")
            }
        }

        val data = WordNetReader.read(input, ::isCatalogWord)
        log.info("Read {} entries and {} synsets", data.entries.size, data.synsets.size)

        val categoryIds = jdbc.query("SELECT slug, id FROM category") { rs, _ -> rs.getString(1) to rs.getLong(2) }.toMap()

        val wordRows = mutableListOf<Array<Any?>>()
        val senseRows = mutableListOf<Array<Any?>>()
        val exampleRows = mutableListOf<Array<Any?>>()
        val pronunciationRows = mutableListOf<Array<Any?>>()
        val wordCategoryRows = mutableListOf<Array<Any?>>()
        val usedSlugs = HashSet<String>()
        var wordId = 0L
        var senseId = 0L
        var exampleId = 0L
        var pronunciationId = 0L

        for ((lemma, entries) in data.entries.groupBy { it.lemma }.toSortedMap()) {
            val senses = entries.flatMap { entry ->
                entry.synsetIds.distinct().mapNotNull { id -> data.synsets[id]?.takeIf { it.definition != null } }
            }.distinctBy { it.id }
            if (senses.isEmpty()) continue

            wordId++
            wordRows += arrayOf(wordId, lemma, uniqueSlug(lemma, usedSlugs), null, SOURCE)

            val wordCategories = LinkedHashSet<Long>()
            senses.forEachIndexed { index, synset ->
                senseId++
                val categoryId = LEXFILE_CATEGORIES[synset.lexfile]?.let { categoryIds[it] }
                categoryId?.let { wordCategories += it }
                senseRows += arrayOf(senseId, wordId, index, partOfSpeech(synset.partOfSpeech), synset.definition, categoryId, synset.id)
                synset.examples.forEachIndexed { exampleIndex, example ->
                    exampleId++
                    exampleRows += arrayOf(exampleId, senseId, exampleIndex, example.text, example.source)
                }
            }
            wordCategories.forEach { wordCategoryRows += arrayOf(wordId, it) }

            entries.flatMap { it.pronunciations }.distinct().forEach {
                pronunciationId++
                pronunciationRows += arrayOf(pronunciationId, wordId, it.ipa, it.variety)
            }
        }

        insert("INSERT INTO word (id, lemma, slug, difficulty, source) VALUES (?, ?, ?, ?, ?)", wordRows)
        insert(
            "INSERT INTO sense (id, word_id, sort_order, part_of_speech, definition, category_id, source_ref) VALUES (?, ?, ?, ?, ?, ?, ?)",
            senseRows,
        )
        insert("INSERT INTO example (id, sense_id, sort_order, sentence, source) VALUES (?, ?, ?, ?, ?)", exampleRows)
        insert("INSERT INTO pronunciation (id, word_id, ipa, variety) VALUES (?, ?, ?, ?)", pronunciationRows)
        insert("INSERT INTO word_category (word_id, category_id) VALUES (?, ?)", wordCategoryRows)

        return ImportStats(wordRows.size, senseRows.size, exampleRows.size, pronunciationRows.size)
            .also { log.info("Imported {}", it) }
    }

    private fun insert(sql: String, rows: List<Array<Any?>>) {
        rows.chunked(BATCH_SIZE).forEach { jdbc.batchUpdate(sql, it) }
    }

    companion object {
        const val SOURCE = "oewn"
        private const val BATCH_SIZE = 1_000
        private val CATALOG_WORD = Regex("[a-z][a-z'-]*[a-z]")

        fun isCatalogWord(lemma: String) = lemma.length >= 3 && CATALOG_WORD.matches(lemma)

        fun partOfSpeech(code: String) = when (code) {
            "n" -> "noun"
            "v" -> "verb"
            "a", "s" -> "adjective"
            "r" -> "adverb"
            else -> "other"
        }

        fun uniqueSlug(lemma: String, used: MutableSet<String>): String {
            val base = lemma.replace("'", "").replace(Regex("[^a-z0-9]+"), "-").trim('-')
            var slug = base
            var n = 2
            while (!used.add(slug)) slug = "$base-${n++}"
            return slug
        }

        // WordNet lexicographer files → category slugs from V2__catalog.sql. Broad files (adj.all, adv.all, ...) get none.
        val LEXFILE_CATEGORIES = mapOf(
            "noun.act" to "actions",
            "noun.animal" to "animals",
            "noun.artifact" to "objects",
            "noun.attribute" to "qualities",
            "noun.body" to "body",
            "noun.cognition" to "mind",
            "noun.communication" to "communication",
            "noun.event" to "events",
            "noun.feeling" to "feelings",
            "noun.food" to "food",
            "noun.group" to "groups",
            "noun.location" to "places",
            "noun.motive" to "motives",
            "noun.object" to "nature",
            "noun.person" to "people",
            "noun.phenomenon" to "phenomena",
            "noun.plant" to "plants",
            "noun.possession" to "possessions",
            "noun.process" to "processes",
            "noun.quantity" to "quantities",
            "noun.relation" to "relations",
            "noun.shape" to "shapes",
            "noun.state" to "states",
            "noun.substance" to "substances",
            "noun.time" to "time",
            "verb.body" to "body",
            "verb.change" to "change",
            "verb.cognition" to "mind",
            "verb.communication" to "communication",
            "verb.competition" to "competition",
            "verb.consumption" to "food",
            "verb.contact" to "contact",
            "verb.creation" to "creation",
            "verb.emotion" to "feelings",
            "verb.motion" to "movement",
            "verb.perception" to "perception",
            "verb.possession" to "possessions",
            "verb.social" to "society",
            "verb.weather" to "phenomena",
        )
    }
}
