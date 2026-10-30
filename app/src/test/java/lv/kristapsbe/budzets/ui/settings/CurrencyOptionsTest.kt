package lv.kristapsbe.budzets.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/** Robolectric for `android.icu`'s list of currencies in use. */
@RunWith(RobolectricTestRunner::class)
class CurrencyOptionsTest {

    @Test
    fun `names sort in the language's alphabet, not by character code`() {
        val names = currencyOptions(Locale.forLanguageTag("lv-LV")).map { it.name }
        fun first(letter: String) = names.indexOfFirst { it.startsWith(letter) }

        assertTrue(first("Č") in 0 until first("D"))
        assertTrue(first("Š") in 0 until first("T"))
    }
}
