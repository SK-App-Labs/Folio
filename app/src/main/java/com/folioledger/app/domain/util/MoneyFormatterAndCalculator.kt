/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/domain/util/MoneyFormatterAndCalculator.kt
package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SecondaryCurrency
import com.folioledger.app.domain.model.SupportedCurrencies
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale

object MoneyFormatterAndCalculator {

    private const val MAX_EXPRESSION_LENGTH = 512
    private const val MAX_PARSER_DEPTH = 64

    private val COMPACT_CRORE_THRESHOLD = BigDecimal("9950000")
    private val COMPACT_CRORE_DIVISOR = BigDecimal("10000000")
    private val COMPACT_LAKH_THRESHOLD = BigDecimal("99500")
    private val COMPACT_LAKH_DIVISOR = BigDecimal("100000")
    private val COMPACT_MILLION_THRESHOLD = BigDecimal("999500")
    private val COMPACT_MILLION_DIVISOR = BigDecimal("1000000")
    private val COMPACT_THOUSAND_THRESHOLD = BigDecimal("999.5")
    private val COMPACT_TEN_THOUSAND_THRESHOLD = BigDecimal("9950")
    private val COMPACT_THOUSAND_DIVISOR = BigDecimal("1000")

    private val POWERS_OF_TEN = longArrayOf(
        1L,
        10L,
        100L,
        1_000L,
        10_000L,
        100_000L,
        1_000_000L,
    )

    fun formatMinorMoney(
        money: MinorMoney,
        currencySpec: CurrencySpec,
        grouping: NumberGroupingSystem = currencySpec.defaultGrouping,
        includeSymbol: Boolean = true,
        showPlusSignForPositive: Boolean = false,
    ): String {
        val fractionDigits = currencySpec.fractionDigits.coerceAtLeast(0)
        val signPrefix = when {
            money.isNegative -> "-"
            money.isPositive && showPlusSignForPositive -> "+"
            else -> ""
        }

        val rawMinor = money.amountMinor
        if (rawMinor == Long.MIN_VALUE || fractionDigits > 6) {
            return formatBigIntegerMinorMoney(money, currencySpec, grouping, includeSymbol, showPlusSignForPositive)
        }

        val absMinor = kotlin.math.abs(rawMinor)
        val divisor = POWERS_OF_TEN[fractionDigits]
        val integerPartRaw: String = (absMinor / divisor).toString()
        val groupedIntegerPart: String = when (grouping) {
            NumberGroupingSystem.INDIAN -> formatIndianGrouping(integerPartRaw)
            NumberGroupingSystem.INTERNATIONAL -> formatInternationalGrouping(integerPartRaw)
        }

        val formattedNumber = if (fractionDigits > 0) {
            val fractionalPart = (absMinor % divisor)
                .toString()
                .padStart(fractionDigits, '0')
            "$groupedIntegerPart.$fractionalPart"
        } else {
            groupedIntegerPart
        }

        val symbolPart = if (includeSymbol) currencySpec.symbol else ""
        return "$signPrefix$symbolPart$formattedNumber"
    }

    private fun formatBigIntegerMinorMoney(
        money: MinorMoney,
        currencySpec: CurrencySpec,
        grouping: NumberGroupingSystem,
        includeSymbol: Boolean,
        showPlusSignForPositive: Boolean,
    ): String {
        val fractionDigits = currencySpec.fractionDigits.coerceAtLeast(0)
        val signPrefix = when {
            money.isNegative -> "-"
            money.isPositive && showPlusSignForPositive -> "+"
            else -> ""
        }

        val absMinor: BigInteger = BigInteger.valueOf(money.amountMinor).abs()
        val divisor: BigInteger = BigInteger.TEN.pow(fractionDigits)
        val integerPartRaw: String = absMinor.divide(divisor).toString()
        val groupedIntegerPart: String = when (grouping) {
            NumberGroupingSystem.INDIAN -> formatIndianGrouping(integerPartRaw)
            NumberGroupingSystem.INTERNATIONAL -> formatInternationalGrouping(integerPartRaw)
        }

        val formattedNumber = if (fractionDigits > 0) {
            val fractionalPart = absMinor.remainder(divisor)
                .toString()
                .padStart(fractionDigits, '0')
            "$groupedIntegerPart.$fractionalPart"
        } else {
            groupedIntegerPart
        }

        val symbolPart = if (includeSymbol) currencySpec.symbol else ""
        return "$signPrefix$symbolPart$formattedNumber"
    }

    fun formatCompactMinorMoney(
        money: MinorMoney,
        currencySpec: CurrencySpec,
        grouping: NumberGroupingSystem = currencySpec.defaultGrouping,
        showPlusSignForPositive: Boolean = false,
    ): String {
        val fractionDigits = currencySpec.fractionDigits.coerceAtLeast(0)
        val majorBigDecimal = BigDecimal.valueOf(money.amountMinor).movePointLeft(fractionDigits)
        val isNegative = majorBigDecimal.signum() < 0
        val isPositive = majorBigDecimal.signum() > 0
        val absMajor = majorBigDecimal.abs()

        fun formatScaled(divisor: BigDecimal, scale: Int, suffix: String): String {
            val formatted = absMajor.divide(divisor, scale, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
            return "$formatted$suffix"
        }

        val (formattedAmount, isDisplayedZero) = if (grouping == NumberGroupingSystem.INDIAN) {
            when {
                // >= 99.5 Lakhs rounds to 100.0L -> should format as Crores (e.g. 1Cr, 2.5Cr)
                absMajor >= COMPACT_CRORE_THRESHOLD -> formatScaled(COMPACT_CRORE_DIVISOR, 1, "Cr") to false

                // >= 99.5k rounds to 100k -> should format as Lakhs (e.g. 1L, 1.5L)
                absMajor >= COMPACT_LAKH_THRESHOLD -> formatScaled(COMPACT_LAKH_DIVISOR, 1, "L") to false

                // >= 999.5 rounds to 1000 -> should format as 'k' (e.g. 1k, 1.2k, 75k)
                absMajor >= COMPACT_THOUSAND_THRESHOLD -> {
                    val scale = if (absMajor >= COMPACT_TEN_THOUSAND_THRESHOLD) 0 else 1
                    formatScaled(COMPACT_THOUSAND_DIVISOR, scale, "k") to false
                }

                else -> {
                    val rounded = absMajor.setScale(0, RoundingMode.HALF_UP)
                    rounded.toPlainString() to (rounded.compareTo(BigDecimal.ZERO) == 0)
                }
            }
        } else {
            when {
                // >= 999.5k (999,500.0) rounds to 1000k -> should format as Millions (e.g. 1M, 1.5M)
                absMajor >= COMPACT_MILLION_THRESHOLD -> formatScaled(COMPACT_MILLION_DIVISOR, 1, "M") to false

                // >= 999.5 rounds to 1000 -> should format as 'k' (e.g. 1k, 1.2k, 150k)
                absMajor >= COMPACT_THOUSAND_THRESHOLD -> {
                    val scale = if (absMajor >= COMPACT_TEN_THOUSAND_THRESHOLD) 0 else 1
                    formatScaled(COMPACT_THOUSAND_DIVISOR, scale, "k") to false
                }

                else -> {
                    val rounded = absMajor.setScale(0, RoundingMode.HALF_UP)
                    rounded.toPlainString() to (rounded.compareTo(BigDecimal.ZERO) == 0)
                }
            }
        }

        val sign = when {
            isDisplayedZero -> ""
            isNegative -> "-"
            isPositive && showPlusSignForPositive -> "+"
            else -> ""
        }
        return "$sign$formattedAmount"
    }

    private fun formatInternationalGrouping(digits: String): String {
        if (digits.length <= 3) return digits
        val sb = StringBuilder(digits.length + digits.length / 3)
        val firstGroupLen = digits.length % 3
        if (firstGroupLen > 0) {
            sb.append(digits, 0, firstGroupLen)
        }
        var index = firstGroupLen
        while (index < digits.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(digits, index, index + 3)
            index += 3
        }
        return sb.toString()
    }

    private fun formatIndianGrouping(digits: String): String {
        if (digits.length <= 3) return digits
        val lastThree = digits.substring(digits.length - 3)
        val prefix = digits.substring(0, digits.length - 3)

        val sb = StringBuilder(digits.length + digits.length / 2)
        val firstGroupLen = prefix.length % 2
        if (firstGroupLen > 0) {
            sb.append(prefix, 0, firstGroupLen)
        }
        var index = firstGroupLen
        while (index < prefix.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(prefix, index, index + 2)
            index += 2
        }
        sb.append(',').append(lastThree)
        return sb.toString()
    }

    fun evaluateKeypadExpressionToMinor(
        expression: String,
        fractionDigits: Int,
    ): Result<MinorMoney> = evaluateExpressionInternal(
        expression = expression,
        fractionDigits = fractionDigits,
        requireStrictlyPositive = true,
    )

    fun evaluateNonNegativeExpressionToMinor(
        expression: String,
        fractionDigits: Int,
    ): Result<MinorMoney> = evaluateExpressionInternal(
        expression = expression,
        fractionDigits = fractionDigits,
        requireStrictlyPositive = false,
        requireNonNegative = true,
    )

    fun evaluateSignedExpressionToMinor(
        expression: String,
        fractionDigits: Int,
    ): Result<MinorMoney> = evaluateExpressionInternal(
        expression = expression,
        fractionDigits = fractionDigits,
        requireStrictlyPositive = false,
    )

    private fun evaluateExpressionInternal(
        expression: String,
        fractionDigits: Int,
        requireStrictlyPositive: Boolean,
        requireNonNegative: Boolean = false,
    ): Result<MinorMoney> {
        if (fractionDigits !in 0..6) {
            return Result.failure(
                IllegalArgumentException("Unsupported fractionDigits: $fractionDigits"),
            )
        }

        val stripped = expression
            .replace(Regex("[\\s\\u00A0\\u202F]"), "")
            .replace('\u00D7', '*')
            .replace('\u00F7', '/')
            .replace('\u2212', '-')

        if (stripped.length > MAX_EXPRESSION_LENGTH) {
            return Result.failure(
                IllegalArgumentException("Expression exceeds maximum length of $MAX_EXPRESSION_LENGTH characters."),
            )
        }

        // Normalize decimal comma when unambiguous or when the locale itself uses ',' as the decimal separator
        val isLocaleCommaDecimal = DecimalFormatSymbols.getInstance().decimalSeparator == ','
        val sanitized = if (fractionDigits > 0 && stripped.contains(',')) {
            val parts = mutableListOf<String>()
            val ops = mutableListOf<Char>()
            var currentToken = StringBuilder()
            for (ch in stripped) {
                if (ch in "+-*/()") {
                    parts.add(currentToken.toString())
                    currentToken = StringBuilder()
                    ops.add(ch)
                } else {
                    currentToken.append(ch)
                }
            }
            parts.add(currentToken.toString())

            val normalizedParts = parts.map { token ->
                val lastComma = token.lastIndexOf(',')
                val lastDot = token.lastIndexOf('.')
                when {
                    lastComma != -1 && lastDot != -1 -> {
                        if (lastComma > lastDot) {
                            // European format: 1.234,56 -> 1234.56
                            token.replace(".", "").replace(',', '.')
                        } else {
                            // Standard format: 1,234.56 -> 1234.56
                            token.replace(",", "")
                        }
                    }

                    lastComma != -1 -> {
                        val commaCount = token.count { it == ',' }
                        val digitsAfterComma = token.length - lastComma - 1
                        val isDecimalComma = commaCount == 1 && (
                            (digitsAfterComma in 1..fractionDigits && (digitsAfterComma != 3 || isLocaleCommaDecimal)) ||
                                (isLocaleCommaDecimal && digitsAfterComma >= 1)
                            )
                        if (isDecimalComma) {
                            token.replace(',', '.')
                        } else {
                            token.replace(",", "")
                        }
                    }

                    else -> token
                }
            }

            val reconstructed = StringBuilder()
            for (i in normalizedParts.indices) {
                reconstructed.append(normalizedParts[i])
                if (i < ops.size) {
                    reconstructed.append(ops[i])
                }
            }
            reconstructed.toString()
        } else {
            stripped.replace(",", "")
        }

        if (sanitized.isEmpty()) {
            return Result.failure(IllegalArgumentException("Expression cannot be empty."))
        }

        return try {
            val parser = ExpressionParser(sanitized)
            val evaluatedValue: BigDecimal = parser.parse()
            val scaledMajor = evaluatedValue.setScale(fractionDigits, RoundingMode.HALF_UP)
            val minorBigDecimal = scaledMajor.movePointRight(fractionDigits)
            val minorLong = minorBigDecimal.longValueExact()

            if (requireStrictlyPositive && minorLong <= 0L) {
                Result.failure(
                    IllegalArgumentException("Evaluated transaction total must be strictly positive."),
                )
            } else if (requireNonNegative && minorLong < 0L) {
                Result.failure(
                    IllegalArgumentException("Evaluated amount cannot be negative."),
                )
            } else {
                Result.success(MinorMoney(minorLong))
            }
        } catch (e: ArithmeticException) {
            Result.failure(
                ArithmeticException(e.message ?: "Arithmetic overflow or division by zero."),
            )
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                IllegalArgumentException("Malformed keypad expression: $expression", e),
            )
        }
    }

    private class ExpressionParser(private val input: String) {
        private var pos: Int = 0
        private var depth: Int = 0

        fun parse(): BigDecimal {
            val result = parseExpression()
            if (pos < input.length) {
                throw IllegalArgumentException(
                    "Unexpected character '${input[pos]}' at index $pos",
                )
            }
            return result
        }

        private fun parseExpression(): BigDecimal {
            var left = parseTerm()
            while (pos < input.length) {
                val op = input[pos]
                if (op == '+' || op == '-') {
                    pos++
                    val right = parseTerm()
                    left = if (op == '+') {
                        left.add(right, MathContext.DECIMAL128)
                    } else {
                        left.subtract(right, MathContext.DECIMAL128)
                    }
                } else {
                    break
                }
            }
            return left
        }

        private fun parseTerm(): BigDecimal {
            var left = parseFactor()
            while (pos < input.length) {
                val op = input[pos]
                if (op == '*' || op == '/') {
                    pos++
                    val right = parseFactor()
                    left = if (op == '*') {
                        left.multiply(right, MathContext.DECIMAL128)
                    } else {
                        if (right.compareTo(BigDecimal.ZERO) == 0) {
                            throw ArithmeticException("Division by zero is not allowed.")
                        }
                        left.divide(right, MathContext.DECIMAL128)
                    }
                } else {
                    break
                }
            }
            return left
        }

        private fun parseFactor(): BigDecimal {
            if (depth >= MAX_PARSER_DEPTH) {
                throw IllegalArgumentException("Expression exceeds maximum nesting depth.")
            }
            depth++
            try {
                if (pos >= input.length) {
                    throw IllegalArgumentException("Unexpected end of expression.")
                }

                val current = input[pos]
                if (current == '+') {
                    pos++
                    return parseFactor()
                }
                if (current == '-') {
                    pos++
                    return parseFactor().negate()
                }
                if (current == '(') {
                    pos++
                    val inside = parseExpression()
                    if (pos >= input.length || input[pos] != ')') {
                        throw IllegalArgumentException("Missing closing parenthesis ')'.")
                    }
                    pos++
                    return inside
                }

                val start = pos
                var dotCount = 0
                while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) {
                    if (input[pos] == '.') {
                        dotCount++
                        if (dotCount > 1) {
                            throw IllegalArgumentException("Multiple decimal points in number token.")
                        }
                    }
                    pos++
                }

                if (start == pos) {
                    throw IllegalArgumentException(
                        "Expected number at index $start but found '${input[start]}'.",
                    )
                }

                val token = input.substring(start, pos)
                if (token == ".") {
                    throw IllegalArgumentException("Standalone decimal point is not a valid number.")
                }
                return BigDecimal(token)
            } finally {
                depth--
            }
        }
    }

    private fun clampToLongRange(scaled: BigDecimal): Long = try {
        scaled.longValueExact()
    } catch (_: ArithmeticException) {
        scaled.toBigInteger()
            .max(BigInteger.valueOf(Long.MIN_VALUE))
            .min(BigInteger.valueOf(Long.MAX_VALUE))
            .toLong()
    }

    private fun scaleMinorMoney(
        sourceMinor: Long,
        sourceFractionDigits: Int,
        targetFractionDigits: Int,
        rate: BigDecimal,
    ): Long {
        if (sourceMinor == 0L) return 0L
        val scaleShift = targetFractionDigits.coerceAtLeast(0) - sourceFractionDigits.coerceAtLeast(0)
        if (rate.compareTo(BigDecimal.ONE) == 0 && scaleShift == 0) return sourceMinor

        val scaled = BigDecimal.valueOf(sourceMinor)
            .multiply(rate)
            .movePointRight(scaleShift)
            .setScale(0, RoundingMode.HALF_UP)
        return clampToLongRange(scaled)
    }

    private fun resolveRateToPrimaryBigDecimal(
        normalizedCurrencyCode: String,
        normalizedPrimaryCode: String,
        secondaryCurrenciesByCode: Map<String, SecondaryCurrency>,
    ): BigDecimal? {
        if (normalizedCurrencyCode == normalizedPrimaryCode) return BigDecimal.ONE
        val rate = secondaryCurrenciesByCode[normalizedCurrencyCode]?.exchangeRateToPrimary ?: return null
        return if (rate > 0.0 && rate.isFinite()) BigDecimal.valueOf(rate) else null
    }

    /**
     * Converts a minor currency amount (e.g. cents, paise) to another currency's minor units
     * using a direct exchange rate (1 Unit of Source = [rateToTarget] Units of Target),
     * correctly scaling for differences in fraction digits (e.g. JPY=0, USD=2, KWD=3).
     */
    fun convertMinorMoney(
        sourceMinor: Long,
        sourceFractionDigits: Int,
        targetFractionDigits: Int,
        rateToTarget: Double,
    ): Long {
        if (sourceMinor == 0L) return 0L
        val safeRate = if (rateToTarget > 0.0 && rateToTarget.isFinite()) {
            BigDecimal.valueOf(rateToTarget)
        } else {
            BigDecimal.ONE
        }
        return scaleMinorMoney(
            sourceMinor = sourceMinor,
            sourceFractionDigits = sourceFractionDigits,
            targetFractionDigits = targetFractionDigits,
            rate = safeRate,
        )
    }

    /**
     * Converts a minor currency amount between any two currencies ([fromCurrencyCode] -> [toCurrencyCode])
     * in a single BigDecimal step (combining Source -> Primary -> Target rates without intermediate rounding
     * or Double division drift). If [toCurrencyCode] is neither [primaryCurrencyCode] nor a configured
     * secondary currency with a valid positive rate, falls back to [primaryCurrencyCode].
     */
    fun convertBetweenCurrenciesMinor(
        sourceMinor: Long,
        fromCurrencyCode: String,
        toCurrencyCode: String,
        primaryCurrencyCode: String,
        secondaryCurrenciesByCode: Map<String, SecondaryCurrency>,
    ): Long {
        if (sourceMinor == 0L) return 0L
        val from = fromCurrencyCode.trim().uppercase(Locale.ROOT)
        val to = toCurrencyCode.trim().uppercase(Locale.ROOT)
        val primary = primaryCurrencyCode.trim().uppercase(Locale.ROOT)
        if (from == to) return sourceMinor

        val fromSpec = SupportedCurrencies.fromCode(from)
        val fromRateBd = resolveRateToPrimaryBigDecimal(from, primary, secondaryCurrenciesByCode)
            ?: BigDecimal.ONE

        if (to == primary) {
            val primarySpec = SupportedCurrencies.fromCode(primary)
            return scaleMinorMoney(
                sourceMinor = sourceMinor,
                sourceFractionDigits = fromSpec.fractionDigits,
                targetFractionDigits = primarySpec.fractionDigits,
                rate = fromRateBd,
            )
        }

        val toRateBd = resolveRateToPrimaryBigDecimal(to, primary, secondaryCurrenciesByCode)
        return if (toRateBd != null) {
            val toSpec = SupportedCurrencies.fromCode(to)
            val scaleShift = toSpec.fractionDigits.coerceAtLeast(0) - fromSpec.fractionDigits.coerceAtLeast(0)
            val scaled = BigDecimal.valueOf(sourceMinor)
                .multiply(fromRateBd)
                .movePointRight(scaleShift)
                .divide(toRateBd, 0, RoundingMode.HALF_UP)
            clampToLongRange(scaled)
        } else {
            val primarySpec = SupportedCurrencies.fromCode(primary)
            scaleMinorMoney(
                sourceMinor = sourceMinor,
                sourceFractionDigits = fromSpec.fractionDigits,
                targetFractionDigits = primarySpec.fractionDigits,
                rate = fromRateBd,
            )
        }
    }

    /**
     * Converts a minor money amount from [sourceCurrencyCode] to [primaryCurrencyCode]
     * using the configured [exchangeRate].
     * If no rate is configured, normalizes fraction digits using an exchange rate of 1.0.
     */
    fun convertToPrimaryMinor(
        sourceMinor: Long,
        sourceCurrencyCode: String,
        primaryCurrencyCode: String,
        exchangeRate: Double?,
    ): Long {
        if (sourceMinor == 0L) return 0L
        val cleanSource = sourceCurrencyCode.trim().uppercase(Locale.ROOT)
        val cleanPrimary = primaryCurrencyCode.trim().uppercase(Locale.ROOT)
        if (cleanSource == cleanPrimary) return sourceMinor

        val sourceSpec = SupportedCurrencies.fromCode(cleanSource)
        val primarySpec = SupportedCurrencies.fromCode(cleanPrimary)
        val rate = if (exchangeRate != null && exchangeRate > 0.0 && exchangeRate.isFinite()) {
            exchangeRate
        } else {
            1.0
        }

        return convertMinorMoney(
            sourceMinor = sourceMinor,
            sourceFractionDigits = sourceSpec.fractionDigits,
            targetFractionDigits = primarySpec.fractionDigits,
            rateToTarget = rate,
        )
    }

    /**
     * Converts a minor money amount from [sourceCurrencyCode] to [primaryCurrencyCode]
     * using the configured [secondaryCurrencies] exchange rates.
     * If no rate is configured, normalizes fraction digits using an exchange rate of 1.0.
     */
    fun convertToPrimaryMinor(
        sourceMinor: Long,
        sourceCurrencyCode: String,
        primaryCurrencyCode: String,
        secondaryCurrencies: List<SecondaryCurrency> = emptyList(),
    ): Long {
        if (sourceMinor == 0L) return 0L
        val cleanSource = sourceCurrencyCode.trim().uppercase(Locale.ROOT)
        val secondary = secondaryCurrencies.firstOrNull { it.code.trim().uppercase(Locale.ROOT) == cleanSource }
        return convertToPrimaryMinor(
            sourceMinor = sourceMinor,
            sourceCurrencyCode = sourceCurrencyCode,
            primaryCurrencyCode = primaryCurrencyCode,
            exchangeRate = secondary?.exchangeRateToPrimary,
        )
    }

    /**
     * Safely computes absolute value of a Long without overflowing on Long.MIN_VALUE.
     */
    fun safeAbs(value: Long): Long = if (value == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(value)

    /**
     * Safely negates a Long, saturating at Long.MAX_VALUE when value is Long.MIN_VALUE instead of overflowing.
     */
    fun safeNegate(value: Long): Long = if (value == Long.MIN_VALUE) Long.MAX_VALUE else -value

    /**
     * Safely adds two Longs, saturating at Long.MAX_VALUE or Long.MIN_VALUE instead of throwing ArithmeticException.
     */
    fun safeAdd(a: Long, b: Long): Long = try {
        Math.addExact(a, b)
    } catch (_: ArithmeticException) {
        if (b > 0L) Long.MAX_VALUE else Long.MIN_VALUE
    }

    /**
     * Safely subtracts two Longs, saturating at Long.MAX_VALUE or Long.MIN_VALUE instead of throwing ArithmeticException.
     */
    fun safeSubtract(a: Long, b: Long): Long = try {
        Math.subtractExact(a, b)
    } catch (_: ArithmeticException) {
        if (b < 0L) Long.MAX_VALUE else Long.MIN_VALUE
    }

    /**
     * Safely computes percentage (part / total * 100) using BigDecimal to avoid Float precision loss
     * and divide-by-zero errors.
     */
    fun calculatePercentage(
        partMinor: Long,
        totalMinor: Long,
        scale: Int = 2,
    ): Float {
        if (totalMinor == 0L || partMinor == 0L) return 0f
        return runCatching {
            BigDecimal.valueOf(partMinor)
                .multiply(BigDecimal.valueOf(100L))
                .divide(BigDecimal.valueOf(totalMinor), scale + 2, RoundingMode.HALF_UP)
                .toFloat()
        }.getOrDefault(0f)
    }
}
