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

import com.folioledger.app.data.repository.runCatchingNonCancellation
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class RepositoryExtensionsTest {

    @Test
    fun `runCatchingNonCancellation returns Result success on regular return`() {
        val result = runCatchingNonCancellation {
            42
        }
        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `runCatchingNonCancellation returns Result failure on standard exception`() {
        val result = runCatchingNonCancellation {
            throw IOException("Disk error")
        }
        assertTrue(result.isFailure)
        assertEquals("Disk error", result.exceptionOrNull()?.message)
    }

    @Test
    fun `runCatchingNonCancellation rethrows CancellationException for coroutine structured concurrency`() {
        try {
            runCatchingNonCancellation {
                throw CancellationException("Job was cancelled")
            }
            fail("Expected CancellationException to be rethrown, but it was caught")
        } catch (e: CancellationException) {
            assertEquals("Job was cancelled", e.message)
        }
    }

    @Test
    fun `tag rename updates and deduplicates bookmark tagsCsv`() {
        val oldName = "food"
        val newName = "dining"
        val originalCsv = "food, groceries, Food, trip"

        val updatedCsv = com.folioledger.app.data.local.dao.updateBookmarkTagsCsv(
            tagsCsv = originalCsv,
            oldName = oldName,
            newName = newName
        )

        assertEquals("dining,groceries,trip", updatedCsv)
    }
}
