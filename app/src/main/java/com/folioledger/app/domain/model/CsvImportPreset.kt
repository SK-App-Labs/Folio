/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.domain.model

enum class CsvImportPreset(
    val displayName: String,
    val subtitle: String,
    val exportPathHint: String,
    val isPrimary: Boolean = false
) {
    AUTO_DETECT(
        displayName = "Auto-Detect",
        subtitle = "Automatically identifies your export format",
        exportPathHint = "Pick any exported .csv or .tsv file",
        isPrimary = true
    ),
    FOLIO_NATIVE(
        displayName = "Folio / Generic CSV",
        subtitle = "Folio exports, side-by-side templates, or bank CSVs",
        exportPathHint = "Settings → Spreadsheets & CSV → Export Full Ledger",
        isPrimary = false
    ),
    // Primary 6 Competitor Presets
    IVY_WALLET(
        displayName = "Ivy Wallet",
        subtitle = "UTF-16/UTF-8 CSV · transfers, balances & unpaid filter",
        exportPathHint = "Ivy Wallet → Settings → Export to CSV",
        isPrimary = true
    ),
    CASHEW(
        displayName = "Cashew",
        subtitle = "Subcategories, Balance Corrections & paired transfers",
        exportPathHint = "Cashew → Settings → Export Data → Export CSV",
        isPrimary = true
    ),
    REALBYTE_MONEY_MANAGER(
        displayName = "Money Manager (Realbyte)",
        subtitle = "CSV / TSV · maps Transfer-Out destinations cleanly",
        exportPathHint = "Money Manager → Settings → Backup → Export Excel/CSV",
        isPrimary = true
    ),
    WALLET_BUDGETBAKERS(
        displayName = "Wallet by BudgetBakers",
        subtitle = "Semicolon CSV · labels & paired transfer consolidation",
        exportPathHint = "Wallet → Exports → CSV",
        isPrimary = true
    ),
    MONEY_LOVER(
        displayName = "Money Lover",
        subtitle = "Comma/semicolon CSV · Wallet, Events & transfer pairs",
        exportPathHint = "Money Lover → Tools → Export CSV",
        isPrimary = true
    ),
    YNAB(
        displayName = "YNAB",
        subtitle = "Register CSV · Outflow/Inflow, Category Groups & transfers",
        exportPathHint = "YNAB Web → Budget Menu → Export Budget (use Register.csv)",
        isPrimary = true
    ),
    // Secondary 8 Competitor Presets (Collapsible "More Apps")
    INTUIT_MINT(
        displayName = "Intuit Mint",
        subtitle = "Credit/Debit polarity, Categories & Labels",
        exportPathHint = "Mint → Transactions → Export all transactions",
        isPrimary = false
    ),
    SPENDEE(
        displayName = "Spendee",
        subtitle = "ISO date-time & multi-wallet CSV",
        exportPathHint = "Spendee → More → Export data → CSV",
        isPrimary = false
    ),
    MONEFY(
        displayName = "Monefy",
        subtitle = "dd/MM/yyyy signed amounts across accounts",
        exportPathHint = "Monefy → Settings → Export to file (CSV)",
        isPrimary = false
    ),
    ONE_MONEY(
        displayName = "1Money",
        subtitle = "Categories & single-row transfer destinations",
        exportPathHint = "1Money → Settings → Export to CSV",
        isPrimary = false
    ),
    BLUECOINS(
        displayName = "Bluecoins Finance",
        subtitle = "Category Groups & paired transfer entries",
        exportPathHint = "Bluecoins → Settings → Export to CSV",
        isPrimary = false
    ),
    FINANCISTO(
        displayName = "Financisto",
        subtitle = "Split Date/Time & validated 2-row transfers",
        exportPathHint = "Financisto → Menu → Export CSV",
        isPrimary = false
    ),
    KTW_MONEY_MANAGER(
        displayName = "Money Manager (KTW)",
        subtitle = "Standard UTF-8 transaction export",
        exportPathHint = "Money Manager (KTW) → Settings → Export CSV",
        isPrimary = false
    ),
    FORTUNE_CITY(
        displayName = "Fortune City",
        subtitle = "Category-based income/expense & 2-digit dates",
        exportPathHint = "Fortune City → Settings → Export CSV",
        isPrimary = false
    );

    val isCompetitor: Boolean
        get() = this != AUTO_DETECT && this != FOLIO_NATIVE
}
