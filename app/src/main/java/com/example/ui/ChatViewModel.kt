package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chat.ChatAudioManager
import com.example.chat.ChatFileManager
import com.example.chat.ChatLocationManager
import com.example.chat.ChatSettingsStore
import com.example.chat.RecordedVoiceDraft
import com.example.chat.StagedChatAttachment
import com.example.chat.realtime.EphemeralChatActivity
import com.example.chat.realtime.MemberPresenceState
import com.example.chat.realtime.RtdbPresenceManager
import com.example.data.AgencyRepository
import com.example.data.ChatRepository
import com.example.data.NewStatusSubmission
import com.example.data.SendMessageOptions
import com.example.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class ChatFilterTab(val label: String) {
    ALL("All"),
    DIRECT("Direct"),
    GROUPS("Groups"),
    UNREAD("Unread"),
    FAVOURITES("Favourites"),
    ARCHIVED("Archived"),
    LOCKED("Locked")
}

data class FailedOutgoingMessage(
    val id: String,
    val channelId: String,
    val body: String,
    val options: SendMessageOptions,
    val reason: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

enum class StatusUploadJobStatus {
    QUEUED,
    UPLOADING,
    FAILED,
    DONE,
    CANCELLED
}

data class StatusUploadJob(
    val id: String,
    val label: String,
    val kind: StatusKind,
    val progress: Float = 0f,
    val status: StatusUploadJobStatus = StatusUploadJobStatus.QUEUED,
    val errorMessage: String = "",
    val submission: NewStatusSubmission? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val coreRepo = AgencyRepository(application)
    val chatRepo = ChatRepository(coreRepo)
    private val rtdb = RtdbPresenceManager

    private val _activeChannel = MutableStateFlow<ChatChannel?>(null)
    val activeChannel: StateFlow<ChatChannel?> = _activeChannel.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _hasPendingWrites = MutableStateFlow(false)
    val hasPendingWrites: StateFlow<Boolean> = _hasPendingWrites.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filterTab = MutableStateFlow(ChatFilterTab.ALL)
    val filterTab: StateFlow<ChatFilterTab> = _filterTab.asStateFlow()

    private val _selectedCustomList = MutableStateFlow<String?>(null)
    val selectedCustomList: StateFlow<String?> = _selectedCustomList.asStateFlow()

    private val _ephemeralActivity = MutableStateFlow(EphemeralChatActivity())
    val ephemeralActivity: StateFlow<EphemeralChatActivity> = _ephemeralActivity.asStateFlow()

    private val _replyTarget = MutableStateFlow<ChatMessage?>(null)
    val replyTarget: StateFlow<ChatMessage?> = _replyTarget.asStateFlow()

    private val _editingMessage = MutableStateFlow<ChatMessage?>(null)
    val editingMessage: StateFlow<ChatMessage?> = _editingMessage.asStateFlow()

    private val _stagedAttachments = MutableStateFlow<List<StagedChatAttachment>>(emptyList())
    val stagedAttachments: StateFlow<List<StagedChatAttachment>> = _stagedAttachments.asStateFlow()

    private val _isRecordingAudio = MutableStateFlow(false)
    val isRecordingAudio: StateFlow<Boolean> = _isRecordingAudio.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0)
    val recordingDurationSec: StateFlow<Int> = _recordingDurationSec.asStateFlow()

    private val _voicePreview = MutableStateFlow<RecordedVoiceDraft?>(null)
    val voicePreview: StateFlow<RecordedVoiceDraft?> = _voicePreview.asStateFlow()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _failedMessages = MutableStateFlow<List<FailedOutgoingMessage>>(emptyList())
    val failedMessages: StateFlow<List<FailedOutgoingMessage>> = _failedMessages.asStateFlow()

    private val _activeLiveLocationMessageId = MutableStateFlow<Pair<String, String>?>(null)
    val activeLiveLocationMessageId: StateFlow<Pair<String, String>?> = _activeLiveLocationMessageId.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _statusUploadJobs = MutableStateFlow<List<StatusUploadJob>>(emptyList())
    val statusUploadJobs: StateFlow<List<StatusUploadJob>> = _statusUploadJobs.asStateFlow()

    private val statusCoroutines = mutableMapOf<String, Job>()
    private val statusHttpCalls = mutableMapOf<String, okhttp3.Call>()

    private var messagesJob: Job? = null
    private var activityJob: Job? = null
    private var currentMyUid: String = ""
    private var recordingTimerJob: Job? = null
    private var liveLocationJob: Job? = null

    init {
        ChatSettingsStore.ensureLoaded(application)
    }

    fun observeMemberPresence(uid: String): Flow<MemberPresenceState> =
        rtdb.observeMemberPresence(coreRepo.companyId, uid)

    fun selectChannel(channel: ChatChannel, myUid: String) {
        currentMyUid = myUid
        _activeChannel.value = channel
        _replyTarget.value = null
        _editingMessage.value = null
        _stagedAttachments.value = emptyList()
        _isRecordingAudio.value = false
        _recordingDurationSec.value = 0

        activityJob?.cancel()
        activityJob = viewModelScope.launch {
            rtdb.observeChatActivity(coreRepo.companyId, channel.id, myUid)
                .catch { emit(EphemeralChatActivity()) }
                .collectLatest { act ->
                    _ephemeralActivity.value = act
                }
        }

        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            chatRepo.observeMessages(channel.id)
                .catch { emit(com.example.data.SyncedData(emptyList(), false)) }
                .collectLatest { synced ->
                    _messages.value = synced.data
                    _hasPendingWrites.value = synced.hasPendingWrites
                    if (synced.data.isNotEmpty() && myUid.isNotBlank()) {
                        chatRepo.markMessagesDeliveredAndRead(channel.id, synced.data, myUid)
                    }
                }
        }
    }

    fun markThreadMessagesRead(channelId: String, messages: List<ChatMessage>, myUid: String) {
        if (channelId.isBlank() || myUid.isBlank()) return
        viewModelScope.launch {
            chatRepo.markMessagesDeliveredAndRead(channelId, messages, myUid)
        }
    }

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    fun setFilterTab(tab: ChatFilterTab) {
        _filterTab.value = tab
        _selectedCustomList.value = null
    }

    fun selectCustomList(listName: String?) {
        _selectedCustomList.value = listName
    }

    fun setReply(message: ChatMessage?) {
        _replyTarget.value = message
        if (message != null) _editingMessage.value = null
    }

    fun setEditing(message: ChatMessage?) {
        _editingMessage.value = message
        if (message != null) _replyTarget.value = null
    }

    fun stageAttachments(attachments: List<StagedChatAttachment>) {
        _stagedAttachments.value = (_stagedAttachments.value + attachments).take(10)
    }

    fun removeStagedAttachment(id: String) {
        _stagedAttachments.value = _stagedAttachments.value.filterNot { it.id == id }
    }

    fun clearStagedAttachments() {
        _stagedAttachments.value = emptyList()
    }

    fun notifyTyping(channelId: String, myUid: String, senderName: String) {
        val uid = myUid.ifBlank { currentMyUid }
        if (uid.isBlank() || channelId.isBlank()) return
        rtdb.setTyping(coreRepo.companyId, channelId, uid, senderName, true)
    }

    fun startVoiceRecording(context: Context, channelId: String, myUid: String, senderName: String) {
        val res = ChatAudioManager.startRecording(context)
        res.onSuccess {
            _voicePreview.value = null
            _isRecordingAudio.value = true
            _recordingDurationSec.value = 0
            rtdb.setRecording(coreRepo.companyId, channelId, myUid, senderName, true)
            recordingTimerJob?.cancel()
            recordingTimerJob = viewModelScope.launch {
                while (_isRecordingAudio.value) {
                    delay(1000)
                    _recordingDurationSec.value += 1
                }
            }
        }.onFailure { err ->
            _statusBanner.value = err.message ?: "Unable to access microphone."
        }
    }

    fun stopRecordingForPreview(channelId: String, myUid: String, senderName: String) {
        _isRecordingAudio.value = false
        recordingTimerJob?.cancel()
        rtdb.setRecording(coreRepo.companyId, channelId, myUid, senderName, false)
        val draft = ChatAudioManager.stopRecording()
        if (draft == null) {
            _statusBanner.value = "Recording too short."
        } else {
            _voicePreview.value = draft
        }
    }

    fun discardVoicePreview() {
        ChatAudioManager.stopPlayback()
        runCatching { _voicePreview.value?.file?.delete() }
        _voicePreview.value = null
    }

    fun transcribeVoiceIntoDraft(context: Context) {
        _isTranscribing.value = true
        ChatAudioManager.startOnDeviceSpeechToText(
            context = context,
            onResult = { spoken ->
                _isTranscribing.value = false
                val cur = _voicePreview.value
                if (cur != null) {
                    _voicePreview.value = cur.copy(transcript = spoken)
                }
                _statusBanner.value = "Transcribed: $spoken"
            },
            onError = { err ->
                _isTranscribing.value = false
                _statusBanner.value = err
            }
        )
    }

    fun sendVoicePreview(
        channelId: String,
        actor: Member,
        viewOnce: Boolean = false,
        onSent: () -> Unit = {}
    ) {
        val draft = _voicePreview.value ?: return
        ChatAudioManager.stopPlayback()
        viewModelScope.launch {
            _isUploading.value = true
            val staged = ChatFileManager.prepareVoiceAttachmentFromFile(
                file = draft.file,
                durationMs = draft.durationMs,
                waveform = draft.waveform,
                transcript = draft.transcript
            ).getOrNull()
            if (staged == null) {
                _isUploading.value = false
                _statusBanner.value = "Failed to prepare voice recording."
                return@launch
            }
            val sec = (draft.durationMs / 1000L).coerceAtLeast(1L)
            val options = SendMessageOptions(
                kind = ChatMessageKind.VOICE,
                parentMessage = _replyTarget.value,
                attachment = staged,
                viewOnce = viewOnce
            )
            runCatching {
                chatRepo.sendRichMessage(channelId, "Voice message (${sec}s)", actor, options)
                _voicePreview.value = null
                _replyTarget.value = null
                _isUploading.value = false
                onSent()
            }.onFailure { err ->
                _isUploading.value = false
                queueFailed(channelId, "Voice message (${sec}s)", options, err.message ?: "Send failed")
                _voicePreview.value = null
                _statusBanner.value = "Voice note failed to send (tap Retry): ${err.message}"
            }
        }
    }

    fun sendMessage(
        channelId: String,
        body: String,
        actor: Member,
        viewOnce: Boolean = false,
        mentions: List<String> = emptyList(),
        onSuccess: () -> Unit = {}
    ) {
        val clean = body.trim()
        val attachments = _stagedAttachments.value
        val editing = _editingMessage.value

        if (editing != null) {
            viewModelScope.launch {
                runCatching {
                    chatRepo.editMessage(channelId, editing.id, clean)
                    _editingMessage.value = null
                    onSuccess()
                }.onFailure { err ->
                    _statusBanner.value = "Failed to edit message: ${err.message}"
                }
            }
            return
        }

        if (clean.isEmpty() && attachments.isEmpty()) return

        viewModelScope.launch {
            _isUploading.value = attachments.isNotEmpty()
            var anyError: String? = null
            if (attachments.isEmpty()) {
                val opts = SendMessageOptions(
                    kind = ChatMessageKind.TEXT,
                    parentMessage = _replyTarget.value,
                    viewOnce = viewOnce,
                    mentions = mentions
                )
                runCatching {
                    chatRepo.sendRichMessage(channelId, clean, actor, opts)
                }.onFailure { err ->
                    anyError = err.message ?: "Network error"
                    queueFailed(channelId, clean, opts, anyError!!)
                }
            } else {
                attachments.forEachIndexed { idx, att ->
                    val caption = if (idx == 0) clean else ""
                    val kind = when {
                        att.type == ChatAttachmentType.IMAGE && att.isHd -> ChatMessageKind.HD_IMAGE
                        att.type == ChatAttachmentType.IMAGE -> ChatMessageKind.IMAGE
                        att.type == ChatAttachmentType.VIDEO && att.isHd -> ChatMessageKind.HD_VIDEO
                        att.type == ChatAttachmentType.VIDEO -> ChatMessageKind.VIDEO
                        att.type == ChatAttachmentType.AUDIO -> ChatMessageKind.AUDIO
                        else -> ChatMessageKind.DOCUMENT
                    }
                    val opts = SendMessageOptions(
                        kind = kind,
                        parentMessage = if (idx == 0) _replyTarget.value else null,
                        attachment = att,
                        viewOnce = viewOnce,
                        mentions = if (idx == 0) mentions else emptyList()
                    )
                    runCatching {
                        chatRepo.sendRichMessage(channelId, caption, actor, opts)
                    }.onFailure { err ->
                        anyError = err.message ?: "Upload failed"
                        queueFailed(channelId, caption, opts, anyError!!)
                    }
                }
            }
            _isUploading.value = false
            _replyTarget.value = null
            _stagedAttachments.value = emptyList()
            if (anyError == null) {
                onSuccess()
            } else {
                _statusBanner.value = "Failed to send (tap Retry in thread): $anyError"
            }
        }
    }

    fun sendRichOptions(
        channelId: String,
        body: String,
        actor: Member,
        options: SendMessageOptions,
        onSent: (ChatMessage) -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching {
                val sent = chatRepo.sendRichMessage(channelId, body, actor, options)
                onSent(sent)
            }.onFailure { err ->
                queueFailed(channelId, body, options, err.message ?: "Failed")
                _statusBanner.value = "Failed to send: ${err.message}"
            }
        }
    }

    fun startLiveLocationSharing(
        context: Context,
        channelId: String,
        durationMinutes: Int,
        actor: Member
    ) {
        viewModelScope.launch {
            val initial = ChatLocationManager.fetchCurrentLocation(context).getOrElse { err ->
                _statusBanner.value = err.message ?: "Unable to fetch location."
                return@launch
            }
            val untilMillis = System.currentTimeMillis() + (durationMinutes * 60_000L)
            val opts = SendMessageOptions(
                kind = ChatMessageKind.LIVE_LOCATION,
                locationLat = initial.latitude,
                locationLng = initial.longitude,
                locationLabel = initial.label,
                liveLocationUntilMillis = untilMillis
            )
            runCatching {
                val sent = chatRepo.sendRichMessage(
                    channelId,
                    "Sharing live location for ${durationMinutes}m",
                    actor,
                    opts
                )
                _activeLiveLocationMessageId.value = channelId to sent.id
                liveLocationJob?.cancel()
                liveLocationJob = viewModelScope.launch {
                    while (System.currentTimeMillis() < untilMillis && _activeLiveLocationMessageId.value == (channelId to sent.id)) {
                        delay(20_000L)
                        val loc = ChatLocationManager.fetchCurrentLocation(context).getOrNull() ?: continue
                        runCatching {
                            chatRepo.updateLiveLocation(channelId, sent.id, loc.latitude, loc.longitude, loc.label)
                        }
                    }
                    if (_activeLiveLocationMessageId.value == (channelId to sent.id)) {
                        _activeLiveLocationMessageId.value = null
                    }
                }
            }.onFailure { err ->
                _statusBanner.value = "Failed to share live location: ${err.message}"
            }
        }
    }

    fun stopLiveLocation(channelId: String, messageId: String) {
        if (_activeLiveLocationMessageId.value == (channelId to messageId)) {
            liveLocationJob?.cancel()
            _activeLiveLocationMessageId.value = null
        }
        viewModelScope.launch {
            runCatching { chatRepo.stopLiveLocation(channelId, messageId) }
        }
    }

    private fun queueFailed(channelId: String, body: String, options: SendMessageOptions, reason: String) {
        val item = FailedOutgoingMessage(
            id = "fail_" + java.util.UUID.randomUUID().toString().take(8),
            channelId = channelId,
            body = body,
            options = options,
            reason = reason
        )
        _failedMessages.value = _failedMessages.value + item
    }

    fun retryFailedMessage(failed: FailedOutgoingMessage, actor: Member) {
        _failedMessages.value = _failedMessages.value.filterNot { it.id == failed.id }
        viewModelScope.launch {
            runCatching {
                chatRepo.sendRichMessage(failed.channelId, failed.body, actor, failed.options)
            }.onFailure { err ->
                queueFailed(failed.channelId, failed.body, failed.options, err.message ?: "Retry failed")
                _statusBanner.value = "Retry failed: ${err.message}"
            }
        }
    }

    fun dismissFailedMessage(id: String) {
        _failedMessages.value = _failedMessages.value.filterNot { it.id == id }
    }

    fun forwardMessage(message: ChatMessage, targetChannel: ChatChannel, actor: Member, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                val opts = SendMessageOptions(
                    kind = message.kindEnum(),
                    forwardedFromName = message.senderName,
                    stickerId = message.stickerId,
                    gifId = message.gifId,
                    gifTitle = message.gifTitle,
                    contactName = message.contactName,
                    contactPhone = message.contactPhone,
                    locationLat = message.locationLat,
                    locationLng = message.locationLng,
                    locationLabel = message.locationLabel
                )
                val body = message.body.ifBlank { message.attachmentName.ifBlank { message.kindEnum().label } }
                chatRepo.sendRichMessage(targetChannel.id, body, actor, opts)
                _statusBanner.value = "Forwarded to ${targetChannel.title}"
                onDone()
            }.onFailure { err ->
                _statusBanner.value = "Forward failed: ${err.message}"
            }
        }
    }

    fun sendPoll(channelId: String, question: String, options: List<String>, actor: Member, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                chatRepo.sendPollMessage(channelId, question, options, actor)
                onSuccess()
            }.onFailure { err ->
                _statusBanner.value = "Unable to create poll: ${err.message}"
            }
        }
    }

    fun votePoll(channelId: String, messageId: String, optionIndex: Int, myUid: String) {
        viewModelScope.launch { runCatching { chatRepo.votePoll(channelId, messageId, optionIndex, myUid) } }
    }

    fun rsvpEvent(channelId: String, messageId: String, myUid: String, rsvp: String) {
        viewModelScope.launch { runCatching { chatRepo.rsvpEvent(channelId, messageId, myUid, rsvp) } }
    }

    fun reactToMessage(channelId: String, messageId: String, emoji: String, myUid: String) {
        viewModelScope.launch { runCatching { chatRepo.reactToMessage(channelId, messageId, myUid, emoji) } }
    }

    fun deleteForEveryone(channelId: String, messageId: String) {
        viewModelScope.launch { runCatching { chatRepo.deleteMessageForEveryone(channelId, messageId) } }
    }

    fun deleteForMe(channelId: String, messageId: String, myUid: String) {
        viewModelScope.launch { runCatching { chatRepo.deleteMessageForMe(channelId, messageId, myUid) } }
    }

    fun toggleStar(channelId: String, messageId: String, myUid: String) {
        viewModelScope.launch { runCatching { chatRepo.toggleStarMessage(channelId, messageId, myUid) } }
    }

    fun togglePin(channelId: String, messageId: String) {
        viewModelScope.launch { runCatching { chatRepo.togglePinMessage(channelId, messageId) } }
    }

    fun markViewedOnce(channelId: String, messageId: String, myUid: String) {
        viewModelScope.launch { runCatching { chatRepo.markMessageViewedOnce(channelId, messageId, myUid) } }
    }

    fun sendBusinessCard(
        channelId: String,
        kind: String,
        refId: String,
        title: String,
        subtitle: String,
        status: String,
        amountPaise: Long,
        actor: Member,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching {
                chatRepo.sendBusinessCardMessage(channelId, kind, refId, title, subtitle, status, amountPaise, actor)
                onSuccess()
            }.onFailure { err ->
                _statusBanner.value = "Failed to share business card: ${err.message}"
            }
        }
    }

    fun toggleChannelPin(channelId: String, myUid: String) {
        viewModelScope.launch { runCatching { chatRepo.togglePinChannel(channelId, myUid) } }
    }

    fun toggleChannelMute(channelId: String, myUid: String) {
        viewModelScope.launch { runCatching { chatRepo.toggleMuteChannel(channelId, myUid) } }
    }

    fun toggleChannelArchive(channelId: String, myUid: String) {
        viewModelScope.launch { runCatching { chatRepo.toggleArchiveChannel(channelId, myUid) } }
    }

    fun toggleChannelFavourite(channelId: String, myUid: String) {
        viewModelScope.launch { runCatching { chatRepo.toggleFavouriteChannel(channelId, myUid) } }
    }

    fun markChannelReadUnread(channelId: String, myUid: String, markAsUnread: Boolean) {
        viewModelScope.launch {
            runCatching {
                if (markAsUnread) chatRepo.markChannelUnread(channelId, myUid)
                else chatRepo.markChannelRead(channelId, myUid)
            }
        }
    }

    fun updateChannelInfo(channelId: String, title: String, topic: String, photoBase64: String? = null) {
        viewModelScope.launch {
            runCatching { chatRepo.updateChannelInfo(channelId, title, topic, photoBase64) }
                .onSuccess { _statusBanner.value = "Group details updated" }
                .onFailure { _statusBanner.value = it.message ?: "Unable to update group" }
        }
    }

    fun updateGroupPermissions(
        channelId: String,
        onlyAdminsCanMessage: Boolean,
        onlyAdminsCanEditInfo: Boolean,
        requireAdminApproval: Boolean
    ) {
        viewModelScope.launch {
            runCatching {
                chatRepo.updateGroupPermissions(channelId, onlyAdminsCanMessage, onlyAdminsCanEditInfo, requireAdminApproval)
            }
        }
    }

    fun updateDisappearingTimer(channelId: String, seconds: Long) {
        viewModelScope.launch {
            runCatching { chatRepo.updateDisappearingTimer(channelId, seconds) }
                .onSuccess {
                    val label = when (seconds) {
                        86_400L -> "24 hours"
                        604_800L -> "7 days"
                        7_776_000L -> "90 days"
                        else -> "Off"
                    }
                    _statusBanner.value = "Disappearing messages: $label"
                }
        }
    }

    fun resetInviteLink(channelId: String) {
        viewModelScope.launch {
            runCatching { chatRepo.resetInviteCode(channelId) }
                .onSuccess { _statusBanner.value = "Invite link reset" }
        }
    }

    fun joinByInviteCode(code: String, actor: Member, onJoined: (ChatChannel) -> Unit) {
        viewModelScope.launch {
            chatRepo.joinByInviteCode(code, actor)
                .onSuccess { (ch, immediate) ->
                    if (immediate) {
                        _statusBanner.value = "Joined ${ch.title}"
                        onJoined(ch)
                    } else {
                        _statusBanner.value = "Join request sent to ${ch.title} admins"
                    }
                }
                .onFailure { _statusBanner.value = it.message ?: "Invalid invite link" }
        }
    }

    fun approvePendingMember(channelId: String, uid: String) {
        viewModelScope.launch { runCatching { chatRepo.approvePendingMember(channelId, uid) } }
    }

    fun declinePendingMember(channelId: String, uid: String) {
        viewModelScope.launch { runCatching { chatRepo.declinePendingMember(channelId, uid) } }
    }

    fun addChannelMember(channelId: String, memberUid: String) {
        viewModelScope.launch { runCatching { chatRepo.addChannelMember(channelId, memberUid) } }
    }

    fun removeChannelMember(channelId: String, memberUid: String) {
        viewModelScope.launch { runCatching { chatRepo.removeChannelMember(channelId, memberUid) } }
    }

    fun exitGroup(channelId: String, myUid: String, onExited: () -> Unit) {
        viewModelScope.launch {
            runCatching { chatRepo.exitGroup(channelId, myUid) }
                .onSuccess {
                    _statusBanner.value = "Exited group"
                    onExited()
                }
        }
    }

    fun toggleChannelAdmin(channelId: String, memberUid: String) {
        viewModelScope.launch { runCatching { chatRepo.toggleChannelAdmin(channelId, memberUid) } }
    }

    fun clearChatHistory(channelId: String, myUid: String) {
        viewModelScope.launch {
            runCatching { chatRepo.clearChatHistory(channelId, myUid) }
                .onSuccess { _statusBanner.value = "Chat history cleared" }
        }
    }

    fun reportContent(channel: ChatChannel, message: ChatMessage?, reason: String, actor: Member) {
        viewModelScope.launch {
            runCatching { chatRepo.reportMessageOrChannel(channel, message, reason, actor) }
                .onSuccess { _statusBanner.value = "Report submitted to workspace admin" }
                .onFailure { _statusBanner.value = it.message ?: "Failed to report" }
        }
    }

    fun postStatus(text: String, backgroundPreset: Int, imageBase64: String, actor: Member, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { chatRepo.postStatus(text, backgroundPreset, imageBase64, actor) }
                .onSuccess {
                    _statusBanner.value = "Status posted for 24h"
                    onDone()
                }
                .onFailure { _statusBanner.value = it.message ?: "Unable to post status" }
        }
    }

    fun enqueueStatusUploads(
        submissions: List<NewStatusSubmission>,
        actor: Member,
        onQueued: () -> Unit = {}
    ) {
        if (submissions.isEmpty()) return
        val createdJobs = submissions.mapIndexed { idx, sub ->
            val jobId = "stjob_${System.currentTimeMillis()}_$idx"
            val label = when (sub.kind) {
                StatusKind.TEXT -> sub.text.take(28).ifBlank { "Text status" }
                StatusKind.IMAGE -> sub.caption.take(24).ifBlank { "Photo status ${idx + 1}" }
                StatusKind.VIDEO -> sub.caption.take(24).ifBlank { "Video status ${idx + 1}" }
                StatusKind.VOICE -> sub.caption.take(24).ifBlank { "Voice status ${idx + 1}" }
            }
            StatusUploadJob(
                id = jobId,
                label = label,
                kind = sub.kind,
                progress = 0.05f,
                status = StatusUploadJobStatus.QUEUED,
                submission = sub
            )
        }
        _statusUploadJobs.value = createdJobs + _statusUploadJobs.value
        onQueued()

        createdJobs.forEach { job ->
            launchSingleStatusUpload(job, actor)
        }
    }

    private fun launchSingleStatusUpload(job: StatusUploadJob, actor: Member) {
        val sub = job.submission ?: return
        val coroutineJob = viewModelScope.launch {
            updateUploadJob(job.id) {
                it.copy(status = StatusUploadJobStatus.UPLOADING, progress = 0.08f, errorMessage = "")
            }
            try {
                chatRepo.postRichStatus(
                    submission = sub,
                    actor = actor,
                    progressKey = job.id,
                    onProgress = { p ->
                        updateUploadJob(job.id) { cur ->
                            if (cur.status == StatusUploadJobStatus.CANCELLED) cur
                            else cur.copy(progress = p.coerceIn(0.05f, 1f))
                        }
                    },
                    onCallCreated = { call ->
                        statusHttpCalls[job.id] = call
                    }
                )
                statusHttpCalls.remove(job.id)
                statusCoroutines.remove(job.id)
                updateUploadJob(job.id) {
                    it.copy(status = StatusUploadJobStatus.DONE, progress = 1f)
                }
                _statusBanner.value = "${sub.kind.label} status shared for 24h"
                delay(1500L)
                _statusUploadJobs.value = _statusUploadJobs.value.filterNot { it.id == job.id && it.status == StatusUploadJobStatus.DONE }
            } catch (_: CancellationException) {
                statusHttpCalls.remove(job.id)
                statusCoroutines.remove(job.id)
                updateUploadJob(job.id) {
                    it.copy(status = StatusUploadJobStatus.CANCELLED, errorMessage = "Upload cancelled")
                }
            } catch (err: Throwable) {
                statusHttpCalls.remove(job.id)
                statusCoroutines.remove(job.id)
                updateUploadJob(job.id) {
                    it.copy(
                        status = StatusUploadJobStatus.FAILED,
                        errorMessage = err.message ?: "Upload failed"
                    )
                }
            }
        }
        statusCoroutines[job.id] = coroutineJob
    }

    fun cancelStatusUpload(jobId: String) {
        statusHttpCalls.remove(jobId)?.cancel()
        statusCoroutines.remove(jobId)?.cancel()
        _statusUploadJobs.value = _statusUploadJobs.value.filterNot { it.id == jobId }
    }

    fun retryStatusUpload(jobId: String, actor: Member) {
        val job = _statusUploadJobs.value.firstOrNull { it.id == jobId } ?: return
        launchSingleStatusUpload(job, actor)
    }

    fun dismissStatusUploadJob(jobId: String) {
        _statusUploadJobs.value = _statusUploadJobs.value.filterNot { it.id == jobId }
    }

    private fun updateUploadJob(jobId: String, transform: (StatusUploadJob) -> StatusUploadJob) {
        _statusUploadJobs.value = _statusUploadJobs.value.map { cur ->
            if (cur.id == jobId) transform(cur) else cur
        }
    }

    fun markStatusViewed(statusId: String, myUid: String) {
        viewModelScope.launch { runCatching { chatRepo.markStatusViewed(statusId, myUid) } }
    }

    fun reactToStatusAndSendDm(
        status: ChatStatus,
        ownerMember: Member,
        actor: Member,
        emoji: String,
        onChannelReady: (ChatChannel) -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching {
                chatRepo.reactToStatus(status.id, actor.uid, emoji)
                chatRepo.sendStatusInteractionToDm(
                    ownerMember = ownerMember,
                    actor = actor,
                    status = status,
                    replyText = "",
                    reactionEmoji = emoji
                )
            }.onSuccess { ch ->
                _statusBanner.value = "Reacted $emoji to ${ownerMember.fullName.substringBefore(" ")}'s status"
                onChannelReady(ch)
            }.onFailure { err ->
                _statusBanner.value = err.message ?: "Unable to react to status"
            }
        }
    }

    fun replyToStatusInDm(
        status: ChatStatus,
        ownerMember: Member,
        actor: Member,
        replyText: String,
        onChannelReady: (ChatChannel) -> Unit = {}
    ) {
        val clean = replyText.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            runCatching {
                chatRepo.sendStatusInteractionToDm(
                    ownerMember = ownerMember,
                    actor = actor,
                    status = status,
                    replyText = clean,
                    reactionEmoji = ""
                )
            }.onSuccess { ch ->
                _statusBanner.value = "Reply sent to ${ownerMember.fullName.substringBefore(" ")}"
                onChannelReady(ch)
            }.onFailure { err ->
                _statusBanner.value = err.message ?: "Unable to reply to status"
            }
        }
    }

    fun syncBlockedAndPrivacyToMyStatuses(
        activeMyStatuses: List<ChatStatus>,
        privacyMode: StatusPrivacyMode,
        allowedUids: List<String>,
        excludedUids: List<String>,
        blockedUids: List<String>
    ) {
        viewModelScope.launch {
            runCatching {
                chatRepo.updateMyActiveStatusesPrivacyAndBlocks(
                    activeMyStatuses = activeMyStatuses,
                    privacyMode = privacyMode,
                    allowedUids = allowedUids,
                    excludedUids = excludedUids,
                    blockedUids = blockedUids
                )
            }
        }
    }

    fun reportStatus(
        status: ChatStatus,
        reason: String,
        actor: Member,
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching { chatRepo.reportStatus(status, reason, actor) }
                .onSuccess {
                    _statusBanner.value = "Status report sent to admin"
                    onComplete(true)
                }
                .onFailure {
                    _statusBanner.value = it.message ?: "Failed to report status"
                    onComplete(false)
                }
        }
    }

    fun deleteStatus(
        statusId: String,
        storagePath: String = "",
        onDeleted: () -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching { chatRepo.deleteStatus(statusId, storagePath) }
                .onSuccess {
                    _statusBanner.value = "Status deleted"
                    onDeleted()
                }
                .onFailure { _statusBanner.value = it.message ?: "Unable to delete status" }
        }
    }

    fun deleteAllMyStatuses(
        myStatuses: List<ChatStatus>,
        onDeleted: () -> Unit = {}
    ) {
        if (myStatuses.isEmpty()) return
        viewModelScope.launch {
            runCatching { chatRepo.deleteAllStatuses(myStatuses) }
                .onSuccess {
                    _statusBanner.value = "All active statuses deleted"
                    onDeleted()
                }
                .onFailure { _statusBanner.value = it.message ?: "Unable to delete statuses" }
        }
    }

    fun openOrStartDm(
        targetMember: Member,
        actor: Member,
        onReady: (ChatChannel) -> Unit
    ) {
        viewModelScope.launch {
            runCatching { chatRepo.createOrGetDirectChannel(targetMember, actor) }
                .onSuccess { ch -> onReady(ch) }
                .onFailure { err -> _statusBanner.value = err.message ?: "Unable to open direct chat" }
        }
    }

    fun clearBanner() {
        _statusBanner.value = null
    }
}
