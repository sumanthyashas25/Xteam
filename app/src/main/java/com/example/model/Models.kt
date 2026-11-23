package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties

inline fun <reified E : Enum<E>> safeEnum(name: String?, fallback: E): E {
    if (name.isNullOrBlank()) return fallback
    return enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: fallback
}

enum class AccessLevel(val label: String) {
    SUPER_ADMIN("Super Admin"),
    ADMIN("Admin / Manager"),
    STAFF("Staff")
}

enum class MembershipStatus(val label: String) {
    PENDING("Pending Approval"),
    APPROVED("Approved"),
    DECLINED("Declined"),
    SUSPENDED("Suspended")
}

enum class ScopeType(val label: String) {
    COMPANY("Company-wide"),
    TEAM("Assigned Departments")
}

enum class AppPermission(
    val label: String,
    val description: String,
    val staffEligible: Boolean,
    val reserved: Boolean = false
) {
    MANAGE_CLIENTS("Manage Clients", "Create and edit client contact profiles & notes", true),
    CREATE_REQUESTS("Create Requests", "Capture new client project requests", true),
    CREATE_QUOTATIONS("Create Quotations", "Build draft quotations and calculate pricing", true),
    ISSUE_QUOTATIONS("Issue & Share Quotations", "Issue quote numbers, generate PDFs, revise, mark sent/approved", true),
    VIEW_ALL_CLIENTS_AND_QUOTES("View Clients & Quotes", "Read company client directory and quotation history", true),
    EDIT_CATALOGUE_PRICES("Edit Catalogue Prices", "Update or archive services, packages, and add-ons", true),
    OVERRIDE_QUOTE_PRICE("Override Quote Price", "Set custom line unit prices inside quotations", true),
    APPLY_DISCOUNTS("Apply Discounts", "Apply commercial discounts on quotations", true),
    VIEW_PAYMENTS("View Payments", "Inspect project agreed values, advances, and payment history", true),
    RECORD_PAYMENTS("Record Payments", "Log advance and part payments against accepted projects", true),
    ASSIGN_WORK("Assign Deliverables", "Assign or reassign project deliverables and due dates", true),
    APPROVE_DELIVERABLES("Approve Deliverables", "Approve work, request changes, and track client revisions", true),
    ACCEPT_PROJECT("Accept Project", "Reserved: Convert approved request/quote into active project & deliverables", false, reserved = true),
    DECLINE_PROJECT("Decline Project", "Reserved: Decline a client request internally", false, reserved = true),
    DELETE_PROJECT("Delete Project", "Reserved: Soft-delete / archive an accepted project", false, reserved = true)
}

enum class CatalogueKind(val label: String) {
    SERVICE("Service"),
    PACKAGE("One-Time Package"),
    ADDON("Add-On")
}

enum class PricingMode(val label: String) {
    FIXED("Fixed Price"),
    STARTING_FROM("Starting From"),
    PER_UNIT("Per Unit"),
    PERCENTAGE_SURCHARGE("% Surcharge"),
    ACTUAL_COST("Actual Cost"),
    CUSTOM_QUOTE("Custom Quote")
}

enum class BillingFrequency(val label: String, val shortTag: String) {
    ONE_TIME("One-time", "One-time"),
    MONTHLY("Monthly", "/month"),
    QUARTERLY("Quarterly", "/quarter"),
    ANNUALLY("Annually", "/year"),
    PER_CAMPAIGN("Per Campaign", "/campaign")
}

enum class DecisionStatus(val label: String) {
    PENDING("Awaiting Decision"),
    ACCEPTED("Accepted"),
    DECLINED("Declined")
}

enum class QuotationStatus(val label: String) {
    DRAFT("Draft"),
    ISSUED("Issued"),
    SENT("Sent to Client"),
    CLIENT_APPROVED("Client Approved"),
    SUPERSEDED("Superseded")
}

enum class ProjectStatus(val label: String) {
    AWAITING_ADVANCE("Awaiting Advance"),
    IN_PROGRESS("In Progress"),
    COMPLETED("Completed"),
    ON_HOLD("On Hold")
}

enum class AssignmentStatus(val label: String) {
    ASSIGNED("Assigned"),
    DEPT_QUEUE("Department Queue"),
    NEEDS_ASSIGNMENT("Needs Assignment")
}

enum class DeliverableStatus(val label: String) {
    NOT_STARTED("Not Started"),
    IN_PROGRESS("In Progress"),
    BLOCKED("Blocked"),
    READY_FOR_REVIEW("Ready for Review"),
    CHANGES_REQUESTED("Changes Requested"),
    APPROVED("Approved")
}

enum class UpdateActionType(val label: String) {
    PROGRESS("Progress Update"),
    BLOCKED("Marked Blocked"),
    SUBMIT_REVIEW("Submitted for Review"),
    APPROVED("Manager Approved"),
    CHANGES_REQUESTED("Changes Requested"),
    CLIENT_REVISION("Client Revision Logged")
}

enum class PaymentMethod(val label: String) {
    UPI("UPI"),
    BANK_TRANSFER("Bank Transfer / NEFT / IMPS"),
    CARD("Card"),
    CASH("Cash"),
    CHEQUE("Cheque")
}

enum class ActivityCategory(val label: String) {
    MEMBERSHIP("Membership"),
    PERMISSIONS("Permissions"),
    QUOTATION("Quotation"),
    PROJECT("Project"),
    ASSIGNMENT("Assignment"),
    DELIVERABLE("Deliverable"),
    PAYMENT("Payment"),
    COMPANY("Company")
}

@Keep
@IgnoreExtraProperties
data class PermissionGrant(
    val allowed: Boolean = false,
    val canDelegate: Boolean = false
)

@Keep
@IgnoreExtraProperties
data class DelegatedGrant(
    val allowed: Boolean = false,
    val grantedByUid: String = "",
    val grantedAtMillis: Long = 0L
)

@Keep
@IgnoreExtraProperties
data class CompanyProfile(
    @DocumentId val id: String = "xmax",
    val name: String = "X.MAX",
    val tagline: String = "Creative & Digital Marketing Agency",
    val address: String = "Indiranagar, Bengaluru, Karnataka 560038",
    val phone: String = "+91 98450 00000",
    val email: String = "hello@xmax.agency",
    val gstin: String = "",
    val gstEnabled: Boolean = false,
    val gstRatePercent: Int = 18,
    val defaultAdvancePercent: Int = 50,
    val defaultValidityDays: Int = 15,
    val defaultRevisionRounds: Int = 2,
    val defaultPaymentTerms: String = "50% advance to commence project and 50% prior to final delivery.",
    val defaultApprovalInstructions: String = "Reply in writing referencing the Quotation Number and Revision to confirm approval.",
    val nextQuoteSeq: Long = 1L,
    val seededCatalogue: Boolean = false,
    val createdByUid: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class Department(
    @DocumentId val id: String = "",
    val name: String = "",
    val description: String = "",
    val active: Boolean = true,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class JobRole(
    @DocumentId val id: String = "",
    val name: String = "",
    val departmentId: String = "",
    val active: Boolean = true,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class Member(
    @DocumentId val id: String = "",
    val uid: String = "",
    val companyNameInput: String = "",
    val fullName: String = "",
    val photoBase64: String = "",
    val accessLevel: String = AccessLevel.STAFF.name,
    val status: String = MembershipStatus.PENDING.name,
    val active: Boolean = true,
    val departmentId: String = "",
    val departmentName: String = "",
    val jobRoleId: String = "",
    val jobRoleName: String = "",
    val requestedDepartment: String = "",
    val requestedJobRole: String = "",
    val scopeType: String = ScopeType.TEAM.name,
    val scopeDepartmentIds: List<String> = emptyList(),
    val permissions: Map<String, PermissionGrant> = emptyMap(),
    val delegatedGrants: Map<String, DelegatedGrant> = emptyMap(),
    val invitedByUid: String = "",
    val approvedByUid: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun accessLevelEnum(): AccessLevel = safeEnum(accessLevel, AccessLevel.STAFF)
    fun statusEnum(): MembershipStatus = safeEnum(status, MembershipStatus.PENDING)
    fun scopeTypeEnum(): ScopeType = safeEnum(scopeType, ScopeType.TEAM)
}

@Keep
@IgnoreExtraProperties
data class MemberPrivateInfo(
    @DocumentId val id: String = "",
    val uid: String = "",
    val age: Int = 18,
    val email: String = "",
    val phone: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class Invitation(
    @DocumentId val id: String = "",
    val email: String = "",
    val accessLevel: String = AccessLevel.STAFF.name,
    val departmentId: String = "",
    val departmentName: String = "",
    val jobRoleId: String = "",
    val jobRoleName: String = "",
    val scopeType: String = ScopeType.TEAM.name,
    val scopeDepartmentIds: List<String> = emptyList(),
    val permissions: Map<String, PermissionGrant> = emptyMap(),
    val invitedByUid: String = "",
    val status: String = "PENDING",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PackageInclusion(
    val serviceId: String = "",
    val title: String = "",
    val category: String = "",
    val quantity: Int = 1,
    val description: String = ""
)

@Keep
@IgnoreExtraProperties
data class CatalogueItem(
    @DocumentId val id: String = "",
    val kind: String = CatalogueKind.SERVICE.name,
    val category: String = "",
    val name: String = "",
    val description: String = "",
    val pricePaise: Long = 0L,
    val pricingMode: String = PricingMode.FIXED.name,
    val surchargePercent: Int = 0,
    val requiresBillingFrequency: Boolean = false,
    val unitLabel: String = "unit",
    val inclusions: List<PackageInclusion> = emptyList(),
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun kindEnum(): CatalogueKind = safeEnum(kind, CatalogueKind.SERVICE)
    fun pricingModeEnum(): PricingMode = safeEnum(pricingMode, PricingMode.FIXED)
}

@Keep
@IgnoreExtraProperties
data class RoutingRule(
    @DocumentId val id: String = "",
    val name: String = "",
    val keywords: List<String> = emptyList(),
    val serviceCategories: List<String> = emptyList(),
    val departmentId: String = "",
    val departmentName: String = "",
    val jobRoleId: String = "",
    val jobRoleName: String = "",
    val priority: Int = 10,
    val active: Boolean = true,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class Client(
    @DocumentId val id: String = "",
    val companyName: String = "",
    val contactPerson: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val meetingNotes: String = "",
    val followUpDateMillis: Long = 0L,
    val createdByUid: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ProjectRequest(
    @DocumentId val id: String = "",
    val clientId: String = "",
    val clientCompanyName: String = "",
    val projectTitle: String = "",
    val brief: String = "",
    val desiredServices: List<String> = emptyList(),
    val targetDateMillis: Long = 0L,
    val responsibleManagerUid: String = "",
    val responsibleManagerName: String = "",
    val decisionStatus: String = DecisionStatus.PENDING.name,
    val decisionByUid: String = "",
    val decisionByName: String = "",
    val decisionAtMillis: Long = 0L,
    val decisionNote: String = "",
    val acceptedProjectId: String = "",
    val latestQuoteId: String = "",
    val latestQuoteNumber: String = "",
    val latestQuoteRevision: Int = 0,
    val clientApprovedQuoteId: String = "",
    val createdByUid: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun decisionStatusEnum(): DecisionStatus = safeEnum(decisionStatus, DecisionStatus.PENDING)
}

@Keep
@IgnoreExtraProperties
data class QuoteLineItem(
    val lineId: String = "",
    val catalogueItemId: String = "",
    val kind: String = CatalogueKind.SERVICE.name,
    val category: String = "",
    val title: String = "",
    val description: String = "",
    val pricingMode: String = PricingMode.FIXED.name,
    val quantity: Int = 1,
    val unitPricePaise: Long = 0L,
    val overridePricePaise: Long? = null,
    val lineTotalPaise: Long = 0L,
    val billingFrequency: String = BillingFrequency.ONE_TIME.name,
    val separatelyBilled: Boolean = false,
    val urgentEligible: Boolean = true,
    val inclusions: List<PackageInclusion> = emptyList(),
    val sortOrder: Int = 0
) {
    fun kindEnum(): CatalogueKind = safeEnum(kind, CatalogueKind.SERVICE)
    fun pricingModeEnum(): PricingMode = safeEnum(pricingMode, PricingMode.FIXED)
    fun billingFrequencyEnum(): BillingFrequency = safeEnum(billingFrequency, BillingFrequency.ONE_TIME)
}

@Keep
@IgnoreExtraProperties
data class CompanySnapshot(
    val name: String = "X.MAX",
    val tagline: String = "",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    val gstin: String = "",
    val gstEnabled: Boolean = false,
    val gstRatePercent: Int = 18
)

@Keep
@IgnoreExtraProperties
data class ClientSnapshot(
    val clientId: String = "",
    val companyName: String = "",
    val contactPerson: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = ""
)

@Keep
@IgnoreExtraProperties
data class Quotation(
    @DocumentId val id: String = "",
    val quoteNumber: String = "",
    val revision: Int = 1,
    val parentQuoteId: String = "",
    val requestId: String = "",
    val linkedProjectId: String = "",
    val clientId: String = "",
    val projectTitle: String = "",
    val scopeSummary: String = "",
    val status: String = QuotationStatus.DRAFT.name,
    val companySnapshot: CompanySnapshot = CompanySnapshot(),
    val clientSnapshot: ClientSnapshot = ClientSnapshot(),
    val lines: List<QuoteLineItem> = emptyList(),
    val subtotalPaise: Long = 0L,
    val discountPaise: Long = 0L,
    val surchargePaise: Long = 0L,
    val surchargeBasePaise: Long = 0L,
    val gstEnabled: Boolean = false,
    val gstRatePercent: Int = 18,
    val gstAmountPaise: Long = 0L,
    val totalPaise: Long = 0L,
    val advancePercent: Int = 50,
    val advancePaise: Long = 0L,
    val balancePaise: Long = 0L,
    val paymentTerms: String = "",
    val revisionPolicy: String = "",
    val timelineNote: String = "",
    val separatelyBilledNote: String = "",
    val approvalInstructions: String = "",
    val issuedDateMillis: Long = 0L,
    val expiryDateMillis: Long = 0L,
    val preparedByUid: String = "",
    val preparedByName: String = "",
    val pdfLocalPath: String = "",
    val pdfSha256: String = "",
    val sentAtMillis: Long = 0L,
    val clientApprovedAtMillis: Long = 0L,
    val clientApprovalNote: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun statusEnum(): QuotationStatus = safeEnum(status, QuotationStatus.DRAFT)
}

@Keep
@IgnoreExtraProperties
data class Project(
    @DocumentId val id: String = "",
    val requestId: String = "",
    val clientId: String = "",
    val clientCompanyName: String = "",
    val title: String = "",
    val brief: String = "",
    val responsibleManagerUid: String = "",
    val responsibleManagerName: String = "",
    val approvedQuoteId: String = "",
    val approvedQuoteNumber: String = "",
    val approvedQuoteRevision: Int = 1,
    val agreedValuePaise: Long = 0L,
    val advanceDuePaise: Long = 0L,
    val paidPaise: Long = 0L,
    val paymentTerms: String = "",
    val revisionLimitPerDeliverable: Int = 2,
    val status: String = ProjectStatus.AWAITING_ADVANCE.name,
    val deleted: Boolean = false,
    val deletedByUid: String = "",
    val deletedAtMillis: Long = 0L,
    val totalDeliverables: Int = 0,
    val approvedDeliverables: Int = 0,
    val inReviewDeliverables: Int = 0,
    val blockedDeliverables: Int = 0,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun statusEnum(): ProjectStatus = safeEnum(status, ProjectStatus.AWAITING_ADVANCE)
    val outstandingPaise: Long get() = (agreedValuePaise - paidPaise).coerceAtLeast(0L)
}

@Keep
@IgnoreExtraProperties
data class ChecklistItem(
    val id: String = "",
    val title: String = "",
    val done: Boolean = false
)

@Keep
@IgnoreExtraProperties
data class Deliverable(
    @DocumentId val id: String = "",
    val projectId: String = "",
    val projectTitle: String = "",
    val clientCompanyName: String = "",
    val projectBrief: String = "",
    val departmentId: String = "",
    val departmentName: String = "",
    val jobRoleId: String = "",
    val jobRoleName: String = "",
    val title: String = "",
    val description: String = "",
    val quantity: Int = 1,
    val responsibleManagerUid: String = "",
    val assignedToUid: String = "",
    val assignedToName: String = "",
    val assignmentStatus: String = AssignmentStatus.NEEDS_ASSIGNMENT.name,
    val dueDateMillis: Long = 0L,
    val status: String = DeliverableStatus.NOT_STARTED.name,
    val checklist: List<ChecklistItem> = emptyList(),
    val completedChecklistCount: Int = 0,
    val totalChecklistCount: Int = 0,
    val previewUrl: String = "",
    val latestNote: String = "",
    val blockedReason: String = "",
    val helpNeeded: String = "",
    val latestFeedback: String = "",
    val clientRevisionsUsed: Int = 0,
    val clientRevisionLimit: Int = 2,
    val lastUpdatedByUid: String = "",
    val lastUpdatedByName: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun assignmentStatusEnum(): AssignmentStatus = safeEnum(assignmentStatus, AssignmentStatus.NEEDS_ASSIGNMENT)
    fun statusEnum(): DeliverableStatus = safeEnum(status, DeliverableStatus.NOT_STARTED)
}

@Keep
@IgnoreExtraProperties
data class DeliverableUpdate(
    @DocumentId val id: String = "",
    val deliverableId: String = "",
    val projectId: String = "",
    val assignedToUid: String = "",
    val departmentId: String = "",
    val authorUid: String = "",
    val authorName: String = "",
    val actionType: String = UpdateActionType.PROGRESS.name,
    val statusAfter: String = DeliverableStatus.IN_PROGRESS.name,
    val note: String = "",
    val previewUrl: String = "",
    val blockedReason: String = "",
    val helpNeeded: String = "",
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val createdAt: Timestamp? = null
) {
    fun actionTypeEnum(): UpdateActionType = safeEnum(actionType, UpdateActionType.PROGRESS)
    fun statusAfterEnum(): DeliverableStatus = safeEnum(statusAfter, DeliverableStatus.IN_PROGRESS)
}

@Keep
@IgnoreExtraProperties
data class PaymentRecord(
    @DocumentId val id: String = "",
    val projectId: String = "",
    val projectTitle: String = "",
    val clientCompanyName: String = "",
    val amountPaise: Long = 0L,
    val paymentDateMillis: Long = 0L,
    val method: String = PaymentMethod.UPI.name,
    val reference: String = "",
    val note: String = "",
    val recordedByUid: String = "",
    val recordedByName: String = "",
    val createdAt: Timestamp? = null
) {
    fun methodEnum(): PaymentMethod = safeEnum(method, PaymentMethod.UPI)
}

@Keep
@IgnoreExtraProperties
data class ActivityLog(
    @DocumentId val id: String = "",
    val category: String = ActivityCategory.COMPANY.name,
    val title: String = "",
    val detail: String = "",
    val actorUid: String = "",
    val actorName: String = "",
    val entityId: String = "",
    val createdAt: Timestamp? = null
) {
    fun categoryEnum(): ActivityCategory = safeEnum(category, ActivityCategory.COMPANY)
}

enum class ChatChannelType(val label: String) {
    GENERAL("General"),
    PROJECT("Project"),
    DEPARTMENT("Department"),
    DIRECT("Direct")
}

enum class ChatAttachmentType(val label: String) {
    NONE("None"),
    IMAGE("Image"),
    FILE("Document"),
    AUDIO("Audio")
}

enum class NotificationCategory(val label: String) {
    PROJECT("Project"),
    DELIVERABLE("Task"),
    CHAT("Chat"),
    PAYMENT("Payment"),
    MEMBERSHIP("Team"),
    SYSTEM("Alert")
}

@Keep
@IgnoreExtraProperties
data class ChatChannel(
    @DocumentId val id: String = "",
    val title: String = "",
    val topic: String = "",
    val channelType: String = ChatChannelType.GENERAL.name,
    val projectId: String = "",
    val projectTitle: String = "",
    val departmentId: String = "",
    val departmentName: String = "",
    val participantUids: List<String> = emptyList(),
    val createdByUid: String = "",
    val createdByName: String = "",
    val lastMessagePreview: String = "",
    val lastSenderName: String = "",
    val lastMessageAtMillis: Long = 0L,
    val messageCount: Long = 0L,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun channelTypeEnum(): ChatChannelType = safeEnum(channelType, ChatChannelType.GENERAL)
}

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val channelId: String = "",
    val parentMessageId: String = "",
    val parentPreview: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val senderRole: String = "",
    val body: String = "",
    val attachmentType: String = ChatAttachmentType.NONE.name,
    val attachmentName: String = "",
    val attachmentMimeType: String = "",
    val attachmentSizeBytes: Long = 0L,
    val attachmentLocalPath: String = "",
    val attachmentDataBase64: String = "",
    val createdAt: Timestamp? = null
) {
    fun attachmentTypeEnum(): ChatAttachmentType = safeEnum(attachmentType, ChatAttachmentType.NONE)
    val hasAttachment: Boolean
        get() = attachmentTypeEnum() != ChatAttachmentType.NONE &&
            (attachmentName.isNotBlank() || attachmentDataBase64.isNotBlank() || attachmentLocalPath.isNotBlank())
}

@Keep
@IgnoreExtraProperties
data class WorkspaceNotification(
    @DocumentId val id: String = "",
    val category: String = NotificationCategory.SYSTEM.name,
    val title: String = "",
    val body: String = "",
    val targetUid: String = "ALL",
    val targetDepartmentId: String = "",
    val actorUid: String = "",
    val actorName: String = "",
    val entityId: String = "",
    val channelId: String = "",
    val readByUids: List<String> = emptyList(),
    val createdAt: Timestamp? = null
) {
    fun categoryEnum(): NotificationCategory = safeEnum(category, NotificationCategory.SYSTEM)
    fun isUnreadFor(uid: String): Boolean = uid.isNotBlank() && uid !in readByUids
    fun appliesTo(member: Member?): Boolean {
        val m = member ?: return false
        return targetUid == "ALL" ||
            targetUid == m.uid ||
            (targetDepartmentId.isNotBlank() && targetDepartmentId == m.departmentId) ||
            (targetUid == "MANAGERS" && (m.accessLevelEnum() == AccessLevel.SUPER_ADMIN || m.accessLevelEnum() == AccessLevel.ADMIN))
    }
}


