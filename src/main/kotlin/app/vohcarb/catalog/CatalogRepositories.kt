package app.vohcarb.catalog

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface WordRepository : JpaRepository<Word, Long> {

    fun findBySlug(slug: String): Word?

    fun findByLemmaStartingWith(prefix: String, pageable: Pageable): Page<Word>

    @Query(
        "SELECT w FROM Word w JOIN w.categories c WHERE c.slug = :category AND w.lemma LIKE CONCAT(:prefix, '%')",
        countQuery = "SELECT COUNT(w) FROM Word w JOIN w.categories c WHERE c.slug = :category AND w.lemma LIKE CONCAT(:prefix, '%')",
    )
    fun findByCategoryAndLemmaPrefix(category: String, prefix: String, pageable: Pageable): Page<Word>
}

interface CategoryRepository : JpaRepository<Category, Long> {

    fun findBySlug(slug: String): Category?

    @Query(
        "SELECT c.slug, COUNT(wc.word_id) FROM category c LEFT JOIN word_category wc ON wc.category_id = c.id GROUP BY c.slug",
        nativeQuery = true,
    )
    fun wordCountsBySlug(): List<Array<Any>>
}
