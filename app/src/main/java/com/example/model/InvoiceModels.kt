package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties

enum class InvoiceStatus(val label: String) {
    DRAFT("Draft"),
    ISSUED("Issued"),
    PARTIALLY_PAID("Partially Paid"),
    PAID("Paid"),
    OVERDUE("Overdue"),
    CANCELLED("Cancelled")
}

@Keep
@IgnoreExtraProperties
data class InvoiceLineItem(
    val lineId: String = "",
    val title: String = "",
    val description: String = "",
    val quantity: Int = 1,
    val unitPricePaise: Long = 0L,
    val lineTotalPaise: Long = 0L
)

/**
 * Additive Invoice entity stored independently in `companies/{companyId}/invoices/{invoiceId}`.
 * Coexists cleanly with existing [Quotation] and [PaymentRecord] entities without modifying them.
 */
@Keep
@IgnoreExtraProperties
data class InvoiceRecord(
    @DocumentId val id: String = "",
    val invoiceNumber: String = "",
    val projectId: String = "",
    val projectTitle: String = "",
    val quotationId: String = "",
    val quotationNumber: String = "",
    val clientId: String = "",
    val clientSnapshot: ClientSnapshot = ClientSnapshot(),
    val companySnapshot: CompanySnapshot = CompanySnapshot(),
    val lines: List<InvoiceLineItem> = emptyList(),
    val subtotalPaise: Long = 0L,
    val discountPaise: Long = 0L,
    val gstEnabled: Boolean = false,
    val gstRatePercent: Int = 18,
    val gstAmountPaise: Long = 0L,
    val totalPaise: Long = 0L,
    val advanceCreditedPaise: Long = 0L,
    val balanceDuePaise: Long = 0L,
    val issuedDateMillis: Long = 0L,
    val dueDateMillis: Long = 0L,
    val notes: String = "",
    val status: String = InvoiceStatus.DRAFT.name,
    val pdfLocalPath: String = "",
    val pdfSha256: String = "",
    val createdByUid: String = "",
    val createdByName: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun statusEnum(): InvoiceStatus = safeEnum(status, InvoiceStatus.DRAFT)
}
