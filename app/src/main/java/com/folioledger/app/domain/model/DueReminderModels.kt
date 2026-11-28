/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/domain/model/DueReminderModels.kt
package com.folioledger.app.domain.model

import androidx.compose.runtime.Immutable
import java.time.LocalDate

@Immutable
data class CardDueReminderItem(
    val accountId: Long,
    val accountName: String,
    val currencyCode: String,
    val amountDue: MinorMoney,
    val dueDate: LocalDate,
    val daysRemaining: Long,
    val isOverdue: Boolean,
)

@Immutable
data class BillDueReminderItem(
    val ruleId: Long,
    val title: String,
    val payeeOrDescription: String,
    val currencyCode: String,
    val amount: MinorMoney,
    val dueDate: LocalDate,
    val daysRemaining: Long,
    val accountName: String,
)
