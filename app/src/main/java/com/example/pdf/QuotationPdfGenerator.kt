package com.example.pdf

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.example.R
import com.example.domain.MoneyFormatter
import com.example.model.Quotation
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class GeneratedPdfResult(
    val file: File,
    val sha256: String
)

object QuotationPdfGenerator {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_X = 40f
    private const val MARGIN_TOP = 38f
    private const val MARGIN_BOTTOM = 44f
    private const val CONTENT_WIDTH = PAGE_WIDTH - 80f // 515f

    private const val COL_ITEM_X = MARGIN_X
    private const val COL_ITEM_W = 245f
    private const val COL_QTY_X = COL_ITEM_X + COL_ITEM_W + 8f
    private const val COL_QTY_W = 82f
    private const val COL_RATE_X = COL_QTY_X + COL_QTY_W + 6f
    private const val COL_RATE_W = 84f
    private const val COL_AMT_RIGHT = MARGIN_X + CONTENT_WIDTH

    private val indigoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(79, 70, 229) }
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(13, 17, 25) }
    private val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(241, 244, 252) }
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 250, 252) }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(209, 213, 226)
        strokeWidth = 0.8f
    }

    private fun makeTextPaint(
        size: Float,
        color: Int = Color.rgb(15, 23, 42),
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT
    ) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = align
    }

    fun buildPdfTargetFile(context: Context, quotation: Quotation): File {
        val dir = File(context.filesDir, "quotations").apply { mkdirs() }
        val safeNumber = quotation.quoteNumber.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "${safeNumber}_v${quotation.revision}.pdf")
    }

    fun generateAndSave(context: Context, quotation: Quotation): GeneratedPdfResult {
        val outFile = buildPdfTargetFile(context, quotation)

        val doc = PdfDocument()
        var pageNumber = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var canvas = page.canvas
        var y = MARGIN_TOP

        val titlePaint = makeTextPaint(18f, Color.WHITE, bold = true)
        val subtitlePaint = makeTextPaint(9.5f, Color.rgb(224, 231, 255))
        val h2Paint = makeTextPaint(11.5f, Color.rgb(15, 23, 42), bold = true)
        val bodyPaint = makeTextPaint(9.2f, Color.rgb(30, 41, 59))
        val mutedPaint = makeTextPaint(8.5f, Color.rgb(100, 116, 139))
        val boldBodyPaint = makeTextPaint(9.2f, Color.rgb(15, 23, 42), bold = true)
        val rightPaint = makeTextPaint(9.2f, Color.rgb(15, 23, 42), bold = false, align = Paint.Align.RIGHT)
        val rightBoldPaint = makeTextPaint(9.6f, Color.rgb(15, 23, 42), bold = true, align = Paint.Align.RIGHT)

        fun startNewPage(drawTableHeader: Boolean) {
            drawPageFooter(canvas, quotation, pageNumber, mutedPaint)
            doc.finishPage(page)
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas = page.canvas
            y = MARGIN_TOP
            // Mini continuation header
            canvas.drawText("${quotation.companySnapshot.name} • Quotation ${quotation.quoteNumber} (Rev v${quotation.revision})", MARGIN_X, y + 10f, mutedPaint)
            y += 18f
            canvas.drawLine(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y, dividerPaint)
            y += 10f
            if (drawTableHeader) {
                y = drawItemsTableHeader(canvas, y, boldBodyPaint, rightBoldPaint)
            }
        }

        // === TOP BRAND BANNER ===
        val bannerRect = RectF(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + 74f)
        canvas.drawRoundRect(bannerRect, 10f, 10f, darkPaint)

        val logoBitmap = runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.logo_xmax)
        }.getOrNull()

        if (logoBitmap != null && logoBitmap.width > 0 && logoBitmap.height > 0) {
            val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            }
            val targetH = 22f
            val targetW = targetH * (logoBitmap.width.toFloat() / logoBitmap.height.toFloat())
            val logoRect = RectF(MARGIN_X + 16f, y + 16f, MARGIN_X + 16f + targetW, y + 16f + targetH)
            canvas.drawBitmap(logoBitmap, null, logoRect, logoPaint)
            if (!quotation.companySnapshot.name.equals("X.MAX", ignoreCase = true) &&
                quotation.companySnapshot.name.isNotBlank()
            ) {
                canvas.drawText(quotation.companySnapshot.name, logoRect.right + 10f, y + 34f, titlePaint)
            }
            canvas.drawText(
                quotation.companySnapshot.tagline.ifBlank { "Creative & Digital Marketing Agency" },
                MARGIN_X + 16f,
                y + 53f,
                subtitlePaint
            )
        } else {
            val badgeRect = RectF(MARGIN_X + 14f, y + 14f, MARGIN_X + 58f, y + 58f)
            canvas.drawRoundRect(badgeRect, 8f, 8f, indigoPaint)
            val markPaint = makeTextPaint(14f, Color.WHITE, bold = true, align = Paint.Align.CENTER)
            canvas.drawText("X.M", badgeRect.centerX(), badgeRect.centerY() + 5f, markPaint)
            canvas.drawText(quotation.companySnapshot.name, MARGIN_X + 70f, y + 31f, titlePaint)
            canvas.drawText(
                quotation.companySnapshot.tagline.ifBlank { "Creative & Digital Marketing Agency" },
                MARGIN_X + 70f,
                y + 47f,
                subtitlePaint
            )
        }
        val qLabelRight = makeTextPaint(13f, Color.WHITE, bold = true, align = Paint.Align.RIGHT)
        val qSubRight = makeTextPaint(9.5f, Color.rgb(199, 210, 254), bold = false, align = Paint.Align.RIGHT)
        canvas.drawText("QUOTATION", MARGIN_X + CONTENT_WIDTH - 16f, y + 29f, qLabelRight)
        canvas.drawText("${quotation.quoteNumber}  |  Rev v${quotation.revision}", MARGIN_X + CONTENT_WIDTH - 16f, y + 46f, qSubRight)
        val statusText = if (quotation.status == "CLIENT_APPROVED") "CLIENT APPROVED" else quotation.status
        canvas.drawText(statusText, MARGIN_X + CONTENT_WIDTH - 16f, y + 60f, qSubRight)

        y += 86f

        // === FROM (COMPANY SNAPSHOT) & PREPARED FOR (CLIENT SNAPSHOT) ===
        val colHalf = (CONTENT_WIDTH - 14f) / 2f
        val leftX = MARGIN_X
        val rightX = MARGIN_X + colHalf + 14f

        val fromLines = buildList {
            add("From: ${quotation.companySnapshot.name}")
            if (quotation.companySnapshot.address.isNotBlank()) add(quotation.companySnapshot.address)
            val contactLine = listOfNotNull(
                quotation.companySnapshot.phone.takeIf { it.isNotBlank() },
                quotation.companySnapshot.email.takeIf { it.isNotBlank() }
            ).joinToString(" • ")
            if (contactLine.isNotBlank()) add(contactLine)
            if (quotation.companySnapshot.gstEnabled && quotation.companySnapshot.gstin.isNotBlank()) {
                add("GSTIN: ${quotation.companySnapshot.gstin}")
            }
        }
        val toLines = buildList {
            add("Prepared For: ${quotation.clientSnapshot.companyName}")
            if (quotation.clientSnapshot.contactPerson.isNotBlank()) add("Attn: ${quotation.clientSnapshot.contactPerson}")
            val clientContact = listOfNotNull(
                quotation.clientSnapshot.phone.takeIf { it.isNotBlank() },
                quotation.clientSnapshot.email.takeIf { it.isNotBlank() }
            ).joinToString(" • ")
            if (clientContact.isNotBlank()) add(clientContact)
            if (quotation.clientSnapshot.address.isNotBlank()) add(quotation.clientSnapshot.address)
        }

        val leftLayout = buildStaticLayout(fromLines.joinToString("\n"), bodyPaint, colHalf.toInt() - 16)
        val rightLayout = buildStaticLayout(toLines.joinToString("\n"), bodyPaint, colHalf.toInt() - 16)
        val metaHeight = maxOf(leftLayout.height, rightLayout.height) + 18f

        canvas.drawRoundRect(RectF(leftX, y, leftX + colHalf, y + metaHeight), 8f, 8f, cardBgPaint)
        canvas.drawRoundRect(RectF(rightX, y, rightX + colHalf, y + metaHeight), 8f, 8f, cardBgPaint)
        drawStaticLayout(canvas, leftLayout, leftX + 10f, y + 9f)
        drawStaticLayout(canvas, rightLayout, rightX + 10f, y + 9f)
        y += metaHeight + 10f

        // === PROJECT & DATES STRIP ===
        val issuedStr = MoneyFormatter.formatDate(quotation.issuedDateMillis.takeIf { it > 0L } ?: System.currentTimeMillis())
        val validStr = MoneyFormatter.formatDate(quotation.expiryDateMillis)
        canvas.drawText("Project: ${quotation.projectTitle}", MARGIN_X, y + 11f, h2Paint)
        canvas.drawText("Issued: $issuedStr  •  Valid Until: $validStr", MARGIN_X + CONTENT_WIDTH, y + 11f, rightPaint)
        y += 18f
        if (quotation.scopeSummary.isNotBlank()) {
            val scopeLayout = buildStaticLayout(quotation.scopeSummary, mutedPaint, CONTENT_WIDTH.toInt())
            drawStaticLayout(canvas, scopeLayout, MARGIN_X, y)
            y += scopeLayout.height + 8f
        }
        canvas.drawText("Prepared by: ${quotation.preparedByName}", MARGIN_X, y + 9f, mutedPaint)
        y += 18f

        // === ITEMIZED LINES TABLE ===
        y = drawItemsTableHeader(canvas, y, boldBodyPaint, rightBoldPaint)

        for (line in quotation.lines) {
            val detailsText = buildString {
                if (line.description.isNotBlank()) append(line.description)
                if (line.inclusions.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("Includes: ")
                    append(line.inclusions.joinToString(" • ") {
                        if (it.quantity > 1) "${it.quantity}× ${it.title}" else it.title
                    })
                }
            }
            val titleLayout = buildStaticLayout(line.title, boldBodyPaint, COL_ITEM_W.toInt())
            val detailLayout = if (detailsText.isNotBlank()) buildStaticLayout(detailsText, mutedPaint, COL_ITEM_W.toInt()) else null
            val rowHeight = (titleLayout.height + (detailLayout?.height ?: 0) + 14f).coerceAtLeast(26f)

            if (y + rowHeight > PAGE_HEIGHT - MARGIN_BOTTOM - 20f) {
                startNewPage(drawTableHeader = true)
            }

            drawStaticLayout(canvas, titleLayout, COL_ITEM_X, y + 4f)
            if (detailLayout != null) {
                drawStaticLayout(canvas, detailLayout, COL_ITEM_X, y + 6f + titleLayout.height)
            }

            val freqLabel = if (line.billingFrequency == "ONE_TIME") "" else " ${line.billingFrequency.lowercase()}"
            val qtyStr = "${line.quantity}$freqLabel"
            val effectiveUnit = line.overridePricePaise ?: line.unitPricePaise
            val rateStr = if (line.separatelyBilled) "Actuals" else MoneyFormatter.formatInr(effectiveUnit)
            val amtStr = if (line.separatelyBilled) "Separately Billed" else MoneyFormatter.formatInr(line.lineTotalPaise)

            canvas.drawText(qtyStr, COL_QTY_X, y + 14f, bodyPaint)
            canvas.drawText(rateStr, COL_RATE_X + COL_RATE_W, y + 14f, rightPaint)
            canvas.drawText(amtStr, COL_AMT_RIGHT, y + 14f, rightBoldPaint)

            y += rowHeight
            canvas.drawLine(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y, dividerPaint)
        }

        // === TOTALS BLOCK (kept together on page) ===
        val totalsBlockHeight = 148f
        if (y + totalsBlockHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
            startNewPage(drawTableHeader = false)
        }
        y += 12f
        val totalsW = 265f
        val totalsX = MARGIN_X + CONTENT_WIDTH - totalsW
        val totalsRows = mutableListOf<Pair<String, String>>()
        totalsRows += "Subtotal" to MoneyFormatter.formatInr(quotation.subtotalPaise)
        if (quotation.surchargePaise > 0L) {
            totalsRows += "Urgent Delivery (25% on ${MoneyFormatter.formatInr(quotation.surchargeBasePaise)})" to MoneyFormatter.formatInr(quotation.surchargePaise)
        }
        if (quotation.discountPaise > 0L) {
            totalsRows += "Commercial Discount" to "-${MoneyFormatter.formatInr(quotation.discountPaise)}"
        }
        if (quotation.gstEnabled && quotation.gstAmountPaise > 0L) {
            totalsRows += "GST (${quotation.gstRatePercent}%)" to MoneyFormatter.formatInr(quotation.gstAmountPaise)
        }

        val boxHeight = totalsRows.size * 18f + 62f
        canvas.drawRoundRect(RectF(totalsX, y, MARGIN_X + CONTENT_WIDTH, y + boxHeight), 8f, 8f, cardBgPaint)
        var ty = y + 18f
        for ((k, v) in totalsRows) {
            canvas.drawText(k, totalsX + 12f, ty, mutedPaint)
            canvas.drawText(v, MARGIN_X + CONTENT_WIDTH - 12f, ty, rightPaint)
            ty += 18f
        }
        canvas.drawLine(totalsX + 10f, ty - 8f, MARGIN_X + CONTENT_WIDTH - 10f, ty - 8f, dividerPaint)
        ty += 8f
        val grandLabelPaint = makeTextPaint(11f, Color.rgb(79, 70, 229), bold = true)
        val grandValPaint = makeTextPaint(12f, Color.rgb(13, 17, 25), bold = true, align = Paint.Align.RIGHT)
        canvas.drawText("TOTAL", totalsX + 12f, ty, grandLabelPaint)
        canvas.drawText(MoneyFormatter.formatInr(quotation.totalPaise), MARGIN_X + CONTENT_WIDTH - 12f, ty, grandValPaint)
        ty += 18f
        canvas.drawText("Advance (${quotation.advancePercent}%)", totalsX + 12f, ty, bodyPaint)
        canvas.drawText(MoneyFormatter.formatInr(quotation.advancePaise), MARGIN_X + CONTENT_WIDTH - 12f, ty, rightBoldPaint)
        ty += 15f
        canvas.drawText("Balance on Delivery", totalsX + 12f, ty, mutedPaint)
        canvas.drawText(MoneyFormatter.formatInr(quotation.balancePaise), MARGIN_X + CONTENT_WIDTH - 12f, ty, rightPaint)

        y += boxHeight + 16f

        // === COMMERCIAL TERMS & APPROVAL ===
        val termsText = buildString {
            appendLine("Commercial Terms & Approval:")
            if (quotation.paymentTerms.isNotBlank()) appendLine("• Payment Terms: ${quotation.paymentTerms}")
            if (quotation.revisionPolicy.isNotBlank()) appendLine("• Revisions: ${quotation.revisionPolicy}")
            if (quotation.timelineNote.isNotBlank()) appendLine("• Timeline: ${quotation.timelineNote}")
            if (quotation.separatelyBilledNote.isNotBlank()) appendLine("• Third-Party / Ad Spend: ${quotation.separatelyBilledNote}")
            if (quotation.approvalInstructions.isNotBlank()) appendLine("• Approval: ${quotation.approvalInstructions}")
        }.trim()
        val termsLayout = buildStaticLayout(termsText, bodyPaint, CONTENT_WIDTH.toInt())
        if (y + termsLayout.height + 24f > PAGE_HEIGHT - MARGIN_BOTTOM) {
            startNewPage(drawTableHeader = false)
        }
        drawStaticLayout(canvas, termsLayout, MARGIN_X, y)

        drawPageFooter(canvas, quotation, pageNumber, mutedPaint)
        doc.finishPage(page)

        FileOutputStream(outFile).use { fos ->
            doc.writeTo(fos)
        }
        doc.close()

        return GeneratedPdfResult(
            file = outFile,
            sha256 = sha256Hex(outFile)
        )
    }

    fun buildShareIntent(context: Context, file: File, quotation: Quotation): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "${quotation.companySnapshot.name} Quotation ${quotation.quoteNumber} (v${quotation.revision}) - ${quotation.projectTitle}"
            )
            putExtra(
                Intent.EXTRA_TEXT,
                "Please find attached quotation ${quotation.quoteNumber} (Revision v${quotation.revision}) for ${quotation.projectTitle}. Total: ${MoneyFormatter.formatInr(quotation.totalPaise)}."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun launchShareSheet(context: Context, file: File, quotation: Quotation) {
        val shareIntent = buildShareIntent(context, file, quotation)
        val chooser = Intent.createChooser(shareIntent, "Share Quotation PDF").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun drawItemsTableHeader(
        canvas: Canvas,
        y: Float,
        leftBold: TextPaint,
        rightBold: TextPaint
    ): Float {
        canvas.drawRoundRect(RectF(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y + 24f), 6f, 6f, headerBgPaint)
        canvas.drawText("Service / Deliverable", COL_ITEM_X + 4f, y + 16f, leftBold)
        canvas.drawText("Qty / Cycle", COL_QTY_X, y + 16f, leftBold)
        canvas.drawText("Rate", COL_RATE_X + COL_RATE_W, y + 16f, rightBold)
        canvas.drawText("Amount", COL_AMT_RIGHT - 4f, y + 16f, rightBold)
        return y + 28f
    }

    private fun drawPageFooter(canvas: Canvas, quotation: Quotation, pageNumber: Int, mutedPaint: TextPaint) {
        val footerY = PAGE_HEIGHT - 22f
        canvas.drawLine(MARGIN_X, footerY - 10f, MARGIN_X + CONTENT_WIDTH, footerY - 10f, dividerPaint)
        canvas.drawText("${quotation.companySnapshot.name} • ${quotation.quoteNumber} v${quotation.revision}", MARGIN_X, footerY, mutedPaint)
        val rightMuted = TextPaint(mutedPaint).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText("Page $pageNumber", MARGIN_X + CONTENT_WIDTH, footerY, rightMuted)
    }

    private fun buildStaticLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(40))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1.05f)
            .setIncludePad(false)
            .build()
    }

    private fun drawStaticLayout(canvas: Canvas, layout: StaticLayout, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
