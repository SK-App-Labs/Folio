package com.folioledger.app.data.pdf

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.folioledger.app.domain.model.CurrencySpec
import com.folioledger.app.domain.model.MinorMoney
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/**
 * High-performance, 100% offline Skia vector PDF generation engine for Folio.
 * Generates branded Account Statements and Universal Annual Financial & Tax Reports
 * with zero external library dependencies and zero APK size bloat.
 */
object PdfStatementEngine {

    private const val PAGE_WIDTH = 595f // A4 width at 72 DPI
    private const val PAGE_HEIGHT = 842f // A4 height at 72 DPI
    private const val MARGIN_LEFT = 36f
    private const val MARGIN_RIGHT = 36f
    private const val MARGIN_TOP = 36f
    private const val MARGIN_BOTTOM = 36f
    private const val USABLE_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT // 523f
    private const val USABLE_BOTTOM = PAGE_HEIGHT - MARGIN_BOTTOM // 806f
    private const val FOOTER_Y = 818f
    private const val FOOTER_RULE_Y = 804f

    // Theme Colors
    private val COLOR_PRIMARY_EMERALD = Color.rgb(5, 150, 105) // #059669
    private val COLOR_TEXT_PRIMARY = Color.rgb(15, 23, 42) // #0F172A
    private val COLOR_TEXT_MUTED = Color.rgb(100, 116, 139) // #64748B
    private val COLOR_CARD_BG = Color.rgb(248, 250, 252) // #F8FAFC
    private val COLOR_ZEBRA_BG = Color.rgb(241, 245, 249) // #F1F5F9
    private val COLOR_BORDER = Color.rgb(226, 232, 240) // #E2E8F0
    private val COLOR_HEADER_BG = Color.rgb(30, 41, 59) // #1E293B
    private val COLOR_INFLOW = Color.rgb(4, 120, 87) // #047857
    private val COLOR_ACCENT_SUBTLE = Color.rgb(209, 250, 229) // #D1FAE5

    // Thread-safe instance-scoped paints
    private class PdfThemePaints {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 9f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        val boldTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 9f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_MUTED
            textSize = 8f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_PRIMARY
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_PRIMARY_EMERALD
            textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_BORDER
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
        }

        val accentRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_PRIMARY_EMERALD
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    }

    /**
     * Encapsulates mutable canvas and page management for multi-page PDF generation.
     * Prevents stale Canvas references and bottom overflow when rendering multi-row sections.
     */
    private class PdfPageCursor(
        private val pdfDocument: PdfDocument,
        private val timestampText: String,
        val paints: PdfThemePaints
    ) {
        var currentPageNumber: Int = 1
            private set
        var page: PdfDocument.Page = pdfDocument.startPage(
            PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), currentPageNumber).create()
        )
            private set
        var canvas: Canvas = page.canvas
            private set
        var y: Float = MARGIN_TOP

        fun ensureSpace(neededHeight: Float, onNewPageHeader: (PdfPageCursor) -> Unit = {}) {
            if (y + neededHeight > USABLE_BOTTOM) {
                drawPageFooter(canvas, currentPageNumber, 0, timestampText, paints)
                pdfDocument.finishPage(page)

                currentPageNumber++
                page = pdfDocument.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), currentPageNumber).create()
                )
                canvas = page.canvas
                y = MARGIN_TOP
                onNewPageHeader(this)
            }
        }

        fun finishLastPage() {
            drawPageFooter(canvas, currentPageNumber, 0, timestampText, paints)
            pdfDocument.finishPage(page)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // REPORT 1: ACCOUNT PASSBOOK STATEMENT
    // ---------------------------------------------------------------------------------------------

    suspend fun generateAccountStatementPdf(
        context: Context,
        data: AccountStatementPdfData
    ): Result<PdfExportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val pdfDocument = PdfDocument()
            val paints = PdfThemePaints()

            // Pre-calculate pagination
            val rowHeight = if (data.includeNotesAndTags) 25f else 18f
            val summaryHeight = if (data.isCreditCard) 130f else 105f
            val headerAreaHeight = 70f + summaryHeight + 25f // header + card + table header
            val page1AvailableForRows = USABLE_BOTTOM - MARGIN_TOP - headerAreaHeight
            val rowsOnPage1 = (page1AvailableForRows / rowHeight).toInt().coerceAtLeast(1)
            val otherPageAvailableForRows = USABLE_BOTTOM - MARGIN_TOP - 40f // mini header + table header
            val rowsOnOtherPages = (otherPageAvailableForRows / rowHeight).toInt().coerceAtLeast(1)

            val totalEntries = data.entries.size
            val totalPages = if (totalEntries <= rowsOnPage1) {
                1
            } else {
                1 + ceil((totalEntries - rowsOnPage1).toDouble() / rowsOnOtherPages).toInt()
            }

            var currentPageNumber = 1
            var page = pdfDocument.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), currentPageNumber).create()
            )
            var canvas = page.canvas
            var currentY = MARGIN_TOP

            // Page 1: Brand & Report Title
            currentY = drawBrandAndDocumentTitle(
                canvas = canvas,
                startY = currentY,
                title = "ACCOUNT PASSBOOK STATEMENT",
                subtitle = "${data.accountName} (${data.accountGroupTitle}) • ${data.periodTitle}",
                badgeText = "CONFIDENTIAL",
                paints = paints
            )

            // Executive Summary Card
            currentY = drawPassbookSummaryCard(
                canvas = canvas,
                startY = currentY,
                data = data,
                paints = paints
            )

            // Table Header
            currentY = drawPassbookTableHeader(canvas, currentY, data, paints)

            var entryIndex = 0
            while (entryIndex < data.entries.size) {
                val entry = data.entries[entryIndex]

                if (currentY + rowHeight > USABLE_BOTTOM) {
                    drawPageFooter(canvas, currentPageNumber, totalPages, data.generatedTimestampText, paints)
                    pdfDocument.finishPage(page)

                    currentPageNumber++
                    page = pdfDocument.startPage(
                        PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), currentPageNumber).create()
                    )
                    canvas = page.canvas
                    currentY = MARGIN_TOP

                    // Mini-header for continuation pages
                    currentY = drawContinuationHeader(
                        canvas = canvas,
                        startY = currentY,
                        title = "Account Statement: ${data.accountName} (${data.periodTitle})",
                        paints = paints
                    )
                    currentY = drawPassbookTableHeader(canvas, currentY, data, paints)
                }

                val isZebra = entryIndex % 2 == 1
                drawPassbookEntryRow(
                    canvas = canvas,
                    y = currentY,
                    rowHeight = rowHeight,
                    entry = entry,
                    isZebra = isZebra,
                    currencySpec = data.currencySpec,
                    grouping = data.numberGrouping,
                    includeNotes = data.includeNotesAndTags,
                    includeRunningBalance = data.includeRunningBalance,
                    paints = paints
                )

                currentY += rowHeight
                entryIndex++
            }

            // Draw final page footer
            drawPageFooter(canvas, currentPageNumber, totalPages, data.generatedTimestampText, paints)
            pdfDocument.finishPage(page)

            val sanitizedAccountName = data.accountName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val sanitizedPeriod = data.periodTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val fileName = "Folio_Statement_${sanitizedAccountName}_$sanitizedPeriod.pdf"

            val result = savePdfToStorage(context, pdfDocument, fileName, currentPageNumber)
            pdfDocument.close()
            result
        }
    }

    // ---------------------------------------------------------------------------------------------
    // REPORT 2: UNIVERSAL ANNUAL FINANCIAL & TAX STATEMENT
    // ---------------------------------------------------------------------------------------------

    suspend fun generateAnnualTaxStatementPdf(
        context: Context,
        data: AnnualTaxPdfData
    ): Result<PdfExportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val pdfDocument = PdfDocument()
            val paints = PdfThemePaints()
            val cursor = PdfPageCursor(pdfDocument, data.generatedTimestampText, paints)

            // 1. Title Banner
            cursor.y = drawBrandAndDocumentTitle(
                canvas = cursor.canvas,
                startY = cursor.y,
                title = "ANNUAL FINANCIAL & TAX STATEMENT",
                subtitle = "${data.fiscalPeriodTitle} • ${data.fiscalPeriodSubtitle}",
                badgeText = "OFFICIAL AUDIT REPORT",
                paints = paints
            )

            // 2. Executive Financial Summary
            cursor.ensureSpace(96f) { c ->
                c.y = drawContinuationHeader(c.canvas, MARGIN_TOP, "Financial Summary (Cont.)", paints)
            }
            cursor.y = drawAnnualFinancialSummaryCard(cursor.canvas, cursor.y, data, paints)

            // 3. Multi-Currency Valuation Footnotes (if applicable)
            if (data.valuationExchangeNotes.isNotEmpty()) {
                val needed = 16f + (data.valuationExchangeNotes.size * 12f) + 10f
                cursor.ensureSpace(needed) { c ->
                    c.y = drawContinuationHeader(c.canvas, MARGIN_TOP, "Valuation Notes (Cont.)", paints)
                }
                cursor.y = drawValuationNotesBox(cursor.canvas, cursor.y, data.valuationExchangeNotes, paints)
            }

            // 4. Itemized Tax Deductions & Business Schedule
            drawTaxDeductionsSection(cursor, data)

            // 5. Income Sources Schedule
            drawCategoryScheduleSection(
                cursor = cursor,
                sectionTitle = "INCOME BREAKDOWN BY SOURCE",
                items = data.incomeSources,
                primaryCurrency = data.primaryCurrencySpec,
                grouping = data.numberGrouping,
                isInflow = true
            )

            // 6. Expense Category Schedule
            drawCategoryScheduleSection(
                cursor = cursor,
                sectionTitle = "EXPENSES BY CATEGORY",
                items = data.expenseCategories,
                primaryCurrency = data.primaryCurrencySpec,
                grouping = data.numberGrouping,
                isInflow = false
            )

            // 7. Year-End Balance Sheet Snapshot
            drawBalanceSheetSection(cursor, data)

            // Finish the final page
            cursor.finishLastPage()

            val sanitizedTitle = data.fiscalPeriodTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val fileName = "Folio_Tax_Report_$sanitizedTitle.pdf"

            val result = savePdfToStorage(context, pdfDocument, fileName, cursor.currentPageNumber)
            pdfDocument.close()
            result
        }
    }

    // ---------------------------------------------------------------------------------------------
    // DRAWING HELPERS: HEADERS & BRANDING
    // ---------------------------------------------------------------------------------------------

    private fun drawBrandAndDocumentTitle(
        canvas: Canvas,
        startY: Float,
        title: String,
        subtitle: String,
        badgeText: String,
        paints: PdfThemePaints
    ): Float {
        var y = startY

        // Top Brand Row: FOLIO + Badge
        canvas.drawText("FOLIO", MARGIN_LEFT, y + 12f, paints.brandPaint)

        // Badge pill on right
        val badgeWidth = paints.boldTextPaint.measureText(badgeText) + 14f
        val badgeX = PAGE_WIDTH - MARGIN_RIGHT - badgeWidth
        paints.fillPaint.color = COLOR_ACCENT_SUBTLE
        canvas.drawRoundRect(badgeX, y, badgeX + badgeWidth, y + 16f, 4f, 4f, paints.fillPaint)
        paints.boldTextPaint.color = COLOR_PRIMARY_EMERALD
        canvas.drawText(badgeText, badgeX + 7f, y + 11.5f, paints.boldTextPaint)
        paints.boldTextPaint.color = COLOR_TEXT_PRIMARY // reset

        y += 24f

        // Document Title
        canvas.drawText(title, MARGIN_LEFT, y + 14f, paints.titlePaint)
        y += 20f

        // Subtitle
        paints.mutedPaint.textSize = 9.5f
        canvas.drawText(subtitle, MARGIN_LEFT, y + 8f, paints.mutedPaint)
        paints.mutedPaint.textSize = 8f // reset
        y += 14f

        // Emerald Accent Rule
        canvas.drawLine(MARGIN_LEFT, y, PAGE_WIDTH - MARGIN_RIGHT, y, paints.accentRulePaint)
        y += 10f

        return y
    }

    private fun drawContinuationHeader(
        canvas: Canvas,
        startY: Float,
        title: String,
        paints: PdfThemePaints
    ): Float {
        var y = startY
        paints.boldTextPaint.textSize = 10f
        canvas.drawText(title, MARGIN_LEFT, y + 10f, paints.boldTextPaint)
        paints.boldTextPaint.textSize = 9f
        y += 16f
        canvas.drawLine(MARGIN_LEFT, y, PAGE_WIDTH - MARGIN_RIGHT, y, paints.strokePaint)
        y += 8f
        return y
    }

    private fun drawPageFooter(
        canvas: Canvas,
        pageNumber: Int,
        totalPages: Int,
        timestampText: String,
        paints: PdfThemePaints
    ) {
        // Thin rule above footer
        canvas.drawLine(MARGIN_LEFT, FOOTER_RULE_Y, PAGE_WIDTH - MARGIN_RIGHT, FOOTER_RULE_Y, paints.strokePaint)

        // Left disclaimer
        canvas.drawText(
            "Folio Personal Ledger • 100% Offline & Private • Generated $timestampText",
            MARGIN_LEFT,
            FOOTER_Y,
            paints.mutedPaint
        )

        // Right page count
        val pageText = if (totalPages > 0) "Page $pageNumber of $totalPages" else "Page $pageNumber"
        val textWidth = paints.mutedPaint.measureText(pageText)
        canvas.drawText(pageText, PAGE_WIDTH - MARGIN_RIGHT - textWidth, FOOTER_Y, paints.mutedPaint)
    }

    // ---------------------------------------------------------------------------------------------
    // DRAWING HELPERS: PASSBOOK STATEMENT
    // ---------------------------------------------------------------------------------------------

    private fun drawPassbookSummaryCard(
        canvas: Canvas,
        startY: Float,
        data: AccountStatementPdfData,
        paints: PdfThemePaints
    ): Float {
        val cardHeight = if (data.isCreditCard) 100f else 78f
        val cardRect = RectF(MARGIN_LEFT, startY, PAGE_WIDTH - MARGIN_RIGHT, startY + cardHeight)

        paints.fillPaint.color = COLOR_CARD_BG
        canvas.drawRoundRect(cardRect, 6f, 6f, paints.fillPaint)
        canvas.drawRoundRect(cardRect, 6f, 6f, paints.strokePaint)

        val cellWidth = USABLE_WIDTH / 4f
        val row1Y = startY + 16f
        val row1ValY = row1Y + 14f

        // Cell 1: Opening Balance
        canvas.drawText("OPENING BALANCE", MARGIN_LEFT + 10f, row1Y, paints.mutedPaint)
        val openingText = MoneyFormatterAndCalculator.formatMinorMoney(
            data.openingBalance, data.currencySpec, data.numberGrouping
        )
        canvas.drawText(openingText, MARGIN_LEFT + 10f, row1ValY, paints.boldTextPaint)

        // Cell 2: Total Inflows (+)
        val cell2X = MARGIN_LEFT + cellWidth
        canvas.drawText("TOTAL INFLOWS (+)", cell2X, row1Y, paints.mutedPaint)
        val inflowsText = MoneyFormatterAndCalculator.formatMinorMoney(
            data.totalInflows, data.currencySpec, data.numberGrouping, showPlusSignForPositive = true
        )
        val prevColor = paints.textPaint.color
        paints.textPaint.color = COLOR_INFLOW
        canvas.drawText(inflowsText, cell2X, row1ValY, paints.textPaint)
        paints.textPaint.color = prevColor

        // Cell 3: Total Outflows (-)
        val cell3X = MARGIN_LEFT + (cellWidth * 2)
        canvas.drawText("TOTAL OUTFLOWS (-)", cell3X, row1Y, paints.mutedPaint)
        val outflowsText = MoneyFormatterAndCalculator.formatMinorMoney(
            data.totalOutflows, data.currencySpec, data.numberGrouping
        )
        canvas.drawText(outflowsText, cell3X, row1ValY, paints.textPaint)

        // Cell 4: Closing Balance
        val cell4X = MARGIN_LEFT + (cellWidth * 3)
        canvas.drawText("CLOSING BALANCE", cell4X, row1Y, paints.mutedPaint)
        val closingText = MoneyFormatterAndCalculator.formatMinorMoney(
            data.closingBalance, data.currencySpec, data.numberGrouping
        )
        paints.boldTextPaint.textSize = 10f
        canvas.drawText(closingText, cell4X, row1ValY, paints.boldTextPaint)
        paints.boldTextPaint.textSize = 9f

        // Credit Card Extras Row
        if (data.isCreditCard) {
            val dividerY = startY + 44f
            canvas.drawLine(MARGIN_LEFT + 10f, dividerY, PAGE_WIDTH - MARGIN_RIGHT - 10f, dividerY, paints.strokePaint)

            val row2Y = dividerY + 16f
            val row2ValY = row2Y + 14f

            // Total Debt
            canvas.drawText("TOTAL DEBT", MARGIN_LEFT + 10f, row2Y, paints.mutedPaint)
            val debtText = MoneyFormatterAndCalculator.formatMinorMoney(
                data.closingBalance, data.currencySpec, data.numberGrouping
            )
            canvas.drawText(debtText, MARGIN_LEFT + 10f, row2ValY, paints.boldTextPaint)

            // Credit Limit
            canvas.drawText("CREDIT LIMIT", cell2X, row2Y, paints.mutedPaint)
            val limitText = data.creditCardLimit?.let {
                MoneyFormatterAndCalculator.formatMinorMoney(it, data.currencySpec, data.numberGrouping)
            } ?: "N/A"
            canvas.drawText(limitText, cell2X, row2ValY, paints.textPaint)

            // Statement Payable
            canvas.drawText("STATEMENT PAYABLE", cell3X, row2Y, paints.mutedPaint)
            val payableText = data.creditCardStatementPayable?.let {
                MoneyFormatterAndCalculator.formatMinorMoney(it, data.currencySpec, data.numberGrouping)
            } ?: "₹0.00"
            canvas.drawText(payableText, cell3X, row2ValY, paints.textPaint)

            // Payment Due Date
            canvas.drawText("PAYMENT DUE", cell4X, row2Y, paints.mutedPaint)
            val dueText = data.creditCardDueDateText ?: "N/A"
            canvas.drawText(dueText, cell4X, row2ValY, paints.textPaint)
        } else {
            // Net Flow & Entry Count summary line
            val netFlowText = MoneyFormatterAndCalculator.formatMinorMoney(
                data.netPeriodFlow, data.currencySpec, data.numberGrouping, showPlusSignForPositive = true
            )
            val summaryLine = "Net Period Change: $netFlowText • Total Entries: ${data.transactionCount}"
            canvas.drawText(summaryLine, MARGIN_LEFT + 10f, startY + 58f, paints.mutedPaint)
        }

        return startY + cardHeight + 12f
    }

    private fun drawPassbookTableHeader(
        canvas: Canvas,
        startY: Float,
        data: AccountStatementPdfData,
        paints: PdfThemePaints
    ): Float {
        val headerHeight = 20f
        paints.fillPaint.color = COLOR_HEADER_BG
        canvas.drawRect(MARGIN_LEFT, startY, PAGE_WIDTH - MARGIN_RIGHT, startY + headerHeight, paints.fillPaint)

        paints.boldTextPaint.color = Color.WHITE
        val y = startY + 13.5f

        // Col 1: Date (55pt)
        canvas.drawText("DATE", MARGIN_LEFT + 6f, y, paints.boldTextPaint)

        // Col 2: Payee / Description (190pt)
        canvas.drawText("PAYEE & DETAILS", MARGIN_LEFT + 60f, y, paints.boldTextPaint)

        // Col 3: Category (90pt)
        canvas.drawText("CATEGORY", MARGIN_LEFT + 255f, y, paints.boldTextPaint)

        // Col 4: Inflow (+) (60pt right)
        drawTextRight(canvas, "INFLOW (+)", MARGIN_LEFT + 395f, y, paints.boldTextPaint)

        // Col 5: Outflow (-) (60pt right)
        drawTextRight(canvas, "OUTFLOW (-)", MARGIN_LEFT + 460f, y, paints.boldTextPaint)

        // Col 6: Balance (60pt right)
        if (data.includeRunningBalance) {
            drawTextRight(canvas, "BALANCE", PAGE_WIDTH - MARGIN_RIGHT - 6f, y, paints.boldTextPaint)
        }

        paints.boldTextPaint.color = COLOR_TEXT_PRIMARY // reset
        return startY + headerHeight
    }

    private fun drawPassbookEntryRow(
        canvas: Canvas,
        y: Float,
        rowHeight: Float,
        entry: com.folioledger.app.domain.model.AccountPassbookEntryUiModel,
        isZebra: Boolean,
        currencySpec: CurrencySpec,
        grouping: NumberGroupingSystem,
        includeNotes: Boolean,
        includeRunningBalance: Boolean,
        paints: PdfThemePaints
    ) {
        if (isZebra) {
            paints.fillPaint.color = COLOR_ZEBRA_BG
            canvas.drawRect(MARGIN_LEFT, y, PAGE_WIDTH - MARGIN_RIGHT, y + rowHeight, paints.fillPaint)
        }

        val textY = y + 13f
        val tx = entry.transaction

        // Date (DD MMM)
        val localDate = java.time.LocalDate.ofEpochDay(tx.ledgerDateEpochDay)
        val dateText = localDate.format(DateTimeFormatter.ofPattern("dd MMM", Locale.US))
        canvas.drawText(dateText, MARGIN_LEFT + 6f, textY, paints.textPaint)

        // Payee & Description
        val payeeText = tx.payeeOrDescription.ifBlank { tx.categoryName ?: "Transaction" }
        val truncatedPayee = truncateText(payeeText, paints.boldTextPaint, 185f)
        canvas.drawText(truncatedPayee, MARGIN_LEFT + 60f, textY, paints.boldTextPaint)

        if (includeNotes) {
            val subDetails = buildString {
                if (tx.note.isNotBlank()) append(tx.note)
                if (tx.tags.isNotEmpty()) {
                    if (isNotEmpty()) append(" • ")
                    append(tx.tags.joinToString(" ") { "#$it" })
                }
            }
            if (subDetails.isNotBlank()) {
                val truncatedSub = truncateText(subDetails, paints.mutedPaint, 185f)
                canvas.drawText(truncatedSub, MARGIN_LEFT + 60f, textY + 9f, paints.mutedPaint)
            }
        }

        // Category
        val catText = tx.categoryName ?: "Uncategorized"
        val truncatedCat = truncateText(catText, paints.textPaint, 85f)
        canvas.drawText(truncatedCat, MARGIN_LEFT + 255f, textY, paints.textPaint)

        // Inflow (+)
        if (entry.isInflow) {
            val inflowStr = MoneyFormatterAndCalculator.formatMinorMoney(
                entry.signedDelta, currencySpec, grouping, showPlusSignForPositive = false
            )
            val prevColor = paints.textPaint.color
            paints.textPaint.color = COLOR_INFLOW
            drawTextRight(canvas, inflowStr, MARGIN_LEFT + 395f, textY, paints.textPaint)
            paints.textPaint.color = prevColor
        } else {
            drawTextRight(canvas, "—", MARGIN_LEFT + 395f, textY, paints.mutedPaint)
        }

        // Outflow (-)
        if (!entry.isInflow) {
            val outflowMoney = MinorMoney(kotlin.math.abs(entry.signedDelta.amountMinor))
            val outflowStr = MoneyFormatterAndCalculator.formatMinorMoney(
                outflowMoney, currencySpec, grouping, showPlusSignForPositive = false
            )
            val outflowTargetX = if (currencySpec.symbol.length > 2) MARGIN_LEFT + 475f else MARGIN_LEFT + 460f
            drawTextRight(canvas, outflowStr, outflowTargetX, textY, paints.textPaint)
        } else {
            drawTextRight(canvas, "—", MARGIN_LEFT + 460f, textY, paints.mutedPaint)
        }

        // Running Balance
        if (includeRunningBalance) {
            val balanceStr = MoneyFormatterAndCalculator.formatMinorMoney(
                entry.runningBalanceAfter, currencySpec, grouping
            )
            drawTextRight(canvas, balanceStr, PAGE_WIDTH - MARGIN_RIGHT - 6f, textY, paints.boldTextPaint)
        }

        // Subtle row bottom line
        canvas.drawLine(MARGIN_LEFT, y + rowHeight, PAGE_WIDTH - MARGIN_RIGHT, y + rowHeight, paints.strokePaint)
    }

    // ---------------------------------------------------------------------------------------------
    // DRAWING HELPERS: ANNUAL TAX & FINANCIAL REPORT
    // ---------------------------------------------------------------------------------------------

    private fun drawAnnualFinancialSummaryCard(
        canvas: Canvas,
        startY: Float,
        data: AnnualTaxPdfData,
        paints: PdfThemePaints
    ): Float {
        val cardHeight = 84f
        val cardRect = RectF(MARGIN_LEFT, startY, PAGE_WIDTH - MARGIN_RIGHT, startY + cardHeight)

        paints.fillPaint.color = COLOR_CARD_BG
        canvas.drawRoundRect(cardRect, 6f, 6f, paints.fillPaint)
        canvas.drawRoundRect(cardRect, 6f, 6f, paints.strokePaint)

        val cellWidth = USABLE_WIDTH / 4f
        val row1Y = startY + 16f
        val row1ValY = row1Y + 14f

        // 1. Gross Inflows
        canvas.drawText("GROSS INFLOWS", MARGIN_LEFT + 10f, row1Y, paints.mutedPaint)
        val inflowsText = MoneyFormatterAndCalculator.formatMinorMoney(
            MinorMoney(data.grossInflowsMinor), data.primaryCurrencySpec, data.numberGrouping
        )
        val prevColor = paints.textPaint.color
        paints.textPaint.color = COLOR_INFLOW
        canvas.drawText(inflowsText, MARGIN_LEFT + 10f, row1ValY, paints.textPaint)
        paints.textPaint.color = prevColor

        // 2. Total Expenditures
        val cell2X = MARGIN_LEFT + cellWidth
        canvas.drawText("TOTAL EXPENDITURE", cell2X, row1Y, paints.mutedPaint)
        val expText = MoneyFormatterAndCalculator.formatMinorMoney(
            MinorMoney(data.totalExpensesMinor), data.primaryCurrencySpec, data.numberGrouping
        )
        canvas.drawText(expText, cell2X, row1ValY, paints.textPaint)

        // 3. Net Savings / Surplus
        val cell3X = MARGIN_LEFT + (cellWidth * 2)
        canvas.drawText("NET SAVINGS (SURPLUS)", cell3X, row1Y, paints.mutedPaint)
        val netSavingsText = MoneyFormatterAndCalculator.formatMinorMoney(
            MinorMoney(data.netSavingsMinor), data.primaryCurrencySpec, data.numberGrouping, showPlusSignForPositive = true
        )
        canvas.drawText(netSavingsText, cell3X, row1ValY, paints.boldTextPaint)

        // 4. Year-End Net Worth
        val cell4X = MARGIN_LEFT + (cellWidth * 3)
        canvas.drawText("YEAR-END NET WORTH", cell4X, row1Y, paints.mutedPaint)
        val netWorthText = MoneyFormatterAndCalculator.formatMinorMoney(
            MinorMoney(data.netWorthMinor), data.primaryCurrencySpec, data.numberGrouping
        )
        paints.boldTextPaint.textSize = 10f
        canvas.drawText(netWorthText, cell4X, row1ValY, paints.boldTextPaint)
        paints.boldTextPaint.textSize = 9f

        // Bottom sub-row: Savings Rate % and Total Deductions
        val dividerY = startY + 46f
        canvas.drawLine(MARGIN_LEFT + 10f, dividerY, PAGE_WIDTH - MARGIN_RIGHT - 10f, dividerY, paints.strokePaint)

        val deductionsFormatted = MoneyFormatterAndCalculator.formatMinorMoney(
            MinorMoney(data.totalTaxDeductionsMinor), data.primaryCurrencySpec, data.numberGrouping
        )
        val subLine = "Savings Rate: ${String.format(Locale.US, "%.1f", data.savingsRatePercent)}% • Total Tax Deductions / Business Outflows: $deductionsFormatted"
        canvas.drawText(subLine, MARGIN_LEFT + 10f, startY + 68f, paints.boldTextPaint)

        return startY + cardHeight + 12f
    }

    private fun drawValuationNotesBox(
        canvas: Canvas,
        startY: Float,
        notes: List<String>,
        paints: PdfThemePaints
    ): Float {
        val boxHeight = 16f + (notes.size * 12f)
        val boxRect = RectF(MARGIN_LEFT, startY, PAGE_WIDTH - MARGIN_RIGHT, startY + boxHeight)

        paints.fillPaint.color = Color.rgb(254, 243, 199) // subtle amber #FEF3C7
        canvas.drawRoundRect(boxRect, 4f, 4f, paints.fillPaint)

        var y = startY + 12f
        paints.boldTextPaint.textSize = 8f
        paints.boldTextPaint.color = Color.rgb(146, 64, 14) // amber text
        canvas.drawText("MULTI-CURRENCY VALUATION BASIS:", MARGIN_LEFT + 8f, y, paints.boldTextPaint)
        paints.boldTextPaint.textSize = 9f
        paints.boldTextPaint.color = COLOR_TEXT_PRIMARY // reset

        paints.mutedPaint.textSize = 8f
        for (note in notes) {
            y += 12f
            canvas.drawText("• $note", MARGIN_LEFT + 8f, y, paints.mutedPaint)
        }
        paints.mutedPaint.textSize = 8f

        return startY + boxHeight + 10f
    }

    private fun drawTaxDeductionsSection(
        cursor: PdfPageCursor,
        data: AnnualTaxPdfData
    ) {
        val paints = cursor.paints
        cursor.ensureSpace(50f) { c ->
            c.y = drawContinuationHeader(c.canvas, MARGIN_TOP, "Itemized Tax Deductions & Business Schedule (Cont.)", paints)
        }

        paints.boldTextPaint.textSize = 11f
        cursor.canvas.drawText("ITEMIZED TAX DEDUCTIONS & BUSINESS EXPENSES", MARGIN_LEFT, cursor.y + 10f, paints.boldTextPaint)
        paints.boldTextPaint.textSize = 9f
        cursor.y += 16f

        // Table Header
        val headerHeight = 18f
        paints.fillPaint.color = COLOR_HEADER_BG
        cursor.canvas.drawRect(MARGIN_LEFT, cursor.y, PAGE_WIDTH - MARGIN_RIGHT, cursor.y + headerHeight, paints.fillPaint)
        paints.boldTextPaint.color = Color.WHITE
        val headerY = cursor.y + 12f

        cursor.canvas.drawText("DATE", MARGIN_LEFT + 6f, headerY, paints.boldTextPaint)
        cursor.canvas.drawText("PAYEE / RECEIPT", MARGIN_LEFT + 65f, headerY, paints.boldTextPaint)
        cursor.canvas.drawText("CATEGORY", MARGIN_LEFT + 225f, headerY, paints.boldTextPaint)
        cursor.canvas.drawText("TAX TAG / NOTE", MARGIN_LEFT + 320f, headerY, paints.boldTextPaint)
        drawTextRight(cursor.canvas, "AMOUNT (${data.primaryCurrencySpec.code})", PAGE_WIDTH - MARGIN_RIGHT - 6f, headerY, paints.boldTextPaint)

        paints.boldTextPaint.color = COLOR_TEXT_PRIMARY
        cursor.y += headerHeight

        if (data.taxDeductions.isEmpty()) {
            paints.fillPaint.color = COLOR_CARD_BG
            cursor.canvas.drawRect(MARGIN_LEFT, cursor.y, PAGE_WIDTH - MARGIN_RIGHT, cursor.y + 24f, paints.fillPaint)
            cursor.canvas.drawText("No transactions tagged with tax or business deductions found in this fiscal period.", MARGIN_LEFT + 10f, cursor.y + 15f, paints.mutedPaint)
            cursor.y += 24f
            cursor.canvas.drawLine(MARGIN_LEFT, cursor.y, PAGE_WIDTH - MARGIN_RIGHT, cursor.y, paints.strokePaint)
            cursor.y += 12f
            return
        }

        val rowHeight = 20f
        var index = 0
        for (item in data.taxDeductions) {
            cursor.ensureSpace(rowHeight) { c ->
                c.y = drawContinuationHeader(c.canvas, MARGIN_TOP, "Itemized Tax Deductions & Business Schedule (Cont.)", paints)
                paints.fillPaint.color = COLOR_HEADER_BG
                c.canvas.drawRect(MARGIN_LEFT, c.y, PAGE_WIDTH - MARGIN_RIGHT, c.y + headerHeight, paints.fillPaint)
                paints.boldTextPaint.color = Color.WHITE
                val newHeaderY = c.y + 12f
                c.canvas.drawText("DATE", MARGIN_LEFT + 6f, newHeaderY, paints.boldTextPaint)
                c.canvas.drawText("PAYEE / RECEIPT", MARGIN_LEFT + 65f, newHeaderY, paints.boldTextPaint)
                c.canvas.drawText("CATEGORY", MARGIN_LEFT + 225f, newHeaderY, paints.boldTextPaint)
                c.canvas.drawText("TAX TAG / NOTE", MARGIN_LEFT + 320f, newHeaderY, paints.boldTextPaint)
                drawTextRight(c.canvas, "AMOUNT (${data.primaryCurrencySpec.code})", PAGE_WIDTH - MARGIN_RIGHT - 6f, newHeaderY, paints.boldTextPaint)
                paints.boldTextPaint.color = COLOR_TEXT_PRIMARY
                c.y += headerHeight
            }

            if (index % 2 == 1) {
                paints.fillPaint.color = COLOR_ZEBRA_BG
                cursor.canvas.drawRect(MARGIN_LEFT, cursor.y, PAGE_WIDTH - MARGIN_RIGHT, cursor.y + rowHeight, paints.fillPaint)
            }

            val textY = cursor.y + 13f
            cursor.canvas.drawText(item.dateText, MARGIN_LEFT + 6f, textY, paints.textPaint)

            val payeeText = truncateText(item.payee, paints.boldTextPaint, 155f)
            cursor.canvas.drawText(payeeText, MARGIN_LEFT + 65f, textY, paints.boldTextPaint)

            val catText = truncateText(item.categoryName, paints.textPaint, 90f)
            cursor.canvas.drawText(catText, MARGIN_LEFT + 225f, textY, paints.textPaint)

            val tagOrNote = truncateText(item.tagOrDeductionType + (item.note?.let { " • $it" } ?: ""), paints.mutedPaint, 110f)
            cursor.canvas.drawText(tagOrNote, MARGIN_LEFT + 320f, textY, paints.mutedPaint)

            val amountStr = MoneyFormatterAndCalculator.formatMinorMoney(
                MinorMoney(item.convertedMinor), data.primaryCurrencySpec, data.numberGrouping
            )
            drawTextRight(cursor.canvas, amountStr, PAGE_WIDTH - MARGIN_RIGHT - 6f, textY, paints.boldTextPaint)

            cursor.canvas.drawLine(MARGIN_LEFT, cursor.y + rowHeight, PAGE_WIDTH - MARGIN_RIGHT, cursor.y + rowHeight, paints.strokePaint)
            cursor.y += rowHeight
            index++
        }

        // Total Deductions Footer Row
        val totalRowHeight = 22f
        cursor.ensureSpace(totalRowHeight + 14f) { c ->
            c.y = drawContinuationHeader(c.canvas, MARGIN_TOP, "Itemized Tax Deductions & Business Schedule (Cont.)", paints)
        }
        paints.fillPaint.color = COLOR_ACCENT_SUBTLE
        cursor.canvas.drawRect(MARGIN_LEFT, cursor.y, PAGE_WIDTH - MARGIN_RIGHT, cursor.y + totalRowHeight, paints.fillPaint)
        paints.boldTextPaint.color = COLOR_PRIMARY_EMERALD
        cursor.canvas.drawText("TOTAL DEDUCTIONS & AUDIT EXPENSES", MARGIN_LEFT + 10f, cursor.y + 15f, paints.boldTextPaint)
        val totalDeductionStr = MoneyFormatterAndCalculator.formatMinorMoney(
            MinorMoney(data.totalTaxDeductionsMinor), data.primaryCurrencySpec, data.numberGrouping
        )
        drawTextRight(cursor.canvas, totalDeductionStr, PAGE_WIDTH - MARGIN_RIGHT - 6f, cursor.y + 15f, paints.boldTextPaint)
        paints.boldTextPaint.color = COLOR_TEXT_PRIMARY
        cursor.y += totalRowHeight + 14f
    }

    private fun drawCategoryScheduleSection(
        cursor: PdfPageCursor,
        sectionTitle: String,
        items: List<CategoryAggregateItem>,
        primaryCurrency: CurrencySpec,
        grouping: NumberGroupingSystem,
        isInflow: Boolean
    ) {
        val paints = cursor.paints
        cursor.ensureSpace(50f) { c ->
            c.y = drawContinuationHeader(c.canvas, MARGIN_TOP, "$sectionTitle (Cont.)", paints)
        }

        paints.boldTextPaint.textSize = 11f
        cursor.canvas.drawText(sectionTitle, MARGIN_LEFT, cursor.y + 10f, paints.boldTextPaint)
        paints.boldTextPaint.textSize = 9f
        cursor.y += 16f

        // Header
        val headerHeight = 18f
        paints.fillPaint.color = COLOR_HEADER_BG
        cursor.canvas.drawRect(MARGIN_LEFT, cursor.y, PAGE_WIDTH - MARGIN_RIGHT, cursor.y + headerHeight, paints.fillPaint)
        paints.boldTextPaint.color = Color.WHITE
        val headerY = cursor.y + 12f

        cursor.canvas.drawText("CATEGORY", MARGIN_LEFT + 6f, headerY, paints.boldTextPaint)
        cursor.canvas.drawText("ENTRIES", MARGIN_LEFT + 220f, headerY, paints.boldTextPaint)
        drawTextRight(cursor.canvas, "% OF TOTAL", MARGIN_LEFT + 360f, headerY, paints.boldTextPaint)
        drawTextRight(cursor.canvas, "TOTAL AMOUNT (${primaryCurrency.code})", PAGE_WIDTH - MARGIN_RIGHT - 6f, headerY, paints.boldTextPaint)

        paints.boldTextPaint.color = COLOR_TEXT_PRIMARY
        cursor.y += headerHeight

        if (items.isEmpty()) {
            paints.fillPaint.color = COLOR_CARD_BG
            cursor.canvas.drawRect(MARGIN_LEFT, cursor.y, PAGE_WIDTH - MARGIN_RIGHT, cursor.y + 20f, paints.fillPaint)
            cursor.canvas.drawText("No items recorded in this schedule.", MARGIN_LEFT + 10f, cursor.y + 14f, paints.mutedPaint)
            cursor.y += 20f
            cursor.y += 12f
            return
        }

        val rowHeight = 18f
        var index = 0
        for (item in items) {
            cursor.ensureSpace(rowHeight) { c ->
                c.y = drawContinuationHeader(c.canvas, MARGIN_TOP, "$sectionTitle (Cont.)", paints)
                paints.fillPaint.color = COLOR_HEADER_BG
                c.canvas.drawRect(MARGIN_LEFT, c.y, PAGE_WIDTH - MARGIN_RIGHT, c.y + headerHeight, paints.fillPaint)
                paints.boldTextPaint.color = Color.WHITE
                val newHeaderY = c.y + 12f
                c.canvas.drawText("CATEGORY", MARGIN_LEFT + 6f, newHeaderY, paints.boldTextPaint)
                c.canvas.drawText("ENTRIES", MARGIN_LEFT + 220f, newHeaderY, paints.boldTextPaint)
                drawTextRight(c.canvas, "% OF TOTAL", MARGIN_LEFT + 360f, newHeaderY, paints.boldTextPaint)
                drawTextRight(c.canvas, "TOTAL AMOUNT (${primaryCurrency.code})", PAGE_WIDTH - MARGIN_RIGHT - 6f, newHeaderY, paints.boldTextPaint)
                paints.boldTextPaint.color = COLOR_TEXT_PRIMARY
                c.y += headerHeight
            }

            if (index % 2 == 1) {
                paints.fillPaint.color = COLOR_ZEBRA_BG
                cursor.canvas.drawRect(MARGIN_LEFT, cursor.y, PAGE_WIDTH - MARGIN_RIGHT, cursor.y + rowHeight, paints.fillPaint)
            }

            val textY = cursor.y + 12.5f
            cursor.canvas.drawText(item.categoryName, MARGIN_LEFT + 6f, textY, paints.boldTextPaint)
            cursor.canvas.drawText("${item.count} txns", MARGIN_LEFT + 220f, textY, paints.mutedPaint)

            val pctStr = String.format(Locale.US, "%.1f%%", item.percentageOfTotal)
            drawTextRight(cursor.canvas, pctStr, MARGIN_LEFT + 360f, textY, paints.textPaint)

            val amountStr = MoneyFormatterAndCalculator.formatMinorMoney(
                MinorMoney(item.convertedAmountMinor), primaryCurrency, grouping
            )
            if (isInflow) {
                val prev = paints.textPaint.color
                paints.textPaint.color = COLOR_INFLOW
                drawTextRight(cursor.canvas, amountStr, PAGE_WIDTH - MARGIN_RIGHT - 6f, textY, paints.textPaint)
                paints.textPaint.color = prev
            } else {
                drawTextRight(cursor.canvas, amountStr, PAGE_WIDTH - MARGIN_RIGHT - 6f, textY, paints.textPaint)
            }

            cursor.canvas.drawLine(MARGIN_LEFT, cursor.y + rowHeight, PAGE_WIDTH - MARGIN_RIGHT, cursor.y + rowHeight, paints.strokePaint)
            cursor.y += rowHeight
            index++
        }

        cursor.y += 12f
    }

    private fun drawBalanceSheetSection(
        cursor: PdfPageCursor,
        data: AnnualTaxPdfData
    ) {
        val paints = cursor.paints
        cursor.ensureSpace(50f) { c ->
            c.y = drawContinuationHeader(c.canvas, MARGIN_TOP, "Year-End Balance Sheet Snapshot (Cont.)", paints)
        }

        paints.boldTextPaint.textSize = 11f
        cursor.canvas.drawText("YEAR-END ASSETS, LIABILITIES & WEALTH SNAPSHOT", MARGIN_LEFT, cursor.y + 10f, paints.boldTextPaint)
        paints.boldTextPaint.textSize = 9f
        cursor.y += 16f

        val headerHeight = 18f
        paints.fillPaint.color = COLOR_HEADER_BG
        cursor.canvas.drawRect(MARGIN_LEFT, cursor.y, PAGE_WIDTH - MARGIN_RIGHT, cursor.y + headerHeight, paints.fillPaint)
        paints.boldTextPaint.color = Color.WHITE
        val headerY = cursor.y + 12f

        cursor.canvas.drawText("ACCOUNT NAME", MARGIN_LEFT + 6f, headerY, paints.boldTextPaint)
        cursor.canvas.drawText("TYPE", MARGIN_LEFT + 200f, headerY, paints.boldTextPaint)
        drawTextRight(cursor.canvas, "CLOSING BALANCE", MARGIN_LEFT + 370f, headerY, paints.boldTextPaint)
        drawTextRight(cursor.canvas, "CONVERTED (${data.primaryCurrencySpec.code})", PAGE_WIDTH - MARGIN_RIGHT - 6f, headerY, paints.boldTextPaint)

        paints.boldTextPaint.color = COLOR_TEXT_PRIMARY
        cursor.y += headerHeight

        val rowHeight = 18f
        var index = 0
        for (acc in data.accountsSnapshot) {
            cursor.ensureSpace(rowHeight) { c ->
                c.y = drawContinuationHeader(c.canvas, MARGIN_TOP, "Year-End Balance Sheet Snapshot (Cont.)", paints)
                paints.fillPaint.color = COLOR_HEADER_BG
                c.canvas.drawRect(MARGIN_LEFT, c.y, PAGE_WIDTH - MARGIN_RIGHT, c.y + headerHeight, paints.fillPaint)
                paints.boldTextPaint.color = Color.WHITE
                val newHeaderY = c.y + 12f
                c.canvas.drawText("ACCOUNT NAME", MARGIN_LEFT + 6f, newHeaderY, paints.boldTextPaint)
                c.canvas.drawText("TYPE", MARGIN_LEFT + 200f, newHeaderY, paints.boldTextPaint)
                drawTextRight(c.canvas, "CLOSING BALANCE", MARGIN_LEFT + 370f, newHeaderY, paints.boldTextPaint)
                drawTextRight(c.canvas, "CONVERTED (${data.primaryCurrencySpec.code})", PAGE_WIDTH - MARGIN_RIGHT - 6f, newHeaderY, paints.boldTextPaint)
                paints.boldTextPaint.color = COLOR_TEXT_PRIMARY
                c.y += headerHeight
            }

            if (index % 2 == 1) {
                paints.fillPaint.color = COLOR_ZEBRA_BG
                cursor.canvas.drawRect(MARGIN_LEFT, cursor.y, PAGE_WIDTH - MARGIN_RIGHT, cursor.y + rowHeight, paints.fillPaint)
            }

            val textY = cursor.y + 12.5f
            cursor.canvas.drawText(acc.accountName, MARGIN_LEFT + 6f, textY, paints.boldTextPaint)
            cursor.canvas.drawText(acc.groupName, MARGIN_LEFT + 200f, textY, paints.mutedPaint)

            // Raw native balance
            val nativeSpec = SupportedCurrencies.fromCode(acc.currencyCode)
            val nativeText = MoneyFormatterAndCalculator.formatMinorMoney(
                MinorMoney(acc.nativeBalanceMinor), nativeSpec, data.numberGrouping
            )
            drawTextRight(cursor.canvas, nativeText, MARGIN_LEFT + 370f, textY, paints.textPaint)

            // Converted balance
            val convStr = MoneyFormatterAndCalculator.formatMinorMoney(
                MinorMoney(acc.convertedBalanceMinor), data.primaryCurrencySpec, data.numberGrouping
            )
            drawTextRight(cursor.canvas, convStr, PAGE_WIDTH - MARGIN_RIGHT - 6f, textY, paints.boldTextPaint)

            cursor.canvas.drawLine(MARGIN_LEFT, cursor.y + rowHeight, PAGE_WIDTH - MARGIN_RIGHT, cursor.y + rowHeight, paints.strokePaint)
            cursor.y += rowHeight
            index++
        }

        // Balance sheet final totals
        val totalsHeight = 22f
        cursor.ensureSpace(totalsHeight + 16f) { c ->
            c.y = drawContinuationHeader(c.canvas, MARGIN_TOP, "Year-End Balance Sheet Snapshot (Cont.)", paints)
        }
        paints.fillPaint.color = COLOR_CARD_BG
        cursor.canvas.drawRect(MARGIN_LEFT, cursor.y, PAGE_WIDTH - MARGIN_RIGHT, cursor.y + totalsHeight, paints.fillPaint)

        val netWorthStr = MoneyFormatterAndCalculator.formatMinorMoney(
            MinorMoney(data.netWorthMinor), data.primaryCurrencySpec, data.numberGrouping
        )
        paints.boldTextPaint.color = COLOR_PRIMARY_EMERALD
        cursor.canvas.drawText("NET WORTH AT FISCAL YEAR END", MARGIN_LEFT + 10f, cursor.y + 15f, paints.boldTextPaint)
        drawTextRight(cursor.canvas, netWorthStr, PAGE_WIDTH - MARGIN_RIGHT - 6f, cursor.y + 15f, paints.boldTextPaint)
        paints.boldTextPaint.color = COLOR_TEXT_PRIMARY
        cursor.y += totalsHeight + 16f
    }

    // ---------------------------------------------------------------------------------------------
    // STORAGE & PERSISTENCE
    // ---------------------------------------------------------------------------------------------

    private fun savePdfToStorage(
        context: Context,
        pdfDocument: PdfDocument,
        fileName: String,
        pageCount: Int
    ): PdfExportResult {
        // 1. Internal storage file for 1-tap FileProvider sharing and printing
        val statementsDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "FolioStatements").apply {
            mkdirs()
        }
        val localFile = File(statementsDir, fileName)
        FileOutputStream(localFile).use { out ->
            pdfDocument.writeTo(out)
        }

        // 2. Public Downloads/Folio/Statements copy via MediaStore (API 29+) or direct write
        val publicDisplayPath = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/Folio/Statements"
                    )
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                )
                uri?.let { destUri ->
                    context.contentResolver.openOutputStream(destUri)?.use { destStream ->
                        localFile.inputStream().use { inStream ->
                            inStream.copyTo(destStream)
                        }
                    }
                }
                "Downloads/Folio/Statements/$fileName"
            } else {
                @Suppress("DEPRECATION")
                val pubDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Folio/Statements"
                ).apply { mkdirs() }
                val pubFile = File(pubDir, fileName)
                localFile.copyTo(pubFile, overwrite = true)
                pubFile.absolutePath
            }
        }.getOrDefault("Downloads/Folio/Statements/$fileName")

        return PdfExportResult(
            file = localFile,
            publicDisplayPath = publicDisplayPath,
            fileName = fileName,
            fileSizeBytes = localFile.length(),
            pageCount = pageCount
        )
    }

    // ---------------------------------------------------------------------------------------------
    // UTILITY METHODS
    // ---------------------------------------------------------------------------------------------

    private fun drawTextRight(canvas: Canvas, text: String, rightX: Float, y: Float, paint: Paint) {
        val width = paint.measureText(text)
        canvas.drawText(text, rightX - width, y, paint)
    }

    private fun truncateText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var low = 0
        var high = text.length
        var best = ""
        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = text.substring(0, mid) + "…"
            if (paint.measureText(candidate) <= maxWidth) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }
}
