package app.vohcarb.catalog

import jakarta.persistence.criteria.JoinType
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query

interface WordRepository : JpaRepository<Word, Long>, JpaSpecificationExecutor<Word> {

    fun findBySlug(slug: String): Word?

    fun findAllBySlugIn(slugs: Collection<String>): List<Word>

    @Query("SELECT w.difficulty, COUNT(w) FROM Word w GROUP BY w.difficulty")
    fun countsByDifficulty(): List<Array<Any?>>
}

interface CategoryRepository : JpaRepository<Category, Long> {

    fun findBySlug(slug: String): Category?

    @Query(
        "SELECT c.slug, COUNT(wc.word_id) FROM category c LEFT JOIN word_category wc ON wc.category_id = c.id GROUP BY c.slug",
        nativeQuery = true,
    )
    fun wordCountsBySlug(): List<Array<Any>>
}

/** Optional word filters; null means "don't filter on this". */
data class WordFilter(
    val prefix: String? = null,
    val category: String? = null,
    val difficulty: Int? = null,
    /** Leave out [Word.sensitive] words; set wherever words are picked for the user rather than searched. */
    val excludeSensitive: Boolean = false,
) {

    fun toSpecification(): Specification<Word> {
        val parts = listOfNotNull(
            prefix?.takeIf { it.isNotEmpty() }?.let { p ->
                Specification<Word> { root, _, cb -> cb.like(root.get("lemma"), "$p%") }
            },
            category?.let { slug ->
                Specification<Word> { root, _, cb -> cb.equal(root.join<Word, Category>("categories", JoinType.INNER).get<String>("slug"), slug) }
            },
            difficulty?.let { level ->
                Specification<Word> { root, _, cb -> cb.equal(root.get<Short>("difficulty"), level.toShort()) }
            },
            if (excludeSensitive) Specification<Word> { root, _, cb -> cb.isFalse(root.get("sensitive")) } else null,
        )
        return Specification.allOf(parts)
    }
}
