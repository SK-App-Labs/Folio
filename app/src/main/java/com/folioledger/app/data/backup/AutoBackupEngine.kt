/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.data.backup

import com.folioledger.app.domain.model.AutoBackupRetention
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe tracker that records when data is modified in the app.
 * Uses monotonic version counters so concurrent writes during a backup are never dropped.
 */
object SessionChangeTracker {
    private val changeVersion = AtomicLong(0L)
    private val backedUpVersion = AtomicLong(0L)
    private val lastBackupEpochMillis = AtomicLong(0L)

    fun markDirty() {
        changeVersion.incrementAndGet()
    }

    fun currentVersion(): Long = changeVersion.get()

    fun isDirty(): Boolean = changeVersion.get() > backedUpVersion.get()

    fun resetDirty() {
        val current = changeVersion.get()
        backedUpVersion.updateAndGet { prev -> maxOf(prev, current) }
    }

    fun canBackupNow(minIntervalMillis: Long = 60_000L): Boolean {
        if (!isDirty()) return false
        val now = System.currentTimeMillis()
        val last = lastBackupEpochMillis.get()
        return (now - last) >= minIntervalMillis
    }

    fun recordBackupFinished(snapshotVersion: Long = changeVersion.get()) {
        backedUpVersion.updateAndGet { prev -> maxOf(prev, snapshotVersion) }
        lastBackupEpochMillis.set(System.currentTimeMillis())
    }
}

/**
 * Pure logic engine that parses auto-backup filenames, timestamps,
 * and computes the exact set of files to retain based on the chosen retention policy.
 */
object AutoBackupRetentionEngine {

    data class BackupFileInfo(
        val file: File,
        val epochMillis: Long,
        val localDate: LocalDate,
        val yearMonth: YearMonth
    )

    private val fileNameRegex = Regex("""Folio_AutoBackup_(\d{4}-?\d{2}-?\d{2}_\d{6})""")
    private val fileNameFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun parseBackupFile(file: File, zoneId: ZoneId = ZoneId.systemDefault()): BackupFileInfo? {
        if (!file.isFile || !file.name.endsWith(".zip", ignoreCase = true)) return null
        
        val timestampFromName = runCatching {
            val dateStr = fileNameRegex.find(file.name)?.groupValues?.get(1)
            if (dateStr != null) {
                val normalizedStr = dateStr.replace("-", "")
                val ldt = java.time.LocalDateTime.parse(normalizedStr, fileNameFormatter)
                ldt.atZone(zoneId).toInstant().toEpochMilli()
            } else null
        }.getOrNull()

        val epochMillis = timestampFromName ?: file.lastModified()
        if (epochMillis <= 0L) return null
        val localDate = Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()
        return BackupFileInfo(
            file = file,
            epochMillis = epochMillis,
            localDate = localDate,
            yearMonth = YearMonth.from(localDate)
        )
    }

    fun computeFilesToRetain(
        files: List<File>,
        policy: AutoBackupRetention,
        nowEpochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Set<File> {
        val parsed = files.mapNotNull { parseBackupFile(it, zoneId) }
            .sortedByDescending { it.epochMillis }
        if (parsed.isEmpty()) return emptySet()

        val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()

        return when (policy) {
            AutoBackupRetention.TEN_SESSIONS -> {
                parsed.take(10).map { it.file }.toSet()
            }

            AutoBackupRetention.THIRTY_DAYS -> {
                val retained = mutableSetOf<File>()
                retained.add(parsed.first().file)

                // 1. Intra-day (including future-dated timestamps under westward travel / clock skew): keep 3 newest
                val todayOrFutureBackups = parsed.filter { !it.localDate.isBefore(today) }
                retained.addAll(todayOrFutureBackups.take(3).map { it.file })

                // 2. Daily: Keep 1 newest backup per day for days 1..30 in the past
                val pastDaysBackups = parsed.filter {
                    val daysAgo = ChronoUnit.DAYS.between(it.localDate, today)
                    daysAgo in 1..30
                }
                pastDaysBackups.groupBy { it.localDate }
                    .values
                    .forEach { dayList ->
                        retained.add(dayList.maxByOrNull { it.epochMillis }!!.file)
                    }

                // 3. Monthly Anchors: Keep 1 newest backup for up to 3 months prior to 30 days ago
                val olderMonthlyBackups = parsed.filter {
                    val daysAgo = ChronoUnit.DAYS.between(it.localDate, today)
                    daysAgo > 30 && ChronoUnit.MONTHS.between(it.yearMonth, YearMonth.from(today)) in 1..3
                }
                olderMonthlyBackups.groupBy { it.yearMonth }
                    .values
                    .forEach { monthList ->
                        retained.add(monthList.maxByOrNull { it.epochMillis }!!.file)
                    }

                retained
            }

            AutoBackupRetention.NINETY_DAYS -> {
                val retained = mutableSetOf<File>()
                retained.add(parsed.first().file)

                // 1. Intra-day (including future-dated timestamps under westward travel / clock skew): keep 3 newest
                val todayOrFutureBackups = parsed.filter { !it.localDate.isBefore(today) }
                retained.addAll(todayOrFutureBackups.take(3).map { it.file })

                // 2. Daily: Keep 1 newest backup per day for days 1..90 in the past
                val pastDaysBackups = parsed.filter {
                    val daysAgo = ChronoUnit.DAYS.between(it.localDate, today)
                    daysAgo in 1..90
                }
                pastDaysBackups.groupBy { it.localDate }
                    .values
                    .forEach { dayList ->
                        retained.add(dayList.maxByOrNull { it.epochMillis }!!.file)
                    }

                retained
            }
        }
    }

    fun pruneDirectory(
        directory: File,
        policy: AutoBackupRetention,
        nowEpochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Int {
        if (!directory.exists() || !directory.isDirectory) return 0
        val allFiles = directory.listFiles { file ->
            file.isFile && file.name.startsWith("Folio_AutoBackup_") && file.name.endsWith(".zip", ignoreCase = true)
        }?.toList() ?: return 0

        val toRetain = computeFilesToRetain(allFiles, policy, nowEpochMillis, zoneId)
        var deletedCount = 0
        allFiles.forEach { file ->
            if (file !in toRetain) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }
        return deletedCount
    }
}
