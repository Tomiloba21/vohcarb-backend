package app.vohcarb.catalog.importer

import app.vohcarb.game.AnswerListImporter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * One-off import, run with the `import` profile. Every step can run on its own; when both are given
 * WordNet goes first, since re-importing it resets the difficulty levels.
 *
 *     ./gradlew bootRun --args='--spring.profiles.active=import
 *         --vohcarb.import.file=data/raw/english-wordnet-2025.xml.gz
 *         --vohcarb.import.frequency-file=data/raw/en_full.txt'
 *
 * The word games' Name and Place lists (README, "Load the game lists"):
 *
 *         --vohcarb.import.countries-file=data/raw/countryInfo.txt
 *         --vohcarb.import.cities-file=data/raw/cities15000.zip
 *         --vohcarb.import.names-files=data/raw/baby-names.csv,data/raw/more-names.txt
 */
@Component
@Profile("import")
class ImportRunner(
    private val wordNet: WordNetImporter,
    private val frequency: FrequencyImporter,
    private val answerLists: AnswerListImporter,
    @Value("\${vohcarb.import.file:}") private val file: String,
    @Value("\${vohcarb.import.frequency-file:}") private val frequencyFile: String,
    @Value("\${vohcarb.import.replace:false}") private val replace: Boolean,
    @Value("\${vohcarb.import.countries-file:}") private val countriesFile: String,
    @Value("\${vohcarb.import.cities-file:}") private val citiesFile: String,
    @Value("\${vohcarb.import.names-files:}") private val namesFiles: String,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        require(listOf(file, frequencyFile, countriesFile, citiesFile, namesFiles).any { it.isNotBlank() }) {
            "Set vohcarb.import.file (WordNet .xml/.xml.gz), vohcarb.import.frequency-file (word frequency list), " +
                "and/or the game lists: vohcarb.import.countries-file, vohcarb.import.cities-file, vohcarb.import.names-files"
        }
        if (file.isNotBlank()) wordNet.importFile(Path.of(file), replace)
        if (frequencyFile.isNotBlank()) frequency.importFile(Path.of(frequencyFile))
        if (countriesFile.isNotBlank() || citiesFile.isNotBlank()) {
            answerLists.importPlaces(countriesFile.ifBlank { null }?.let(Path::of), citiesFile.ifBlank { null }?.let(Path::of))
        }
        if (namesFiles.isNotBlank()) answerLists.importNames(namesFiles.split(',').map { Path.of(it.trim()) })
    }
}
