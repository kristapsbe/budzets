package lv.kristapsbe.budzets.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import lv.kristapsbe.budzets.BudzetsApplication
import lv.kristapsbe.budzets.MainActivity
import lv.kristapsbe.budzets.R
import lv.kristapsbe.budzets.domain.calculation.DailyAllowance
import lv.kristapsbe.budzets.domain.calculation.PaceStatus
import lv.kristapsbe.budzets.util.MoneyFormatter
import lv.kristapsbe.budzets.util.displayName
import java.time.LocalDate

/**
 * The home-screen widget, reading the same repositories as the app. While a Glance session is
 * alive its content follows the data directly: an update to a live session only recomposes it,
 * so a snapshot taken before [provideContent] would go stale. [WidgetRefresher] and
 * [WidgetSchedule] start new sessions when none is running.
 *
 * "Today" is fixed for the session. Sessions last seconds, so one alive at midnight is rare and
 * the next change corrects it.
 */
class BudzetsWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as BudzetsApplication).container
        val states = WidgetStates.observe(
            budgets = container.budgets,
            categories = container.categories,
            surplus = container.surplus,
            settings = container.settings,
            expenses = container.expenses,
            today = LocalDate.now(container.clock),
        )
        // Loaded up front so the first frame already has data.
        val initial = states.first()
        provideContent {
            val state by states.collectAsState(initial)
            val language by languageChanges.collectAsState()
            key(language) { GlanceTheme { WidgetContent(state) } }
        }
    }

    companion object {
        /**
         * Counts language changes. Text is resolved while composing, so a live session keeps the
         * old language unless something makes it compose again.
         */
        val languageChanges = MutableStateFlow(0)
    }
}

/** 2×1-ish: remaining and the bar only. */
internal val SMALL = DpSize(100.dp, 48.dp)

/** 2×2 / 3×2: adds the month, the + button, the daily amount (or spent of budget without one) and status. */
internal val MEDIUM = DpSize(160.dp, 110.dp)

/** 4×2 and up: adds spent of budget beside the daily amount, the most over-budget category and saved surplus. */
internal val LARGE = DpSize(250.dp, 150.dp)

/** Asks [MainActivity] for Add expense (Q17). The widget goes through [QuickAddActivity] (Q18). */
fun addExpenseIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_ADD_EXPENSE)

@Composable
fun WidgetContent(state: WidgetState) {
    val context = LocalContext.current
    val size = LocalSize.current
    val padding = if (size.height < MEDIUM.height) 8.dp else 12.dp
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(padding)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            WidgetState.NotSetUp -> {
                Text(context.getString(R.string.app_name), style = titleStyle())
                Text(context.getString(R.string.widget_not_set_up), style = bodyStyle())
            }
            is WidgetState.Month -> MonthContent(state, context, size)
        }
    }
}

@Composable
private fun MonthContent(state: WidgetState.Month, context: Context, size: DpSize) {
    val locale = context.resources.configuration.locales[0]
    val money = MoneyFormatter(state.currency, locale)
    val roomy = size.height >= MEDIUM.height && size.width >= MEDIUM.width
    val large = size.height >= LARGE.height && size.width >= LARGE.width

    if (roomy) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(state.month.displayName(locale), style = bodyStyle(), modifier = GlanceModifier.defaultWeight())
            CircleIconButton(
                imageProvider = ImageProvider(R.drawable.ic_widget_add),
                contentDescription = context.getString(R.string.add_expense),
                onClick = actionStartActivity(Intent(context, QuickAddActivity::class.java)),
                backgroundColor = GlanceTheme.colors.primaryContainer,
                contentColor = GlanceTheme.colors.onPrimaryContainer,
                modifier = GlanceModifier.size(36.dp),
            )
        }
    }

    val headline = when {
        !state.hasBudget -> context.getString(R.string.spent_amount, money.format(state.spent))
        state.remaining.isNegative -> context.getString(R.string.over_by_amount, money.format(-state.remaining))
        else -> context.getString(R.string.remaining_amount, money.format(state.remaining))
    }
    Text(
        headline,
        style = TextStyle(
            fontSize = if (roomy) 22.sp else 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (state.isOver) GlanceTheme.colors.error else GlanceTheme.colors.onSurface,
        ),
        maxLines = 1,
    )
    Spacer(GlanceModifier.height(4.dp))
    LinearProgressIndicator(
        progress = state.progress,
        modifier = GlanceModifier.fillMaxWidth().semantics {
            contentDescription = context.getString(R.string.spent_of_budget, money.format(state.spent), money.format(state.budget))
        },
        color = if (state.isOver) GlanceTheme.colors.error else GlanceTheme.colors.primary,
        backgroundColor = GlanceTheme.colors.surfaceVariant,
    )
    if (!roomy) return

    Spacer(GlanceModifier.height(6.dp))
    // Medium shows the daily amount instead of "spent of" (Q34); the bar's description still reads it.
    if (state.hasBudget && (large || state.daily == null)) {
        Text(context.getString(R.string.spent_of_budget, money.format(state.spent), money.format(state.budget)), style = bodyStyle(), maxLines = 1)
    }
    state.daily?.let { DailyLines(it, money, context, oneLine = large) }
    StatusLine(state, context)

    if (!large) return
    state.topOver?.let {
        Text(
            context.getString(R.string.widget_top_over, it.name, money.format(it.overBy)),
            style = bodyStyle(GlanceTheme.colors.error),
            maxLines = 1,
        )
    }
    Text(context.getString(R.string.widget_saved_surplus, money.format(state.surplus)), style = bodyStyle(), maxLines = 1)
}

/**
 * The daily amount and what's left of it today (Q34). Medium is too narrow for both on one line,
 * so they take the two lines "spent of" and the daily line would have; large joins them and may
 * wrap, as Latvian and big amounts can run long.
 */
@Composable
private fun DailyLines(daily: DailyAllowance, money: MoneyFormatter, context: Context, oneLine: Boolean) {
    val over = daily.leftToday.isNegative
    val perDay = context.getString(R.string.daily_amount, money.format(daily.perDay))
    val today =
        if (over) context.getString(R.string.over_today, money.format(-daily.leftToday))
        else context.getString(R.string.left_today, money.format(daily.leftToday))
    // The words say "over", so colour isn't the only signal.
    val todayColor = if (over) GlanceTheme.colors.error else GlanceTheme.colors.onSurface
    if (oneLine) {
        Text("$perDay · $today", style = bodyStyle(todayColor), maxLines = 2)
    } else {
        Text(perDay, style = bodyStyle(GlanceTheme.colors.onSurface), maxLines = 1)
        // May wrap: Latvian's "over" runs past 2×2's width, and the amount comes last.
        Text(today.replaceFirstChar { it.uppercase(context.resources.configuration.locales[0]) }, style = bodyStyle(todayColor), maxLines = 2)
    }
}

/** Status in words with an icon, never by colour alone. */
@Composable
private fun StatusLine(state: WidgetState.Month, context: Context) {
    val (icon, label, tint) = when (state.status) {
        PaceStatus.ON_TRACK -> Triple(R.drawable.ic_widget_on_track, R.string.status_on_track, GlanceTheme.colors.primary)
        PaceStatus.WATCH -> Triple(R.drawable.ic_widget_warning, R.string.status_watch, GlanceTheme.colors.tertiary)
        PaceStatus.OVER -> Triple(R.drawable.ic_widget_warning, R.string.status_over, GlanceTheme.colors.error)
        PaceStatus.NO_BUDGET -> Triple(R.drawable.ic_widget_info, R.string.status_no_budget, GlanceTheme.colors.onSurfaceVariant)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(ImageProvider(icon), contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = GlanceModifier.size(16.dp))
        Spacer(GlanceModifier.width(6.dp))
        Text(
            context.getString(label) + " · " + context.resources.getQuantityString(R.plurals.days_left, state.daysLeft, state.daysLeft),
            style = bodyStyle(),
            maxLines = 1,
        )
    }
}

@Composable
private fun titleStyle() = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface)

@Composable
private fun bodyStyle(color: ColorProvider = GlanceTheme.colors.onSurfaceVariant) = TextStyle(fontSize = 13.sp, color = color)
