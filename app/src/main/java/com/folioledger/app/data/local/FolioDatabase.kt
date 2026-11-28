/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/data/local/FolioDatabase.kt
package com.folioledger.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.folioledger.app.data.local.dao.AccountDao
import com.folioledger.app.data.local.dao.BudgetAndRecurringDao
import com.folioledger.app.data.local.dao.CategoryAndTagDao
import com.folioledger.app.data.local.dao.TransactionDao
import com.folioledger.app.data.local.entity.AccountEntity
import com.folioledger.app.data.local.entity.BookmarkEntity
import com.folioledger.app.data.local.entity.BudgetEntity
import com.folioledger.app.data.local.entity.CategoryEntity
import com.folioledger.app.data.local.entity.FolioTypeConverters
import com.folioledger.app.data.local.entity.InstallmentPlanEntity
import com.folioledger.app.data.local.entity.RecurringRuleEntity
import com.folioledger.app.data.local.entity.TagEntity
import com.folioledger.app.data.local.entity.TransactionEntity
import com.folioledger.app.data.local.entity.TransactionSplitEntity
import com.folioledger.app.data.local.entity.TransactionTagCrossRef
import java.io.File

const val DATABASE_VERSION: Int = 6

@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        TransactionSplitEntity::class,
        TagEntity::class,
        TransactionTagCrossRef::class,
        BookmarkEntity::class,
        BudgetEntity::class,
        RecurringRuleEntity::class,
        InstallmentPlanEntity::class,
    ],
    version = DATABASE_VERSION,
    exportSchema = true,
)
@TypeConverters(FolioTypeConverters::class)
abstract class FolioDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun categoryAndTagDao(): CategoryAndTagDao
    abstract fun transactionDao(): TransactionDao
    abstract fun budgetAndRecurringDao(): BudgetAndRecurringDao

    fun checkpointDatabase() {
        runCatching {
            openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                cursor.moveToFirst()
            }
        }
    }

    companion object {
        const val DATABASE_NAME: String = "folio_ledger.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN initialUnbilledMinor INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_accountId_ledgerDateEpochDay` ON `transactions` (`accountId`, `ledgerDateEpochDay`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_transferToAccountId_ledgerDateEpochDay` ON `transactions` (`transferToAccountId`, `ledgerDateEpochDay`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_accounts_isPinned` ON `accounts` (`isPinned`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. TransactionSplitEntity: Allow null categoryId and SET_NULL on delete
                db.execSQL("DROP TABLE IF EXISTS `transaction_splits_new`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transaction_splits_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `transactionId` INTEGER NOT NULL,
                        `categoryId` INTEGER,
                        `amountMinor` INTEGER NOT NULL,
                        `note` TEXT NOT NULL,
                        FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `transaction_splits_new` (`id`, `transactionId`, `categoryId`, `amountMinor`, `note`)
                    SELECT `id`, `transactionId`, `categoryId`, `amountMinor`, `note` FROM `transaction_splits`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `transaction_splits`")
                db.execSQL("ALTER TABLE `transaction_splits_new` RENAME TO `transaction_splits`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_splits_transactionId` ON `transaction_splits` (`transactionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_splits_categoryId` ON `transaction_splits` (`categoryId`)")

                // 2. TransactionEntity: Optimize indices
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_accountId`")
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_transferToAccountId`")
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_ledgerDateEpochDay`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_ledgerDateEpochDay_timestampEpochMillis_id` ON `transactions` (`ledgerDateEpochDay`, `timestampEpochMillis`, `id`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `budgets_new`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `budgets_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `categoryId` INTEGER,
                        `name` TEXT NOT NULL,
                        `limitAmountMinor` INTEGER NOT NULL,
                        `alertThresholdPercent` INTEGER NOT NULL,
                        `periodType` TEXT NOT NULL,
                        `startDateEpochDay` INTEGER,
                        `endDateEpochDay` INTEGER,
                        `rolloverEnabled` INTEGER NOT NULL,
                        `rolloverIncludeDeficit` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `budgets_new` (
                        `id`, `categoryId`, `name`, `limitAmountMinor`, `alertThresholdPercent`,
                        `periodType`, `startDateEpochDay`, `endDateEpochDay`,
                        `rolloverEnabled`, `rolloverIncludeDeficit`, `updatedAtEpochMillis`
                    )
                    SELECT
                        `id`, `categoryId`, '', `limitAmountMinor`, `alertThresholdPercent`,
                        'MONTHLY', NULL, NULL,
                        0, 0, `updatedAtEpochMillis`
                    FROM `budgets`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `budgets`")
                db.execSQL("ALTER TABLE `budgets_new` RENAME TO `budgets`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_budgets_categoryId` ON `budgets` (`categoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_budgets_periodType` ON `budgets` (`periodType`)")
            }
        }

        @Volatile
        private var INSTANCE: FolioDatabase? = null

        fun getInstance(context: Context): FolioDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: run {
                val appContext = context.applicationContext
                val createdPreMigrationBackup = DatabaseBackupManager.createPreMigrationBackup(appContext)
                var candidateDb: FolioDatabase? = null
                val db = runCatching {
                    buildDatabase(appContext).also {
                        candidateDb = it
                        it.openHelper.writableDatabase
                    }
                }.getOrElse { error ->
                    INSTANCE = null
                    runCatching { candidateDb?.close() }
                    if (createdPreMigrationBackup) {
                        DatabaseBackupManager.restorePreMigrationBackup(appContext)
                    }
                    throw IllegalStateException(
                        if (createdPreMigrationBackup) {
                            "Database initialization or migration failed. Restored and preserved pre-migration backup."
                        } else {
                            "Database initialization failed."
                        },
                        error,
                    )
                }
                if (createdPreMigrationBackup) {
                    // Flush migrated user_version header from WAL into folio_ledger.db before removing rollback snapshot
                    db.checkpointDatabase()
                    DatabaseBackupManager.clearPreMigrationBackup(appContext)
                }
                INSTANCE = db
                db
            }
        }

        private fun buildDatabase(appContext: Context): FolioDatabase = Room.databaseBuilder(
            appContext,
            FolioDatabase::class.java,
            DATABASE_NAME,
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    DefaultCategorySeeder.seedSynchronously(db)
                }
            })
            .build()
    }
}

object DatabaseBackupManager {
    private const val DB_NAME = FolioDatabase.DATABASE_NAME
    private const val BAK_SUFFIX = ".pre_migration_bak"
    const val CURRENT_SCHEMA_VERSION = DATABASE_VERSION

    private fun readOnDiskVersion(dbFile: File): Int? {
        if (!dbFile.exists() || dbFile.length() < 100L) return null
        return runCatching {
            java.io.RandomAccessFile(dbFile, "r").use { raf ->
                raf.seek(60L)
                raf.readInt()
            }
        }.getOrNull()
    }

    fun clearPreMigrationBackup(context: Context) {
        runCatching {
            val dbFile = context.getDatabasePath(DB_NAME)
            val parentDir = dbFile.parentFile ?: return@runCatching
            File(parentDir, "$DB_NAME$BAK_SUFFIX").delete()
            File(parentDir, "$DB_NAME-wal$BAK_SUFFIX").delete()
            File(parentDir, "$DB_NAME-shm$BAK_SUFFIX").delete()
        }
    }

    fun createPreMigrationBackup(context: Context): Boolean {
        return runCatching {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (dbFile.exists() && dbFile.length() > 0L) {
                val onDiskVersion = readOnDiskVersion(dbFile)
                if (onDiskVersion != null && onDiskVersion >= CURRENT_SCHEMA_VERSION) {
                    // Database is already on the current schema version; clear stale backups and skip redundant copy
                    clearPreMigrationBackup(context)
                    return@runCatching false
                }
                val parentDir = dbFile.parentFile ?: return@runCatching false
                val bakFile = File(parentDir, "$DB_NAME$BAK_SUFFIX")
                dbFile.copyTo(bakFile, overwrite = true)

                val walFile = File(parentDir, "$DB_NAME-wal")
                val walBak = File(parentDir, "$DB_NAME-wal$BAK_SUFFIX")
                if (walFile.exists()) walFile.copyTo(walBak, overwrite = true) else walBak.delete()

                val shmFile = File(parentDir, "$DB_NAME-shm")
                val shmBak = File(parentDir, "$DB_NAME-shm$BAK_SUFFIX")
                if (shmFile.exists()) shmFile.copyTo(shmBak, overwrite = true) else shmBak.delete()
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    fun restorePreMigrationBackup(context: Context): Boolean {
        return runCatching {
            val dbFile = context.getDatabasePath(DB_NAME)
            val parentDir = dbFile.parentFile ?: return@runCatching false
            val bakFile = File(parentDir, "$DB_NAME$BAK_SUFFIX")
            if (bakFile.exists()) {
                bakFile.copyTo(dbFile, overwrite = true)

                val walFile = File(parentDir, "$DB_NAME-wal")
                val walBak = File(parentDir, "$DB_NAME-wal$BAK_SUFFIX")
                if (walBak.exists()) walBak.copyTo(walFile, overwrite = true) else walFile.delete()

                val shmFile = File(parentDir, "$DB_NAME-shm")
                val shmBak = File(parentDir, "$DB_NAME-shm$BAK_SUFFIX")
                if (shmBak.exists()) shmBak.copyTo(shmFile, overwrite = true) else shmFile.delete()
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }
}
