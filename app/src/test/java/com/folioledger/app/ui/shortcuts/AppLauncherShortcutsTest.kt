/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.shortcuts

import com.folioledger.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AppLauncherShortcutsTest {

    private fun extractShortcutAction(actionStr: String?): TransactionType? {
        if (actionStr.isNullOrBlank()) return null
        return when (actionStr.uppercase(Locale.US)) {
            "EXPENSE" -> TransactionType.EXPENSE
            "INCOME" -> TransactionType.INCOME
            "TRANSFER" -> TransactionType.TRANSFER
            else -> null
        }
    }

    @Test
    fun extractShortcutAction_validActions_mappedToCorrectTransactionType() {
        assertEquals(TransactionType.EXPENSE, extractShortcutAction("EXPENSE"))
        assertEquals(TransactionType.EXPENSE, extractShortcutAction("expense"))
        assertEquals(TransactionType.INCOME, extractShortcutAction("INCOME"))
        assertEquals(TransactionType.INCOME, extractShortcutAction("income"))
        assertEquals(TransactionType.TRANSFER, extractShortcutAction("TRANSFER"))
        assertEquals(TransactionType.TRANSFER, extractShortcutAction("transfer"))
    }

    @Test
    fun extractShortcutAction_invalidOrNull_returnsNull() {
        assertNull(extractShortcutAction(null))
        assertNull(extractShortcutAction(""))
        assertNull(extractShortcutAction("   "))
        assertNull(extractShortcutAction("SETTINGS"))
        assertNull(extractShortcutAction("UNKNOWN"))
    }
}
