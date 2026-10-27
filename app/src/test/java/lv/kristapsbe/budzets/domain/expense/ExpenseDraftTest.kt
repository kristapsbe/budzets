package lv.kristapsbe.budzets.domain.expense

import lv.kristapsbe.budzets.domain.FOOD
import lv.kristapsbe.budzets.domain.calculation.AmountError
import lv.kristapsbe.budzets.domain.err
import lv.kristapsbe.budzets.domain.eur
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.ok
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExpenseDraftTest {
    private val today = LocalDate.of(2026, 4, 15)
    private val eurSpec = CurrencySpec.DEFAULT

    private fun draft(amount: String = "12,50", date: LocalDate = today, note: String = "") =
        ExpenseDraft(amount, FOOD, date, note)

    @Test
    fun `valid draft becomes a saveable expense`() {
        val valid = draft(note = "  bread ").validate(eurSpec, today).ok()
        assertEquals(eur(12, 50), valid.amount)
        assertEquals(FOOD, valid.categoryId)
        assertEquals("bread", valid.note)
        assertNull(draft(note = "   ").validate(eurSpec, today).ok().note)
    }

    @Test
    fun `amount must be positive and respect currency precision`() {
        assertEquals(AmountError.EMPTY, draft("").validate(eurSpec, today).err().amount)
        assertEquals(AmountError.NOT_POSITIVE, draft("0").validate(eurSpec, today).err().amount)
        assertEquals(AmountError.TOO_MANY_DECIMALS, draft("1.234").validate(eurSpec, today).err().amount)
        assertEquals(Money(5), draft("5").validate(CurrencySpec.of("JPY"), today).ok().amount)
        assertEquals(AmountError.TOO_MANY_DECIMALS, draft("5.5").validate(CurrencySpec.of("JPY"), today).err().amount)
    }

    @Test
    fun `category is required`() {
        val errors = ExpenseDraft("3", null, today, "").validate(eurSpec, today).err()
        assertTrue(errors.missingCategory)
        assertNull(errors.amount)
    }

    @Test
    fun `today and past dates are fine, future dates are refused`() {
        draft(date = today).validate(eurSpec, today).ok()
        draft(date = LocalDate.of(2025, 12, 31)).validate(eurSpec, today).ok()
        assertTrue(draft(date = today.plusDays(1)).validate(eurSpec, today).err().futureDate)
    }

    @Test
    fun `all problems are reported together`() {
        val errors = ExpenseDraft("", null, today.plusDays(1), "").validate(eurSpec, today).err()
        assertEquals(DraftErrors(AmountError.EMPTY, missingCategory = true, futureDate = true), errors)
    }
}
