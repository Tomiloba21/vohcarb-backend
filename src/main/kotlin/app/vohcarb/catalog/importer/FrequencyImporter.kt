package app.vohcarb.catalog.importer

import app.vohcarb.catalog.Difficulty
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Sets word.frequency_rank and word.difficulty from a frequency list in the FrequencyWords format
 * (https://github.com/hermitdave/FrequencyWords): one `word count` pair per line, most frequent first.
 * A word's rank is its line number. Words missing from the list become [Difficulty.RARE].
 *
 * Run it after every WordNet import, which recreates the word rows.
 */
@Service
class FrequencyImporter(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun importFile(file: Path): Map<Difficulty, Int> = Files.newInputStream(file).use { importStream(it) }

    @Transactional
    fun importStream(input: InputStream): Map<Difficulty, Int> {
        val wordIds = HashMap<String, Long>()
        jdbc.query("SELECT id, lemma FROM word", RowCallbackHandler { rs -> wordIds[rs.getString(2)] = rs.getLong(1) })

        // Only catalog words are kept in memory; the full list has over a million lines.
        val ranks = HashMap<Long, Int>(wordIds.size)
        input.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, line ->
                val word = line.substringBefore(' ').lowercase()
                val id = wordIds[word]
                if (id != null) ranks.putIfAbsent(id, index + 1)
            }
        }

        val counts = sortedMapOf<Difficulty, Int>()
        val rows = wordIds.values.map { id ->
            val rank = ranks[id]
            val difficulty = Difficulty.forRank(rank)
            counts.merge(difficulty, 1, Int::plus)
            arrayOf<Any?>(rank, difficulty.level, id)
        }
        rows.chunked(BATCH_SIZE).forEach { jdbc.batchUpdate("UPDATE word SET frequency_rank = ?, difficulty = ? WHERE id = ?", it) }

        log.info("Ranked {} of {} words; per level: {}", ranks.size, wordIds.size, counts)
        return counts
    }

    private companion object {
        const val BATCH_SIZE = 1_000
    }
}
