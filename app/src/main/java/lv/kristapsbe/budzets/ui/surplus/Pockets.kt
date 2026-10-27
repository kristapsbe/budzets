package lv.kristapsbe.budzets.ui.surplus

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.model.Category
import lv.kristapsbe.budzets.domain.model.CategoryId
import lv.kristapsbe.budzets.domain.model.SurplusPocket

private const val UNALLOCATED_KEY = "@unallocated"

/** A string form for routes and [androidx.lifecycle.SavedStateHandle]: the category id, or a marker. */
fun SurplusPocket.key(): String = when (this) {
    is SurplusPocket.Category -> id.value
    SurplusPocket.Unallocated -> UNALLOCATED_KEY
}

fun pocketFromKey(key: String): SurplusPocket =
    if (key == UNALLOCATED_KEY) SurplusPocket.Unallocated else SurplusPocket.Category(CategoryId(key))

/** A pocket as screens show it. [name] is null for the unallocated pocket (a localized label). */
data class PocketLabel(val pocket: SurplusPocket, val name: String?, val isArchived: Boolean)

fun SurplusPocket.label(categories: Map<CategoryId, Category>): PocketLabel = when (this) {
    is SurplusPocket.Category -> categories[id].let { PocketLabel(this, it?.name ?: "?", it?.isArchived == true) }
    SurplusPocket.Unallocated -> PocketLabel(this, null, false)
}

/** Category pockets in display order, then the unallocated pocket. */
fun pocketOrder(categories: List<Category>): Comparator<SurplusPocket> {
    val index = categories.mapIndexed { i, c -> c.id to i }.toMap()
    return compareBy { p -> if (p is SurplusPocket.Category) index[p.id] ?: Int.MAX_VALUE - 1 else Int.MAX_VALUE }
}

@Composable
fun PocketLabel.text(): String = when {
    name == null -> stringResource(R.string.pocket_unallocated)
    isArchived -> stringResource(R.string.category_archived, name)
    else -> name
}
