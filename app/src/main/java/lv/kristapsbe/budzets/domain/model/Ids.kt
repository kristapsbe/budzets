package lv.kristapsbe.budzets.domain.model

@JvmInline
value class CategoryId(val value: String)

@JvmInline
value class LedgerEntryId(val value: String)

@JvmInline
value class BoostId(val value: String)

@JvmInline
value class ExpenseId(val value: String)

/** Where a surplus balance lives: a category, or the overall amount not assigned to any category. */
sealed interface SurplusPocket {
    data class Category(val id: CategoryId) : SurplusPocket
    data object Unallocated : SurplusPocket
}

/** Typed success/failure for domain operations that can be refused. */
sealed interface Outcome<out T, out E> {
    data class Ok<T>(val value: T) : Outcome<T, Nothing>
    data class Err<E>(val error: E) : Outcome<Nothing, E>
}
