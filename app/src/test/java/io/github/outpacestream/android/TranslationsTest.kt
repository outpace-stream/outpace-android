package io.github.outpacestream.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Checks every values-xx/strings.xml against the default English file, so a string added later
 * without translations, or a translation that drops a format argument, fails the build.
 */
class TranslationsTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    /** A translatable entry: a string's text, or a plural's items by quantity. */
    private data class Entry(val texts: Map<String, String>)

    private fun parse(file: File): Pair<Map<String, Entry>, Set<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val entries = LinkedHashMap<String, Entry>()
        val untranslatable = HashSet<String>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            if (el.getAttribute("translatable") == "false") {
                untranslatable += name
                continue
            }
            entries[name] = when (el.tagName) {
                "string" -> Entry(mapOf("" to el.textContent))
                "plurals" -> {
                    val items = el.getElementsByTagName("item")
                    Entry((0 until items.length).associate {
                        val item = items.item(it) as Element
                        item.getAttribute("quantity") to item.textContent
                    })
                }
                else -> continue
            }
        }
        return entries to untranslatable
    }

    /** Format arguments in order of appearance, plus literal %% so a dropped percent sign is caught. */
    private fun formats(text: String): List<String> =
        Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]").findAll(text).map { it.value }.sorted().toList()

    private val locales: List<File> =
        res.listFiles { f -> f.isDirectory && Regex("values-[a-z]{2,3}(-r[A-Z]{2})?").matches(f.name) }!!
            .filter { File(it, "strings.xml").isFile }
            .sortedBy { it.name }

    private val default = parse(File(res, "values/strings.xml"))

    @Test fun everyLanguageIsPresent() {
        assertEquals(
            listOf("values-de", "values-es", "values-fr", "values-it", "values-pt"),
            locales.map { it.name },
        )
    }

    @Test fun keysMatchTheDefaultFile() {
        val (base, untranslatable) = default
        for (dir in locales) {
            val (entries, _) = parse(File(dir, "strings.xml"))
            assertEquals("${dir.name}: missing", emptySet<String>(), base.keys - entries.keys)
            assertEquals("${dir.name}: extra or non-translatable", emptySet<String>(), entries.keys - base.keys)
            assertTrue(untranslatable.none { it in entries })
        }
    }

    @Test fun formatArgumentsMatchTheDefaultFile() {
        val (base, _) = default
        for (dir in locales) {
            val (entries, _) = parse(File(dir, "strings.xml"))
            for ((name, entry) in entries) {
                val reference = base.getValue(name).texts.let { it[""] ?: it.getValue("other") }
                for ((quantity, text) in entry.texts) {
                    assertEquals("${dir.name}/$name $quantity", formats(reference), formats(text))
                }
            }
        }
    }

    @Test fun pluralsKeepTheirCategories() {
        // CLDR categories Android uses for integers; "many" covers large round numbers in these languages.
        val required = mapOf(
            "values-de" to setOf("one", "other"),
            "values-es" to setOf("one", "many", "other"),
            "values-fr" to setOf("one", "many", "other"),
            "values-it" to setOf("one", "many", "other"),
            "values-pt" to setOf("one", "many", "other"),
        )
        for (dir in locales) {
            val (entries, _) = parse(File(dir, "strings.xml"))
            for ((name, entry) in entries) {
                if ("" in entry.texts) continue
                assertEquals("${dir.name}/$name", required.getValue(dir.name), entry.texts.keys)
            }
        }
    }

    @Test fun apostrophesAndQuotesAreEscaped() {
        for (dir in locales) {
            File(dir, "strings.xml").readLines().forEachIndexed { i, line ->
                val text = line.substringAfter('>', "").substringBeforeLast('<', "")
                assertTrue("${dir.name}:${i + 1} unescaped ' or \"", !Regex("(?<!\\\\)['\"]").containsMatchIn(text))
            }
        }
    }

    @Test fun localeConfigListsEveryLanguage() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "xml/locales_config.xml"))
        val nodes = doc.getElementsByTagName("locale")
        val declared = (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:name") }
        val expected = listOf("en") + locales.map { it.name.removePrefix("values-").replace("-r", "-") }
        assertEquals(expected.sorted(), declared.sorted())
    }
}
