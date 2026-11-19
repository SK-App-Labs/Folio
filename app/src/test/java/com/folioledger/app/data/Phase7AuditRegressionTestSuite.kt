// File: app/src/test/java/com/folioledger/app/data/Phase7AuditRegressionTestSuite.kt
package com.folioledger.app.data

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import com.folioledger.app.data.notification.DueRemindersScheduler
import com.folioledger.app.data.notification.FolioNotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 7 Audit Regression Test Suite:
 * Validates R8 keep rules, Android backup exclusions, notification ID math invariants,
 * and WorkManager scheduling policies.
 */
class Phase7AuditRegressionTestSuite {

    @Test
    fun dueRemindersScheduler_defaultPolicyIsKeep() {
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, DueRemindersScheduler.DEFAULT_WORK_POLICY)
    }

    @Test
    fun dueRemindersScheduler_rescheduleDailyReminders_methodSignatureValid() {
        val methods = DueRemindersScheduler::class.java.declaredMethods
        val rescheduleMethod = methods.firstOrNull { it.name.startsWith("rescheduleDailyReminders") }
        assertTrue("rescheduleDailyReminders must be declared on DueRemindersScheduler", rescheduleMethod != null)

        val paramTypes = rescheduleMethod!!.parameterTypes
        assertTrue(paramTypes.contains(Context::class.java))
        assertTrue(paramTypes.contains(Int::class.javaPrimitiveType))
    }

    @Test
    fun folioNotificationManager_cardNotificationId_boundsAndNonNegative() {
        val testIds = listOf(
            0L, 1L, 9999L, 10_000L, 12_345L, 999_999L,
            -1L, -2L, -9999L, -10_000L, -12_345L, Long.MIN_VALUE, Long.MAX_VALUE
        )

        for (id in testIds) {
            val notifId = FolioNotificationManager.getCardNotificationId(id)
            assertTrue(
                "Card notification ID for $id was $notifId, expected in [70000, 79999]",
                notifId in 70_000..79_999
            )
        }
    }

    @Test
    fun folioNotificationManager_billNotificationId_boundsAndNonNegative() {
        val testIds = listOf(
            0L, 1L, 9999L, 10_000L, 12_345L, 999_999L,
            -1L, -2L, -9999L, -10_000L, -12_345L, Long.MIN_VALUE, Long.MAX_VALUE
        )

        for (id in testIds) {
            val notifId = FolioNotificationManager.getBillNotificationId(id)
            assertTrue(
                "Bill notification ID for $id was $notifId, expected in [80000, 89999]",
                notifId in 80_000..89_999
            )
        }
    }

    @Test
    fun proguardRules_containsRequiredAttributesAndBackupKeep() {
        // Find app/proguard-rules.pro relative to project directory
        val proguardFile = listOf(
            File("proguard-rules.pro"),
            File("app/proguard-rules.pro"),
            File("../app/proguard-rules.pro")
        ).firstOrNull { it.exists() }

        assertTrue("proguard-rules.pro must exist", proguardFile != null)
        val content = proguardFile!!.readText()

        assertTrue("ProGuard rules must keep EnclosingMethod attribute", content.contains("EnclosingMethod"))
        assertTrue("ProGuard rules must keep InnerClasses attribute", content.contains("InnerClasses"))
        assertTrue("ProGuard rules must keep backup package classes", content.contains("com.folioledger.app.data.backup.**"))
        assertTrue("ProGuard rules must keep @Serializable classes and constructors", content.contains("@kotlinx.serialization.Serializable class **"))
    }

    @Test
    fun backupRulesXml_containsRequiredExclusions() {
        val backupRulesFile = listOf(
            File("src/main/res/xml/backup_rules.xml"),
            File("app/src/main/res/xml/backup_rules.xml"),
            File("../app/src/main/res/xml/backup_rules.xml")
        ).firstOrNull { it.exists() }

        assertTrue("backup_rules.xml must exist", backupRulesFile != null)
        val content = backupRulesFile!!.readText()

        assertTrue("Must exclude SQLite WAL", content.contains("folio_ledger.db-wal") || content.contains("folio_database-wal"))
        assertTrue("Must exclude SQLite SHM", content.contains("folio_ledger.db-shm") || content.contains("folio_database-shm"))
        assertTrue("Must exclude SQLite journal", content.contains("folio_ledger.db-journal") || content.contains("folio_database-journal"))
        assertTrue("Must exclude demo_stash", content.contains("demo_stash"))
    }

    @Test
    fun dataExtractionRulesXml_containsRequiredExclusions() {
        val dataExtractionRulesFile = listOf(
            File("src/main/res/xml/data_extraction_rules.xml"),
            File("app/src/main/res/xml/data_extraction_rules.xml"),
            File("../app/src/main/res/xml/data_extraction_rules.xml")
        ).firstOrNull { it.exists() }

        assertTrue("data_extraction_rules.xml must exist", dataExtractionRulesFile != null)
        val content = dataExtractionRulesFile!!.readText()

        assertTrue("Must exclude SQLite WAL in cloud backup or device transfer", content.contains("folio_ledger.db-wal") || content.contains("folio_database-wal"))
        assertTrue("Must exclude SQLite SHM in cloud backup or device transfer", content.contains("folio_ledger.db-shm") || content.contains("folio_database-shm"))
        assertTrue("Must exclude SQLite journal in cloud backup or device transfer", content.contains("folio_ledger.db-journal") || content.contains("folio_database-journal"))
        assertTrue("Must exclude demo_stash in cloud backup", content.contains("demo_stash"))
    }

    @Test
    fun buildGradleKts_containsSigningFallbackWarning() {
        val buildGradleFile = listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
            File("../app/build.gradle.kts")
        ).firstOrNull { it.exists() }

        assertTrue("app/build.gradle.kts must exist", buildGradleFile != null)
        val content = buildGradleFile!!.readText()

        assertTrue("build.gradle.kts must log warning on debug fallback", content.contains("logger.warn"))
    }
}
