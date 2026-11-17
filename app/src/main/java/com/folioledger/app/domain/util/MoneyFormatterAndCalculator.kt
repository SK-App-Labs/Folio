// File: app/src/main/java/com/folioledger/app/domain/util/MoneyFormatterAndCalculator.kt
package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

object MoneyFormatterAndCalculator {

    private val POWERS_OF_TEN = longArrayOf(
        1L,
        10L,
        100L,
        1_000L,
        10_000L,
        100_000L,
        1_000_000L
    )

    fun formatMinorMoney(
        money: MinorMoney,
        currencySpec: CurrencySpec,
        grouping: NumberGroupingSystem = currencySpec.defaultGrouping,
        includeSymbol: Boolean = true,
        showPlusSignForPositive: Boolean = false
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
        showPlusSignForPositive: Boolean
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
        showPlusSignForPositive: Boolean = false
    ): String {
        val fractionDigits = currencySpec.fractionDigits.coerceAtLeast(0)
        val divisor = Math.pow(10.0, fractionDigits.toDouble())
        val majorValue = money.amountMinor.toDouble() / divisor
        val absMajor = kotlin.math.abs(majorValue)
        val sign = when {
            majorValue < -0.001 -> "-"
            majorValue > 0.001 && showPlusSignForPositive -> "+"
            else -> ""
        }

        val formattedAmount = if (grouping == NumberGroupingSystem.INDIAN) {
            when {
                // >= 99.5 Lakhs rounds to 100.0L -> should format as Crores (e.g. 1Cr, 2.5Cr)
                absMajor >= 9_950_000.0 -> {
                    val inCrores = absMajor / 10_000_000.0
                    String.format(java.util.Locale.US, "%.1fCr", inCrores).replace(".0Cr", "Cr")
                }
                // >= 99.5k rounds to 100k -> should format as Lakhs (e.g. 1L, 1.5L)
                absMajor >= 99_500.0 -> {
                    val inLakhs = absMajor / 100_000.0
                    String.format(java.util.Locale.US, "%.1fL", inLakhs).replace(".0L", "L")
                }
                // >= 999.5 rounds to 1000 -> should format as 'k' (e.g. 1k, 1.2k, 75k)
                absMajor >= 999.5 -> {
                    val inThousands = absMajor / 1_000.0
                    if (inThousands >= 9.95) {
                        String.format(java.util.Locale.US, "%.0fk", inThousands)
                    } else {
                        String.format(java.util.Locale.US, "%.1fk", inThousands).replace(".0k", "k")
                    }
                }
                else -> {
                    kotlin.math.round(absMajor).toLong().toString()
                }
            }
        } else {
            when {
                // >= 999.5k (999,500.0) rounds to 1000k -> should format as Millions (e.g. 1M, 1.5M)
                absMajor >= 999_500.0 -> {
                    val inMillions = absMajor / 1_000_000.0
                    String.format(java.util.Locale.US, "%.1fM", inMillions).replace(".0M", "M")
                }
                // >= 999.5 rounds to 1000 -> should format as 'k' (e.g. 1k, 1.2k, 150k)
                absMajor >= 999.5 -> {
                    val inThousands = absMajor / 1_000.0
                    if (inThousands >= 9.95) {
                        String.format(java.util.Locale.US, "%.0fk", inThousands)
                    } else {
                        String.format(java.util.Locale.US, "%.1fk", inThousands).replace(".0k", "k")
                    }
                }
                else -> {
                    kotlin.math.round(absMajor).toLong().toString()
                }
            }
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
        fractionDigits: Int
    ): Result<MinorMoney> {
        if (fractionDigits !in 0..6) {
            return Result.failure(
                IllegalArgumentException("Unsupported fractionDigits: $fractionDigits")
            )
        }

        val stripped = expression
            .replace(Regex("[\\s\\u00A0\\u202F]"), "")
            .replace('\u00D7', '*')
            .replace('\u00F7', '/')

        // Normalize decimal comma when single comma in a number token is followed by 1..fractionDigits digits and no dot exists before it
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
                        if (
                            commaCount == 1 &&
                            digitsAfterComma in 1..fractionDigits &&
                            (digitsAfterComma != 3 || java.text.DecimalFormatSymbols.getInstance().decimalSeparator == ',')
                        ) {
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

            if (minorLong <= 0L) {
                Result.failure(
                    IllegalArgumentException("Evaluated transaction total must be strictly positive.")
                )
            } else {
                Result.success(MinorMoney(minorLong))
            }
        } catch (e: ArithmeticException) {
            Result.failure(
                ArithmeticException(e.message ?: "Arithmetic overflow or division by zero.")
            )
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                IllegalArgumentException("Malformed keypad expression: $expression", e)
            )
        }
    }

    private class ExpressionParser(private val input: String) {
        private var pos: Int = 0

        fun parse(): BigDecimal {
            val result = parseExpression()
            if (pos < input.length) {
                throw IllegalArgumentException(
                    "Unexpected character '${input[pos]}' at index $pos"
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
                    "Expected number at index $start but found '${input[start]}'."
                )
            }

            val token = input.substring(start, pos)
            if (token == ".") {
                throw IllegalArgumentException("Standalone decimal point is not a valid number.")
            }
            return BigDecimal(token)
        }
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
        rateToTarget: Double
    ): Long {
        if (rateToTarget <= 0.0 || !rateToTarget.isFinite() || sourceMinor == 0L) return sourceMinor
        if (rateToTarget == 1.0 && sourceFractionDigits == targetFractionDigits) return sourceMinor

        val scaleShift = targetFractionDigits.coerceAtLeast(0) - sourceFractionDigits.coerceAtLeast(0)
        val scaled = BigDecimal.valueOf(sourceMinor)
            .multiply(BigDecimal.valueOf(rateToTarget))
            .movePointRight(scaleShift)
            .setScale(0, RoundingMode.HALF_UP)

        return try {
            scaled.longValueExact()
        } catch (_: ArithmeticException) {
            scaled.toBigInteger()
                .max(BigInteger.valueOf(Long.MIN_VALUE))
                .min(BigInteger.valueOf(Long.MAX_VALUE))
                .toLong()
        }
    }

    /**
     * Safely computes absolute value of a Long without overflowing on Long.MIN_VALUE.
     */
    fun safeAbs(value: Long): Long = if (value == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(value)

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
}