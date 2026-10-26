package app.vohcarb.catalog.importer

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * One-off import, run with the `import` profile. Either step can run on its own; when both are given
 * WordNet goes first, since re-importing it resets the difficulty levels.
 *
 *     ./gradlew bootRun --args='--spring.profiles.active=import
 *         --vohcarb.import.file=data/raw/english-wordnet-2025.xml.gz
 *         --vohcarb.import.frequency-file=data/raw/en_full.txt'
 */
@Component
@Profile("import")
class ImportRunner(
    private val wordNet: WordNetImporter,
    private val frequency: FrequencyImporter,
    @Value("\${vohcarb.import.file:}") private val file: String,
    @Value("\${vohcarb.import.frequency-file:}") private val frequencyFile: String,
    @Value("\${vohcarb.import.replace:false}") private val replace: Boolean,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        require(file.isNotBlank() || frequencyFile.isNotBlank()) {
            "Set vohcarb.import.file (WordNet .xml/.xml.gz) and/or vohcarb.import.frequency-file (word frequency list)"
        }
        if (file.isNotBlank()) wordNet.importFile(Path.of(file), replace)
        if (frequencyFile.isNotBlank()) frequency.importFile(Path.of(frequencyFile))
    }
}
