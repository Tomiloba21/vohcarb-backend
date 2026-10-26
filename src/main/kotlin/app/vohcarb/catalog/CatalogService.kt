package app.vohcarb.catalog

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import kotlin.random.Random

@Service
@Transactional(readOnly = true)
class CatalogService(
    private val words: WordRepository,
    private val categories: CategoryRepository,
) {

    fun listCategories(): List<CategoryDto> {
        val counts = categories.wordCountsBySlug().associate { (slug, count) -> slug as String to (count as Number).toLong() }
        return categories.findAll(Sort.by("name")).map {
            CategoryDto(it.slug, it.name, it.description, counts[it.slug] ?: 0)
        }
    }

    fun listLevels(): List<LevelDto> {
        val counts = words.countsByDifficulty().associate { (level, count) -> (level as Number?)?.toInt() to (count as Number).toLong() }
        return Difficulty.entries.map { LevelDto(it.level, it.title, it.description, counts[it.level] ?: 0) }
    }

    fun getWord(slug: String): WordDetailDto =
        words.findBySlug(slug)?.toDetail() ?: throw notFound("word '$slug'")

    fun searchWords(filter: WordFilter, page: Int, size: Int): PageResponse<WordSummaryDto> {
        val result = words.findAll(validated(filter).toSpecification(), PageRequest.of(page, size, Sort.by("lemma")))
        return PageResponse.of(result) { it.toSummary() }
    }

    fun randomWord(filter: WordFilter, random: Random = Random.Default): WordDetailDto {
        val spec = validated(filter).toSpecification()
        val total = words.count(spec)
        if (total == 0L) throw notFound("words")

        // Page size 1, so the page number is the row offset.
        val pick = PageRequest.of(random.nextLong(total).toInt(), 1, Sort.by("id"))
        return words.findAll(spec, pick).content.first().toDetail()
    }

    private fun validated(filter: WordFilter): WordFilter {
        filter.category?.let { categories.findBySlug(it) ?: throw notFound("category '$it'") }
        filter.difficulty?.let {
            Difficulty.of(it) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Difficulty must be 1 to ${Difficulty.entries.size}")
        }
        return filter.copy(prefix = filter.prefix?.trim()?.lowercase())
    }

    private fun notFound(what: String) = ResponseStatusException(HttpStatus.NOT_FOUND, "No $what found")
}
