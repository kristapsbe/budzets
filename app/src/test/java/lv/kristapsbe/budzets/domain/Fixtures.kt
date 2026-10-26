package lv.kristapsbe.budzets.domain

import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.Money
import lv.kristapsbe.budzets.domain.model.Outcome
import lv.kristapsbe.budzets.domain.model.SurplusPocket
import org.junit.Assert.fail
import java.time.Instant
import java.time.YearMonth

val FOOD = CategoryId("food")
val FUN = CategoryId("fun")
val TRAVEL = CategoryId("travel")
val FOOD_POCKET = SurplusPocket.Category(FOOD)
val FUN_POCKET = SurplusPocket.Category(FUN)
val TRAVEL_POCKET = SurplusPocket.Category(TRAVEL)

fun eur(units: Long, cents: Long = 0) = Money(units * 100 + cents)
fun ym(text: String): YearMonth = YearMonth.parse(text)

/** Deterministic id source: "id-1", "id-2", ... */
class SequentialIds {
    private var n = 0
    fun next(): String = "id-${++n}"
}

val FIXED_NOW: Instant = Instant.parse("2026-03-01T10:00:00Z")

fun <T, E> Outcome<T, E>.ok(): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> fail("expected Ok but was $error") as Nothing
}

fun <T, E> Outcome<T, E>.err(): E = when (this) {
    is Outcome.Ok -> fail("expected Err but was $value") as Nothing
    is Outcome.Err -> error
}
