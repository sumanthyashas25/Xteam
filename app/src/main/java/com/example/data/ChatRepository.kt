package com.example.data

import com.example.chat.ChatFileManager
import com.example.chat.StagedChatAttachment
import com.example.chat.remote.WorkerBridgeClient
import com.example.chat.remote.WorkerResult
import com.example.model.*
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.UUID

data class SendMessageOptions(
    val kind: ChatMessageKind = ChatMessageKind.TEXT,
    val parentMessage: ChatMessage? = null,
    val attachment: StagedChatAttachment? = null,
    val forwardedFromName: String = "",
    val viewOnce: Boolean = false,
    val mentions: List<String> = emptyList(),
    val stickerId: String = "",
    val gifId: String = "",
    val gifTitle: String = "",
    val contactName: String = "",
    val contactPhone: String = "",
    val locationLat: Double = 0.0,
    val locationLng: Double = 0.0,
    val locationLabel: String = "",
    val liveLocationUntilMillis: Long = 0L,
    val eventTitle: String = "",
    val eventLocation: String = "",
    val eventTimeMillis: Long = 0L
)

data class NewStatusSubmission(
    val kind: StatusKind,
    val text: String = "",
    val caption: String = "",
    val textOverlay: String = "",
    val textOverlayX: Float = 0.5f,
    val textOverlayY: Float = 0.5f,
    val overlayColorHex: Long = 0xFFFFFFFFL,
    val fontStyle: String = "BOLD",
    val backgroundPreset: Int = 0,
    val mediaFile: java.io.File? = null,
    val mediaMimeType: String = "",
    val mediaSha256: String = "",
    val imageThumbnailBase64: String = "",
    val durationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val audioWaveform: List<Int> = emptyList(),
    val privacyMode: StatusPrivacyMode = StatusPrivacyMode.ALL_APPROVED,
    val allowedUids: List<String> = emptyList(),
    val excludedUids: List<String> = emptyList(),
    val ownerBlockedUids: List<String> = emptyList()
)

class ChatRepository(private val core: AgencyRepository) {

    private val db get() = core.db
    private fun sub(name: String) = core.sub(name)
    private val est = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE

    companion object {
        const val GENERAL_CHANNEL_ID = "ch_general"
        private val INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()

        fun directChannelId(uid1: String, uid2: String): String {
            val sortedUids = listOf(uid1, uid2).distinct().sorted()
            val rawSuffix = sortedUids.joinToString("_").replace(Regex("[^A-Za-z0-9_-]"), "_")
            return "ch_dm_$rawSuffix".take(120)
        }

        fun savedNotesChannelId(uid: String): String {
            val safe = uid.replace(Regex("[^A-Za-z0-9_-]"), "_")
            return "ch_saved_$safe".take(120)
        }

        fun departmentChannelId(deptId: String): String {
            val safeDept = deptId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            return "ch_dept_$safeDept".take(120)
        }

        fun generateInviteCode(): String {
            return buildString(6) {
                repeat(6) { append(INVITE_ALPHABET[random.nextInt(INVITE_ALPHABET.length)]) }
            }
        }
    }

    fun observeChannels(myUid: String, isSuperAdmin: Boolean): Flow<List<ChatChannel>> {
        val sharedFlow = callbackFlow<List<ChatChannel>> {
            val reg = sub("chat_channels")
                .whereIn(
                    "channelType",
                    listOf(
                        ChatChannelType.GENERAL.name,
                        ChatChannelType.PROJECT.name,
                        ChatChannelType.ANNOUNCEMENT.name
                    )
                )
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/chat_channels?shared")
                        close(err)
                        return@addSnapshotListener
                    }
                    val list = snap?.documents?.mapNotNull { doc ->
                        doc.get("createdAt", est)
                        doc.get("updatedAt", est)
                        doc.toObject(ChatChannel::class.java)
                    }.orEmpty()
                    trySend(list)
                }
            awaitClose { reg.remove() }
        }

        val memberChannelsFlow = callbackFlow<List<ChatChannel>> {
            val reg = sub("chat_channels")
                .whereArrayContains("participantUids", myUid)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/chat_channels?member=$myUid")
                        close(err)
                        return@addSnapshotListener
                    }
                    val list = snap?.documents?.mapNotNull { doc ->
                        doc.get("createdAt", est)
                        doc.get("updatedAt", est)
                        doc.toObject(ChatChannel::class.java)
                    }.orEmpty().filter { ch ->
                        val type = ch.channelTypeEnum()
                        type != ChatChannelType.DEPARTMENT && when (type) {
                            ChatChannelType.DIRECT -> myUid in ch.participantUids
                            ChatChannelType.SAVED -> ch.createdByUid == myUid || myUid in ch.participantUids
                            else -> true
                        }
                    }
                    trySend(list)
                }
            awaitClose { reg.remove() }
        }

        val superGroupFlow = if (isSuperAdmin) {
            callbackFlow<List<ChatChannel>> {
                val reg = sub("chat_channels")
                    .whereEqualTo("channelType", ChatChannelType.GROUP.name)
                    .addSnapshotListener { snap, err ->
                        if (err != null) {
                            handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/chat_channels?groups")
                            close(err)
                            return@addSnapshotListener
                        }
                        val list = snap?.documents?.mapNotNull { doc ->
                            doc.get("createdAt", est)
                            doc.get("updatedAt", est)
                            doc.toObject(ChatChannel::class.java)
                        }.orEmpty()
                        trySend(list)
                    }
                awaitClose { reg.remove() }
            }
        } else null

        return if (superGroupFlow != null) {
            combine(sharedFlow, memberChannelsFlow, superGroupFlow) { shared, memberCh, groups ->
                (shared + memberCh + groups)
                    .distinctBy { it.id }
                    .sortedByDescending { it.lastMessageAtMillis }
            }
        } else {
            combine(sharedFlow, memberChannelsFlow) { shared, memberCh ->
                (shared + memberCh)
                    .distinctBy { it.id }
                    .sortedByDescending { it.lastMessageAtMillis }
            }
        }
    }

    fun observeMessages(channelId: String): Flow<SyncedData<List<ChatMessage>>> = callbackFlow {
        if (channelId.isBlank()) {
            trySend(SyncedData(emptyList(), false))
            close()
            return@callbackFlow
        }
        val reg = sub("chat_channels").document(channelId).collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limitToLast(150)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/chat_channels/$channelId/messages")
                    close(err)
                    return@addSnapshotListener
                }
                val now = System.currentTimeMillis()
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.get("createdAt", est)
                    doc.toObject(ChatMessage::class.java)
                }.orEmpty().filter { !it.isExpired(now) }
                val pending = snap?.metadata?.hasPendingWrites() == true
                trySend(SyncedData(list, pending))
            }
        awaitClose { reg.remove() }
    }

    // --- Status / Stories (24h ephemeral) ---
    fun observeStatuses(): Flow<List<ChatStatus>> = callbackFlow {
        val reg = sub("chat_statuses")
            .orderBy("createdAtMillis", Query.Direction.DESCENDING)
            .limit(80)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                val now = System.currentTimeMillis()
                val active = snap?.documents?.mapNotNull { doc ->
                    doc.get("createdAt", est)
                    doc.toObject(ChatStatus::class.java)
                }.orEmpty().filter { it.isActive(now) }
                trySend(active)
            }
        awaitClose { reg.remove() }
    }

    suspend fun postStatus(
        text: String,
        backgroundPreset: Int,
        imageBase64: String = "",
        actor: Member
    ): ChatStatus {
        val kind = if (imageBase64.isNotBlank()) StatusKind.IMAGE else StatusKind.TEXT
        return postRichStatus(
            submission = NewStatusSubmission(
                kind = kind,
                text = text,
                caption = if (kind == StatusKind.IMAGE) text else "",
                backgroundPreset = backgroundPreset,
                imageThumbnailBase64 = imageBase64
            ),
            actor = actor
        )
    }

    suspend fun postRichStatus(
        submission: NewStatusSubmission,
        actor: Member,
        progressKey: String = "status_${System.currentTimeMillis()}",
        onProgress: (Float) -> Unit = {},
        onCallCreated: (okhttp3.Call) -> Unit = {}
    ): ChatStatus {
        val cleanText = submission.text.trim().take(500)
        val cleanCaption = submission.caption.trim().take(500)
        val cleanOverlay = submission.textOverlay.trim().take(120)
        require(
            cleanText.isNotBlank() ||
                cleanCaption.isNotBlank() ||
                submission.imageThumbnailBase64.isNotBlank() ||
                submission.mediaFile != null
        ) {
            "Enter text or select photo, video, or audio for your status."
        }

        val statusId = "st_" + UUID.randomUUID().toString().take(10)
        val now = System.currentTimeMillis()
        val expires = now + 86_400_000L

        var resolvedBucket = ""
        var resolvedPath = ""
        var resolvedMime = submission.mediaMimeType
        var resolvedBytes = submission.mediaFile?.length() ?: 0L
        var resolvedProvider = "FIRESTORE_INLINE"

        val mediaFile = submission.mediaFile
        if (mediaFile != null && mediaFile.exists() && mediaFile.length() > 0L) {
            val bucket = when (submission.kind) {
                StatusKind.IMAGE -> "chat-media"
                StatusKind.VIDEO -> "chat-videos"
                StatusKind.VOICE -> "chat-voice"
                StatusKind.TEXT -> "chat-media"
            }
            val mime = submission.mediaMimeType.ifBlank {
                when (submission.kind) {
                    StatusKind.VIDEO -> "video/mp4"
                    StatusKind.VOICE -> "audio/mp4"
                    else -> "image/jpeg"
                }
            }
            if (WorkerBridgeClient.isConfigured()) {
                when (val up = WorkerBridgeClient.uploadStatusMediaToCloudflare(
                    companyId = core.companyId,
                    bucket = bucket,
                    fileName = mediaFile.name,
                    mimeType = mime,
                    file = mediaFile,
                    sha256 = submission.mediaSha256,
                    progressKey = progressKey,
                    onProgress = onProgress,
                    onCallCreated = onCallCreated
                )) {
                    is WorkerResult.Success -> {
                        resolvedBucket = up.data.bucket
                        resolvedPath = up.data.storagePath
                        resolvedMime = up.data.mimeType.ifBlank { mime }
                        resolvedBytes = up.data.byteSize
                        resolvedProvider = up.data.provider
                    }
                    is WorkerResult.RateLimited -> {
                        error("Upload rate limited; retry in ${up.retryAfterSeconds}s.")
                    }
                    is WorkerResult.Error -> {
                        error(up.message)
                    }
                    WorkerResult.NotConfigured -> {
                        if (submission.kind != StatusKind.IMAGE || submission.imageThumbnailBase64.isBlank()) {
                            error("Cloudflare media storage is not configured for ${submission.kind.label}.")
                        }
                    }
                }
            } else if (submission.kind != StatusKind.IMAGE || submission.imageThumbnailBase64.isBlank()) {
                error("Cloudflare media storage is required for ${submission.kind.label} status.")
            }
        } else {
            onProgress(0.6f)
        }

        val payload = mapOf(
            "ownerUid" to actor.uid,
            "ownerName" to actor.fullName.ifBlank { "Member" }.take(120),
            "ownerRole" to actor.jobRoleName.ifBlank { actor.accessLevelEnum().label }.take(80),
            "ownerPhotoBase64" to actor.photoBase64,
            "kind" to submission.kind.name,
            "text" to cleanText,
            "caption" to cleanCaption,
            "textOverlay" to cleanOverlay,
            "textOverlayX" to submission.textOverlayX.coerceIn(0.05f, 0.95f),
            "textOverlayY" to submission.textOverlayY.coerceIn(0.1f, 0.9f),
            "overlayColorHex" to submission.overlayColorHex,
            "fontStyle" to submission.fontStyle.ifBlank { "BOLD" },
            "backgroundPreset" to submission.backgroundPreset,
            "imageDataBase64" to submission.imageThumbnailBase64,
            "imageStoragePath" to resolvedPath,
            "imageBucket" to resolvedBucket,
            "mediaStoragePath" to resolvedPath,
            "mediaBucket" to resolvedBucket,
            "mediaMimeType" to resolvedMime,
            "mediaByteSize" to resolvedBytes,
            "storageProvider" to resolvedProvider,
            "durationMs" to submission.durationMs,
            "trimStartMs" to submission.trimStartMs,
            "trimEndMs" to submission.trimEndMs,
            "audioWaveform" to submission.audioWaveform.take(40),
            "viewerUids" to emptyList<String>(),
            "viewerTimestamps" to emptyMap<String, Long>(),
            "reactions" to emptyMap<String, String>(),
            "replyCount" to 0L,
            "privacyMode" to submission.privacyMode.name,
            "allowedUids" to submission.allowedUids.distinct().take(150),
            "excludedUids" to submission.excludedUids.distinct().take(150),
            "ownerBlockedUids" to submission.ownerBlockedUids.distinct().take(150),
            "createdAtMillis" to now,
            "expiresAtMillis" to expires,
            "createdAt" to FieldValue.serverTimestamp()
        )
        sub("chat_statuses").document(statusId).set(payload).await()
        onProgress(1f)
        return ChatStatus(
            id = statusId,
            ownerUid = actor.uid,
            ownerName = actor.fullName.ifBlank { "Member" },
            ownerRole = actor.jobRoleName.ifBlank { actor.accessLevelEnum().label },
            ownerPhotoBase64 = actor.photoBase64,
            kind = submission.kind.name,
            text = cleanText,
            caption = cleanCaption,
            textOverlay = cleanOverlay,
            textOverlayX = submission.textOverlayX,
            textOverlayY = submission.textOverlayY,
            overlayColorHex = submission.overlayColorHex,
            fontStyle = submission.fontStyle,
            backgroundPreset = submission.backgroundPreset,
            imageDataBase64 = submission.imageThumbnailBase64,
            imageStoragePath = resolvedPath,
            imageBucket = resolvedBucket,
            mediaStoragePath = resolvedPath,
            mediaBucket = resolvedBucket,
            mediaMimeType = resolvedMime,
            mediaByteSize = resolvedBytes,
            storageProvider = resolvedProvider,
            durationMs = submission.durationMs,
            trimStartMs = submission.trimStartMs,
            trimEndMs = submission.trimEndMs,
            audioWaveform = submission.audioWaveform,
            privacyMode = submission.privacyMode.name,
            allowedUids = submission.allowedUids,
            excludedUids = submission.excludedUids,
            ownerBlockedUids = submission.ownerBlockedUids,
            createdAtMillis = now,
            expiresAtMillis = expires
        )
    }

    suspend fun markStatusViewed(statusId: String, myUid: String) {
        if (statusId.isBlank() || myUid.isBlank()) return
        val now = System.currentTimeMillis()
        sub("chat_statuses").document(statusId)
            .update(
                mapOf(
                    "viewerUids" to FieldValue.arrayUnion(myUid),
                    "viewerTimestamps.$myUid" to now
                )
            )
            .await()
    }

    suspend fun reactToStatus(statusId: String, myUid: String, emoji: String) {
        if (statusId.isBlank() || myUid.isBlank() || emoji.isBlank()) return
        val now = System.currentTimeMillis()
        sub("chat_statuses").document(statusId)
            .update(
                mapOf(
                    "viewerUids" to FieldValue.arrayUnion(myUid),
                    "viewerTimestamps.$myUid" to now,
                    "reactions.$myUid" to emoji.take(12)
                )
            )
            .await()
    }

    suspend fun updateMyActiveStatusesPrivacyAndBlocks(
        activeMyStatuses: List<ChatStatus>,
        privacyMode: StatusPrivacyMode,
        allowedUids: List<String>,
        excludedUids: List<String>,
        blockedUids: List<String>
    ) {
        if (activeMyStatuses.isEmpty()) return
        val batch = db.batch()
        activeMyStatuses.forEach { st ->
            batch.update(
                sub("chat_statuses").document(st.id),
                mapOf(
                    "privacyMode" to privacyMode.name,
                    "allowedUids" to allowedUids.distinct().take(150),
                    "excludedUids" to excludedUids.distinct().take(150),
                    "ownerBlockedUids" to blockedUids.distinct().take(150)
                )
            )
        }
        batch.commit().await()
    }

    suspend fun sendStatusInteractionToDm(
        ownerMember: Member,
        actor: Member,
        status: ChatStatus,
        replyText: String = "",
        reactionEmoji: String = ""
    ): ChatChannel {
        val dmChannel = createOrGetDirectChannel(actor, ownerMember)
        val channelRef = sub("chat_channels").document(dmChannel.id)
        val msgId = "stref_" + UUID.randomUUID().toString().take(10)
        val senderName = actor.fullName.ifBlank { "Member" }.take(120)
        val now = System.currentTimeMillis()
        val isReaction = reactionEmoji.isNotBlank() && replyText.isBlank()
        val body = when {
            isReaction -> "Reacted $reactionEmoji to your status"
            reactionEmoji.isNotBlank() -> "$reactionEmoji $replyText".trim()
            else -> replyText.trim()
        }.take(600)

        val excerpt = status.effectiveDisplayText()
            .ifBlank { status.textOverlay }
            .ifBlank { "${status.kindEnum().label} status" }
            .take(120)

        val refSubtitle = if (isReaction) {
            "Reaction $reactionEmoji • ${status.ownerName.ifBlank { "Status" }}"
        } else {
            "${status.kindEnum().label} status • ${status.ownerName.ifBlank { "Status" }}"
        }

        val recipients = dmChannel.participantUids.filter { it != actor.uid }
        val nextUnread = dmChannel.unreadCounts.toMutableMap()
        recipients.forEach { uid -> nextUnread[uid] = (nextUnread[uid] ?: 0L) + 1L }

        val msgPayload = mapOf(
            "channelId" to dmChannel.id,
            "kind" to ChatMessageKind.TEXT.name,
            "parentMessageId" to "",
            "parentPreview" to "",
            "senderUid" to actor.uid,
            "senderName" to senderName,
            "senderRole" to actor.jobRoleName.ifBlank { actor.accessLevelEnum().label }.take(80),
            "body" to body,
            "attachmentType" to ChatAttachmentType.NONE.name,
            "attachmentName" to "",
            "attachmentMimeType" to "",
            "attachmentSizeBytes" to 0L,
            "attachmentLocalPath" to "",
            "attachmentDataBase64" to status.imageDataBase64.take(180000),
            "businessRefKind" to "STATUS",
            "businessRefId" to status.id,
            "businessRefTitle" to excerpt,
            "businessRefSubtitle" to refSubtitle.take(160),
            "businessRefStatus" to status.kind,
            "businessRefAmountPaise" to 0L,
            "deliveryStatus" to "SENT",
            "deliveredToUids" to emptyList<String>(),
            "readByUids" to emptyList<String>(),
            "createdAt" to FieldValue.serverTimestamp()
        )
        val preview = if (isReaction) "Reacted $reactionEmoji to status" else "Status reply: $body"
        val batch = db.batch()
        batch.set(channelRef.collection("messages").document(msgId), msgPayload)
        batch.update(
            channelRef,
            mapOf(
                "lastMessagePreview" to preview.take(200),
                "lastSenderName" to senderName,
                "lastSenderUid" to actor.uid,
                "lastMessageStatus" to "SENT",
                "lastMessageAtMillis" to now,
                "messageCount" to FieldValue.increment(1L),
                "unreadCounts" to nextUnread,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        if (!isReaction) {
            batch.update(sub("chat_statuses").document(status.id), "replyCount", FieldValue.increment(1L))
        }
        batch.commit().await()
        if (ownerMember.uid != actor.uid) {
            core.createNotification(
                category = NotificationCategory.CHAT,
                title = "Status ${if (isReaction) "Reaction" else "Reply"} • $senderName",
                body = preview.take(160),
                actor = actor,
                targetUid = ownerMember.uid,
                channelId = dmChannel.id
            )
        }
        return dmChannel
    }

    suspend fun deleteStatus(statusId: String, storagePath: String = "") {
        if (statusId.isBlank()) return
        sub("chat_statuses").document(statusId).delete().await()
        if (storagePath.isNotBlank() && WorkerBridgeClient.isConfigured()) {
            runCatching { WorkerBridgeClient.deleteStatusMediaFromCloudflare(core.companyId, storagePath) }
        }
    }

    suspend fun deleteAllStatuses(statuses: List<ChatStatus>) {
        if (statuses.isEmpty()) return
        statuses.chunked(200).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { st ->
                batch.delete(sub("chat_statuses").document(st.id))
            }
            batch.commit().await()
        }
        statuses.forEach { st ->
            val path = st.effectiveStoragePath()
            if (path.isNotBlank() && WorkerBridgeClient.isConfigured()) {
                runCatching { WorkerBridgeClient.deleteStatusMediaFromCloudflare(core.companyId, path) }
            }
        }
    }

    suspend fun reportStatus(status: ChatStatus, reason: String, actor: Member) {
        val reportId = "rep_" + UUID.randomUUID().toString().take(10)
        val excerpt = "${status.ownerName}: ${status.effectiveDisplayText().ifBlank { status.kindEnum().label }}".take(200)
        val payload = mapOf(
            "channelId" to "status_${status.id}".take(120),
            "channelTitle" to "Status Update",
            "messageId" to status.id,
            "snapshotMessages" to listOf(excerpt),
            "reportedUid" to status.ownerUid,
            "reporterUid" to actor.uid,
            "reporterName" to actor.fullName.ifBlank { "Member" }.take(120),
            "reason" to reason.trim().ifBlank { "Inappropriate status" }.take(400),
            "status" to "OPEN",
            "createdAtMillis" to System.currentTimeMillis(),
            "createdAt" to FieldValue.serverTimestamp()
        )
        sub("chatReports").document(reportId).set(payload).await()
    }

    // --- Channel Creation & Retrieval ---
    suspend fun ensureDefaultGeneralChannel(actor: Member): ChatChannel {
        val ref = sub("chat_channels").document(GENERAL_CHANNEL_ID)
        val snap = runCatching { ref.get().await() }.getOrNull()
        if (snap != null && snap.exists()) {
            snap.toObject(ChatChannel::class.java)?.let { return it }
        }
        val now = System.currentTimeMillis()
        val payload = mapOf(
            "title" to "General",
            "topic" to "Agency-wide updates, briefs & file sharing",
            "description" to "Workspace announcements, shared media, and team discussions.",
            "channelType" to ChatChannelType.GENERAL.name,
            "projectId" to "",
            "projectTitle" to "",
            "departmentId" to "",
            "departmentName" to "",
            "participantUids" to emptyList<String>(),
            "pendingMemberUids" to emptyList<String>(),
            "adminUids" to listOf(actor.uid),
            "createdByUid" to actor.uid,
            "createdByName" to actor.fullName.ifBlank { "Member" }.take(120),
            "lastMessagePreview" to "Channel ready for team messages & file uploads",
            "lastSenderName" to actor.fullName.ifBlank { "Member" }.take(120),
            "lastSenderUid" to actor.uid,
            "lastMessageStatus" to "SENT",
            "lastMessageAtMillis" to now,
            "messageCount" to 0L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        ref.set(payload).await()
        return ChatChannel(
            id = GENERAL_CHANNEL_ID,
            title = "General",
            topic = "Agency-wide updates, briefs & file sharing",
            channelType = ChatChannelType.GENERAL.name,
            adminUids = listOf(actor.uid),
            createdByUid = actor.uid,
            createdByName = actor.fullName.ifBlank { "Member" },
            lastMessagePreview = "Channel ready for team messages & file uploads",
            lastSenderName = actor.fullName.ifBlank { "Member" },
            lastMessageAtMillis = now
        )
    }

    suspend fun createOrGetSavedNotesChannel(actor: Member): ChatChannel {
        val channelId = savedNotesChannelId(actor.uid)
        val ref = sub("chat_channels").document(channelId)
        val existing = runCatching { ref.get().await() }.getOrNull()
        if (existing != null && existing.exists()) {
            existing.toObject(ChatChannel::class.java)?.let { return it }
        }
        val now = System.currentTimeMillis()
        val cleanTitle = "${actor.fullName.substringBefore(" ").ifBlank { "My" }} (You)"
        val payload = mapOf(
            "title" to cleanTitle.take(100),
            "topic" to "Personal notes, links & files saved to yourself",
            "description" to "Private space — only visible to you.",
            "channelType" to ChatChannelType.SAVED.name,
            "projectId" to "",
            "projectTitle" to "",
            "departmentId" to "",
            "departmentName" to "",
            "participantUids" to listOf(actor.uid),
            "pendingMemberUids" to emptyList<String>(),
            "adminUids" to listOf(actor.uid),
            "createdByUid" to actor.uid,
            "createdByName" to actor.fullName.ifBlank { "Member" }.take(120),
            "lastMessagePreview" to "Message yourself notes & files",
            "lastSenderName" to actor.fullName.ifBlank { "Member" }.take(120),
            "lastSenderUid" to actor.uid,
            "lastMessageStatus" to "READ",
            "lastMessageAtMillis" to now,
            "messageCount" to 0L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        ref.set(payload).await()
        return ChatChannel(
            id = channelId,
            title = cleanTitle,
            topic = "Personal notes, links & files saved to yourself",
            channelType = ChatChannelType.SAVED.name,
            participantUids = listOf(actor.uid),
            adminUids = listOf(actor.uid),
            createdByUid = actor.uid,
            createdByName = actor.fullName.ifBlank { "Member" },
            lastMessagePreview = "Message yourself notes & files",
            lastSenderName = actor.fullName.ifBlank { "Member" },
            lastMessageAtMillis = now
        )
    }

    suspend fun createOrGetChannel(
        title: String,
        topic: String,
        channelType: ChatChannelType,
        project: Project?,
        actor: Member
    ): ChatChannel {
        if (channelType == ChatChannelType.SAVED) {
            return createOrGetSavedNotesChannel(actor)
        }
        val isProjectDeterministic = project != null
        val channelId = if (project != null) {
            "ch_proj_" + project.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        } else {
            "ch_" + UUID.randomUUID().toString().take(8)
        }
        val ref = sub("chat_channels").document(channelId)
        if (isProjectDeterministic) {
            val existing = runCatching { ref.get().await() }.getOrNull()
            if (existing != null && existing.exists()) {
                existing.toObject(ChatChannel::class.java)?.let { return it }
            }
        }

        val cleanTitle = title.trim().ifBlank { project?.title ?: "Group" }.take(100)
        val cleanTopic = topic.trim().ifBlank {
            if (project != null) "Project group & files for ${project.clientCompanyName}" else "Shared group chat"
        }.take(240)
        val now = System.currentTimeMillis()
        val inviteCode = generateInviteCode()
        val payload = mapOf(
            "title" to cleanTitle,
            "topic" to cleanTopic,
            "description" to cleanTopic,
            "channelType" to channelType.name,
            "projectId" to (project?.id ?: ""),
            "projectTitle" to (project?.title ?: "").take(160),
            "departmentId" to "",
            "departmentName" to "",
            "participantUids" to listOf(actor.uid),
            "pendingMemberUids" to emptyList<String>(),
            "adminUids" to listOf(actor.uid),
            "inviteCode" to inviteCode,
            "createdByUid" to actor.uid,
            "createdByName" to actor.fullName.ifBlank { "Member" }.take(120),
            "lastMessagePreview" to "Created $cleanTitle",
            "lastSenderName" to actor.fullName.ifBlank { "Member" }.take(120),
            "lastSenderUid" to actor.uid,
            "lastMessageStatus" to "SENT",
            "lastMessageAtMillis" to now,
            "messageCount" to 0L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        ref.set(payload).await()
        return ChatChannel(
            id = channelId,
            title = cleanTitle,
            topic = cleanTopic,
            description = cleanTopic,
            channelType = channelType.name,
            projectId = project?.id ?: "",
            projectTitle = project?.title ?: "",
            participantUids = listOf(actor.uid),
            adminUids = listOf(actor.uid),
            inviteCode = inviteCode,
            createdByUid = actor.uid,
            createdByName = actor.fullName.ifBlank { "Member" },
            lastMessagePreview = "Created $cleanTitle",
            lastSenderName = actor.fullName.ifBlank { "Member" },
            lastMessageAtMillis = now
        )
    }

    suspend fun createGroupChannel(
        title: String,
        description: String,
        memberUids: List<String>,
        photoBase64: String,
        isAnnouncement: Boolean,
        actor: Member,
        requireAdminApproval: Boolean = false
    ): ChatChannel {
        val cleanTitle = title.trim().ifBlank { "Team Group" }.take(100)
        val cleanDesc = description.trim().take(240)
        val participants = (listOf(actor.uid) + memberUids).distinct().take(50)
        val channelId = "ch_grp_" + UUID.randomUUID().toString().take(8)
        val inviteCode = generateInviteCode()
        val now = System.currentTimeMillis()
        val type = if (isAnnouncement) ChatChannelType.ANNOUNCEMENT else ChatChannelType.GROUP

        val payload = mapOf(
            "title" to cleanTitle,
            "topic" to cleanDesc.ifBlank { "${participants.size} members" },
            "description" to cleanDesc,
            "channelType" to type.name,
            "projectId" to "",
            "projectTitle" to "",
            "departmentId" to "",
            "departmentName" to "",
            "participantUids" to participants,
            "pendingMemberUids" to emptyList<String>(),
            "adminUids" to listOf(actor.uid),
            "photoBase64" to photoBase64,
            "inviteCode" to inviteCode,
            "onlyAdminsCanMessage" to isAnnouncement,
            "onlyAdminsCanEditInfo" to isAnnouncement,
            "requireAdminApproval" to requireAdminApproval,
            "createdByUid" to actor.uid,
            "createdByName" to actor.fullName.ifBlank { "Member" }.take(120),
            "lastMessagePreview" to "Group created by ${actor.fullName.substringBefore(" ")}",
            "lastSenderName" to actor.fullName.ifBlank { "Member" }.take(120),
            "lastSenderUid" to actor.uid,
            "lastMessageStatus" to "SENT",
            "lastMessageAtMillis" to now,
            "messageCount" to 0L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        sub("chat_channels").document(channelId).set(payload).await()
        return ChatChannel(
            id = channelId,
            title = cleanTitle,
            topic = cleanDesc.ifBlank { "${participants.size} members" },
            description = cleanDesc,
            channelType = type.name,
            participantUids = participants,
            adminUids = listOf(actor.uid),
            photoBase64 = photoBase64,
            inviteCode = inviteCode,
            onlyAdminsCanMessage = isAnnouncement,
            onlyAdminsCanEditInfo = isAnnouncement,
            requireAdminApproval = requireAdminApproval,
            createdByUid = actor.uid,
            createdByName = actor.fullName.ifBlank { "Member" },
            lastMessagePreview = "Group created by ${actor.fullName.substringBefore(" ")}",
            lastSenderName = actor.fullName.ifBlank { "Member" },
            lastMessageAtMillis = now
        )
    }

    suspend fun createOrGetDirectChannel(otherMember: Member, actor: Member): ChatChannel {
        if (otherMember.uid == actor.uid) {
            return createOrGetSavedNotesChannel(actor)
        }
        val sortedUids = listOf(actor.uid, otherMember.uid).distinct().sorted()
        val rawSuffix = sortedUids.joinToString("_").replace(Regex("[^A-Za-z0-9_-]"), "_")
        val channelId = "ch_dm_$rawSuffix".take(120)
        val ref = sub("chat_channels").document(channelId)
        val existing = runCatching { ref.get().await() }.getOrNull()
        if (existing != null && existing.exists()) {
            existing.toObject(ChatChannel::class.java)?.let { return it }
        }
        val cleanTitle = "${actor.fullName.substringBefore(" ")} & ${otherMember.fullName}".take(100)
        val cleanTopic = "Personal Chat"
        val now = System.currentTimeMillis()
        val payload = mapOf(
            "title" to cleanTitle,
            "topic" to cleanTopic,
            "channelType" to ChatChannelType.DIRECT.name,
            "projectId" to "",
            "projectTitle" to "",
            "departmentId" to "",
            "departmentName" to "",
            "participantUids" to sortedUids,
            "createdByUid" to actor.uid,
            "createdByName" to actor.fullName.ifBlank { "Member" }.take(120),
            "lastMessagePreview" to "Direct chat opened",
            "lastSenderName" to actor.fullName.ifBlank { "Member" }.take(120),
            "lastSenderUid" to actor.uid,
            "lastMessageStatus" to "SENT",
            "lastMessageAtMillis" to now,
            "messageCount" to 0L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        ref.set(payload).await()
        return ChatChannel(
            id = channelId,
            title = cleanTitle,
            topic = cleanTopic,
            channelType = ChatChannelType.DIRECT.name,
            participantUids = sortedUids,
            createdByUid = actor.uid,
            createdByName = actor.fullName.ifBlank { "Member" },
            lastMessagePreview = "Direct chat opened",
            lastSenderName = actor.fullName.ifBlank { "Member" },
            lastMessageAtMillis = now
        )
    }

    suspend fun createOrGetDepartmentChannel(dept: Department, actor: Member): ChatChannel {
        val safeDept = dept.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val channelId = "ch_grp_dept_$safeDept".take(120)
        val ref = sub("chat_channels").document(channelId)
        val existing = runCatching { ref.get().await() }.getOrNull()
        if (existing != null && existing.exists()) {
            existing.toObject(ChatChannel::class.java)?.let { return it }
        }
        val cleanTitle = "${dept.name} Group".take(100)
        val cleanTopic = dept.description.ifBlank { "${dept.name} group collaboration" }.take(240)
        val now = System.currentTimeMillis()
        val payload = mapOf(
            "title" to cleanTitle,
            "topic" to cleanTopic,
            "channelType" to ChatChannelType.GROUP.name,
            "projectId" to "",
            "projectTitle" to "",
            "departmentId" to dept.id,
            "departmentName" to dept.name.take(80),
            "participantUids" to listOf(actor.uid),
            "adminUids" to listOf(actor.uid),
            "createdByUid" to actor.uid,
            "createdByName" to actor.fullName.ifBlank { "Member" }.take(120),
            "lastMessagePreview" to "${dept.name} group ready",
            "lastSenderName" to actor.fullName.ifBlank { "Member" }.take(120),
            "lastSenderUid" to actor.uid,
            "lastMessageStatus" to "SENT",
            "lastMessageAtMillis" to now,
            "messageCount" to 0L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        ref.set(payload).await()
        return ChatChannel(
            id = channelId,
            title = cleanTitle,
            topic = cleanTopic,
            channelType = ChatChannelType.GROUP.name,
            departmentId = dept.id,
            departmentName = dept.name,
            participantUids = listOf(actor.uid),
            adminUids = listOf(actor.uid),
            createdByUid = actor.uid,
            createdByName = actor.fullName.ifBlank { "Member" },
            lastMessagePreview = "${dept.name} department chat ready",
            lastSenderName = actor.fullName.ifBlank { "Member" },
            lastMessageAtMillis = now
        )
    }

    // --- Sending Rich Messages ---
    suspend fun sendMessage(
        channelId: String,
        body: String,
        parentMessage: ChatMessage?,
        attachment: StagedChatAttachment?,
        actor: Member
    ): ChatMessage = sendRichMessage(
        channelId = channelId,
        body = body,
        actor = actor,
        options = SendMessageOptions(
            kind = when {
                attachment?.type == ChatAttachmentType.IMAGE && attachment.isHd -> ChatMessageKind.HD_IMAGE
                attachment?.type == ChatAttachmentType.IMAGE -> ChatMessageKind.IMAGE
                attachment?.type == ChatAttachmentType.VIDEO && attachment.isHd -> ChatMessageKind.HD_VIDEO
                attachment?.type == ChatAttachmentType.VIDEO -> ChatMessageKind.VIDEO
                attachment?.type == ChatAttachmentType.AUDIO && attachment.fileName.startsWith("voice_") -> ChatMessageKind.VOICE
                attachment?.type == ChatAttachmentType.AUDIO -> ChatMessageKind.AUDIO
                attachment?.type == ChatAttachmentType.FILE -> ChatMessageKind.DOCUMENT
                else -> ChatMessageKind.TEXT
            },
            parentMessage = parentMessage,
            attachment = attachment
        )
    )

    suspend fun sendRichMessage(
        channelId: String,
        body: String,
        actor: Member,
        options: SendMessageOptions
    ): ChatMessage {
        val cleanBody = body.trim().take(4000)
        val attachment = options.attachment
        val hasStructuredContent = cleanBody.isNotEmpty() ||
            attachment != null ||
            options.stickerId.isNotBlank() ||
            options.gifId.isNotBlank() ||
            options.contactName.isNotBlank() ||
            options.locationLabel.isNotBlank() ||
            options.eventTitle.isNotBlank()
        require(hasStructuredContent) { "Enter a message or select content before sending." }

        val effectiveChannelId = channelId.ifBlank { GENERAL_CHANNEL_ID }
        val channelRef = sub("chat_channels").document(effectiveChannelId)
        val channelSnap = channelRef.get().await()
        if (!channelSnap.exists() && effectiveChannelId == GENERAL_CHANNEL_ID) {
            ensureDefaultGeneralChannel(actor)
        }
        val channelObj = channelSnap.toObject(ChatChannel::class.java)

        var resolvedProvider = ""
        var resolvedBucket = ""
        var resolvedStoragePath = ""
        var resolvedSha256 = attachment?.sha256.orEmpty()
        var resolvedBase64 = ""

        if (attachment != null) {
            val localFile = attachment.localCachePath
                .takeIf { it.isNotBlank() }
                ?.let { java.io.File(it) }
                ?.takeIf { it.exists() && it.length() > 0L }

            val uploadRes = if (localFile != null && WorkerBridgeClient.isConfigured()) {
                WorkerBridgeClient.uploadChatFileToCloudflare(
                    companyId = core.companyId,
                    chatId = effectiveChannelId,
                    bucket = attachment.bucket,
                    fileName = attachment.fileName,
                    mimeType = attachment.mimeType,
                    file = localFile,
                    sha256 = attachment.sha256
                )
            } else {
                WorkerResult.NotConfigured
            }

            when (uploadRes) {
                is WorkerResult.Success -> {
                    resolvedProvider = uploadRes.data.provider
                    resolvedBucket = uploadRes.data.bucket
                    resolvedStoragePath = uploadRes.data.storagePath
                    resolvedSha256 = uploadRes.data.sha256.ifBlank { attachment.sha256 }
                    resolvedBase64 = ""
                }
                is WorkerResult.Error,
                is WorkerResult.RateLimited,
                WorkerResult.NotConfigured -> {
                    if (attachment.sizeBytes <= ChatFileManager.MAX_INLINE_RAW_BYTES &&
                        attachment.base64Payload.isNotBlank()
                    ) {
                        resolvedProvider = "FIRESTORE_INLINE"
                        resolvedBase64 = attachment.base64Payload
                    } else {
                        val reason = when (uploadRes) {
                            is WorkerResult.Error -> uploadRes.message
                            is WorkerResult.RateLimited -> "Rate limited (${uploadRes.retryAfterSeconds}s)"
                            WorkerResult.NotConfigured -> "Cloudflare Worker URL is not configured"
                            is WorkerResult.Success -> ""
                        }
                        error("Upload failed: $reason")
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        val messageId = "msg_" + UUID.randomUUID().toString().take(12)
        val msgRef = channelRef.collection("messages").document(messageId)
        val senderName = actor.fullName.ifBlank { "Member" }.take(120)
        val senderRole = actor.jobRoleName.ifBlank { actor.accessLevelEnum().label }.take(80)
        val parentMessage = options.parentMessage
        val parentPreview = parentMessage?.let { parent ->
            val excerpt = parent.body.ifBlank {
                parent.attachmentName.ifBlank {
                    parent.eventTitle.ifBlank { parent.kindEnum().label }
                }
            }
            "${parent.senderName.substringBefore(" ")}: ${excerpt.take(120)}"
        }.orEmpty().take(200)

        val linkUrl = ChatFileManager.extractHttpsUrls(cleanBody).firstOrNull().orEmpty()
        val expiresAtMillis = if ((channelObj?.disappearingSeconds ?: 0L) > 0L) {
            now + (channelObj!!.disappearingSeconds * 1000L)
        } else 0L

        val recipients = channelObj?.participantUids.orEmpty().filter { it != actor.uid }
        val nextUnreadMap = (channelObj?.unreadCounts ?: emptyMap()).toMutableMap()
        recipients.forEach { uid ->
            nextUnreadMap[uid] = (nextUnreadMap[uid] ?: 0L) + 1L
        }

        val effectiveKind = options.kind.name
        val msgPayload = hashMapOf<String, Any>(
            "channelId" to effectiveChannelId,
            "kind" to effectiveKind,
            "parentMessageId" to (parentMessage?.id ?: ""),
            "parentPreview" to parentPreview,
            "forwardedFromName" to options.forwardedFromName.take(120),
            "senderUid" to actor.uid,
            "senderName" to senderName,
            "senderRole" to senderRole,
            "body" to cleanBody,
            "linkUrl" to linkUrl.take(400),
            "mentions" to options.mentions.take(25),
            "attachmentType" to (attachment?.type?.name ?: ChatAttachmentType.NONE.name),
            "attachmentName" to (attachment?.fileName?.take(200) ?: ""),
            "attachmentMimeType" to (attachment?.mimeType?.take(120) ?: ""),
            "attachmentSizeBytes" to (attachment?.sizeBytes ?: 0L),
            "attachmentLocalPath" to (attachment?.localCachePath?.take(500) ?: ""),
            "attachmentDataBase64" to resolvedBase64,
            "attachmentBucket" to resolvedBucket.take(64),
            "attachmentStoragePath" to resolvedStoragePath.take(500),
            "attachmentSha256" to resolvedSha256.take(128),
            "storageProvider" to resolvedProvider,
            "isHd" to (attachment?.isHd ?: false),
            "viewOnce" to options.viewOnce,
            "viewedByUids" to emptyList<String>(),
            "expiresAtMillis" to expiresAtMillis,
            "deliveryStatus" to "SENT",
            "deliveredToUids" to emptyList<String>(),
            "readByUids" to emptyList<String>(),
            "audioDurationMs" to (attachment?.durationMs ?: 0L),
            "audioWaveform" to (attachment?.waveform ?: emptyList<Int>()),
            "transcript" to (attachment?.transcript?.take(1000) ?: ""),
            "stickerId" to options.stickerId.take(64),
            "gifId" to options.gifId.take(64),
            "gifTitle" to options.gifTitle.take(120),
            "contactName" to options.contactName.take(120),
            "contactPhone" to options.contactPhone.take(40),
            "locationLat" to options.locationLat,
            "locationLng" to options.locationLng,
            "locationLabel" to options.locationLabel.take(200),
            "liveLocationUntilMillis" to options.liveLocationUntilMillis,
            "eventTitle" to options.eventTitle.take(140),
            "eventLocation" to options.eventLocation.take(160),
            "eventTimeMillis" to options.eventTimeMillis,
            "createdAt" to FieldValue.serverTimestamp()
        )

        val preview = when {
            options.viewOnce -> "🔒 View-once ${attachment?.type?.label ?: "message"}"
            options.stickerId.isNotBlank() -> "Sent a sticker"
            options.gifId.isNotBlank() -> "GIF: ${options.gifTitle.ifBlank { "Reaction" }}"
            options.contactName.isNotBlank() -> "👤 Contact: ${options.contactName}"
            options.liveLocationUntilMillis > now -> "📍 Live location: ${options.locationLabel}"
            options.locationLabel.isNotBlank() -> "📍 Location: ${options.locationLabel}"
            options.eventTitle.isNotBlank() -> "📅 Event: ${options.eventTitle}"
            cleanBody.isNotBlank() && attachment != null -> "$cleanBody • ${attachment.fileName}"
            cleanBody.isNotBlank() -> cleanBody
            attachment != null -> "Shared ${attachment.type.label}: ${attachment.fileName}"
            else -> "Message"
        }.take(240)

        val batch = db.batch()
        batch.set(msgRef, msgPayload)
        batch.update(
            channelRef,
            mapOf(
                "lastMessagePreview" to preview,
                "lastSenderName" to senderName,
                "lastSenderUid" to actor.uid,
                "lastMessageStatus" to "SENT",
                "lastMessageAtMillis" to now,
                "messageCount" to FieldValue.increment(1L),
                "unreadCounts" to nextUnreadMap,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()

        val (targetUid, targetDeptId) = when (channelObj?.channelTypeEnum()) {
            ChatChannelType.DIRECT -> {
                val recipient = channelObj.participantUids.firstOrNull { it != actor.uid } ?: actor.uid
                recipient to ""
            }
            ChatChannelType.DEPARTMENT -> "" to channelObj.departmentId
            else -> "ALL" to ""
        }
        val chTitle = channelObj?.title ?: "Chat"
        if (channelObj?.channelTypeEnum() != ChatChannelType.SAVED) {
            core.createNotification(
                category = NotificationCategory.CHAT,
                title = "New Message • $chTitle",
                body = "$senderName: $preview",
                actor = actor,
                targetUid = targetUid,
                targetDepartmentId = targetDeptId,
                channelId = effectiveChannelId
            )
        }

        return ChatMessage(
            id = messageId,
            channelId = effectiveChannelId,
            kind = effectiveKind,
            parentMessageId = parentMessage?.id ?: "",
            parentPreview = parentPreview,
            forwardedFromName = options.forwardedFromName,
            senderUid = actor.uid,
            senderName = senderName,
            senderRole = senderRole,
            body = cleanBody,
            linkUrl = linkUrl,
            mentions = options.mentions,
            attachmentType = attachment?.type?.name ?: ChatAttachmentType.NONE.name,
            attachmentName = attachment?.fileName ?: "",
            attachmentMimeType = attachment?.mimeType ?: "",
            attachmentSizeBytes = attachment?.sizeBytes ?: 0L,
            attachmentLocalPath = attachment?.localCachePath ?: "",
            attachmentDataBase64 = resolvedBase64,
            attachmentBucket = resolvedBucket,
            attachmentStoragePath = resolvedStoragePath,
            attachmentSha256 = resolvedSha256,
            storageProvider = resolvedProvider,
            isHd = attachment?.isHd ?: false,
            viewOnce = options.viewOnce,
            expiresAtMillis = expiresAtMillis,
            audioDurationMs = attachment?.durationMs ?: 0L,
            audioWaveform = attachment?.waveform ?: emptyList(),
            transcript = attachment?.transcript ?: "",
            stickerId = options.stickerId,
            gifId = options.gifId,
            gifTitle = options.gifTitle,
            contactName = options.contactName,
            contactPhone = options.contactPhone,
            locationLat = options.locationLat,
            locationLng = options.locationLng,
            locationLabel = options.locationLabel,
            liveLocationUntilMillis = options.liveLocationUntilMillis,
            eventTitle = options.eventTitle,
            eventLocation = options.eventLocation,
            eventTimeMillis = options.eventTimeMillis
        )
    }

    suspend fun deleteMessage(channelId: String, messageId: String) {
        val msgRef = sub("chat_channels").document(channelId).collection("messages").document(messageId)
        val snap = runCatching { msgRef.get().await() }.getOrNull()
        val storagePath = snap?.getString("attachmentStoragePath").orEmpty()
        msgRef.delete().await()
        if (storagePath.isNotBlank() && WorkerBridgeClient.isConfigured()) {
            runCatching {
                WorkerBridgeClient.deleteChatFileFromCloudflare(
                    companyId = core.companyId,
                    chatId = channelId,
                    storagePath = storagePath
                )
            }
        }
    }

    suspend fun deleteChannelCascade(channelId: String) {
        val msgSnap = sub("chat_channels").document(channelId).collection("messages").get().await()
        val r2Paths = msgSnap.documents.mapNotNull { it.getString("attachmentStoragePath")?.takeIf(String::isNotBlank) }
        msgSnap.documents.chunked(200).forEach { slice ->
            val batch = db.batch()
            slice.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
        sub("chat_channels").document(channelId).delete().await()
        if (WorkerBridgeClient.isConfigured()) {
            r2Paths.forEach { path ->
                runCatching {
                    WorkerBridgeClient.deleteChatFileFromCloudflare(
                        companyId = core.companyId,
                        chatId = channelId,
                        storagePath = path
                    )
                }
            }
        }
    }

    suspend fun editMessage(channelId: String, messageId: String, newBody: String) {
        val clean = newBody.trim().take(4000)
        if (clean.isBlank()) return
        val url = ChatFileManager.extractHttpsUrls(clean).firstOrNull().orEmpty()
        sub("chat_channels").document(channelId).collection("messages").document(messageId)
            .update(
                mapOf(
                    "body" to clean,
                    "linkUrl" to url,
                    "isEdited" to true,
                    "editedAtMillis" to System.currentTimeMillis()
                )
            ).await()
    }

    suspend fun sendPollMessage(channelId: String, question: String, options: List<String>, actor: Member) {
        val cleanQ = question.trim().take(240)
        val cleanOpts = options.map { it.trim().take(100) }.filter { it.isNotBlank() }.take(6)
        require(cleanQ.isNotBlank() && cleanOpts.size >= 2) { "Poll requires a question and at least 2 options." }
        val channelRef = sub("chat_channels").document(channelId)
        val msgId = "poll_" + UUID.randomUUID().toString().take(10)
        val senderName = actor.fullName.ifBlank { "Member" }.take(120)
        val now = System.currentTimeMillis()
        val payload = mapOf(
            "channelId" to channelId,
            "kind" to ChatMessageKind.POLL.name,
            "parentMessageId" to "",
            "parentPreview" to "",
            "senderUid" to actor.uid,
            "senderName" to senderName,
            "senderRole" to actor.jobRoleName.ifBlank { actor.accessLevelEnum().label }.take(80),
            "body" to "📊 Poll: $cleanQ",
            "attachmentType" to ChatAttachmentType.NONE.name,
            "attachmentName" to "",
            "attachmentMimeType" to "",
            "attachmentSizeBytes" to 0L,
            "attachmentLocalPath" to "",
            "attachmentDataBase64" to "",
            "pollQuestion" to cleanQ,
            "pollOptions" to cleanOpts,
            "pollVotes" to emptyMap<String, List<String>>(),
            "deliveryStatus" to "SENT",
            "createdAt" to FieldValue.serverTimestamp()
        )
        val batch = db.batch()
        batch.set(channelRef.collection("messages").document(msgId), payload)
        batch.update(
            channelRef,
            mapOf(
                "lastMessagePreview" to "📊 Poll: $cleanQ",
                "lastSenderName" to senderName,
                "lastSenderUid" to actor.uid,
                "lastMessageAtMillis" to now,
                "messageCount" to FieldValue.increment(1L),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
    }

    suspend fun votePoll(channelId: String, messageId: String, optionIndex: Int, myUid: String) {
        val msgRef = sub("chat_channels").document(channelId).collection("messages").document(messageId)
        db.runTransaction { tx ->
            val snap = tx.get(msgRef)
            val raw = (snap.get("pollVotes") as? Map<*, *>).orEmpty()
            val updated = mutableMapOf<String, List<String>>()
            raw.forEach { (k, v) ->
                val list = (v as? List<*>)?.mapNotNull { it as? String }?.filter { it != myUid }.orEmpty()
                if (list.isNotEmpty()) updated[k.toString()] = list
            }
            val key = optionIndex.toString()
            val prevForOption = (raw[key] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            if (myUid !in prevForOption) {
                updated[key] = (updated[key].orEmpty() + myUid).distinct()
            }
            tx.update(msgRef, "pollVotes", updated)
        }.await()
    }

    suspend fun rsvpEvent(channelId: String, messageId: String, myUid: String, rsvp: String) {
        val msgRef = sub("chat_channels").document(channelId).collection("messages").document(messageId)
        db.runTransaction { tx ->
            val snap = tx.get(msgRef)
            val raw = (snap.get("eventRsvps") as? Map<*, *>).orEmpty()
            val updated = raw.entries.associate { it.key.toString() to it.value.toString() }.toMutableMap()
            if (updated[myUid] == rsvp) updated.remove(myUid) else updated[myUid] = rsvp
            tx.update(msgRef, "eventRsvps", updated)
        }.await()
    }

    suspend fun reactToMessage(channelId: String, messageId: String, myUid: String, emoji: String) {
        val msgRef = sub("chat_channels").document(channelId).collection("messages").document(messageId)
        db.runTransaction { tx ->
            val snap = tx.get(msgRef)
            val raw = (snap.get("reactions") as? Map<*, *>).orEmpty()
            val updated = raw.entries.associate { it.key.toString() to it.value.toString() }.toMutableMap()
            if (updated[myUid] == emoji) updated.remove(myUid) else updated[myUid] = emoji
            tx.update(msgRef, "reactions", updated)
        }.await()
    }

    suspend fun deleteMessageForEveryone(channelId: String, messageId: String) {
        val chRef = sub("chat_channels").document(channelId)
        val msgRef = chRef.collection("messages").document(messageId)
        val snap = runCatching { msgRef.get().await() }.getOrNull()
        val storagePath = snap?.getString("attachmentStoragePath").orEmpty()
        val oldBody = snap?.getString("body").orEmpty()
        val oldAttach = snap?.getString("attachmentName").orEmpty()

        msgRef.update(
            mapOf(
                "isDeletedForEveryone" to true,
                "body" to "",
                "attachmentType" to ChatAttachmentType.NONE.name,
                "attachmentDataBase64" to "",
                "attachmentName" to "",
                "attachmentStoragePath" to ""
            )
        ).await()

        runCatching {
            val chSnap = chRef.get().await()
            val curPreview = chSnap.getString("lastMessagePreview").orEmpty()
            val matchesDeleted = curPreview.isNotBlank() && (
                (oldBody.isNotBlank() && curPreview.contains(oldBody.take(40))) ||
                    (oldAttach.isNotBlank() && curPreview.contains(oldAttach.take(40)))
                )
            if (matchesDeleted) {
                chRef.update(
                    mapOf(
                        "lastMessagePreview" to "This message was deleted",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        }

        if (storagePath.isNotBlank() && WorkerBridgeClient.isConfigured()) {
            runCatching {
                WorkerBridgeClient.deleteChatFileFromCloudflare(
                    companyId = core.companyId,
                    chatId = channelId,
                    storagePath = storagePath
                )
            }
        }
    }

    suspend fun deleteMessageForMe(channelId: String, messageId: String, uid: String) {
        sub("chat_channels").document(channelId).collection("messages").document(messageId)
            .update("deletedForUids", FieldValue.arrayUnion(uid))
            .await()
    }

    suspend fun toggleStarMessage(channelId: String, messageId: String, uid: String) {
        val msgRef = sub("chat_channels").document(channelId).collection("messages").document(messageId)
        db.runTransaction { tx ->
            val snap = tx.get(msgRef)
            val current = (snap.get("starredByUids") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            val next = if (uid in current) current - uid else current + uid
            tx.update(msgRef, "starredByUids", next.distinct())
        }.await()
    }

    suspend fun togglePinMessage(channelId: String, messageId: String) {
        val chRef = sub("chat_channels").document(channelId)
        val msgRef = chRef.collection("messages").document(messageId)
        db.runTransaction { tx ->
            val snap = tx.get(msgRef)
            val cur = snap.getBoolean("pinned") == true
            val next = !cur
            val body = snap.getString("body").orEmpty().ifBlank { snap.getString("attachmentName").orEmpty() }
            tx.update(msgRef, "pinned", next)
            tx.update(
                chRef,
                mapOf(
                    "pinnedMessageId" to if (next) messageId else "",
                    "pinnedMessagePreview" to if (next) body.take(120) else ""
                )
            )
        }.await()
    }

    suspend fun markMessageViewedOnce(channelId: String, messageId: String, uid: String) {
        sub("chat_channels").document(channelId).collection("messages").document(messageId)
            .update("viewedByUids", FieldValue.arrayUnion(uid))
            .await()
    }

    suspend fun updateLiveLocation(channelId: String, messageId: String, lat: Double, lng: Double, label: String) {
        sub("chat_channels").document(channelId).collection("messages").document(messageId)
            .update(
                mapOf(
                    "locationLat" to lat,
                    "locationLng" to lng,
                    "locationLabel" to label.take(200)
                )
            ).await()
    }

    suspend fun stopLiveLocation(channelId: String, messageId: String) {
        sub("chat_channels").document(channelId).collection("messages").document(messageId)
            .update("liveLocationUntilMillis", System.currentTimeMillis())
            .await()
    }

    suspend fun markMessagesDeliveredAndRead(channelId: String, messages: List<ChatMessage>, myUid: String) {
        if (channelId.isBlank() || myUid.isBlank()) return
        val toMarkRead = messages.filter {
            it.senderUid != myUid && (myUid !in it.readByUids || myUid !in it.deliveredToUids)
        }.takeLast(30)
        if (toMarkRead.isNotEmpty()) {
            val batch = db.batch()
            toMarkRead.forEach { msg ->
                val ref = sub("chat_channels").document(channelId).collection("messages").document(msg.id)
                batch.update(
                    ref,
                    mapOf(
                        "deliveredToUids" to FieldValue.arrayUnion(myUid),
                        "readByUids" to FieldValue.arrayUnion(myUid),
                        "deliveryStatus" to "READ"
                    )
                )
            }
            runCatching { batch.commit().await() }
        }
        markChannelRead(channelId, myUid)
    }

    suspend fun markChannelRead(channelId: String, uid: String) {
        if (channelId.isBlank() || uid.isBlank()) return
        val chRef = sub("chat_channels").document(channelId)
        runCatching {
            db.runTransaction { tx ->
                val snap = tx.get(chRef)
                val map = (snap.get("unreadCounts") as? Map<*, *>).orEmpty()
                    .entries.associate { it.key.toString() to ((it.value as? Number)?.toLong() ?: 0L) }
                    .toMutableMap()
                if ((map[uid] ?: 0L) != 0L) {
                    map[uid] = 0L
                    tx.update(chRef, "unreadCounts", map)
                }
            }.await()
        }
    }

    suspend fun markChannelUnread(channelId: String, uid: String) {
        if (channelId.isBlank() || uid.isBlank()) return
        val chRef = sub("chat_channels").document(channelId)
        db.runTransaction { tx ->
            val snap = tx.get(chRef)
            val map = (snap.get("unreadCounts") as? Map<*, *>).orEmpty()
                .entries.associate { it.key.toString() to ((it.value as? Number)?.toLong() ?: 0L) }
                .toMutableMap()
            val next = ((map[uid] ?: 0L) + 1L).coerceAtLeast(1L)
            map[uid] = next
            tx.update(chRef, "unreadCounts", map)
        }.await()
    }

    suspend fun sendBusinessCardMessage(
        channelId: String,
        kind: String,
        refId: String,
        title: String,
        subtitle: String,
        status: String,
        amountPaise: Long,
        actor: Member
    ) {
        val channelRef = sub("chat_channels").document(channelId)
        val msgId = "biz_" + UUID.randomUUID().toString().take(10)
        val senderName = actor.fullName.ifBlank { "Member" }.take(120)
        val now = System.currentTimeMillis()
        val payload = mapOf(
            "channelId" to channelId,
            "kind" to ChatMessageKind.TEXT.name,
            "parentMessageId" to "",
            "parentPreview" to "",
            "senderUid" to actor.uid,
            "senderName" to senderName,
            "senderRole" to actor.jobRoleName.ifBlank { actor.accessLevelEnum().label }.take(80),
            "body" to "Shared $kind: $title",
            "attachmentType" to ChatAttachmentType.NONE.name,
            "attachmentName" to "",
            "attachmentMimeType" to "",
            "attachmentSizeBytes" to 0L,
            "attachmentLocalPath" to "",
            "attachmentDataBase64" to "",
            "businessRefKind" to kind,
            "businessRefId" to refId,
            "businessRefTitle" to title.take(140),
            "businessRefSubtitle" to subtitle.take(160),
            "businessRefStatus" to status,
            "businessRefAmountPaise" to amountPaise,
            "deliveryStatus" to "SENT",
            "createdAt" to FieldValue.serverTimestamp()
        )
        val batch = db.batch()
        batch.set(channelRef.collection("messages").document(msgId), payload)
        batch.update(
            channelRef,
            mapOf(
                "lastMessagePreview" to "Shared $kind: $title",
                "lastSenderName" to senderName,
                "lastSenderUid" to actor.uid,
                "lastMessageAtMillis" to now,
                "messageCount" to FieldValue.increment(1L),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
    }

    suspend fun togglePinChannel(channelId: String, uid: String) {
        toggleUidArrayField(channelId, "pinnedUids", uid)
    }

    suspend fun toggleMuteChannel(channelId: String, uid: String) {
        toggleUidArrayField(channelId, "mutedUids", uid)
    }

    suspend fun toggleArchiveChannel(channelId: String, uid: String) {
        toggleUidArrayField(channelId, "archivedUids", uid)
    }

    suspend fun toggleFavouriteChannel(channelId: String, uid: String) {
        toggleUidArrayField(channelId, "favouriteUids", uid)
    }

    private suspend fun toggleUidArrayField(channelId: String, field: String, uid: String) {
        val chRef = sub("chat_channels").document(channelId)
        db.runTransaction { tx ->
            val snap = tx.get(chRef)
            val current = (snap.get(field) as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            val next = if (uid in current) current - uid else current + uid
            tx.update(chRef, field, next.distinct())
        }.await()
    }

    suspend fun updateChannelInfo(channelId: String, title: String, topic: String, photoBase64: String? = null) {
        val updates = mutableMapOf<String, Any>(
            "title" to title.trim().ifBlank { "Group" }.take(100),
            "topic" to topic.trim().take(240),
            "description" to topic.trim().take(240),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (photoBase64 != null) {
            updates["photoBase64"] = photoBase64
        }
        sub("chat_channels").document(channelId).update(updates).await()
    }

    suspend fun updateGroupPermissions(
        channelId: String,
        onlyAdminsCanMessage: Boolean,
        onlyAdminsCanEditInfo: Boolean,
        requireAdminApproval: Boolean
    ) {
        sub("chat_channels").document(channelId).update(
            mapOf(
                "onlyAdminsCanMessage" to onlyAdminsCanMessage,
                "onlyAdminsCanEditInfo" to onlyAdminsCanEditInfo,
                "requireAdminApproval" to requireAdminApproval,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun updateDisappearingTimer(channelId: String, seconds: Long) {
        sub("chat_channels").document(channelId).update(
            mapOf(
                "disappearingSeconds" to seconds.coerceAtLeast(0L),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun resetInviteCode(channelId: String): String {
        val code = generateInviteCode()
        sub("chat_channels").document(channelId).update(
            mapOf(
                "inviteCode" to code,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        return code
    }

    suspend fun joinByInviteCode(rawInput: String, actor: Member): Result<Pair<ChatChannel, Boolean>> = runCatching {
        val code = rawInput.trim().substringAfterLast("/").uppercase()
        require(code.length >= 4) { "Invalid invite code or link." }
        val snap = sub("chat_channels")
            .whereEqualTo("inviteCode", code)
            .limit(1)
            .get()
            .await()
        val doc = snap.documents.firstOrNull() ?: error("No active group found for code \"$code\".")
        val ch = doc.toObject(ChatChannel::class.java) ?: error("Unable to read group.")
        if (actor.uid in ch.participantUids) {
            return@runCatching ch to true
        }
        if (ch.requireAdminApproval) {
            doc.reference.update(
                "pendingMemberUids",
                FieldValue.arrayUnion(actor.uid)
            ).await()
            ch to false // joined = pending approval
        } else {
            doc.reference.update(
                "participantUids",
                FieldValue.arrayUnion(actor.uid)
            ).await()
            ch.copy(participantUids = ch.participantUids + actor.uid) to true
        }
    }

    suspend fun approvePendingMember(channelId: String, uid: String) {
        sub("chat_channels").document(channelId).update(
            mapOf(
                "pendingMemberUids" to FieldValue.arrayRemove(uid),
                "participantUids" to FieldValue.arrayUnion(uid)
            )
        ).await()
    }

    suspend fun declinePendingMember(channelId: String, uid: String) {
        sub("chat_channels").document(channelId).update(
            "pendingMemberUids",
            FieldValue.arrayRemove(uid)
        ).await()
    }

    suspend fun addChannelMember(channelId: String, uid: String) {
        sub("chat_channels").document(channelId).update(
            mapOf(
                "participantUids" to FieldValue.arrayUnion(uid),
                "pendingMemberUids" to FieldValue.arrayRemove(uid)
            )
        ).await()
    }

    suspend fun removeChannelMember(channelId: String, uid: String) {
        sub("chat_channels").document(channelId).update(
            mapOf(
                "participantUids" to FieldValue.arrayRemove(uid),
                "adminUids" to FieldValue.arrayRemove(uid)
            )
        ).await()
    }

    suspend fun exitGroup(channelId: String, myUid: String) {
        removeChannelMember(channelId, myUid)
    }

    suspend fun toggleChannelAdmin(channelId: String, uid: String) {
        toggleUidArrayField(channelId, "adminUids", uid)
    }

    suspend fun clearChatHistory(channelId: String, myUid: String) {
        val snap = sub("chat_channels").document(channelId).collection("messages").get().await()
        snap.documents.chunked(200).forEach { slice ->
            val batch = db.batch()
            slice.forEach { doc ->
                batch.update(doc.reference, "deletedForUids", FieldValue.arrayUnion(myUid))
            }
            batch.commit().await()
        }
    }

    suspend fun reportMessageOrChannel(
        channel: ChatChannel,
        message: ChatMessage?,
        reason: String,
        actor: Member
    ) {
        val reportId = "rep_" + UUID.randomUUID().toString().take(10)
        val excerpt = message?.let {
            "${it.senderName}: ${it.body.ifBlank { it.attachmentName }.take(200)}"
        } ?: "Reported channel: ${channel.title}"
        val payload = mapOf(
            "channelId" to channel.id,
            "channelTitle" to channel.title.take(100),
            "messageId" to (message?.id ?: ""),
            "snapshotMessages" to listOf(excerpt),
            "reportedUid" to (message?.senderUid ?: channel.createdByUid),
            "reporterUid" to actor.uid,
            "reporterName" to actor.fullName.ifBlank { "Member" }.take(120),
            "reason" to reason.trim().ifBlank { "Inappropriate content" }.take(400),
            "status" to "OPEN",
            "createdAtMillis" to System.currentTimeMillis(),
            "createdAt" to FieldValue.serverTimestamp()
        )
        sub("chatReports").document(reportId).set(payload).await()
    }
}
