/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.settings.sheets

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.folioledger.app.data.pdf.PassbookPeriodPreset
import com.folioledger.app.data.pdf.PassbookPeriodPresetResolver
import com.folioledger.app.domain.model.AccountSummary
import com.folioledger.app.domain.model.FiscalYearType
import com.folioledger.app.domain.model.PdfExportResult
import com.folioledger.app.domain.model.UserPreferences
import com.folioledger.app.ui.settings.sheets.components.PdfAccountStatementSection
import com.folioledger.app.ui.settings.sheets.components.PdfExportSuccessDialog
import com.folioledger.app.ui.settings.sheets.components.PdfTaxReportSection
import com.folioledger.app.ui.theme.LocalFolioSemanticColors
import java.io.File
import java.time.LocalDate

private enum class PdfReportTab {
    TAX_REPORT,
    ACCOUNT_STATEMENT,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReportsBottomSheet(
    prefs: UserPreferences,
    accounts: List<AccountSummary>,
    allLedgerTags: List<String> = emptyList(),
    isGeneratingPdf: Boolean,
    lastGeneratedPdfResult: PdfExportResult?,
    onGenerateAccountStatement: (
        accountId: Long,
        startEpochDay: Long,
        endEpochDay: Long,
        includeNotesAndTags: Boolean,
        includeRunningBalance: Boolean,
    ) -> Unit,
    onGenerateAnnualTaxStatement: (
        fiscalYearType: FiscalYearType,
        anchorYear: Int,
        customStartEpochDay: Long?,
        customEndEpochDay: Long?,
        selectedTaxTags: Set<String>,
    ) -> Unit,
    onSaveCopyToUri: (File, Uri) -> Unit = { _, _ -> },
    onClearLastResult: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val semanticColors = LocalFolioSemanticColors.current

    var selectedTab by rememberSaveable { mutableStateOf(PdfReportTab.TAX_REPORT) }

    // Annual Tax Report State
    val currentYear = remember { LocalDate.now().year }
    val minAnchorYear = 1970
    val maxAnchorYear = currentYear + 1
    var selectedFiscalYearType by rememberSaveable { mutableStateOf(FiscalYearType.CALENDAR_YEAR) }
    var anchorYear by rememberSaveable { mutableIntStateOf(currentYear) }
    val combinedTaxTags = remember(allLedgerTags) {
        val defaults = listOf("tax", "business", "medical", "deductible", "donation", "charity", "work")
        (defaults + allLedgerTags.map { it.trim().removePrefix("#") })
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }
    // Empty selection triggers smart auto-detection (default tax keywords + deductible categories)
    var selectedTaxTags by rememberSaveable { mutableStateOf(emptyList<String>()) }

    // Account Statement State
    val activeAccounts = remember(accounts) { accounts.filter { !it.isArchived } }
    var selectedAccountId by rememberSaveable {
        mutableLongStateOf(activeAccounts.firstOrNull()?.id ?: 0L)
    }
    LaunchedEffect(activeAccounts) {
        if (activeAccounts.none { it.id == selectedAccountId }) {
            selectedAccountId = activeAccounts.firstOrNull()?.id ?: 0L
        }
    }
    var selectedPeriodPreset by rememberSaveable { mutableStateOf(PassbookPeriodPreset.CURRENT_MONTH) }
    var includeRunningBalance by rememberSaveable { mutableStateOf(true) }
    var includeNotesAndTags by rememberSaveable { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(semanticColors.incomeEmerald.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            tint = semanticColors.incomeEmerald,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "PDF Statements & Reports",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Branded, publication-ready A4 reports • 100% Offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Navigation Tabs
            PrimaryTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = { HorizontalDivider(color = semanticColors.cardStrokeSubtle) },
            ) {
                Tab(
                    selected = selectedTab == PdfReportTab.TAX_REPORT,
                    onClick = { selectedTab = PdfReportTab.TAX_REPORT },
                    text = { Text("Annual Tax Statement", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                Tab(
                    selected = selectedTab == PdfReportTab.ACCOUNT_STATEMENT,
                    onClick = { selectedTab = PdfReportTab.ACCOUNT_STATEMENT },
                    text = { Text("Account Statement", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Default.AccountBalance, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (selectedTab) {
                    PdfReportTab.TAX_REPORT -> {
                        PdfTaxReportSection(
                            prefs = prefs,
                            selectedFiscalYearType = selectedFiscalYearType,
                            onSelectFiscalYearType = { selectedFiscalYearType = it },
                            anchorYear = anchorYear,
                            minYear = minAnchorYear,
                            maxYear = maxAnchorYear,
                            onPreviousYear = { anchorYear = (anchorYear - 1).coerceAtLeast(minAnchorYear) },
                            onNextYear = { anchorYear = (anchorYear + 1).coerceAtMost(maxAnchorYear) },
                            allTaxTags = combinedTaxTags,
                            selectedTaxTags = selectedTaxTags.toSet(),
                            onToggleTag = { tag ->
                                selectedTaxTags = if (selectedTaxTags.contains(tag)) {
                                    selectedTaxTags - tag
                                } else {
                                    selectedTaxTags + tag
                                }
                            },
                            isGenerating = isGeneratingPdf,
                            onGenerate = {
                                onGenerateAnnualTaxStatement(
                                    selectedFiscalYearType,
                                    anchorYear,
                                    null,
                                    null,
                                    selectedTaxTags.toSet(),
                                )
                            },
                        )
                    }

                    PdfReportTab.ACCOUNT_STATEMENT -> {
                        PdfAccountStatementSection(
                            accounts = activeAccounts,
                            selectedAccountId = selectedAccountId,
                            onSelectAccount = { selectedAccountId = it },
                            selectedPeriodPreset = selectedPeriodPreset,
                            onSelectPeriodPreset = { selectedPeriodPreset = it },
                            includeRunningBalance = includeRunningBalance,
                            onToggleRunningBalance = { includeRunningBalance = it },
                            includeNotesAndTags = includeNotesAndTags,
                            onToggleNotesAndTags = { includeNotesAndTags = it },
                            isGenerating = isGeneratingPdf,
                            onGenerate = {
                                val (startDay, endDay) = PassbookPeriodPresetResolver.resolvePresetRange(selectedPeriodPreset)
                                val resolvedStart = if (selectedPeriodPreset == PassbookPeriodPreset.ALL_TIME) {
                                    PassbookPeriodPresetResolver.ALL_TIME_SENTINEL_EPOCH_DAY
                                } else {
                                    startDay
                                }
                                onGenerateAccountStatement(
                                    selectedAccountId,
                                    resolvedStart,
                                    endDay,
                                    includeNotesAndTags,
                                    includeRunningBalance,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    // Success Dialog on PDF Completion
    if (lastGeneratedPdfResult != null) {
        PdfExportSuccessDialog(
            result = lastGeneratedPdfResult,
            onSaveCopyToUri = onSaveCopyToUri,
            onDismiss = onClearLastResult,
        )
    }
}
