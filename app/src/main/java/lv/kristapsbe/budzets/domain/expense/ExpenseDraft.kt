package lv.kristapsbe.budzets.domain.expense

import lv.kristapsbe.budzets.domain.calculation.AmountError
import lv.kristapsbe.budzets.domain.calculation.AmountParser
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.CurrencySpec
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.Outcome
import java.time.LocalDate

/** What the expense form holds before validation. */
data class ExpenseDraft(
    val amountText: String,
    val categoryId: CategoryId?,
    val date: LocalDate,
    val note: String,
)

/** A draft that passed validation and can be saved. */
data class ValidExpense(val amount: Money, val categoryId: CategoryId, val date: LocalDate, val note: String?)

data class DraftErrors(
    val amount: AmountError? = null,
    val missingCategory: Boolean = false,
    /** Expenses record purchases already made, so dates after today are refused (Phase 4 review). */
    val futureDate: Boolean = false,
) {
    val isEmpty: Boolean get() = amount == null && !missingCategory && !futureDate
}

fun ExpenseDraft.validate(currency: CurrencySpec, today: LocalDate): Outcome<ValidExpense, DraftErrors> {
    val amount = AmountParser.parse(amountText, currency)
    val errors = DraftErrors(
        amount = (amount as? Outcome.Err)?.error,
        missingCategory = categoryId == null,
        futureDate = date.isAfter(today),
    )
    if (!errors.isEmpty) return Outcome.Err(errors)
    return Outcome.Ok(
        ValidExpense((amount as Outcome.Ok).value, categoryId!!, date, note.trim().takeIf { it.isNotEmpty() }),
    )
}
