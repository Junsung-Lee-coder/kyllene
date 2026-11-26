package dev.hermeskotlin.wear

import android.app.Application
import android.content.res.Configuration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fork: the watch reader's quantity strings. Each language has exactly the forms its plural rules use (CLDR: one,
 * many and other for es, fr and pt; one and other for en, de and hu; other alone for zh), with the default's
 * placeholders; and Android picks them as expected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReaderPluralsTest {
    private val plurals = listOf("wear_saved_chats", "wear_reader_unreadable", "wear_reader_partial", "wear_call_ended_silence")
    private val forms = mapOf(
        "values" to setOf("one", "other"),
        "values-de" to setOf("one", "other"),
        "values-hu" to setOf("one", "other"),
        "values-es" to setOf("one", "many", "other"),
        "values-fr" to setOf("one", "many", "other"),
        "values-pt-rBR" to setOf("one", "many", "other"),
        "values-zh-rCN" to setOf("other"),
    )
    private val placeholder = Regex("%\\d\\$[ds]")

    private fun items(dir: String): Map<String, Map<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings.xml"))
        val list = doc.getElementsByTagName("plurals")
        return (0 until list.length).map { list.item(it) as Element }.associate { p ->
            val children = p.getElementsByTagName("item")
            p.getAttribute("name") to (0 until children.length).map { children.item(it) as Element }.associate { it.getAttribute("quantity") to it.textContent }
        }
    }

    @Test
    fun everyLanguageHasExactlyItsFormsWithTheDefaultsPlaceholders() {
        val defaults = items("values")
        for ((dir, expected) in forms) {
            val all = items(dir)
            for (name in plurals) {
                val got = all[name] ?: error("$dir has no $name")
                assertEquals(expected, got.keys, "$dir $name")
                val wanted = placeholder.findAll(defaults[name]!!.getValue("other")).map { it.value }.toSet()
                got.forEach { (quantity, text) -> assertEquals(wanted, placeholder.findAll(text).map { it.value }.toSet(), "$dir $name $quantity") }
            }
        }
        // The partial-message line is a plural now, not a string.
        forms.keys.forEach { dir -> assertTrue("<string name=\"wear_reader_partial\"" !in File("src/main/res/$dir/strings.xml").readText(), dir) }
    }

    private fun resources(locale: Locale) = RuntimeEnvironment.getApplication()
        .createConfigurationContext(Configuration().apply { setLocale(locale) }).resources

    @Test
    fun androidPicksTheFormForTheCount() {
        val en = resources(Locale.ENGLISH)
        assertEquals("Saved 1 of 1 part", en.getQuantityString(R.plurals.wear_reader_partial, 1, 1, 1))
        assertEquals("Saved 1 of 3 parts", en.getQuantityString(R.plurals.wear_reader_partial, 3, 1, 3))
        assertEquals("1 chat · 2 kB", en.getQuantityString(R.plurals.wear_saved_chats, 1, 1, "2 kB"))
        assertEquals("12 chats · 2 kB", en.getQuantityString(R.plurals.wear_saved_chats, 12, 12, "2 kB"))
        for ((locale, many) in listOf(Locale("es"), Locale.FRENCH, Locale("pt", "BR")).zip(listOf("de partes", "de parties", "de partes"))) {
            val res = resources(locale)
            assertTrue(res.getQuantityString(R.plurals.wear_reader_partial, 1_000_000, 2, 1_000_000).contains(many), "$locale many")
            assertTrue(!res.getQuantityString(R.plurals.wear_reader_partial, 3, 2, 3).contains(" de part"), "$locale other")
            assertTrue(res.getQuantityString(R.plurals.wear_saved_chats, 1_000_000, 1_000_000, "1 GB").contains("de chats"), "$locale chats many")
            assertTrue(res.getQuantityString(R.plurals.wear_reader_unreadable, 1_000_000, 1_000_000).startsWith("1000000 de "), "$locale lines many")
        }
        val zh = resources(Locale.SIMPLIFIED_CHINESE)
        assertEquals("已保存 1/1 部分", zh.getQuantityString(R.plurals.wear_reader_partial, 1, 1, 1))
        assertEquals("1 行保存的内容无法读取，已原样保留。", zh.getQuantityString(R.plurals.wear_reader_unreadable, 1, 1))
        assertEquals("3 von 4 Teilen gespeichert", resources(Locale.GERMAN).getQuantityString(R.plurals.wear_reader_partial, 4, 3, 4))
    }
}
