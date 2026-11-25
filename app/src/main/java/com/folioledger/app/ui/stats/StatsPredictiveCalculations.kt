/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.stats

import com.folioledger.app.domain.model.AccountShortfallAlert
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.EmergencyRunwayState
import com.folioledger.app.domain.model.MonthlyTrendBar
import com.folioledger.app.domain.model.NetWorthGrowthTrendItem
import com.folioledger.app.domain.model.NetWorthVelocityState
import com.folioledger.app.domain.model.PayeeDrillDownState
import com.folioledger.app.domain.model.RecurringCommitmentItem
import com.folioledger.app.domain.model.RepeatFrequency
import com.folioledger.app.domain.model.SafeToSpendState
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.TagProjectDrillDownState
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.util.MerchantAndTagAnalyticsCalculator
import com.folioledger.app.domain.util.NetWorthPredictiveCalculator
import com.folioledger.app.domain.util.SafeToSpendCalculator

object StatsPredictiveCalculations {

    fun computeNetWorthVelocity(
        netWorthTrend: List<NetWorthGrowthTrendItem>
    ): NetWorthVelocityState = NetWorthPredictiveCalculator.computeNetWorthVelocity(netWorthTrend)

    fun computeEmergencyRunway(
        allAccounts: List<AccountSummary>,
        trendBars: List<MonthlyTrendBar>,
        primaryCurrencyCode: String,
        secondaryCurrencies: List<SecondaryCurrency>,
        includesInvestments: Boolean
    ): EmergencyRunwayState = NetWorthPredictiveCalculator.computeEmergencyRunway(
        allAccounts = allAccounts,
        trendBars = trendBars,
        primaryCurrencyCode = primaryCurrencyCode,
        secondaryCurrencies = secondaryCurrencies,
        includesInvestments = includesInvestments
    )

    fun computePayeeDrillDownState(
        payeeName: String,
        allTxs: List<TransactionItem>,
        trendBars: List<MonthlyTrendBar>,
        startEpochDay: Long,
        endEpochDay: Long,
        isAllTime: Boolean,
        primaryCurrencyCode: String,
        secondaryCurrencies: List<SecondaryCurrency> = emptyList()
    ): PayeeDrillDownState = MerchantAndTagAnalyticsCalculator.computePayeeDrillDownState(
        payeeName = payeeName,
        allTxs = allTxs,
        trendBars = trendBars,
        startEpochDay = startEpochDay,
        endEpochDay = endEpochDay,
        isAllTime = isAllTime,
        primaryCurrencyCode = primaryCurrencyCode,
        secondaryCurrencies = secondaryCurrencies
    )

    fun computeTagProjectDrillDownState(
        tagName: String,
        allTxs: List<TransactionItem>,
        trendBars: List<MonthlyTrendBar>,
        startEpochDay: Long,
        endEpochDay: Long,
        isAllTime: Boolean,
        primaryCurrencyCode: String,
        secondaryCurrencies: List<SecondaryCurrency> = emptyList()
    ): TagProjectDrillDownState = MerchantAndTagAnalyticsCalculator.computeTagProjectDrillDownState(
        tagName = tagName,
        allTxs = allTxs,
        trendBars = trendBars,
        startEpochDay = startEpochDay,
        endEpochDay = endEpochDay,
        isAllTime = isAllTime,
        primaryCurrencyCode = primaryCurrencyCode,
        secondaryCurrencies = secondaryCurrencies
    )

    fun projectRecurringOccurrencesInHorizon(
        startDay: Long,
        frequency: RepeatFrequency,
        todayEpochDay: Long,
        horizonEpochDay: Long,
        anchorDayOfMonth: Int? = null,
        endDateEpochDay: Long? = null
    ): List<Long> = SafeToSpendCalculator.projectRecurringOccurrencesInHorizon(
        startDay = startDay,
        frequency = frequency,
        todayEpochDay = todayEpochDay,
        horizonEpochDay = horizonEpochDay,
        anchorDayOfMonth = anchorDayOfMonth,
        endDateEpochDay = endDateEpochDay
    )

    fun computeSafeToSpend(
        allAccounts: List<AccountSummary>,
        recurringRules: List<RecurringCommitmentItem>,
        futureTransactions: List<TransactionItem>,
        todayEpochDay: Long,
        periodStartEpochDay: Long,
        periodEndEpochDay: Long,
        primaryCurrencyCode: String,
        secondaryCurrencies: List<SecondaryCurrency> = emptyList()
    ): SafeToSpendState = SafeToSpendCalculator.computeSafeToSpend(
        allAccounts = allAccounts,
        recurringRules = recurringRules,
        futureTransactions = futureTransactions,
        todayEpochDay = todayEpochDay,
        periodStartEpochDay = periodStartEpochDay,
        periodEndEpochDay = periodEndEpochDay,
        primaryCurrencyCode = primaryCurrencyCode,
        secondaryCurrencies = secondaryCurrencies
    )

    fun computeShortfallAlerts(
        allAccounts: List<AccountSummary>,
        recurringRules: List<RecurringCommitmentItem>,
        futureTransactions: List<TransactionItem>,
        todayEpochDay: Long,
        primaryCurrencyCode: String,
        secondaryCurrencies: List<SecondaryCurrency> = emptyList()
    ): List<AccountShortfallAlert> = SafeToSpendCalculator.computeShortfallAlerts(
        allAccounts = allAccounts,
        recurringRules = recurringRules,
        futureTransactions = futureTransactions,
        todayEpochDay = todayEpochDay,
        primaryCurrencyCode = primaryCurrencyCode,
        secondaryCurrencies = secondaryCurrencies
    )
}
