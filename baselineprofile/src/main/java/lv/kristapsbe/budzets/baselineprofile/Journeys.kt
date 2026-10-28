package lv.kristapsbe.budzets.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal const val PACKAGE_NAME = "lv.kristapsbe.budzets"
private const val TIMEOUT_MILLIS = 5_000L

/** Overview has loaded: its numbers or, on a fresh install, the starter-categories card. */
fun MacrobenchmarkScope.waitForOverview() {
    device.wait(Until.hasObject(By.text(Pattern.compile("Add expense|Add starter categories"))), TIMEOUT_MILLIS)
}

/**
 * The common path through the app, for the profile: Overview (with starter categories on a fresh
 * install), a scroll, every tab, and Add expense. Finds things by their English labels.
 */
fun MacrobenchmarkScope.browseApp() {
    waitForOverview()
    device.findObject(By.text("Add starter categories"))?.let {
        it.click()
        device.wait(Until.hasObject(By.text("Add expense")), TIMEOUT_MILLIS)
    }
    scrollOverview()
    for (tab in listOf("Spending", "Budgets", "Surplus", "Settings", "Overview")) {
        openTab(tab)
    }
    device.findObject(By.text("Add expense"))?.click()
    device.wait(Until.hasObject(By.text("Save")), TIMEOUT_MILLIS)
    device.findObject(By.desc("Cancel"))?.click()
    waitForOverview()
}

fun MacrobenchmarkScope.scrollOverview() {
    device.findObject(By.scrollable(true))?.let { list ->
        list.setGestureMargin(device.displayWidth / 5)
        list.fling(Direction.DOWN)
        list.fling(Direction.UP)
    }
    device.waitForIdle()
}

/** Taps the bottom-bar item, the lowest of the texts (a tab's title can repeat its label). */
fun MacrobenchmarkScope.openTab(label: String) {
    device.findObjects(By.text(label)).maxByOrNull { it.visibleBounds.top }?.click()
    device.waitForIdle()
}
