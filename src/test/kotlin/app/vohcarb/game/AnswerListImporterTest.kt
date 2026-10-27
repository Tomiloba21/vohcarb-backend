package app.vohcarb.game

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AnswerListImporterTest {

    @Test
    fun `reads countries from GeoNames countryInfo, skipping comments`() {
        val text = """
            #ISO	ISO3	ISO-Numeric	fips	Country	Capital
            BJ	BEN	204	BN	Benin	Porto-Novo
            NG	NGA	566	NI	Nigeria	Abuja
        """.trimIndent()
        val places = AnswerListImporter.readCountries(text.byteInputStream())
        assertEquals(listOf("benin" to "BJ", "nigeria" to "NG"), places.map { it.normalized to it.country })
    }

    @Test
    fun `reads cities with both their name and ASCII name`() {
        val line = listOf("2332453", "Lagos", "Lagos", "Eko", "6.45", "3.39", "P", "PPLA", "NG").joinToString("\t")
        val accented = listOf("3448439", "São Paulo", "Sao Paulo", "", "0", "0", "P", "PPLA", "BR").joinToString("\t")
        val places = AnswerListImporter.readCities("$line\n$accented".byteInputStream()).toList()
        assertEquals(listOf("lagos", "lagos", "saopaulo", "saopaulo"), places.map { it.normalized })
        assertEquals("São Paulo", places[2].display)
    }

    @Test
    fun `reads names from a CSV with a name column or from a plain list`() {
        val csv = "\"year\",\"name\",\"percent\",\"sex\"\n1880,\"John\",0.08,\"boy\"\n1880,\"Mary\",0.07,\"girl\""
        assertEquals(listOf("John", "Mary"), AnswerListImporter.readNames(csv.byteInputStream()).toList())
        assertEquals(listOf("Tobiloba", "Adaeze"), AnswerListImporter.readNames("Tobiloba\n\nAdaeze\n".byteInputStream()).toList())
    }
}
