package lv.kristapsbe.budzets.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import lv.kristapsbe.budzets.MainActivity

/**
 * Where the widget's + button lands (Q17, Q18). It opens the app the way the launcher does, then
 * asks for Add expense on top, and closes; it is invisible and kept out of recents.
 *
 * Starting [MainActivity] with the add-expense intent directly would make that intent the base
 * of the app's task. After an app update Android rebuilds the task from its base intent, so the
 * app icon would reopen Add expense instead of Overview.
 */
class QuickAddActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivities(quickAddIntents(this))
        finish()
    }
}

/** A launcher-style start (an existing task comes forward as it was), then Add expense. */
fun quickAddIntents(context: Context): Array<Intent> = arrayOf(
    Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
    addExpenseIntent(context),
)
