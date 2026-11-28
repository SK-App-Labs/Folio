/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/domain/util/SavingsGoalCalculator.kt
package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSavingsGoal
import com.folioledger.app.domain.model.AccountSavingsGoalConfig
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.MonthlySavingsProgress
import com.folioledger.app.domain.model.PeriodLedgerSummary
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAbs
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAdd
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeSubtract
import org.json.JSONObject
import java.util.Locale

object SavingsGoalCalculator {

    /**
     * Parses the persistent savings goals JSON map: `{ "accountId": { ...config } }`.
     */
    fun parseGoals(json: String?): Map<Long, AccountSavingsGoalConfig> {
        if (json.isNullOrBlank() || json.trim() == "{}") return emptyMap()
        return runCatching {
            val root = JSONObject(json)
            val map = mutableMapOf<Long, AccountSavingsGoalConfig>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val accountId = key.toLongOrNull() ?: continue
                val obj = root.optJSONObject(key) ?: continue
                runCatching {
                    val targetMinor = obj.optLong("targetAmountMinor", 500_000_00L)
                    if (targetMinor > 0L) {
                        map[accountId] = AccountSavingsGoalConfig(
                            accountId = accountId,
                            goalTitle = obj.optString("goalTitle", "Savings Goal"),
                            targetAmountMinor = targetMinor,
                            targetDateEpochDay = if (obj.has("targetDateEpochDay") && !obj.isNull("targetDateEpochDay")) {
                                obj.getLong("targetDateEpochDay")
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            map
        }.getOrDefault(emptyMap())
    }

    /**
     * Upserts a goal config for [config.accountId] into the existing JSON string.
     */
    fun upsertGoal(
        currentJson: String?,
        config: AccountSavingsGoalConfig,
    ): String = runCatching {
        val root = if (currentJson.isNullOrBlank() || currentJson.trim() == "{}") JSONObject() else JSONObject(currentJson)
        val obj = JSONObject().apply {
            put("accountId", config.accountId)
            put("goalTitle", config.goalTitle)
            put("targetAmountMinor", config.targetAmountMinor)
            put("targetDateEpochDay", config.targetDateEpochDay ?: JSONObject.NULL)
        }
        root.put(config.accountId.toString(), obj)
        root.toString()
    }.getOrDefault(currentJson ?: "{}")

    /**
     * Removes the goal config for [accountId] from the existing JSON string.
     */
    fun removeGoal(currentJson: String?, accountId: Long): String = runCatching {
        if (currentJson.isNullOrBlank() || currentJson.trim() == "{}") return "{}"
        val root = JSONObject(currentJson)
        root.remove(accountId.toString())
        root.toString()
    }.getOrDefault(currentJson ?: "{}")

    /**
     * Computes account-backed savings goals combining configs with live ledger account balances.
     */
    fun computeAccountSavingsGoals(
        allAccounts: List<AccountSummary>,
        goalsJson: String?,
    ): List<AccountSavingsGoal> = computeAccountSavingsGoals(allAccounts, parseGoals(goalsJson))

    fun computeAccountSavingsGoals(
        allAccounts: List<AccountSummary>,
        configs: Map<Long, AccountSavingsGoalConfig>,
    ): List<AccountSavingsGoal> {
        if (configs.isEmpty()) return emptyList()

        val accountMap = allAccounts.associateBy { it.id }
        return configs.values.mapNotNull { config ->
            val account = accountMap[config.accountId] ?: return@mapNotNull null
            if (account.isArchived) return@mapNotNull null
            val currentBalance = account.currentBalance
            val targetAmount = MinorMoney(config.targetAmountMinor)
            val currentMinor = currentBalance.amountMinor
            val targetMinor = config.targetAmountMinor

            val progressPct = if (targetMinor > 0L) {
                MoneyFormatterAndCalculator.calculatePercentage(currentMinor, targetMinor).coerceAtLeast(0f)
            } else {
                0f
            }

            val remaining = if (currentMinor < targetMinor) {
                MinorMoney(safeSubtract(targetMinor, currentMinor))
            } else {
                MinorMoney.ZERO
            }

            AccountSavingsGoal(
                accountId = account.id,
                accountName = account.name,
                accountGroup = account.groupType,
                colorHex = account.colorHex,
                iconName = account.iconName,
                goalTitle = config.goalTitle,
                targetAmount = targetAmount,
                currentBalance = currentBalance,
                progressPercentage = progressPct,
                remainingAmount = remaining,
                targetDateEpochDay = config.targetDateEpochDay,
                isAchieved = targetMinor > 0L && currentMinor >= targetMinor,
                currencyCode = account.currencyCode,
            )
        }.sortedWith(
            compareBy<AccountSavingsGoal> { it.isAchieved }
                .thenByDescending { it.progressPercentage },
        )
    }

    /**
     * Computes the "Pay Yourself First" monthly savings progress by tracking net wealth transfers
     * from operational accounts into SAVINGS and INVESTMENT accounts.
     */
    fun computeMonthlySavingsProgress(
        allAccounts: List<AccountSummary>,
        periodTransactions: List<TransactionItem>,
        periodSummary: PeriodLedgerSummary,
        monthlySavingsTargetMinor: Long,
        primaryCurrencyCode: String = "INR",
        secondaryCurrencies: List<SecondaryCurrency> = emptyList(),
    ): MonthlySavingsProgress {
        val accountMap = allAccounts.associateBy { it.id }
        val wealthGroups = setOf(AccountGroup.SAVINGS, AccountGroup.INVESTMENT)
        val primarySpec = SupportedCurrencies.fromCode(primaryCurrencyCode)
        val secondaryMap = secondaryCurrencies.associateBy { it.code.trim().uppercase(Locale.ROOT) }

        fun toPrimaryMinor(amountMinor: Long, currencyCode: String): Long {
            val sec = secondaryMap[currencyCode.trim().uppercase(Locale.ROOT)]
            return MoneyFormatterAndCalculator.convertToPrimaryMinor(
                sourceMinor = amountMinor,
                sourceCurrencyCode = currencyCode,
                primaryCurrencyCode = primarySpec.code,
                exchangeRate = sec?.exchangeRateToPrimary,
            )
        }

        fun effectiveAccount(accountId: Long?): AccountSummary? {
            if (accountId == null) return null
            val acc = accountMap[accountId] ?: return null
            return if (acc.groupType == AccountGroup.DEBIT_CARD && acc.parentAccountId != null) {
                accountMap[acc.parentAccountId] ?: acc
            } else {
                acc
            }
        }

        var savingsDepositsMinor = 0L
        var savingsWithdrawalsMinor = 0L

        for (tx in periodTransactions) {
            when (tx.type) {
                TransactionType.TRANSFER,
                TransactionType.CC_SETTLEMENT,
                -> {
                    val source = effectiveAccount(tx.accountId)
                    val dest = effectiveAccount(tx.transferToAccountId)

                    val sourceIsWealth = source?.groupType in wealthGroups
                    val destIsWealth = dest?.groupType in wealthGroups
                    val txCurrency = tx.currencyCode.ifBlank { source?.currencyCode ?: primaryCurrencyCode }
                    val convertedAmount = toPrimaryMinor(safeAbs(tx.amount.amountMinor), txCurrency)

                    if (!sourceIsWealth && destIsWealth) {
                        // Deposit into wealth accumulation account
                        savingsDepositsMinor = safeAdd(savingsDepositsMinor, convertedAmount)
                    } else if (sourceIsWealth && !destIsWealth) {
                        // Withdrawal from wealth accumulation account (including CC_SETTLEMENT from savings)
                        savingsWithdrawalsMinor = safeAdd(savingsWithdrawalsMinor, convertedAmount)
                    }
                    if (sourceIsWealth && tx.transferFee.amountMinor != 0L) {
                        val convertedFee = toPrimaryMinor(safeAbs(tx.transferFee.amountMinor), txCurrency)
                        savingsWithdrawalsMinor = safeAdd(savingsWithdrawalsMinor, convertedFee)
                    }
                }

                TransactionType.INCOME, TransactionType.REFUND -> {
                    val acc = effectiveAccount(tx.accountId)
                    if (acc?.groupType in wealthGroups) {
                        val txCurrency = tx.currencyCode.ifBlank { acc?.currencyCode ?: primaryCurrencyCode }
                        val convertedAmount = toPrimaryMinor(safeAbs(tx.amount.amountMinor), txCurrency)
                        savingsDepositsMinor = safeAdd(savingsDepositsMinor, convertedAmount)
                    }
                }

                TransactionType.EXPENSE -> {
                    val acc = effectiveAccount(tx.accountId)
                    if (acc?.groupType in wealthGroups) {
                        val txCurrency = tx.currencyCode.ifBlank { acc?.currencyCode ?: primaryCurrencyCode }
                        val totalMinor = safeAdd(safeAbs(tx.amount.amountMinor), safeAbs(tx.transferFee.amountMinor))
                        val convertedAmount = toPrimaryMinor(totalMinor, txCurrency)
                        savingsWithdrawalsMinor = safeAdd(savingsWithdrawalsMinor, convertedAmount)
                    }
                }

                else -> Unit
            }
        }

        val netSavedMinor = safeSubtract(savingsDepositsMinor, savingsWithdrawalsMinor).coerceAtLeast(0L)
        val savedMoney = MinorMoney(netSavedMinor)
        val targetMoney = MinorMoney(monthlySavingsTargetMinor)

        val progressPercentage = if (monthlySavingsTargetMinor > 0L) {
            MoneyFormatterAndCalculator.calculatePercentage(netSavedMinor, monthlySavingsTargetMinor)
                .coerceAtLeast(0f)
        } else {
            0f
        }

        val remainingMoney = if (netSavedMinor < monthlySavingsTargetMinor) {
            MinorMoney(safeSubtract(monthlySavingsTargetMinor, netSavedMinor))
        } else {
            MinorMoney.ZERO
        }

        // Unallocated Cashflow Surplus = Income - Expenses - Net Savings Transferred
        val netCashflowMinor = periodSummary.netCashflow.amountMinor
        val unallocatedMinor = safeSubtract(netCashflowMinor, netSavedMinor).coerceAtLeast(0L)
        val unallocatedSurplus = MinorMoney(unallocatedMinor)

        return MonthlySavingsProgress(
            targetAmount = targetMoney,
            savedAmount = savedMoney,
            remainingAmount = remainingMoney,
            progressPercentage = progressPercentage,
            unallocatedSurplus = unallocatedSurplus,
            isConfigured = monthlySavingsTargetMinor > 0L,
        )
    }
}
