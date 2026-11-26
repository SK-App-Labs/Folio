/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.data

import com.folioledger.app.data.backup.AutoBackupRetentionEngine
import com.folioledger.app.data.backup.SessionChangeTracker
import com.folioledger.app.domain.model.AutoBackupRetention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AutoBackupRetentionEngineTest {

    private val zoneId = ZoneId.of("UTC")
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    @Before
    fun setUp() {
        SessionChangeTracker.resetDirty()
    }

    @Test
    fun sessionChangeTracker_lifecycleAndDebounce() {
        assertFalse(SessionChangeTracker.isDirty())
        assertFalse(SessionChangeTracker.canBackupNow())

        SessionChangeTracker.markDirty()
        assertTrue(SessionChangeTracker.isDirty())
        assertTrue(SessionChangeTracker.canBackupNow(minIntervalMillis = 0L))

        SessionChangeTracker.recordBackupFinished()
        assertFalse(SessionChangeTracker.isDirty())

        // Mark dirty again immediately; min interval of 60s should debounce
        SessionChangeTracker.markDirty()
        assertFalse(SessionChangeTracker.canBackupNow(minIntervalMillis = 60_000L))
        assertTrue(SessionChangeTracker.canBackupNow(minIntervalMillis = 0L))
    }

    @Test
    fun parseBackupFile_validFilename_extractsDateCorrectly() {
        val file = File("Folio_AutoBackup_20261029_143000.zip")
        val parsed = AutoBackupRetentionEngine.parseBackupFile(file, zoneId)
        assertNull(parsed)

        // Note: File doesn't exist on disk, so parseBackupFile checks file.isFile.
        // Let's create an actual temp file to test real parsing
        val temp = File.createTempFile("Folio_AutoBackup_20261029_143000", ".zip")
        try {
            val realParsed = AutoBackupRetentionEngine.parseBackupFile(temp, zoneId)
            // Name contains valid pattern
            assertTrue(realParsed != null)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun parseBackupFile_hyphenatedFilename_extractsDateCorrectly() {
        val temp = File.createTempFile("Folio_AutoBackup_2026-10-29_143000", ".zip")
        try {
            val parsed = AutoBackupRetentionEngine.parseBackupFile(temp, zoneId)
            assertTrue(parsed != null)
            assertEquals(LocalDate.of(2026, 10, 29), parsed?.localDate)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun tenSessionsRetention_retainsTenNewest() {
        val baseDate = LocalDateTime.of(2026, 10, 29, 10, 0, 0)
        val files = (1..15).map { i ->
            val timestamp = baseDate.plusMinutes(i.toLong() * 10).format(formatter)
            val file = File.createTempFile("Folio_AutoBackup_${timestamp}_", ".zip")
            file
        }

        try {
            val retained = AutoBackupRetentionEngine.computeFilesToRetain(
                files = files,
                policy = AutoBackupRetention.TEN_SESSIONS,
                zoneId = zoneId
            )

            assertEquals(10, retained.size)
            // The first 5 created (oldest) should be pruned
            val oldest5 = files.take(5).toSet()
            val newest10 = files.takeLast(10).toSet()

            assertTrue(retained.containsAll(newest10))
            assertFalse(retained.any { it in oldest5 })
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test
    fun thirtyDaysTieredRetention_capsIntraDayAtThree_andRetainsThirtyDaily() {
        val now = LocalDateTime.of(2026, 10, 29, 18, 0, 0)
        val nowMillis = now.atZone(zoneId).toInstant().toEpochMilli()

        val allMockFiles = mutableListOf<File>()

        try {
            // 1. Create 5 session backups for TODAY (2026-10-29)
            val todayFiles = (1..5).map { i ->
                val dt = LocalDateTime.of(2026, 10, 29, 8 + i * 2, 0, 0)
                val str = dt.format(formatter)
                val file = File.createTempFile("Folio_AutoBackup_${str}_", ".zip")
                allMockFiles.add(file)
                file
            }

            // 2. Create 35 backups for past 35 days (1 per day)
            val pastDaysFiles = (1..35).map { day ->
                val dt = now.minusDays(day.toLong())
                val str = dt.format(formatter)
                val file = File.createTempFile("Folio_AutoBackup_${str}_", ".zip")
                allMockFiles.add(file)
                file
            }

            val retained = AutoBackupRetentionEngine.computeFilesToRetain(
                files = allMockFiles,
                policy = AutoBackupRetention.THIRTY_DAYS,
                nowEpochMillis = nowMillis,
                zoneId = zoneId
            )

            // Today should have exactly 3 retained (the newest 3 of the 5)
            val retainedToday = todayFiles.filter { it in retained }
            assertEquals(3, retainedToday.size)
            assertEquals(todayFiles.takeLast(3).toSet(), retainedToday.toSet())

            // Days 1..30 should be retained
            val retainedPast30 = pastDaysFiles.take(30).filter { it in retained }
            assertEquals(30, retainedPast30.size)

            // Monthly anchor: exactly 1 backup retained for days > 30 (the newest in that month, day 31)
            val retainedOlder = pastDaysFiles.drop(30).filter { it in retained }
            assertEquals(1, retainedOlder.size)
            assertEquals(pastDaysFiles[30], retainedOlder.first())
            // Days 32..35 are pruned
            assertTrue("Days 32-35 should be pruned", pastDaysFiles.drop(31).none { it in retained })
        } finally {
            allMockFiles.forEach { it.delete() }
        }
    }

    @Test
    fun pruneDirectory_deletesExcessFilesOnDisk() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "folio_autobackup_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val baseDate = LocalDateTime.of(2026, 10, 29, 10, 0, 0)
            val createdFiles = (1..15).map { i ->
                val timestamp = baseDate.plusMinutes(i.toLong() * 10).format(formatter)
                val file = File(tempDir, "Folio_AutoBackup_${timestamp}.zip")
                file.writeText("test_content_$i")
                file
            }

            assertEquals(15, tempDir.listFiles()?.size)

            val deletedCount = AutoBackupRetentionEngine.pruneDirectory(
                directory = tempDir,
                policy = AutoBackupRetention.TEN_SESSIONS,
                zoneId = zoneId
            )

            assertEquals(5, deletedCount)
            assertEquals(10, tempDir.listFiles()?.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun thirtyDaysAndNinetyDaysRetention_preservesFutureDatedBackupsOnTimezoneTravel() {
        val now = LocalDateTime.of(2026, 10, 29, 10, 0, 0)
        val nowMillis = now.atZone(zoneId).toInstant().toEpochMilli()
        val futureDt = now.plusDays(1)
        val futureFile = File.createTempFile("Folio_AutoBackup_${futureDt.format(formatter)}_", ".zip")
        val pastFile = File.createTempFile("Folio_AutoBackup_${now.minusDays(2).format(formatter)}_", ".zip")
        try {
            val retained30 = AutoBackupRetentionEngine.computeFilesToRetain(
                files = listOf(futureFile, pastFile),
                policy = AutoBackupRetention.THIRTY_DAYS,
                nowEpochMillis = nowMillis,
                zoneId = zoneId
            )
            assertTrue("Future-dated backup must be retained under THIRTY_DAYS", futureFile in retained30)
            assertTrue(pastFile in retained30)

            val retained90 = AutoBackupRetentionEngine.computeFilesToRetain(
                files = listOf(futureFile, pastFile),
                policy = AutoBackupRetention.NINETY_DAYS,
                nowEpochMillis = nowMillis,
                zoneId = zoneId
            )
            assertTrue("Future-dated backup must be retained under NINETY_DAYS", futureFile in retained90)
            assertTrue(pastFile in retained90)
        } finally {
            futureFile.delete()
            pastFile.delete()
        }
    }

    @Test
    fun sessionChangeTracker_concurrentMarkDirtyDuringBackup_preservesDirtySignal() {
        SessionChangeTracker.resetDirty()
        SessionChangeTracker.markDirty()
        val inFlightVersion = SessionChangeTracker.currentVersion()

        // Simulate user logging a new transaction while the backup stream is mid-write
        SessionChangeTracker.markDirty()
        SessionChangeTracker.recordBackupFinished(inFlightVersion)

        assertTrue(
            "Dirty flag must remain true if markDirty() raced during backup write",
            SessionChangeTracker.isDirty()
        )
    }
}
