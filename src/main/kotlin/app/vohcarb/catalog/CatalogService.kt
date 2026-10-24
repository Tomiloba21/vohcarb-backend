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

    fun getWord(slug: String): WordDetailDto =
        words.findBySlug(slug)?.toDetail() ?: throw notFound("word '$slug'")

    fun searchWords(query: String?, category: String?, page: Int, size: Int): PageResponse<WordSummaryDto> {
        val prefix = query?.trim()?.lowercase().orEmpty()
        val pageable = PageRequest.of(page, size, Sort.by("lemma"))
        val result = if (category == null) {
            words.findByLemmaStartingWith(prefix, pageable)
        } else {
            requireCategory(category)
            words.findByCategoryAndLemmaPrefix(category, prefix, pageable)
        }
        return PageResponse.of(result) { it.toSummary() }
    }

    fun randomWord(category: String?, random: Random = Random.Default): WordDetailDto {
        val total = if (category == null) {
            words.count()
        } else {
            requireCategory(category)
            words.findByCategoryAndLemmaPrefix(category, "", PageRequest.of(0, 1)).totalElements
        }
        if (total == 0L) throw notFound("words")

        // Page size 1, so the page number is the row offset.
        val pick = PageRequest.of(random.nextLong(total).toInt(), 1, Sort.by("id"))
        val word = if (category == null) {
            words.findAll(pick).content.first()
        } else {
            words.findByCategoryAndLemmaPrefix(category, "", pick).content.first()
        }
        return word.toDetail()
    }

    private fun requireCategory(slug: String) {
        categories.findBySlug(slug) ?: throw notFound("category '$slug'")
    }

    private fun notFound(what: String) = ResponseStatusException(HttpStatus.NOT_FOUND, "No $what found")
}
