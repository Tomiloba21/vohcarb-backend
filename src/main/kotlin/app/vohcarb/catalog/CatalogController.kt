package app.vohcarb.catalog

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Catalog", description = "Words, definitions and categories")
class CatalogController(private val catalog: CatalogService) {

    @GetMapping("/categories")
    @Operation(summary = "List all categories with how many words each has")
    fun categories(): List<CategoryDto> = catalog.listCategories()

    @GetMapping("/levels")
    @Operation(summary = "List difficulty levels (1 = everyday … 5 = rare) with how many words each has")
    fun levels(): List<LevelDto> = catalog.listLevels()

    @GetMapping("/words")
    @Operation(summary = "Browse or search words by prefix, optionally within a category and/or level")
    fun words(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) category: String?,
        @Parameter(description = "1 (everyday) to 5 (rare)") @RequestParam(required = false) difficulty: Int?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): PageResponse<WordSummaryDto> = catalog.searchWords(WordFilter(q, category, difficulty), page, size)

    @GetMapping("/random-word")
    @Operation(summary = "A random word with full details, optionally from a category and/or level")
    fun randomWord(
        @RequestParam(required = false) category: String?,
        @Parameter(description = "1 (everyday) to 5 (rare)") @RequestParam(required = false) difficulty: Int?,
    ): WordDetailDto = catalog.randomWord(WordFilter(category = category, difficulty = difficulty, excludeSensitive = true))

    @GetMapping("/words/{slug}")
    @Operation(summary = "One word with pronunciations, senses and examples")
    fun word(@PathVariable slug: String): WordDetailDto = catalog.getWord(slug)
}
