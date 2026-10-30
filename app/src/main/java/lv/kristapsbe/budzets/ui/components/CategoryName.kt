package lv.kristapsbe.budzets.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import lv.kristapsbe.budzets.R

/** A category's name, marked if it has been archived or deleted. A deleted one is also archived. */
@Composable
fun categoryName(name: String, isArchived: Boolean, isDeleted: Boolean): String = when {
    isDeleted -> stringResource(R.string.category_deleted_label, name)
    isArchived -> stringResource(R.string.category_archived, name)
    else -> name
}
