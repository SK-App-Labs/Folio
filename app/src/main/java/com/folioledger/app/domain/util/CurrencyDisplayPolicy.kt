/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.domain.util

import androidx.compose.runtime.Immutable
import com.folioledger.app.domain.model.CurrencySpec

@Immutable
data class CurrencyDisplayPolicy(
    val primaryCurrencyCode: String = "INR",
    val hidePrimaryCurrencySymbol: Boolean = false,
) {
    fun isPrimaryCurrency(currencyCode: String): Boolean = currencyCode.trim().equals(primaryCurrencyCode.trim(), ignoreCase = true)

    fun isForeignCurrency(currencyCode: String): Boolean = !isPrimaryCurrency(currencyCode)

    fun shouldShowSymbol(currencyCode: String): Boolean = !hidePrimaryCurrencySymbol || !isPrimaryCurrency(currencyCode)

    fun shouldShowSymbol(currencySpec: CurrencySpec): Boolean = shouldShowSymbol(currencySpec.code)

    fun effectiveSymbol(currencySpec: CurrencySpec): String = if (shouldShowSymbol(currencySpec)) currencySpec.symbol else ""

    fun isMultiCharVisibleSymbol(currencySpec: CurrencySpec): Boolean = effectiveSymbol(currencySpec).length > 2
}
