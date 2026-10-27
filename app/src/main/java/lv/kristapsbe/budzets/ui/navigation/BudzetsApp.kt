package lv.kristapsbe.budzets.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import lv.kristapsbe.budzets.ui.expenses.ExpenseEditorScreen
import lv.kristapsbe.budzets.ui.expenses.TransactionsScreen
import lv.kristapsbe.budzets.ui.overview.OverviewScreen
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

@Composable
fun BudzetsApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = topLevelDestinations.any { dest -> currentDestination?.hierarchy?.any { it.hasRoute(dest.routeClass) } == true }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevelDestinations.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any { it.hasRoute(dest.routeClass) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateTopLevel(dest.route) },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(stringResource(dest.label)) },
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
            composable<BudgetsRoute> { PlaceholderScreen(R.string.nav_budgets) }
            composable<SurplusRoute> { PlaceholderScreen(R.string.nav_surplus) }
            composable<SettingsRoute> { PlaceholderScreen(R.string.nav_settings) }
            composable<ExpenseEditorRoute> {
                ExpenseEditorScreen(onClose = { navController.popBackStack<ExpenseEditorRoute>(inclusive = true) })
            }
        }
    }
}

/** Switches tabs keeping one copy of each tab on the back stack, restoring its state. */
private fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Temporary stand-in for screens built in later phases. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderScreen(@StringRes title: Int) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(title)) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.placeholder_coming), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
