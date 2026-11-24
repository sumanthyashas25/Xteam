package com.example.data

import com.example.chat.StagedChatAttachment
import com.example.model.*
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.tasks.await
import java.util.UUID

class ChatRepository(private val core: AgencyRepository) {

    private val db get() = core.db
    private fun sub(name: String) = core.sub(name)
    private val est = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE

    companion object {
        const val GENERAL_CHANNEL_ID = "ch_general"

        fun directChannelId(uid1: String, uid2: String): String {
            val sortedUids = listOf(uid1, uid2).distinct().sorted()
            val rawSuffix = sortedUids.joinToString("_").replace(Regex("[^A-Za-z0-9_-]"), "_")
            return "ch_dm_$rawSuffix".take(120)
        }

        fun departmentChannelId(deptId: String): String {
            val safeDept = deptId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            return "ch_dept_$safeDept".take(120)
        }
    }

    fun observeChannels(myUid: String, isSuperAdmin: Boolean): Flow<List<ChatChannel>> {
        if (isSuperAdmin) {
            return callbackFlow {
                val reg = sub("chat_channels").addSnapshotListener { snap, err ->
                    if (err != null) {
                        handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/chat_channels")
                        close(err)
                        return@addSnapshotListener
                    }
                    val list = snap?.documents?.mapNotNull { doc ->
                        doc.get("createdAt", est)
                        doc.get("updatedAt", est)
                        doc.toObject(ChatChannel::class.java)
                    }.orEmpty().sortedByDescending { it.lastMessageAtMillis }
                    trySend(list)
                }
                awaitClose { reg.remove() }
            }
        }

        val sharedFlow = callbackFlow<List<ChatChannel>> {
            val reg = sub("chat_channels")
                .whereIn(
                    "channelType",
                    listOf(
                        ChatChannelType.GENERAL.name,
                        ChatChannelType.PROJECT.name,
                        ChatChannelType.DEPARTMENT.name
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

        val dmFlow = callbackFlow<List<ChatChannel>> {
            val reg = sub("chat_channels")
                .whereArrayContains("participantUids", myUid)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/chat_channels?dm=$myUid")
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

        return combine(sharedFlow, dmFlow) { shared, dms ->
            (shared + dms)
                .distinctBy { it.id }
                .sortedByDescending { it.lastMessageAtMillis }
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
            .limitToLast(100)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/chat_channels/$channelId/messages")
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.get("createdAt", est)
                    doc.toObject(ChatMessage::class.java)
                }.orEmpty()
                val pending = snap?.metadata?.hasPendingWrites() == true
                trySend(SyncedData(list, pending))
            }
        awaitClose { reg.remove() }
    }

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
            "channelType" to ChatChannelType.GENERAL.name,
            "projectId" to "",
            "projectTitle" to "",
            "departmentId" to "",
            "departmentName" to "",
            "participantUids" to emptyList<String>(),
            "createdByUid" to actor.uid,
            "createdByName" to actor.fullName.ifBlank { "Member" }.take(120),
            "lastMessagePreview" to "Channel ready for team messages & file uploads",
            "lastSenderName" to actor.fullName.ifBlank { "Member" }.take(120),
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
            createdByUid = actor.uid,
            createdByName = actor.fullName.ifBlank { "Member" },
            lastMessagePreview = "Channel ready for team messages & file uploads",
            lastSenderName = actor.fullName.ifBlank { "Member" },
            lastMessageAtMillis = now,
            messageCount = 0L
        )
    }

    suspend fun createOrGetChannel(
        title: String,
        topic: String,
        channelType: ChatChannelType,
        project: Project?,
        actor: Member
    ): ChatChannel {
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

        val cleanTitle = title.trim().ifBlank { project?.title ?: "Team Thread" }.take(100)
        val cleanTopic = topic.trim().ifBlank {
            if (project != null) "Project files & team updates for ${project.clientCompanyName}" else "Shared team thread"
        }.take(240)
        val now = System.currentTimeMillis()
        val payload = mapOf(
            "title" to cleanTitle,
            "topic" to cleanTopic,
            "channelType" to channelType.name,
            "projectId" to (project?.id ?: ""),
            "projectTitle" to (project?.title ?: "").take(160),
            "departmentId" to "",
            "departmentName" to "",
            "participantUids" to emptyList<String>(),
            "createdByUid" to actor.uid,
            "createdByName" to actor.fullName.ifBlank { "Member" }.take(120),
            "lastMessagePreview" to "Created $cleanTitle",
            "lastSenderName" to actor.fullName.ifBlank { "Member" }.take(120),
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
            channelType = channelType.name,
            projectId = project?.id ?: "",
            projectTitle = project?.title ?: "",
            createdByUid = actor.uid,
            createdByName = actor.fullName.ifBlank { "Member" },
            lastMessagePreview = "Created $cleanTitle",
            lastSenderName = actor.fullName.ifBlank { "Member" },
            lastMessageAtMillis = now,
            messageCount = 0L
        )
    }

    suspend fun createOrGetDirectChannel(otherMember: Member, actor: Member): ChatChannel {
        val sortedUids = listOf(actor.uid, otherMember.uid).distinct().sorted()
        val rawSuffix = sortedUids.joinToString("_").replace(Regex("[^A-Za-z0-9_-]"), "_")
        val channelId = "ch_dm_$rawSuffix".take(120)
        val ref = sub("chat_channels").document(channelId)
        val existing = runCatching { ref.get().await() }.getOrNull()
        if (existing != null && existing.exists()) {
            existing.toObject(ChatChannel::class.java)?.let { return it }
        }
        val cleanTitle = "${actor.fullName.substringBefore(" ")} & ${otherMember.fullName}".take(100)
        val cleanTopic = "Personal Chat • ${otherMember.jobRoleName.ifBlank { otherMember.accessLevelEnum().label }}".take(240)
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
            lastMessageAtMillis = now,
            messageCount = 0L
        )
    }

    suspend fun createOrGetDepartmentChannel(dept: Department, actor: Member): ChatChannel {
        val safeDept = dept.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val channelId = "ch_dept_$safeDept".take(120)
        val ref = sub("chat_channels").document(channelId)
        val existing = runCatching { ref.get().await() }.getOrNull()
        if (existing != null && existing.exists()) {
            existing.toObject(ChatChannel::class.java)?.let { return it }
        }
        val cleanTitle = "${dept.name} Dept".take(100)
        val cleanTopic = dept.description.ifBlank { "${dept.name} department team collaboration & handoffs" }.take(240)
        val now = System.currentTimeMillis()
        val payload = mapOf(
            "title" to cleanTitle,
            "topic" to cleanTopic,
            "channelType" to ChatChannelType.DEPARTMENT.name,
            "projectId" to "",
            "projectTitle" to "",
            "departmentId" to dept.id,
            "departmentName" to dept.name.take(80),
            "participantUids" to emptyList<String>(),
            "createdByUid" to actor.uid,
            "createdByName" to actor.fullName.ifBlank { "Member" }.take(120),
            "lastMessagePreview" to "${dept.name} department chat ready",
            "lastSenderName" to actor.fullName.ifBlank { "Member" }.take(120),
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
            channelType = ChatChannelType.DEPARTMENT.name,
            departmentId = dept.id,
            departmentName = dept.name,
            createdByUid = actor.uid,
            createdByName = actor.fullName.ifBlank { "Member" },
            lastMessagePreview = "${dept.name} department chat ready",
            lastSenderName = actor.fullName.ifBlank { "Member" },
            lastMessageAtMillis = now,
            messageCount = 0L
        )
    }

    suspend fun sendMessage(
        channelId: String,
        body: String,
        parentMessage: ChatMessage?,
        attachment: StagedChatAttachment?,
        actor: Member
    ): ChatMessage {
        val cleanBody = body.trim().take(4000)
        require(cleanBody.isNotEmpty() || attachment != null) {
            "Enter a message or attach a file before sending."
        }

        val effectiveChannelId = channelId.ifBlank { GENERAL_CHANNEL_ID }
        val channelRef = sub("chat_channels").document(effectiveChannelId)
        val channelSnap = channelRef.get().await()
        if (!channelSnap.exists() && effectiveChannelId == GENERAL_CHANNEL_ID) {
            ensureDefaultGeneralChannel(actor)
        }
        val channelObj = channelSnap.toObject(ChatChannel::class.java)

        val messageId = "msg_" + UUID.randomUUID().toString().take(12)
        val msgRef = channelRef.collection("messages").document(messageId)
        val senderName = actor.fullName.ifBlank { "Member" }.take(120)
        val senderRole = actor.jobRoleName.ifBlank { actor.accessLevelEnum().label }.take(80)
        val parentPreview = parentMessage?.let { parent ->
            val excerpt = parent.body.ifBlank { parent.attachmentName.ifBlank { parent.attachmentTypeEnum().label } }
            "${parent.senderName.substringBefore(" ")}: ${excerpt.take(120)}"
        }.orEmpty().take(200)

        val msgPayload = mapOf(
            "channelId" to effectiveChannelId,
            "parentMessageId" to (parentMessage?.id ?: ""),
            "parentPreview" to parentPreview,
            "senderUid" to actor.uid,
            "senderName" to senderName,
            "senderRole" to senderRole,
            "body" to cleanBody,
            "attachmentType" to (attachment?.type?.name ?: ChatAttachmentType.NONE.name),
            "attachmentName" to (attachment?.fileName?.take(200) ?: ""),
            "attachmentMimeType" to (attachment?.mimeType?.take(120) ?: ""),
            "attachmentSizeBytes" to (attachment?.sizeBytes ?: 0L),
            "attachmentLocalPath" to (attachment?.localCachePath?.take(500) ?: ""),
            "attachmentDataBase64" to (attachment?.base64Payload ?: ""),
            "createdAt" to FieldValue.serverTimestamp()
        )

        val preview = when {
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
                "lastMessageAtMillis" to System.currentTimeMillis(),
                "messageCount" to FieldValue.increment(1L),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()

        // Emit real-time notification with sound
        val (targetUid, targetDeptId) = when (channelObj?.channelTypeEnum()) {
            ChatChannelType.DIRECT -> {
                val recipient = channelObj.participantUids.firstOrNull { it != actor.uid } ?: actor.uid
                recipient to ""
            }
            ChatChannelType.DEPARTMENT -> "" to channelObj.departmentId
            else -> "ALL" to ""
        }
        val chTitle = channelObj?.title ?: "Chat"
        core.createNotification(
            category = NotificationCategory.CHAT,
            title = "New Message • $chTitle",
            body = "$senderName: $preview",
            actor = actor,
            targetUid = targetUid,
            targetDepartmentId = targetDeptId,
            channelId = effectiveChannelId
        )

        return ChatMessage(
            id = messageId,
            channelId = effectiveChannelId,
            parentMessageId = parentMessage?.id ?: "",
            parentPreview = parentPreview,
            senderUid = actor.uid,
            senderName = senderName,
            senderRole = senderRole,
            body = cleanBody,
            attachmentType = attachment?.type?.name ?: ChatAttachmentType.NONE.name,
            attachmentName = attachment?.fileName ?: "",
            attachmentMimeType = attachment?.mimeType ?: "",
            attachmentSizeBytes = attachment?.sizeBytes ?: 0L,
            attachmentLocalPath = attachment?.localCachePath ?: "",
            attachmentDataBase64 = attachment?.base64Payload ?: ""
        )
    }

    suspend fun deleteMessage(channelId: String, messageId: String) {
        sub("chat_channels").document(channelId).collection("messages").document(messageId).delete().await()
    }

    suspend fun deleteChannelCascade(channelId: String) {
        val msgSnap = sub("chat_channels").document(channelId).collection("messages").get().await()
        msgSnap.documents.chunked(200).forEach { slice ->
            val batch = db.batch()
            slice.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
        sub("chat_channels").document(channelId).delete().await()
    }

    suspend fun editMessage(channelId: String, messageId: String, newBody: String) {
        sub("chat_channels").document(channelId).collection("messages").document(messageId)
            .update(mapOf("body" to newBody, "updatedAt" to FieldValue.serverTimestamp())).await()
    }

    suspend fun sendPollMessage(channelId: String, question: String, options: List<String>, actor: Member) {}
    suspend fun votePoll(channelId: String, messageId: String, optionIndex: Int, myUid: String) {}
    suspend fun reactToMessage(channelId: String, messageId: String, myUid: String, emoji: String) {}
    suspend fun deleteMessageForEveryone(channelId: String, messageId: String) { deleteMessage(channelId, messageId) }
    suspend fun deleteMessageForMe(channelId: String, messageId: String, uid: String) {}
    suspend fun toggleStarMessage(channelId: String, messageId: String, uid: String) {}
    suspend fun togglePinMessage(channelId: String, messageId: String) {}
    suspend fun sendBusinessCardMessage(
        channelId: String,
        kind: String,
        refId: String,
        title: String,
        subtitle: String,
        status: String,
        amountPaise: Long,
        actor: Member
    ) {}
    suspend fun togglePinChannel(channelId: String, uid: String) {}
    suspend fun toggleMuteChannel(channelId: String, uid: String) {}
    suspend fun toggleArchiveChannel(channelId: String, uid: String) {}
    suspend fun toggleFavouriteChannel(channelId: String, uid: String) {}
    suspend fun updateChannelInfo(channelId: String, title: String, topic: String, photoUrl: String = "") {}
    suspend fun addChannelMember(channelId: String, uid: String) {}
    suspend fun removeChannelMember(channelId: String, uid: String) {}
    suspend fun toggleChannelAdmin(channelId: String, uid: String) {}
}

