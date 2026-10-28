package lv.kristapsbe.budzets.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start to Overview, then a scroll and a tab switch, without and with the Baseline Profile.
 * Time to full display is when Overview's numbers are on screen (`ReportDrawnWhen`).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupWithoutProfile() = startup(CompilationMode.None())

    @Test
    fun startupWithProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = {
            pressHome()
            // With a widget on the home screen, the previous iteration's widget refresh can bring
            // the process back (WorkManager job) before this one; a cold start needs it gone.
            killProcess()
        },
    ) {
        startActivityAndWait()
        waitForOverview()
        scrollOverview()
        openTab("Spending")
        openTab("Overview")
    }
}
