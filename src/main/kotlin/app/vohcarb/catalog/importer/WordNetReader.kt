package app.vohcarb.catalog.importer

import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

data class WnPronunciation(val ipa: String, val variety: String?)

data class WnEntry(
    val lemma: String,
    val partOfSpeech: String,
    val pronunciations: List<WnPronunciation>,
    val synsetIds: List<String>,
)

data class WnExample(val text: String, val source: String?)

data class WnSynset(
    val id: String,
    val partOfSpeech: String,
    val lexfile: String?,
    val definition: String?,
    val examples: List<WnExample>,
)

class WordNetData(val entries: List<WnEntry>, val synsets: Map<String, WnSynset>)

/**
 * Streams a WN-LMF file (the XML format Open English WordNet publishes) and keeps only what the catalog needs.
 * Entries come before synsets in the file, so both are collected and joined afterwards by the importer.
 */
object WordNetReader {

    private val factory: XMLInputFactory = XMLInputFactory.newFactory().apply {
        // The file declares a remote DTD; never fetch it, and never resolve external entities.
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }

    fun read(input: InputStream, keepLemma: (String) -> Boolean): WordNetData {
        val entries = mutableListOf<WnEntry>()
        val synsets = HashMap<String, WnSynset>(128_000)

        var lemma: String? = null
        var entryPos = ""
        var pronunciations = mutableListOf<WnPronunciation>()
        var senseSynsets = mutableListOf<String>()
        var inEntry = false

        var synsetId: String? = null
        var synsetPos = ""
        var lexfile: String? = null
        var definition: String? = null
        var examples = mutableListOf<WnExample>()

        val r = factory.createXMLStreamReader(input)
        try {
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "LexicalEntry" -> {
                            inEntry = true
                            lemma = null
                            pronunciations = mutableListOf()
                            senseSynsets = mutableListOf()
                        }
                        "Lemma" -> {
                            lemma = r.attr("writtenForm")
                            entryPos = r.attr("partOfSpeech").orEmpty()
                        }
                        "Pronunciation" -> {
                            val variety = r.attr("variety")
                            val ipa = r.elementText.trim()
                            if (ipa.isNotEmpty()) pronunciations += WnPronunciation(ipa, variety)
                        }
                        "Sense" -> if (inEntry) r.attr("synset")?.let { senseSynsets += it }
                        "Synset" -> {
                            synsetId = r.attr("id")
                            synsetPos = r.attr("partOfSpeech").orEmpty()
                            lexfile = r.attr("lexfile")
                            definition = null
                            examples = mutableListOf()
                        }
                        "Definition" -> {
                            val text = r.elementText.trim()
                            if (definition == null && text.isNotEmpty()) definition = text
                        }
                        "Example" -> {
                            val source = r.attr("source")
                            val text = r.elementText.trim()
                            if (text.isNotEmpty()) examples += WnExample(text, source)
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "LexicalEntry" -> {
                            val l = lemma
                            if (l != null && keepLemma(l)) {
                                entries += WnEntry(l, entryPos, pronunciations, senseSynsets)
                            }
                            inEntry = false
                        }
                        "Synset" -> {
                            val id = synsetId
                            if (id != null) synsets[id] = WnSynset(id, synsetPos, lexfile, definition, examples)
                            synsetId = null
                        }
                    }
                }
            }
        } finally {
            r.close()
        }
        return WordNetData(entries, synsets)
    }

    /** Attribute by local name, ignoring namespace (examples use `dc:source`). */
    private fun XMLStreamReader.attr(name: String): String? =
        (0 until attributeCount).firstOrNull { getAttributeLocalName(it) == name }?.let { getAttributeValue(it) }
}
