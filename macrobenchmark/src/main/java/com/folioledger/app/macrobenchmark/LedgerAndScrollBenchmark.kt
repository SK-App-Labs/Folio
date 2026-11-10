package com.folioledger.app.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class LedgerAndScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun measureLedgerFeedScrollJank() {
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

            // Tap demo mode button if present to ensure feed has rich data to scroll
            device.findObject(By.text("Preview App with Live Demo Data"))?.click()
            device.waitForIdle()

            val width = device.displayWidth
            val height = device.displayHeight
            val midX = width / 2
            val topY = (height * 0.3).toInt()
            val bottomY = (height * 0.7).toInt()

            // Fling scroll down and up
            device.swipe(midX, bottomY, midX, topY, 8)
            device.waitForIdle()

            device.swipe(midX, topY, midX, bottomY, 8)
            device.waitForIdle()
        }
    }

    @Test
    fun measureLedgerSubtabSwitchingJank() {
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

            // Tap demo mode button if present
            device.findObject(By.text("Preview App with Live Demo Data"))?.click()
            device.waitForIdle()

            val width = device.displayWidth
            val height = device.displayHeight
            val midY = height / 2
            val leftX = (width * 0.2).toInt()
            val rightX = (width * 0.8).toInt()

            // Swipe across ledger subtabs (Daily -> Calendar -> Weekly and back)
            device.swipe(rightX, midY, leftX, midY, 15)
            device.waitForIdle()

            device.swipe(rightX, midY, leftX, midY, 15)
            device.waitForIdle()

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
