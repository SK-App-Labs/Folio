/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.data.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RoomSchemaIntegrityTest {

    private fun locateSchemasDir(): File = listOf(
        File("schemas/com.folioledger.app.data.local.FolioDatabase"),
        File("app/schemas/com.folioledger.app.data.local.FolioDatabase"),
        File("../app/schemas/com.folioledger.app.data.local.FolioDatabase"),
    ).firstOrNull { it.exists() && it.isDirectory }
        ?: error("Could not locate Room schemas directory from ${File("").absolutePath}")

    @Test
    fun exportedRoomSchemasExistFromVersion1ToCurrentAndDefineCoreTables() {
        val schemasDir = locateSchemasDir()
        assertEquals(6, DATABASE_VERSION)

        for (v in 1..DATABASE_VERSION) {
            val schemaFile = File(schemasDir, "$v.json")
            assertTrue("Missing Room schema export $v.json", schemaFile.exists())
            val root = JSONObject(schemaFile.readText())
            val dbObj = root.getJSONObject("database")
            assertEquals(v, dbObj.getInt("version"))
        }

        val latestRoot = JSONObject(File(schemasDir, "$DATABASE_VERSION.json").readText())
        val entities = latestRoot.getJSONObject("database").getJSONArray("entities")
        val tableNames = (0 until entities.length()).map { idx ->
            entities.getJSONObject(idx).getString("tableName")
        }.toSet()

        val expectedTables = setOf(
            "accounts",
            "categories",
            "transactions",
            "transaction_splits",
            "tags",
            "transaction_tags",
            "bookmarks",
            "budgets",
            "recurring_rules",
            "installment_plans",
        )
        assertTrue("Missing expected tables in v$DATABASE_VERSION schema: ${expectedTables - tableNames}", tableNames.containsAll(expectedTables))
    }

    @Test
    fun databaseMigrationsFormContiguousChainFrom1ToCurrent() {
        val migrations = listOf(
            FolioDatabase.MIGRATION_1_2,
            FolioDatabase.MIGRATION_2_3,
            FolioDatabase.MIGRATION_3_4,
            FolioDatabase.MIGRATION_4_5,
            FolioDatabase.MIGRATION_5_6,
        )
        assertEquals(DATABASE_VERSION - 1, migrations.size)
        migrations.forEachIndexed { idx, migration ->
            val expectedStart = idx + 1
            assertEquals(expectedStart, migration.startVersion)
            assertEquals(expectedStart + 1, migration.endVersion)
        }
    }
}
