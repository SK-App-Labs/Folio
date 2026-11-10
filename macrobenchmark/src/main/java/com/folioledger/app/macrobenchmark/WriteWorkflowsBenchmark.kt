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
class WriteWorkflowsBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun measureAddTransactionSheetAndKeypad() {
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

            val width = device.displayWidth
            val height = device.displayHeight

            // Open Add Transaction sheet via FAB (matches R.string.cd_add_transaction_fab)
            val addBtn = device.findObject(By.desc("Add new transaction"))
                ?: device.findObject(By.desc("Add Transaction"))
                ?: device.findObject(By.text("Add Transaction"))
            if (addBtn != null) {
                addBtn.click()
            } else {
                device.click(width - 120, height - 250)
            }
            device.waitForIdle()

            // Interact with docked calculator keypad
            device.findObject(By.text("1"))?.click()
            device.findObject(By.text("0"))?.click()
            device.findObject(By.text("0"))?.click()
            device.waitForIdle()

            device.pressBack()
            device.waitForIdle()
        }
    }

    @Test
    fun measureAddAccountBottomSheet() {
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

            val width = device.displayWidth
            val height = device.displayHeight

            // Open Accounts tab (Tab 3 of 4)
            val accountsTab = device.findObject(By.text("Accounts"))
                ?: device.findObject(By.desc("Accounts"))
            if (accountsTab != null) {
                accountsTab.click()
            } else {
                device.click((width * 0.625f).toInt(), height - 100)
            }
            device.waitForIdle()

            // Open Add Account sheet
            val addAccountBtn = device.findObject(By.desc("Add Account"))
                ?: device.findObject(By.text("Add Account"))
            addAccountBtn?.click()
            device.waitForIdle()

            device.pressBack()
            device.waitForIdle()
        }
    }

    companion object {
        private const val PACKAGE_NAME = "com.skal.folio"
    }
}
