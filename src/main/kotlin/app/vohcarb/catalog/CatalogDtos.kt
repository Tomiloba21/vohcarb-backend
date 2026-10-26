package app.vohcarb.catalog

import org.springframework.data.domain.Page

data class CategoryDto(val slug: String, val name: String, val description: String?, val wordCount: Long)

data class CategoryRef(val slug: String, val name: String)

data class LevelDto(val level: Int, val name: String, val description: String, val wordCount: Long)

data class PronunciationDto(val ipa: String, val variety: String?)

data class ExampleDto(val sentence: String, val source: String?)

data class SenseDto(
    val partOfSpeech: String,
    val definition: String,
    val category: String?,
    val examples: List<ExampleDto>,
)

data class WordSummaryDto(
    val slug: String,
    val lemma: String,
    val difficulty: Int?,
    val partsOfSpeech: List<String>,
    val shortDefinition: String?,
)

data class WordDetailDto(
    val slug: String,
    val lemma: String,
    val difficulty: Int?,
    val pronunciations: List<PronunciationDto>,
    val senses: List<SenseDto>,
    val categories: List<CategoryRef>,
)

data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
) {
    companion object {
        fun <E : Any, T> of(page: Page<E>, map: (E) -> T) = PageResponse(
            items = page.content.map(map),
            page = page.number,
            size = page.size,
            totalItems = page.totalElements,
            totalPages = page.totalPages,
        )
    }
}

fun Word.toSummary() = WordSummaryDto(
    slug = slug,
    lemma = lemma,
    difficulty = difficulty?.toInt(),
    partsOfSpeech = senses.map { it.partOfSpeech }.distinct(),
    shortDefinition = senses.firstOrNull()?.definition,
)

fun Word.toDetail() = WordDetailDto(
    slug = slug,
    lemma = lemma,
    difficulty = difficulty?.toInt(),
    pronunciations = pronunciations.map { PronunciationDto(it.ipa, it.variety) },
    senses = senses.map { sense ->
        SenseDto(
            partOfSpeech = sense.partOfSpeech,
            definition = sense.definition,
            category = sense.category?.slug,
            examples = sense.examples.map { ExampleDto(it.sentence, it.source) },
        )
    },
    categories = categories.map { CategoryRef(it.slug, it.name) },
)
