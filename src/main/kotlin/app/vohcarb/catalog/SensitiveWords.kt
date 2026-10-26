package app.vohcarb.catalog

/**
 * Words with a sense WordNet labels as a slur or obscenity. They stay searchable (it's a dictionary) but are kept
 * out of anything that picks words for the user: the feed, the random word and quiz distractors.
 */
object SensitiveWords {

    /** Phrases WordNet uses to label such senses; matched against lower-cased definitions. */
    val LABELS = listOf(
        "offensive term", "offensive name", "offensive word", "offensive slang", "ethnic slur",
        "obscene term", "obscene word", "obscene slang", "vulgar term", "vulgar word", "vulgar slang",
        "derogatory term", "disparaging term",
        "slang for sexual", "slang for fellatio", "slang for cunnilingus", "slang for a penis", "slang for vagina",
        "slang for masturbat",
    )

    /** Marks every word having at least one labelled sense. Run after each import (and once by migration V7). */
    val MARK_SQL = "UPDATE word SET is_sensitive = TRUE WHERE id IN (SELECT word_id FROM sense WHERE " +
        LABELS.joinToString(" OR ") { "LOWER(definition) LIKE '%$it%'" } + ")"
}
