package app.feddy.sdk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// Reads the resource files themselves: a missing key in one language falls
// back to English silently at runtime, so nothing else would notice.
class StringsTest {
    private val res = File("src/main/res")

    private fun strings(dir: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    @Test
    fun everyLanguageHasEveryKey() {
        val english = strings(File(res, "values"))
        val languages = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!
        // ja ko de fr es pt ru it tr pl in vi th ar, and Chinese as zh, zh-rTW, zh-rHK.
        assertEquals(17, languages.size)
        for (dir in languages) {
            val table = strings(dir)
            assertEquals(dir.name, english.keys, table.keys)
            for ((key, value) in table) {
                assertTrue("${dir.name} $key", value.isNotBlank())
                assertEquals("${dir.name} $key", english.getValue(key).contains("%1\$s"), value.contains("%1\$s"))
            }
        }
    }
}
