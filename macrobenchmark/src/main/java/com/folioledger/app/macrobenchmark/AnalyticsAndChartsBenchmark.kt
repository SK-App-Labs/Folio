package com.folioledger.app.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class AnalyticsAndChartsBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun measureStatsChartsAndSubtabSwitching() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = {
                pressHome()
            }
        ) {
            startActivityAndWait()
            device.waitForIdle()

            // Tap demo mode button if present to ensure charts have data
            device.findObject(By.text("Preview App with Live Demo Data"))?.click()
            device.waitForIdle()

            val width = device.displayWidth
            val height = device.displayHeight

            // 1. Open Stats Screen from bottom navigation (Tab 2 of 4)
            val statsTab = device.findObject(By.text("Stats"))
                ?: device.findObject(By.desc("Stats"))
            if (statsTab != null) {
                statsTab.click()
            } else {
                device.click((width * 0.375f).toInt(), height - 100)
            }
            device.waitForIdle()

            val midY = height / 2
            val leftX = (width * 0.2).toInt()
            val rightX = (width * 0.8).toInt()

            // 2. Swipe across stats subtabs (Overview -> Cards Cockpit -> Budgets)
            device.swipe(rightX, midY, leftX, midY, 15)
            device.waitForIdle()

            device.swipe(rightX, midY, leftX, midY, 15)
            device.waitForIdle()

            // 3. Swipe back to Overview
            device.swipe(leftX, midY, rightX, midY, 15)
            device.waitForIdle()

            device.swipe(leftX, midY, rightX, midY, 15)
            device.waitForIdle()
        }
    }

    companion object {
        private const val PACKAGE_NAME = "com.skal.folio"
    }
}
