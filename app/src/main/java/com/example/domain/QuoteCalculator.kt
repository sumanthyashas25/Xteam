package com.example.domain

import com.example.model.BillingFrequency
import com.example.model.CatalogueItem
import com.example.model.CatalogueKind
import com.example.model.PricingMode
import com.example.model.QuoteLineItem
import kotlin.math.roundToLong

data class QuoteCalculationResult(
    val computedLines: List<QuoteLineItem>,
    val subtotalPaise: Long,
    val surchargeBasePaise: Long,
    val surchargePaise: Long,
    val discountPaise: Long,
    val taxableAmountPaise: Long,
    val gstAmountPaise: Long,
    val totalPaise: Long,
    val advancePaise: Long,
    val balancePaise: Long,
    val validationErrors: List<String>,
    val overlapWarnings: List<String>
)

object QuoteCalculator {

    fun calculate(
        rawLines: List<QuoteLineItem>,
        urgentDeliveryEnabled: Boolean,
        urgentSurchargePercent: Int = 25,
        urgentExcludedLineIds: Set<String> = emptySet(),
        discountPaise: Long = 0L,
        gstEnabled: Boolean = false,
        gstRatePercent: Int = 18,
        advancePercent: Int = 50,
        allowUnpricedDraft: Boolean = false
    ): QuoteCalculationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val packageIncludedTitles = rawLines
            .filter { it.kindEnum() == CatalogueKind.PACKAGE }
            .flatMap { it.inclusions }
            .map { it.title.trim().lowercase() }
            .toSet()

        val packageIncludedIds = rawLines
            .filter { it.kindEnum() == CatalogueKind.PACKAGE }
            .flatMap { it.inclusions }
            .map { it.serviceId }
            .filter { it.isNotBlank() }
            .toSet()

        val computedLines = rawLines.mapIndexed { index, line ->
            val mode = line.pricingModeEnum()
            val effectiveUnit = line.overridePricePaise ?: line.unitPricePaise
            val qty = line.quantity.coerceAtLeast(1)
            val lineTotal = when {
                line.separatelyBilled -> 0L
                mode == PricingMode.PERCENTAGE_SURCHARGE -> 0L
                else -> effectiveUnit * qty
            }

            if ((mode == PricingMode.CUSTOM_QUOTE || mode == PricingMode.ACTUAL_COST) &&
                !line.separatelyBilled &&
                effectiveUnit <= 0L &&
                !allowUnpricedDraft
            ) {
                errors += "\"${line.title}\" requires an entered amount or \"Separately Billed\" marked before issuing."
            }

            if (line.kindEnum() == CatalogueKind.SERVICE &&
                (line.catalogueItemId in packageIncludedIds || line.title.trim().lowercase() in packageIncludedTitles)
            ) {
                warnings += "\"${line.title}\" is already included in a selected package — check to avoid double billing."
            }

            line.copy(
                lineTotalPaise = lineTotal,
                sortOrder = index
            )
        }

        val subtotal = computedLines.sumOf { it.lineTotalPaise }

        val surchargeBase = if (urgentDeliveryEnabled) {
            computedLines
                .filter {
                    it.urgentEligible &&
                        !it.separatelyBilled &&
                        it.pricingModeEnum() != PricingMode.PERCENTAGE_SURCHARGE &&
                        it.lineId !in urgentExcludedLineIds &&
                        it.lineTotalPaise > 0L
                }
                .sumOf { it.lineTotalPaise }
        } else {
            0L
        }

        val surcharge = if (urgentDeliveryEnabled && surchargeBase > 0L) {
            ((surchargeBase * urgentSurchargePercent) / 100.0).roundToLong()
        } else {
            0L
        }

        val clampedDiscount = discountPaise.coerceIn(0L, subtotal + surcharge)
        val taxable = (subtotal + surcharge - clampedDiscount).coerceAtLeast(0L)
        val gst = if (gstEnabled && gstRatePercent > 0) {
            ((taxable * gstRatePercent) / 100.0).roundToLong()
        } else {
            0L
        }
        val total = taxable + gst
        val advPct = advancePercent.coerceIn(0, 100)
        val advance = ((total * advPct) / 100.0).roundToLong()
        val balance = (total - advance).coerceAtLeast(0L)

        return QuoteCalculationResult(
            computedLines = computedLines,
            subtotalPaise = subtotal,
            surchargeBasePaise = surchargeBase,
            surchargePaise = surcharge,
            discountPaise = clampedDiscount,
            taxableAmountPaise = taxable,
            gstAmountPaise = gst,
            totalPaise = total,
            advancePaise = advance,
            balancePaise = balance,
            validationErrors = errors,
            overlapWarnings = warnings.distinct()
        )
    }

    fun fromCatalogueItem(
        item: CatalogueItem,
        lineId: String,
        billingFrequency: BillingFrequency? = null
    ): QuoteLineItem {
        val defaultFreq = billingFrequency ?: if (item.requiresBillingFrequency) {
            BillingFrequency.MONTHLY
        } else {
            BillingFrequency.ONE_TIME
        }
        val mode = item.pricingModeEnum()
        return QuoteLineItem(
            lineId = lineId,
            catalogueItemId = item.id,
            kind = item.kind,
            category = item.category,
            title = item.name,
            description = item.description,
            pricingMode = item.pricingMode,
            quantity = 1,
            unitPricePaise = item.pricePaise,
            overridePricePaise = null,
            lineTotalPaise = if (mode == PricingMode.ACTUAL_COST || mode == PricingMode.CUSTOM_QUOTE) 0L else item.pricePaise,
            billingFrequency = defaultFreq.name,
            separatelyBilled = mode == PricingMode.ACTUAL_COST,
            urgentEligible = mode != PricingMode.ACTUAL_COST && mode != PricingMode.PERCENTAGE_SURCHARGE,
            inclusions = item.inclusions
        )
    }
}
