package app.vohcarb.catalog

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable

// Catalog rows are written in bulk by the importer, so the entities are read-only.

@Entity
@Immutable
@Table(name = "category")
class Category(
    @Id val id: Long,
    val slug: String,
    val name: String,
    val description: String?,
)

@Entity
@Immutable
@Table(name = "word")
class Word(
    @Id val id: Long,
    val lemma: String,
    val slug: String,
    val difficulty: Short?,
    val source: String,

    @OneToMany(mappedBy = "word", fetch = FetchType.LAZY)
    @OrderBy("sortOrder")
    val senses: List<Sense> = emptyList(),

    @OneToMany(mappedBy = "word", fetch = FetchType.LAZY)
    val pronunciations: List<Pronunciation> = emptyList(),

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "word_category",
        joinColumns = [JoinColumn(name = "word_id")],
        inverseJoinColumns = [JoinColumn(name = "category_id")],
    )
    @OrderBy("name")
    val categories: List<Category> = emptyList(),
)

@Entity
@Immutable
@Table(name = "sense")
class Sense(
    @Id val id: Long,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "word_id")
    val word: Word,

    val sortOrder: Int,
    val partOfSpeech: String,
    val definition: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    val category: Category?,

    val sourceRef: String?,

    @OneToMany(mappedBy = "sense", fetch = FetchType.LAZY)
    @OrderBy("sortOrder")
    val examples: List<Example> = emptyList(),
)

@Entity
@Immutable
@Table(name = "example")
class Example(
    @Id val id: Long,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sense_id")
    val sense: Sense,

    val sortOrder: Int,
    val sentence: String,
    @Column(name = "source") val source: String?,
)

@Entity
@Immutable
@Table(name = "pronunciation")
class Pronunciation(
    @Id val id: Long,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "word_id")
    val word: Word,

    val ipa: String,
    val variety: String?,
)
