package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties

enum class AdvancedChatType(val label: String) {
    PERSONAL("Personal 1:1"),
    GROUP("Group"),
    PROJECT("Project"),
    DEPARTMENT("Department"),
    ANNOUNCEMENT("Announcements"),
    SAVED("Saved Messages")
}

/**
 * Complete 22 supported internal messaging & attachment types.
 * Voice, video, and group calling are strictly excluded.
 */
enum class MessageKind(val label: String) {
    TEXT("Text"),
    RICH_LINK_TEXT("Rich Link"),
    REPLY("Reply"),
    FORWARDED("Forwarded"),
    MENTION_TEXT("Mention"),
    REACTION_EVENT("Reaction"),
    IMAGE("Image"),
    IMAGE_ALBUM("Image Album"),
    VIDEO("Video"),
    VOICE_NOTE("Voice Note"),
    DOCUMENT("Document"),
    INVOICE_REF("Invoice"),
    QUOTATION_REF("Quotation"),
    PROJECT_REF("Project"),
    TASK_REF("Deliverable Task"),
    APPROVAL_REF("Approval Request"),
    MEETING_REF("Meeting"),
    SERVICE_PLAN_REF("Service / Plan"),
    POLL("Poll"),
    PRIORITY_ANNOUNCEMENT("Priority Announcement"),
    LOCATION_CONTACT("Location / Contact"),
    SYSTEM_EVENT("System Event")
}

enum class MessageDeliveryStatus(val label: String) {
    PENDING("Pending"),
    UPLOADING("Uploading"),
    SENT("Sent"),
    DELIVERED("Delivered"),
    READ("Read"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
    DELETED("Deleted")
}

enum class MessagePriority(val label: String) {
    NORMAL("Normal"),
    HIGH("High"),
    URGENT("Urgent")
}

enum class ChatThreadRole(val label: String) {
    OWNER("Owner"),
    ADMIN("Admin"),
    MEMBER("Member")
}

enum class ChatBusinessKind(val label: String) {
    NONE("None"),
    PROJECT("Project"),
    TASK("Deliverable"),
    QUOTATION("Quotation"),
    INVOICE("Invoice"),
    APPROVAL("Approval Request"),
    MEETING("Meeting"),
    SERVICE("Service / Plan")
}

enum class ChatReportStatus(val label: String) {
    OPEN("Open"),
    REVIEWED("Reviewed"),
    ACTION_TAKEN("Action Taken"),
    DISMISSED("Dismissed")
}

@Keep
@IgnoreExtraProperties
data class ChatAttachmentMeta(
    val storagePath: String = "",
    val thumbStoragePath: String = "",
    val fileName: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String = "",
    val durationMs: Long = 0L,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val localCachePath: String = "",
    val legacyBase64: String = ""
)

@Keep
@IgnoreExtraProperties
data class ChatBusinessRef(
    val kind: String = ChatBusinessKind.NONE.name,
    val refId: String = "",
    val title: String = "",
    val subtitle: String = "",
    val status: String = "",
    val amountPaise: Long = 0L,
    val secondaryId: String = ""
) {
    fun kindEnum(): ChatBusinessKind = safeEnum(kind, ChatBusinessKind.NONE)
}

@Keep
@IgnoreExtraProperties
data class ChatThread(
    @DocumentId val id: String = "",
    val type: String = AdvancedChatType.GROUP.name,
    val title: String = "",
    val topic: String = "",
    val photoUrl: String = "",
    val photoUpdatedAtMillis: Long = 0L,
    val departmentId: String = "",
    val departmentName: String = "",
    val linkedProjectId: String = "",
    val linkedProjectTitle: String = "",
    val participantIds: List<String> = emptyList(),
    val adminIds: List<String> = emptyList(),
    val ownerUid: String = "",
    val createdByUid: String = "",
    val createdByName: String = "",
    val allowMemberMedia: Boolean = true,
    val slowModeSec: Int = 0,
    val pinnedMessageId: String = "",
    val pinnedPreview: String = "",
    val lastMessageId: String = "",
    val lastMessagePreview: String = "",
    val lastSenderUid: String = "",
    val lastSenderName: String = "",
    val lastMessageAtMillis: Long = 0L,
    val messageCount: Long = 0L,
    val archivedByDefault: Boolean = false,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun typeEnum(): AdvancedChatType = safeEnum(type, AdvancedChatType.GROUP)
}

@Keep
@IgnoreExtraProperties
data class ChatThreadMember(
    @DocumentId val id: String = "",
    val uid: String = "",
    val role: String = ChatThreadRole.MEMBER.name,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val mutedUntilMillis: Long = 0L,
    val clearedBeforeMillis: Long = 0L,
    val lastReadMessageId: String = "",
    val lastReadAtMillis: Long = 0L,
    val unreadCount: Int = 0,
    val joinedAtMillis: Long = 0L,
    val updatedAt: Timestamp? = null
) {
    fun roleEnum(): ChatThreadRole = safeEnum(role, ChatThreadRole.MEMBER)
    fun isMuted(nowMillis: Long = System.currentTimeMillis()): Boolean =
        mutedUntilMillis == -1L || mutedUntilMillis > nowMillis
}

@Keep
@IgnoreExtraProperties
data class ChatThreadMessage(
    @DocumentId val id: String = "",
    val clientMsgId: String = "",
    val chatId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val senderRole: String = "",
    val senderPhotoUrl: String = "",
    val kind: String = MessageKind.TEXT.name,
    val body: String = "",
    val linkUrl: String = "",
    val replyToMessageId: String = "",
    val replyPreview: String = "",
    val forwardedFromChatTitle: String = "",
    val forwardedFromSender: String = "",
    val mentions: List<String> = emptyList(),
    val attachments: List<ChatAttachmentMeta> = emptyList(),
    val businessRef: ChatBusinessRef = ChatBusinessRef(),
    val pollId: String = "",
    val locationLabel: String = "",
    val contactSummary: String = "",
    val priority: String = MessagePriority.NORMAL.name,
    val ackRequired: Boolean = false,
    val ackBy: Map<String, Long> = emptyMap(),
    val reactionsSummary: Map<String, Int> = emptyMap(),
    val deliveredTo: List<String> = emptyList(),
    val readBy: List<String> = emptyList(),
    val starredByUids: List<String> = emptyList(),
    val hiddenForUids: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val isEdited: Boolean = false,
    val editedAtMillis: Long = 0L,
    val isDeletedForEveryone: Boolean = false,
    val deletedByUid: String = "",
    val status: String = MessageDeliveryStatus.SENT.name,
    val createdAtMillis: Long = 0L,
    val createdAt: Timestamp? = null
) {
    fun kindEnum(): MessageKind = safeEnum(kind, MessageKind.TEXT)
    fun priorityEnum(): MessagePriority = safeEnum(priority, MessagePriority.NORMAL)
    fun statusEnum(): MessageDeliveryStatus = safeEnum(status, MessageDeliveryStatus.SENT)
}

@Keep
@IgnoreExtraProperties
data class ChatPollOption(
    val id: String = "",
    val label: String = "",
    val voteCount: Int = 0
)

@Keep
@IgnoreExtraProperties
data class ChatPoll(
    @DocumentId val id: String = "",
    val chatId: String = "",
    val messageId: String = "",
    val question: String = "",
    val options: List<ChatPollOption> = emptyList(),
    val multiSelect: Boolean = false,
    val anonymous: Boolean = false,
    val closed: Boolean = false,
    val createdByUid: String = "",
    val createdByName: String = "",
    val voterUids: List<String> = emptyList(),
    val closesAtMillis: Long = 0L,
    val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ChatPollVote(
    @DocumentId val id: String = "",
    val uid: String = "",
    val voterName: String = "",
    val selectedOptionIds: List<String> = emptyList(),
    val votedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ChatReportSnapshotItem(
    val messageId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val body: String = "",
    val kind: String = MessageKind.TEXT.name,
    val createdAtMillis: Long = 0L
)

@Keep
@IgnoreExtraProperties
data class ChatReport(
    @DocumentId val id: String = "",
    val chatId: String = "",
    val chatType: String = AdvancedChatType.GROUP.name,
    val messageId: String = "",
    val reportedSenderUid: String = "",
    val reportedSenderName: String = "",
    val reporterUid: String = "",
    val reporterName: String = "",
    val reason: String = "",
    val snapshotMessages: List<ChatReportSnapshotItem> = emptyList(),
    val status: String = ChatReportStatus.OPEN.name,
    val reviewedByUid: String = "",
    val resolutionNote: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ChatAuditLog(
    @DocumentId val id: String = "",
    val actorUid: String = "",
    val actorName: String = "",
    val action: String = "",
    val targetId: String = "",
    val detail: String = "",
    val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class GlobalChatSettings(
    @DocumentId val id: String = "global",
    val chatEnabled: Boolean = true,
    val globalReadOnly: Boolean = false,
    val maxImageMb: Int = 5,
    val maxDocMb: Int = 10,
    val maxVideoMb: Int = 15,
    val maxVoiceSeconds: Int = 120,
    val retentionDays: Int = 180,
    val allowVoiceNotes: Boolean = true,
    val allowFileSharing: Boolean = true,
    val updatedByUid: String = "",
    val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ChatResetOperation(
    @DocumentId val id: String = "",
    val operationType: String = "COMPLETE_CHAT_RESET",
    val status: String = "IN_PROGRESS",
    val cursorCollection: String = "chats",
    val cursorDocId: String = "",
    val deletedDocsCount: Long = 0L,
    val deletedStorageCount: Long = 0L,
    val initiatedByUid: String = "",
    val initiatedByName: String = "",
    val lastError: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class MeetingSchedule(
    @DocumentId val id: String = "",
    val title: String = "",
    val chatId: String = "",
    val projectId: String = "",
    val projectTitle: String = "",
    val locationOrLink: String = "",
    val notes: String = "",
    val scheduledAtMillis: Long = 0L,
    val durationMinutes: Int = 30,
    val attendeeIds: List<String> = emptyList(),
    val status: String = "SCHEDULED",
    val createdByUid: String = "",
    val createdByName: String = "",
    val createdAt: Timestamp? = null
)
