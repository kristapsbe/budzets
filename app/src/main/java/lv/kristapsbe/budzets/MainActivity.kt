package lv.kristapsbe.budzets

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import lv.kristapsbe.budzets.ui.navigation.BudzetsApp
import lv.kristapsbe.budzets.ui.theme.BudzetsTheme

/**
 * `singleTop`: the widget's + button brings a running app forward with [onNewIntent] instead of
 * clearing it, so a half-filled form elsewhere isn't lost; Add expense opens on top.
 */
class MainActivity : ComponentActivity() {

    /** Set by an [ACTION_ADD_EXPENSE] intent until the navigation has handled it. */
    private val addExpenseRequested = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // After recreation the request was already handled before the activity went away.
        if (savedInstanceState == null) handle(intent)
        setContent {
            BudzetsTheme {
                BudzetsApp(
                    addExpenseRequested = addExpenseRequested.value,
                    onAddExpenseHandled = { addExpenseRequested.value = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_ADD_EXPENSE) addExpenseRequested.value = true
    }

    companion object {
        const val ACTION_ADD_EXPENSE = "lv.kristapsbe.budzets.action.ADD_EXPENSE"
    }
}
