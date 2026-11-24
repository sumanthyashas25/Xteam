package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chat.StagedChatAttachment
import com.example.chat.local.ChatDraftEntity
import com.example.chat.local.XMaxChatDatabase
import com.example.chat.realtime.EphemeralChatActivity
import com.example.chat.realtime.RtdbPresenceManager
import com.example.data.AgencyRepository
import com.example.data.ChatRepository
import com.example.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ChatFilterTab(val label: String) {
    ALL("All"),
    UNREAD("Unread"),
    GROUPS("Groups"),
    FAVOURITES("Favourites")
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val coreRepo = AgencyRepository(application)
    val chatRepo = ChatRepository(coreRepo)
    private val localDb = XMaxChatDatabase.getInstance(application)
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

    private val _ephemeralActivity = MutableStateFlow(EphemeralChatActivity())
    val ephemeralActivity: StateFlow<EphemeralChatActivity> = _ephemeralActivity.asStateFlow()

    private val _replyTarget = MutableStateFlow<ChatMessage?>(null)
    val replyTarget: StateFlow<ChatMessage?> = _replyTarget.asStateFlow()

    private val _editingMessage = MutableStateFlow<ChatMessage?>(null)
    val editingMessage: StateFlow<ChatMessage?> = _editingMessage.asStateFlow()

    private val _stagedAttachment = MutableStateFlow<StagedChatAttachment?>(null)
    val stagedAttachment: StateFlow<StagedChatAttachment?> = _stagedAttachment.asStateFlow()

    private val _isRecordingAudio = MutableStateFlow(false)
    val isRecordingAudio: StateFlow<Boolean> = _isRecordingAudio.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0)
    val recordingDurationSec: StateFlow<Int> = _recordingDurationSec.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private var messagesJob: Job? = null
    private var activityJob: Job? = null
    private var recordingTimerJob: Job? = null

    fun selectChannel(channel: ChatChannel, myUid: String) {
        _activeChannel.value = channel
        _replyTarget.value = null
        _editingMessage.value = null
        _stagedAttachment.value = null
        _isRecordingAudio.value = false
        _recordingDurationSec.value = 0

        // Observe Realtime Activity (typing & recording)
        activityJob?.cancel()
        activityJob = viewModelScope.launch {
            rtdb.observeChatActivity(coreRepo.companyId, channel.id)
                .catch { emit(EphemeralChatActivity()) }
                .collectLatest { act ->
                    _ephemeralActivity.value = act
                }
        }

        // Observe Messages
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            chatRepo.observeMessages(channel.id)
                .catch { emit(com.example.data.SyncedData(emptyList(), false)) }
                .collectLatest { synced ->
                    _messages.value = synced.data
                    _hasPendingWrites.value = synced.hasPendingWrites
                }
        }
    }

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    fun setFilterTab(tab: ChatFilterTab) {
        _filterTab.value = tab
    }

    fun setReply(message: ChatMessage?) {
        _replyTarget.value = message
        if (message != null) _editingMessage.value = null
    }

    fun setEditing(message: ChatMessage?) {
        _editingMessage.value = message
        if (message != null) _replyTarget.value = null
    }

    fun setStagedAttachment(attachment: StagedChatAttachment?) {
        _stagedAttachment.value = attachment
    }

    fun notifyTyping(channelId: String, senderName: String) {
        rtdb.sendTypingIndicator(coreRepo.companyId, channelId, senderName)
    }

    fun startRecording(channelId: String, senderName: String) {
        _isRecordingAudio.value = true
        _recordingDurationSec.value = 0
        rtdb.sendVoiceRecordingIndicator(coreRepo.companyId, channelId, senderName, recording = true)
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            while (_isRecordingAudio.value) {
                delay(1000)
                _recordingDurationSec.value += 1
            }
        }
    }

    fun cancelRecording(channelId: String, senderName: String) {
        _isRecordingAudio.value = false
        _recordingDurationSec.value = 0
        recordingTimerJob?.cancel()
        rtdb.sendVoiceRecordingIndicator(coreRepo.companyId, channelId, senderName, recording = false)
    }

    fun stopAndSendRecording(channelId: String, senderName: String, actor: Member) {
        val durationSec = _recordingDurationSec.value
        _isRecordingAudio.value = false
        recordingTimerJob?.cancel()
        rtdb.sendVoiceRecordingIndicator(coreRepo.companyId, channelId, senderName, recording = false)

        if (durationSec <= 0) return

        viewModelScope.launch {
            runCatching {
                val voiceMeta = "Voice message (${durationSec}s)"
                chatRepo.sendMessage(
                    channelId = channelId,
                    body = voiceMeta,
                    parentMessage = _replyTarget.value,
                    attachment = null,
                    actor = actor
                )
                _replyTarget.value = null
            }.onFailure { err ->
                _statusBanner.value = "Failed to send voice note: ${err.message}"
            }
        }
    }

    fun sendMessage(
        channelId: String,
        body: String,
        actor: Member,
        onSuccess: () -> Unit = {}
    ) {
        val clean = body.trim()
        val attachment = _stagedAttachment.value
        val editing = _editingMessage.value

        if (editing != null) {
            // Edit flow
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

        if (clean.isEmpty() && attachment == null) return

        viewModelScope.launch {
            _isUploading.value = attachment != null
            runCatching {
                chatRepo.sendMessage(
                    channelId = channelId,
                    body = clean,
                    parentMessage = _replyTarget.value,
                    attachment = attachment,
                    actor = actor
                )
                _replyTarget.value = null
                _stagedAttachment.value = null
                _isUploading.value = false
                onSuccess()
            }.onFailure { err ->
                _isUploading.value = false
                _statusBanner.value = "Failed to send: ${err.message}"
            }
        }
    }

    fun sendPoll(
        channelId: String,
        question: String,
        options: List<String>,
        actor: Member,
        onSuccess: () -> Unit = {}
    ) {
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
        viewModelScope.launch {
            runCatching {
                chatRepo.votePoll(channelId, messageId, optionIndex, myUid)
            }
        }
    }

    fun reactToMessage(channelId: String, messageId: String, emoji: String, myUid: String) {
        viewModelScope.launch {
            runCatching {
                chatRepo.reactToMessage(channelId, messageId, myUid, emoji)
            }
        }
    }

    fun deleteForEveryone(channelId: String, messageId: String) {
        viewModelScope.launch {
            runCatching {
                chatRepo.deleteMessageForEveryone(channelId, messageId)
            }
        }
    }

    fun deleteForMe(channelId: String, messageId: String, myUid: String) {
        viewModelScope.launch {
            runCatching {
                chatRepo.deleteMessageForMe(channelId, messageId, myUid)
            }
        }
    }

    fun toggleStar(channelId: String, messageId: String, myUid: String) {
        viewModelScope.launch {
            runCatching {
                chatRepo.toggleStarMessage(channelId, messageId, myUid)
            }
        }
    }

    fun togglePin(channelId: String, messageId: String) {
        viewModelScope.launch {
            runCatching {
                chatRepo.togglePinMessage(channelId, messageId)
            }
        }
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
                chatRepo.sendBusinessCardMessage(
                    channelId = channelId,
                    kind = kind,
                    refId = refId,
                    title = title,
                    subtitle = subtitle,
                    status = status,
                    amountPaise = amountPaise,
                    actor = actor
                )
                onSuccess()
            }.onFailure { err ->
                _statusBanner.value = "Failed to share business card: ${err.message}"
            }
        }
    }

    fun toggleChannelPin(channelId: String, myUid: String) {
        viewModelScope.launch {
            runCatching { chatRepo.togglePinChannel(channelId, myUid) }
        }
    }

    fun toggleChannelMute(channelId: String, myUid: String) {
        viewModelScope.launch {
            runCatching { chatRepo.toggleMuteChannel(channelId, myUid) }
        }
    }

    fun toggleChannelArchive(channelId: String, myUid: String) {
        viewModelScope.launch {
            runCatching { chatRepo.toggleArchiveChannel(channelId, myUid) }
        }
    }

    fun toggleChannelFavourite(channelId: String, myUid: String) {
        viewModelScope.launch {
            runCatching { chatRepo.toggleFavouriteChannel(channelId, myUid) }
        }
    }

    fun updateChannelInfo(channelId: String, title: String, topic: String, photoUrl: String = "") {
        viewModelScope.launch {
            runCatching { chatRepo.updateChannelInfo(channelId, title, topic, photoUrl) }
        }
    }

    fun addChannelMember(channelId: String, memberUid: String) {
        viewModelScope.launch {
            runCatching { chatRepo.addChannelMember(channelId, memberUid) }
        }
    }

    fun removeChannelMember(channelId: String, memberUid: String) {
        viewModelScope.launch {
            runCatching { chatRepo.removeChannelMember(channelId, memberUid) }
        }
    }

    fun toggleChannelAdmin(channelId: String, memberUid: String) {
        viewModelScope.launch {
            runCatching { chatRepo.toggleChannelAdmin(channelId, memberUid) }
        }
    }

    fun clearBanner() {
        _statusBanner.value = null
    }
}
