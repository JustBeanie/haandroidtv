package dev.haquickaccess.tv.benchmark

import android.content.ComponentName
import android.content.Intent
import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "dev.haquickaccess.tv"
private const val BENCHMARK_EXTRA = "dev.haquickaccess.tv.extra.BENCHMARK_FIXTURE"

internal fun MacrobenchmarkScope.startBenchmarkDashboard() {
    startActivityAndWait(benchmarkDashboardIntent(clearTask = true))
    awaitBenchmarkDashboard("dashboard startup")
}

internal fun MacrobenchmarkScope.returnToBenchmarkDashboard() {
    startActivityAndWait(benchmarkDashboardIntent(clearTask = false))
    awaitBenchmarkDashboard("Android TV Home return")
}

private fun benchmarkDashboardIntent(clearTask: Boolean) = Intent(Intent.ACTION_MAIN).apply {
    component = ComponentName(TARGET_PACKAGE, "$TARGET_PACKAGE.MainActivity")
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (clearTask) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    putExtra(BENCHMARK_EXTRA, true)
}

private fun MacrobenchmarkScope.awaitBenchmarkDashboard(journey: String) {
    check(device.wait(Until.hasObject(By.res("dashboard_grid")), 5_000)) {
        "Benchmark dashboard did not become ready; current package=${device.currentPackageName}"
    }
    assertExactlyOneFocusedElement(journey)
}

internal fun MacrobenchmarkScope.navigateDashboardGrid() {
    repeat(7) { device.pressDPadDown() }
    repeat(3) { device.pressDPadRight() }
    repeat(4) { device.pressDPadUp() }
    repeat(3) { device.pressDPadLeft() }
    device.waitForIdle()
    assertExactlyOneFocusedElement("grid traversal")
}

internal fun MacrobenchmarkScope.openSettingsAndReturn() {
    device.pressDPadUp()
    device.pressDPadCenter()
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()
    assertExactlyOneFocusedElement("settings Back return")
}

internal fun MacrobenchmarkScope.openDetailsAndReturn() {
    device.executeShellCommand("input keyevent --longpress ${KeyEvent.KEYCODE_DPAD_CENTER}")
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()
    assertExactlyOneFocusedElement("detail Back return")
}

private fun MacrobenchmarkScope.assertExactlyOneFocusedElement(journey: String) {
    check(device.wait(Until.hasObject(By.focused(true)), 5_000)) {
        "No focused element after $journey"
    }
    val focusedCount = device.findObjects(By.focused(true)).size
    check(focusedCount == 1) {
        "Expected exactly one focused element after $journey, found $focusedCount"
    }
}
