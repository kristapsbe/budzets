package lv.kristapsbe.budzets

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Which devices get Latvian, and that no translation can fail when its arguments are filled in. */
@RunWith(RobolectricTestRunner::class)
class LatvianResourcesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun resources(tag: String): Resources =
        context.createConfigurationContext(Configuration().apply { setLocale(Locale.forLanguageTag(tag)) }).resources

    private fun String.placeholders(): Set<String> =
        Regex("""%(\d+\$)?[a-z]""").findAll(this).map { it.value }.toSet()

    @Test
    @Config(qualifiers = "lv-rLV")
    fun `a device set to Latvian gets Latvian`() {
        assertEquals("Pārskats", context.getString(R.string.nav_overview))
    }

    @Test
    @Config(qualifiers = "lv")
    fun `Latvian needs no particular region`() {
        assertEquals("Pārskats", context.getString(R.string.nav_overview))
    }

    @Test
    @Config(qualifiers = "de-rDE")
    fun `any other language gets English`() {
        assertEquals("Overview", context.getString(R.string.nav_overview))
    }

    @Test
    fun `every string keeps the English placeholders`() {
        val en = resources("en-US")
        val lv = resources("lv-LV")
        for (field in R.string::class.java.fields) {
            val id = field.getInt(null)
            assertEquals(field.name, en.getString(id).placeholders(), lv.getString(id).placeholders())
        }
    }

    /** Lint compares against English, which has no `zero` item. */
    @Test
    fun `every plural quantity keeps the English placeholders`() {
        val en = resources("en-US")
        val lv = resources("lv-LV")
        for (field in R.plurals::class.java.fields) {
            val id = field.getInt(null)
            val expected = en.getQuantityString(id, 2).placeholders()
            for (n in listOf(0, 1, 2)) assertEquals("${field.name} for $n", expected, lv.getQuantityString(id, n).placeholders())
        }
    }

    @Test
    fun `plurals follow Latvian number agreement`() {
        val lv = resources("lv-LV")
        fun expenses(n: Int) = lv.getQuantityString(R.plurals.expense_count, n, n)
        for (n in listOf(1, 21, 31)) assertEquals("$n izdevums", expenses(n))
        for (n in listOf(0, 10, 11, 19, 30)) assertEquals("$n izdevumu", expenses(n))
        for (n in listOf(2, 22)) assertEquals("$n izdevumi", expenses(n))
    }
}
