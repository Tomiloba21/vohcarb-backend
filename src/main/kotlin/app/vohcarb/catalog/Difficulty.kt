package app.vohcarb.catalog

/**
 * Word levels, derived from the word's rank in the frequency list (1 = most common).
 * Cut-offs were picked from how the WordNet catalog spreads over the OpenSubtitles 2018 list.
 */
enum class Difficulty(val level: Int, val title: String, val description: String, val maxRank: Int?) {
    EVERYDAY(1, "Everyday", "Words you use every day", 5_000),
    COMMON(2, "Common", "Well-known words", 20_000),
    INTERMEDIATE(3, "Intermediate", "Words that sharpen your writing", 50_000),
    ADVANCED(4, "Advanced", "Words that impress", 150_000),
    RARE(5, "Rare", "Obscure and specialist words", null);

    companion object {
        fun forRank(rank: Int?): Difficulty =
            if (rank == null) RARE else entries.first { it.maxRank == null || rank <= it.maxRank }

        fun of(level: Int): Difficulty? = entries.firstOrNull { it.level == level }
    }
}
