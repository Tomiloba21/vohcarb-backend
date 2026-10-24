package app.vohcarb.catalog.importer

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * One-off import, run with the `import` profile:
 *
 *     ./gradlew bootRun --args='--spring.profiles.active=import --vohcarb.import.file=data/raw/english-wordnet-2025.xml.gz'
 */
@Component
@Profile("import")
class ImportRunner(
    private val importer: WordNetImporter,
    @Value("\${vohcarb.import.file:}") private val file: String,
    @Value("\${vohcarb.import.replace:false}") private val replace: Boolean,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        require(file.isNotBlank()) { "Set vohcarb.import.file to the WordNet .xml or .xml.gz file" }
        importer.importFile(Path.of(file), replace)
    }
}
