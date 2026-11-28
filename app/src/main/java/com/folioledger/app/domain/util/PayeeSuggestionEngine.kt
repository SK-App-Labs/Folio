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

import com.folioledger.app.domain.model.PayeeSuggestion
import com.folioledger.app.domain.model.TransactionType
import java.util.Locale

object PayeeSuggestionEngine {

    fun rankSuggestions(
        query: String,
        currentMode: TransactionType,
        catalog: List<PayeeSuggestion>,
        maxResults: Int = 8,
    ): List<PayeeSuggestion> {
        if (maxResults <= 0) return emptyList()
        val cleanQuery = query.trim { it.isWhitespace() }
        val validCatalog = catalog.filter {
            it.displayName.isNotBlank() && !it.displayName.contains(" \u2192 ")
        }

        if (cleanQuery.isEmpty()) {
            return validCatalog
                .sortedWith(
                    compareByDescending<PayeeSuggestion> { it.lastType == currentMode }
                        .thenByDescending { it.lastUsedEpochMillis }
                        .thenByDescending { it.usageCount },
                )
                .take(maxResults)
        }

        val q = cleanQuery.lowercase(Locale.ROOT)

        val tier1 = mutableListOf<PayeeSuggestion>() // Prefix
        val tier2 = mutableListOf<PayeeSuggestion>() // Word boundary
        val tier3 = mutableListOf<PayeeSuggestion>() // Substring

        for (item in validCatalog) {
            val key = item.normalizedKey
            if (key.startsWith(q)) {
                tier1.add(item)
            } else if (matchesWordBoundary(key, q)) {
                tier2.add(item)
            } else if (q.length >= 2 && key.contains(q)) {
                tier3.add(item)
            }
        }

        val comparator = compareByDescending<PayeeSuggestion> { it.lastType == currentMode }
            .thenByDescending { it.lastUsedEpochMillis }
            .thenByDescending { it.usageCount }

        tier1.sortWith(comparator)
        tier2.sortWith(comparator)
        tier3.sortWith(comparator)

        val combined = ArrayList<PayeeSuggestion>(maxResults)
        for (item in tier1) {
            if (combined.size >= maxResults) break
            combined.add(item)
        }
        for (item in tier2) {
            if (combined.size >= maxResults) break
            combined.add(item)
        }
        for (item in tier3) {
            if (combined.size >= maxResults) break
            combined.add(item)
        }

        return combined
    }

    private fun matchesWordBoundary(key: String, q: String): Boolean {
        var startOfWord = true
        for (i in key.indices) {
            val ch = key[i]
            if (!ch.isLetterOrDigit()) {
                startOfWord = true
            } else if (startOfWord) {
                if (key.startsWith(q, startIndex = i)) {
                    return true
                }
                startOfWord = false
            }
        }
        return false
    }
}
