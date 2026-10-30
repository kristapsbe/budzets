package lv.kristapsbe.budzets.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.ui.budgets.BudgetEditorScreen
import lv.kristapsbe.budzets.ui.budgets.BudgetsScreen
import lv.kristapsbe.budzets.ui.close.MonthCloseScreen
import lv.kristapsbe.budzets.ui.expenses.ExpenseEditorScreen
import lv.kristapsbe.budzets.ui.expenses.TransactionsScreen
import lv.kristapsbe.budzets.ui.overview.OverviewScreen
import lv.kristapsbe.budzets.ui.surplus.BoostScreen
import lv.kristapsbe.budzets.ui.surplus.MoveSurplusScreen
import lv.kristapsbe.budzets.ui.surplus.OneOffBoostScreen
import lv.kristapsbe.budzets.ui.settings.SettingsScreen
import lv.kristapsbe.budzets.ui.surplus.SurplusScreen
import lv.kristapsbe.budzets.ui.surplus.key
import kotlin.reflect.KClass

@Serializable data object OverviewRoute
@Serializable data object TransactionsRoute
@Serializable data object BudgetsRoute
@Serializable data object SurplusRoute
@Serializable data object SettingsRoute

/**
 * One category's spending in [month] ("YYYY-MM"), opened from the overview. A separate route,
 * not Spending-tab arguments: tab switches save and restore back stacks, which would bring back
 * a stale filter instead of the one just tapped.
 */
@Serializable data class CategorySpendingRoute(val categoryId: String, val month: String)

/** Edits a budget from [month] ("YYYY-MM") on: a category's, or the overall one when [categoryId] is null. */
@Serializable data class BudgetEditorRoute(val categoryId: String? = null, val month: String)

/** Month-end review of [month] ("YYYY-MM"): close, close again, or reopen. */
@Serializable data class MonthCloseRoute(val month: String)

/** Moves surplus, optionally starting from the pocket keyed [from] (see `SurplusPocket.key`). */
@Serializable data class MoveSurplusRoute(val from: String? = null)

/** Boosts a category's budget for a month with saved surplus. */
@Serializable data class BoostRoute(val categoryId: String? = null, val month: String? = null)

/** Adds money from outside the budget to a month, into the unallocated surplus. */
@Serializable data object OneOffBoostRoute

/** Edits [expenseId], or adds a new expense with [categoryId] preselected. */
@Serializable data class ExpenseEditorRoute(val expenseId: String? = null, val categoryId: String? = null)

private data class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    @param:StringRes val label: Int,
    val icon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(OverviewRoute, OverviewRoute::class, R.string.nav_overview, Icons.Filled.Home),
    TopLevelDestination(TransactionsRoute, TransactionsRoute::class, R.string.nav_transactions, Icons.AutoMirrored.Filled.List),
    TopLevelDestination(BudgetsRoute, BudgetsRoute::class, R.string.nav_budgets, Icons.Filled.DateRange),
    TopLevelDestination(SurplusRoute, SurplusRoute::class, R.string.nav_surplus, Icons.Filled.Star),
    TopLevelDestination(SettingsRoute, SettingsRoute::class, R.string.nav_settings, Icons.Filled.Settings),
)

/**
 * @param addExpenseRequested set when the app is opened from the widget's + button; Add expense
 *   opens on top of the current screen, then [onAddExpenseHandled] clears the request.
 */
@Composable
fun BudzetsApp(
    navController: NavHostController = rememberNavController(),
    addExpenseRequested: Boolean = false,
    onAddExpenseHandled: () -> Unit = {},
) {

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = topLevelDestinations.any { dest -> currentDestination?.hierarchy?.any { it.hasRoute(dest.routeClass) } == true }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                // Five labels don't fit at the largest font sizes: then icons only, named for
                // screen readers. Otherwise one line each, never broken mid-word, and a long one
                // (Latvian's "Pārpalikums" on a narrow phone) shrinks rather than being cut off.
                val iconsOnly = LocalDensity.current.fontScale >= ICONS_ONLY_FONT_SCALE
                NavigationBar {
                    topLevelDestinations.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any { it.hasRoute(dest.routeClass) } == true
                        val label = stringResource(dest.label)
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateTopLevel(dest.route) },
                            icon = { Icon(dest.icon, contentDescription = if (iconsOnly) label else null) },
                            label = if (iconsOnly) null else {
                                {
                                    Text(
                                        label,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        autoSize = TextAutoSize.StepBased(MIN_LABEL_FONT_SIZE, LocalTextStyle.current.fontSize, 0.5.sp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Only the bottom bar's space is taken here; each screen's own Scaffold handles the top insets.
        val bottomOnly = PaddingValues(bottom = padding.calculateBottomPadding())
        NavHost(
            navController = navController,
            startDestination = OverviewRoute,
            modifier = Modifier.padding(bottomOnly).consumeWindowInsets(bottomOnly),
        ) {
            composable<OverviewRoute> {
                OverviewScreen(
                    onAddExpense = { navController.navigate(ExpenseEditorRoute()) },
                    onOpenBudgets = { navController.navigateTopLevel(BudgetsRoute) },
                    onOpenSurplus = { navController.navigateTopLevel(SurplusRoute) },
                    onOpenCategory = { id, month -> navController.navigate(CategorySpendingRoute(id.value, month.toString())) },
                    onReviewClose = { navController.navigate(MonthCloseRoute(it.toString())) },
                    onOpenSettings = { navController.navigateTopLevel(SettingsRoute) },
                )
            }
            composable<TransactionsRoute> {
                TransactionsScreen(
                    onAddExpense = { navController.navigate(ExpenseEditorRoute(categoryId = it?.value)) },
                    onOpenExpense = { navController.navigate(ExpenseEditorRoute(expenseId = it.value)) },
                )
            }
            composable<CategorySpendingRoute> {
                TransactionsScreen(
                    onAddExpense = { navController.navigate(ExpenseEditorRoute(categoryId = it?.value)) },
                    onOpenExpense = { navController.navigate(ExpenseEditorRoute(expenseId = it.value)) },
                    // Pops only this route, so a repeated tap can't pop the screen below it too.
                    onBack = { navController.popBackStack<CategorySpendingRoute>(inclusive = true) },
                )
            }
            composable<BudgetsRoute> {
                BudgetsScreen(onEditBudget = { id, month -> navController.navigate(BudgetEditorRoute(id?.value, month.toString())) })
            }
            composable<BudgetEditorRoute> {
                BudgetEditorScreen(onClose = { navController.popBackStack<BudgetEditorRoute>(inclusive = true) })
            }
            composable<SurplusRoute> {
                SurplusScreen(
                    onMove = { navController.navigate(MoveSurplusRoute(it?.key())) },
                    onBoost = { navController.navigate(BoostRoute()) },
                    onOneOff = { navController.navigate(OneOffBoostRoute) },
                )
            }
            composable<MonthCloseRoute> {
                MonthCloseScreen(onClose = { navController.popBackStack<MonthCloseRoute>(inclusive = true) })
            }
            composable<MoveSurplusRoute> {
                MoveSurplusScreen(onClose = { navController.popBackStack<MoveSurplusRoute>(inclusive = true) })
            }
            composable<BoostRoute> {
                BoostScreen(onClose = { navController.popBackStack<BoostRoute>(inclusive = true) })
            }
            composable<OneOffBoostRoute> {
                OneOffBoostScreen(onClose = { navController.popBackStack<OneOffBoostRoute>(inclusive = true) })
            }
            composable<SettingsRoute> { SettingsScreen() }
            composable<ExpenseEditorRoute> {
                ExpenseEditorScreen(onClose = { navController.popBackStack<ExpenseEditorRoute>(inclusive = true) })
            }
        }
        // After the NavHost, so its graph is set before this navigates.
        LaunchedEffect(addExpenseRequested) {
            if (addExpenseRequested) {
                // An editor that is already open (e.g. a half-typed expense) is kept rather than stacked.
                navController.navigate(ExpenseEditorRoute()) { launchSingleTop = true }
                onAddExpenseHandled()
            }
        }
    }
}

private val MIN_LABEL_FONT_SIZE = 8.sp

/** Switches tabs keeping one copy of each tab on the back stack, restoring its state. */
private const val ICONS_ONLY_FONT_SCALE = 1.5f

private fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
