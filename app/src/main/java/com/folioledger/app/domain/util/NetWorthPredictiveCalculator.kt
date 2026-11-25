// File: app/src/main/java/com/folioledger/app/domain/util/NetWorthPredictiveCalculator.kt
package com.folioledger.app.domain.util

import com.folioledger.app.domain.usecase.AccountingRulesEngine
import com.folioledger.app.domain.model.AccountGroup
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.EmergencyRunwayState
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.MonthlyTrendBar
import com.folioledger.app.domain.model.NetWorthGrowthTrendItem
import com.folioledger.app.domain.model.NetWorthVelocityState
import com.folioledger.app.domain.model.RunwayHealthTier
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.StatsPeriodGranularity
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.model.TransactionItem
import com.folioledger.app.domain.model.TransactionType
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAbs
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeAdd
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeNegate
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator.safeSubtract
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

object NetWorthPredictiveCalculator {

    private val shortMonthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)

    fun computeNetWorthTrend(
        allAccounts: List<AccountSummary>,
        allTransactions: List<TransactionItem>,
        referenceDateEpochDay: Long,
        periodGranularity: StatsPeriodGranularity,
        primaryCurrencyCode: String = "INR",
        secondaryCurrencies: List<SecondaryCurrency> = emptyList(),
        todayEpochDay: Long = LocalDate.now().toEpochDay()
    ): List<NetWorthGrowthTrendItem> {
        val refLocalDate = LocalDate.ofEpochDay(referenceDateEpochDay)

        val targetYearMonths: List<YearMonth> = if (periodGranularity == StatsPeriodGranularity.ANNUALLY) {
            (1..12).map { month -> YearMonth.of(refLocalDate.year, month) }
        } else {
            (5 downTo 0).map { offset -> YearMonth.from(refLocalDate.minusMonths(offset.toLong())) }
        }
        val trendItems = ArrayList<NetWorthGrowthTrendItem>(targetYearMonths.size)

        val activeAccounts = allAccounts.filter { !it.isArchived && it.includeInNetWorth && it.groupType != AccountGroup.DEBIT_CARD }
        if (activeAccounts.isEmpty()) {
            return trendItems
        }

        val primarySpec = SupportedCurrencies.fromCode(primaryCurrencyCode)
        val secondaryMap = secondaryCurrencies.associateBy { it.code.trim().uppercase(Locale.ROOT) }
        val debitParentMap = allAccounts
            .filter { it.groupType == AccountGroup.DEBIT_CARD && it.parentAccountId != null }
            .associate { it.id to it.parentAccountId!! }

        fun effectiveId(id: Long): Long = debitParentMap[id] ?: id

        fun toPrimaryMinor(rawMinor: Long, currencyCode: String): Long {
            val sec = secondaryMap[currencyCode.trim().uppercase(Locale.ROOT)]
            return MoneyFormatterAndCalculator.convertToPrimaryMinor(
                sourceMinor = rawMinor,
                sourceCurrencyCode = currencyCode,
                primaryCurrencyCode = primarySpec.code,
                exchangeRate = sec?.exchangeRateToPrimary
            )
        }

        fun convertCurrency(rawMinor: Long, fromCurrency: String, toCurrency: String): Long =
            MoneyFormatterAndCalculator.convertBetweenCurrenciesMinor(
                sourceMinor = rawMinor,
                fromCurrencyCode = fromCurrency,
                toCurrencyCode = toCurrency,
                primaryCurrencyCode = primarySpec.code,
                secondaryCurrenciesByCode = secondaryMap
            )

        // Performance Optimization: Group relevant transactions by account ID once to eliminate
        // O(Months * Accounts * Transactions) repeated quadratic linear scans and intermediate list allocations.
        // Map DEBIT_CARD swipes to parent bank account, and exclude future unposted transactions (ledgerDateEpochDay > todayEpochDay).
        val txsByAccount = HashMap<Long, MutableList<TransactionItem>>()
        val seenTxIds = HashSet<Long>()
        for (tx in allTransactions) {
            if (tx.ledgerDateEpochDay > todayEpochDay) continue
            if (tx.id != 0L && !seenTxIds.add(tx.id)) continue

            val srcId = effectiveId(tx.accountId)
            val toAcc = if (tx.type == TransactionType.TRANSFER || tx.type == TransactionType.CC_SETTLEMENT) {
                tx.transferToAccountId?.let { effectiveId(it) }
            } else {
                tx.transferToAccountId
            }
            val normalizedTx = tx.copy(accountId = srcId, transferToAccountId = toAcc)
            txsByAccount.getOrPut(srcId) { mutableListOf() }.add(normalizedTx)
            if (toAcc != null && toAcc != srcId &&
                (tx.type == TransactionType.TRANSFER || tx.type == TransactionType.CC_SETTLEMENT)
            ) {
                txsByAccount.getOrPut(toAcc) { mutableListOf() }.add(normalizedTx)
            }
        }

        for (targetYearMonth in targetYearMonths) {
            val endOfMonthEpochDay = targetYearMonth.atEndOfMonth().toEpochDay()

            var monthAssetsMinor = 0L
            var monthLiabilitiesMinor = 0L

            for (acc in activeAccounts) {
                var currentMinor = acc.currentBalance.amountMinor

                val accountTxs = txsByAccount[acc.id]
                if (accountTxs != null) {
                    val singletonAccSet = setOf(acc.id)
                    for (tx in accountTxs) {
                        if (tx.ledgerDateEpochDay <= endOfMonthEpochDay) continue

                        val rawSignedDelta = AccountingRulesEngine.computeSignedDeltaMinorForAccount(
                            tx = tx,
                            effectiveAccountIds = singletonAccSet,
                            isSourceLiabilityAccount = acc.groupType.isLiabilityByDefault
                        )
                        val txCurr = tx.currencyCode.trim().ifEmpty { acc.currencyCode }
                        val signedDelta = if (txCurr.equals(acc.currencyCode, ignoreCase = true)) {
                            rawSignedDelta
                        } else {
                            val converted = convertCurrency(safeAbs(rawSignedDelta), txCurr, acc.currencyCode)
                            if (rawSignedDelta < 0L) safeNegate(converted) else converted
                        }
                        currentMinor = safeSubtract(currentMinor, signedDelta)
                    }
                }

                val convertedMinor = toPrimaryMinor(currentMinor, acc.currencyCode)
                if (convertedMinor >= 0L) {
                    monthAssetsMinor = MoneyFormatterAndCalculator.safeAdd(monthAssetsMinor, convertedMinor)
                } else {
                    monthLiabilitiesMinor = MoneyFormatterAndCalculator.safeAdd(
                        monthLiabilitiesMinor,
                        MoneyFormatterAndCalculator.safeAbs(convertedMinor)
                    )
                }
            }

            val assets = MinorMoney(monthAssetsMinor)
            val liabilities = MinorMoney(monthLiabilitiesMinor)
            val netWorth = MinorMoney(MoneyFormatterAndCalculator.safeSubtract(monthAssetsMinor, monthLiabilitiesMinor))

            trendItems.add(
                NetWorthGrowthTrendItem(
                    epochDay = endOfMonthEpochDay,
                    monthYearText = targetYearMonth.format(shortMonthFormatter),
                    assets = assets,
                    liabilities = liabilities,
                    netWorth = netWorth
                )
            )
        }

        return trendItems
    }

    fun computeNetWorthVelocity(
        netWorthTrend: List<NetWorthGrowthTrendItem>
    ): NetWorthVelocityState {
        if (netWorthTrend.isEmpty()) {
            return NetWorthVelocityState()
        }
        val currentItem = netWorthTrend.last()
        val currentNW = currentItem.netWorth
        val previousItem = if (netWorthTrend.size >= 2) netWorthTrend[netWorthTrend.size - 2] else currentItem
        val previousNW = previousItem.netWorth

        val currBd = java.math.BigDecimal.valueOf(currentNW.amountMinor)
        val prevBd = java.math.BigDecimal.valueOf(previousNW.amountMinor)
        val diffBd = currBd.subtract(prevBd)

        val safeDiffMinor = diffBd.max(java.math.BigDecimal.valueOf(Long.MIN_VALUE))
            .min(java.math.BigDecimal.valueOf(Long.MAX_VALUE))
            .toLong()
        val absChange = MinorMoney(safeDiffMinor)
        val isPositive = diffBd.signum() >= 0

        val pct = if (prevBd.signum() != 0) {
            diffBd.multiply(java.math.BigDecimal.valueOf(100L))
                .divide(prevBd.abs(), 2, java.math.RoundingMode.HALF_UP)
                .toFloat()
        } else {
            when {
                diffBd.signum() > 0 -> 100f
                diffBd.signum() < 0 -> -100f
                else -> 0f
            }
        }
        return NetWorthVelocityState(
            currentNetWorth = currentNW,
            previousMonthNetWorth = previousNW,
            absoluteChange = absChange,
            percentageChange = pct,
            isPositive = isPositive
        )
    }

    fun computeEmergencyRunway(
        allAccounts: List<AccountSummary>,
        trendBars: List<MonthlyTrendBar>,
        primaryCurrencyCode: String,
        secondaryCurrencies: List<SecondaryCurrency>,
        includesInvestments: Boolean
    ): EmergencyRunwayState {
        val primarySpec = SupportedCurrencies.fromCode(primaryCurrencyCode)
        val secondaryMap = secondaryCurrencies.associateBy { it.code.trim().uppercase(Locale.ROOT) }

        var totalLiquidMinor = 0L
        var totalInvestmentsMinor = 0L

        for (acc in allAccounts) {
            if (acc.isArchived || acc.currentBalance.amountMinor == 0L) continue

            val isLiquid = acc.groupType == AccountGroup.BANK_ACCOUNT ||
                    acc.groupType == AccountGroup.CASH ||
                    acc.groupType == AccountGroup.SAVINGS ||
                    acc.groupType == AccountGroup.PREPAID_WALLET
            val isInvestment = acc.groupType == AccountGroup.INVESTMENT

            if (!isLiquid && !isInvestment) continue

            val rawBalanceMinor = acc.currentBalance.amountMinor
            val sec = secondaryMap[acc.currencyCode.trim().uppercase(Locale.ROOT)]
            val convertedBalanceMinor = MoneyFormatterAndCalculator.convertToPrimaryMinor(
                sourceMinor = rawBalanceMinor,
                sourceCurrencyCode = acc.currencyCode,
                primaryCurrencyCode = primarySpec.code,
                exchangeRate = sec?.exchangeRateToPrimary
            )

            if (isLiquid) {
                totalLiquidMinor = safeAdd(totalLiquidMinor, convertedBalanceMinor)
            } else if (isInvestment && convertedBalanceMinor > 0L) {
                totalInvestmentsMinor = safeAdd(totalInvestmentsMinor, convertedBalanceMinor)
            }
        }

        val activeBars = trendBars.dropWhile { it.totalIncome.amountMinor <= 0L && it.totalExpense.amountMinor <= 0L }
        val averageBurnMinor = if (activeBars.isNotEmpty()) {
            val totalBurn = activeBars.fold(0L) { sum, bar ->
                safeAdd(sum, bar.totalExpense.amountMinor.coerceAtLeast(0L))
            }
            totalBurn / activeBars.size.toLong()
        } else {
            0L
        }

        val applicableFundsMinor = if (includesInvestments) {
            safeAdd(totalLiquidMinor, totalInvestmentsMinor).coerceAtLeast(0L)
        } else {
            totalLiquidMinor.coerceAtLeast(0L)
        }

        val runwayMonths = if (averageBurnMinor > 0L) {
            (applicableFundsMinor.toFloat() / averageBurnMinor.toFloat()).coerceIn(0f, 99.9f)
        } else {
            if (applicableFundsMinor > 0L) 99.9f else 0f
        }

        val healthTier = when {
            runwayMonths >= 6.0f -> RunwayHealthTier.RESILIENT
            runwayMonths >= 3.0f -> RunwayHealthTier.ADEQUATE
            else -> RunwayHealthTier.VULNERABLE
        }

        return EmergencyRunwayState(
            totalLiquidFunds = MinorMoney(totalLiquidMinor),
            totalInvestmentFunds = MinorMoney(totalInvestmentsMinor),
            averageMonthlyBurnMinor = averageBurnMinor,
            runwayMonths = runwayMonths,
            healthTier = healthTier,
            targetMonths = 6.0f,
            includesInvestments = includesInvestments
        )
    }
}
