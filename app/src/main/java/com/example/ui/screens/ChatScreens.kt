package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chat.ChatAudioManager
import com.example.chat.ChatFileManager
import com.example.chat.ChatLocationManager
import com.example.chat.ChatSettingsStore
import com.example.chat.StagedChatAttachment
import com.example.chat.realtime.MemberPresenceState
import com.example.data.ChatRepository
import com.example.data.SendMessageOptions
import com.example.data.SyncedData
import com.example.domain.CatalogueSeedData
import com.example.model.*
import com.example.system.AppHapticsAndNotifier
import com.example.ui.AgencyUiState
import com.example.ui.ChatViewModel
import com.example.ui.chat.*
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioAvatar
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class ChatScreenMode {
    HUB,
    THREAD
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamChatScreen(
    state: AgencyUiState,
    initialChannelId: String? = null,
    observeMessages: (String) -> Flow<SyncedData<List<ChatMessage>>>,
    onEnsureGeneralChannel: () -> Unit,
    onCreateChannel: (title: String, topic: String, type: ChatChannelType, project: Project?, onReady: (ChatChannel) -> Unit, onError: (String) -> Unit) -> Unit,
    onSendMessage: (channelId: String, body: String, parent: ChatMessage?, attachment: StagedChatAttachment?, onSent: () -> Unit) -> Unit,
    onReportMessage: (String, Boolean) -> Unit,
    onOpenDirectChat: (Member, onReady: (ChatChannel) -> Unit, onError: (String) -> Unit) -> Unit = { _, _, _ -> },
    onOpenDepartmentChat: (Department, onReady: (ChatChannel) -> Unit, onError: (String) -> Unit) -> Unit = { _, _, _ -> },
    onDeleteChannel: (String, () -> Unit) -> Unit = { _, _ -> },
    onDeleteMessage: (String, String) -> Unit = { _, _ -> },
    onCloseChat: () -> Unit,
    chatViewModel: ChatViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val member = state.myMember ?: return
    val companyId = state.company?.id?.ifBlank { CatalogueSeedData.DEFAULT_COMPANY_ID }
        ?: CatalogueSeedData.DEFAULT_COMPANY_ID
    val prefs by ChatSettingsStore.prefsFlow.collectAsStateWithLifecycle()

    LaunchedEffect(member.uid) {
        onEnsureGeneralChannel()
    }

    var mode by remember(initialChannelId) {
        mutableStateOf(if (initialChannelId.isNullOrBlank()) ChatScreenMode.HUB else ChatScreenMode.THREAD)
    }
    var activeChannelId by remember(initialChannelId) {
        mutableStateOf(initialChannelId ?: ChatRepository.GENERAL_CHANNEL_ID)
    }
    var composerText by remember { mutableStateOf("") }
    var hubStagedAttachment by remember { mutableStateOf<StagedChatAttachment?>(null) }
    var isProcessingFile by remember { mutableStateOf(false) }
    var openingThreadLabel by remember { mutableStateOf<String?>(null) }
    var showChannelPicker by remember { mutableStateOf(false) }
    var showNewChannelDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showJoinInviteDialog by remember { mutableStateOf(false) }
    var showCreateStatusDialog by remember { mutableStateOf(false) }
    var viewingStatuses by remember { mutableStateOf<List<ChatStatus>?>(null) }
    var previewImageMessage by remember { mutableStateOf<Pair<ChatMessage, ImageBitmap>?>(null) }

    val activeChannel = remember(state.chatChannels, activeChannelId) {
        state.chatChannels.firstOrNull { it.id == activeChannelId }
            ?: state.chatChannels.firstOrNull { it.id == ChatRepository.GENERAL_CHANNEL_ID }
            ?: state.chatChannels.firstOrNull()
            ?: ChatChannel(
                id = ChatRepository.GENERAL_CHANNEL_ID,
                title = "General",
                topic = "Agency-wide updates, briefs & file sharing"
            )
    }

    LaunchedEffect(activeChannel.id, member.uid, mode) {
        if (mode == ChatScreenMode.THREAD) {
            chatViewModel.selectChannel(activeChannel, member.uid)
        }
    }

    val statusBanner by chatViewModel.statusBanner.collectAsStateWithLifecycle()
    LaunchedEffect(statusBanner) {
        statusBanner?.let { banner ->
            onReportMessage(banner, banner.contains("fail", ignoreCase = true) || banner.contains("error", ignoreCase = true))
            chatViewModel.clearBanner()
        }
    }

    val messagesFlow = remember(activeChannel.id) { observeMessages(activeChannel.id) }
    val syncedMessages by messagesFlow.collectAsStateWithLifecycle(initialValue = SyncedData(emptyList(), false))
    val searchQuery by chatViewModel.searchQuery.collectAsStateWithLifecycle()
    val filterTab by chatViewModel.filterTab.collectAsStateWithLifecycle()
    val selectedCustomList by chatViewModel.selectedCustomList.collectAsStateWithLifecycle()

    LaunchedEffect(syncedMessages.data.size, mode, activeChannel.id) {
        if (mode == ChatScreenMode.THREAD && syncedMessages.data.isNotEmpty()) {
            chatViewModel.markThreadMessagesRead(activeChannel.id, syncedMessages.data, member.uid)
        }
    }

    var hdMediaMode by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun stageSingleUri(uri: Uri?, preferredType: ChatAttachmentType?, isHub: Boolean) {
        if (uri == null) return
        scope.launch {
            isProcessingFile = true
            val res = ChatFileManager.prepareAttachmentFromUri(context, uri, preferredType, hdMode = hdMediaMode)
            isProcessingFile = false
            res.onSuccess { staged ->
                if (isHub) {
                    hubStagedAttachment = staged
                } else {
                    chatViewModel.stageAttachments(listOf(staged))
                }
                onReportMessage("Attached ${staged.fileName} (${ChatFileManager.formatFileSize(staged.sizeBytes)})", false)
            }.onFailure { err ->
                onReportMessage(err.message ?: "Failed to attach file.", true)
            }
        }
    }

    val singleImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        stageSingleUri(uri, ChatAttachmentType.IMAGE, isHub = (mode == ChatScreenMode.HUB))
    }
    val multiImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 8)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            isProcessingFile = true
            val list = uris.mapNotNull { u ->
                ChatFileManager.prepareAttachmentFromUri(context, u, ChatAttachmentType.IMAGE, hdMode = hdMediaMode).getOrNull()
            }
            isProcessingFile = false
            if (list.isNotEmpty()) {
                chatViewModel.stageAttachments(list)
                onReportMessage("Attached ${list.size} photo(s)", false)
            }
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        stageSingleUri(uri, ChatAttachmentType.VIDEO, isHub = false)
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        stageSingleUri(uri, ChatAttachmentType.FILE, isHub = (mode == ChatScreenMode.HUB))
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        stageSingleUri(uri, ChatAttachmentType.AUDIO, isHub = (mode == ChatScreenMode.HUB))
    }
    val cameraCaptureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            stageSingleUri(pendingCameraUri, ChatAttachmentType.IMAGE, isHub = false)
        }
        pendingCameraUri = null
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = ChatFileManager.createCameraTempUri(context)
            pendingCameraUri = uri
            cameraCaptureLauncher.launch(uri)
        } else {
            onReportMessage("Camera permission is needed to take photos.", true)
        }
    }

    fun launchCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val uri = ChatFileManager.createCameraTempUri(context)
            pendingCameraUri = uri
            cameraCaptureLauncher.launch(uri)
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun launchDocs() {
        documentPicker.launch(
            arrayOf(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/plain",
                "application/zip",
                "*/*"
            )
        )
    }

    BackHandler {
        if (mode == ChatScreenMode.THREAD) {
            ChatAudioManager.stopPlayback()
            mode = ChatScreenMode.HUB
        } else {
            onCloseChat()
        }
    }

    AnimatedContent(
        targetState = mode,
        transitionSpec = {
            if (targetState == ChatScreenMode.THREAD) {
                (fadeIn(tween(240, easing = FastOutSlowInEasing)) + slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it / 4 })
                    .togetherWith(fadeOut(tween(170)) + slideOutHorizontally(tween(170)) { -it / 6 })
            } else {
                (fadeIn(tween(240, easing = FastOutSlowInEasing)) + slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it / 4 })
                    .togetherWith(fadeOut(tween(170)) + slideOutHorizontally(tween(170)) { it / 6 })
            }
        },
        label = "chat_screen_mode_transition"
    ) { currentMode ->
        if (currentMode == ChatScreenMode.HUB) {
            ChatHubScreen(
                member = member,
                isSuperAdmin = state.isSuperAdmin,
                channels = state.chatChannels,
                statuses = state.chatStatuses,
                colleagues = state.members.filter {
                    it.statusEnum() == MembershipStatus.APPROVED && it.active && it.uid != member.uid
                },
                departments = state.departments.filter { it.active },
                activeChannel = activeChannel,
                searchQuery = searchQuery,
                filterTab = filterTab,
                selectedCustomList = selectedCustomList,
                composerText = composerText,
                stagedAttachment = hubStagedAttachment,
                isProcessingFile = isProcessingFile,
                openingThreadLabel = openingThreadLabel,
                onSearchQueryChange = chatViewModel::updateSearch,
                onSelectFilterTab = chatViewModel::setFilterTab,
                onSelectCustomList = chatViewModel::selectCustomList,
                onComposerTextChange = { composerText = it },
                onClearAttachment = { hubStagedAttachment = null },
                onOpenChannelsSheet = { showChannelPicker = true },
                onPickAudio = { audioPicker.launch(arrayOf("audio/*")) },
                onPickFiles = ::launchDocs,
                onPickImages = { singleImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onSelectChannel = { ch ->
                    AppHapticsAndNotifier.triggerTickHaptic(context)
                    activeChannelId = ch.id
                    mode = ChatScreenMode.THREAD
                },
                onOpenDirectChat = { colleague ->
                    AppHapticsAndNotifier.triggerTickHaptic(context)
                    openingThreadLabel = "Opening chat with ${formatTitleCase(colleague.fullName)}…"
                    onOpenDirectChat(
                        colleague,
                        { ch ->
                            openingThreadLabel = null
                            activeChannelId = ch.id
                            mode = ChatScreenMode.THREAD
                        },
                        { err ->
                            openingThreadLabel = null
                            onReportMessage("Unable to open chat: $err", true)
                        }
                    )
                },
                onOpenDepartmentChat = { dept ->
                    AppHapticsAndNotifier.triggerTickHaptic(context)
                    openingThreadLabel = "Opening ${formatTitleCase(dept.name)} chat…"
                    onOpenDepartmentChat(
                        dept,
                        { ch ->
                            openingThreadLabel = null
                            activeChannelId = ch.id
                            mode = ChatScreenMode.THREAD
                        },
                        { err ->
                            openingThreadLabel = null
                            onReportMessage("Unable to open department chat: $err", true)
                        }
                    )
                },
                onOpenSavedNotes = {
                    scope.launch {
                        runCatching { chatViewModel.chatRepo.createOrGetSavedNotesChannel(member) }
                            .onSuccess { ch ->
                                activeChannelId = ch.id
                                mode = ChatScreenMode.THREAD
                            }
                            .onFailure { err -> onReportMessage(err.message ?: "Unable to open Saved Notes.", true) }
                    }
                },
                onOpenCreateGroup = { showCreateGroupDialog = true },
                onOpenJoinByInvite = { showJoinInviteDialog = true },
                onOpenCreateStatus = { showCreateStatusDialog = true },
                onOpenStatusViewer = { _, list -> viewingStatuses = list },
                onTogglePinChannel = { ch -> chatViewModel.toggleChannelPin(ch.id, member.uid) },
                onToggleMuteChannel = { ch -> chatViewModel.toggleChannelMute(ch.id, member.uid) },
                onToggleArchiveChannel = { ch -> chatViewModel.toggleChannelArchive(ch.id, member.uid) },
                onToggleFavouriteChannel = { ch -> chatViewModel.toggleChannelFavourite(ch.id, member.uid) },
                onMarkReadUnread = { ch, markUnread -> chatViewModel.markChannelReadUnread(ch.id, member.uid, markUnread) },
                onDeleteChannel = { chId ->
                    onDeleteChannel(chId) {
                        if (activeChannelId == chId) activeChannelId = ChatRepository.GENERAL_CHANNEL_ID
                    }
                },
                onCreateNewChannelClick = { showNewChannelDialog = true },
                onSendQuickCompose = {
                    val clean = composerText.trim()
                    val att = hubStagedAttachment
                    if (clean.isNotEmpty() || att != null) {
                        val targetId = activeChannel.id
                        onSendMessage(targetId, clean, null, att) {
                            AppHapticsAndNotifier.triggerCompletionVibration(context)
                            composerText = ""
                            hubStagedAttachment = null
                            activeChannelId = targetId
                            mode = ChatScreenMode.THREAD
                        }
                    }
                },
                onBack = onCloseChat,
                onClose = onCloseChat
            )
        } else {
            EnhancedChatThreadView(
                state = state,
                companyId = companyId,
                member = member,
                channel = activeChannel,
                messages = syncedMessages.data,
                hasPendingWrites = syncedMessages.hasPendingWrites,
                chatViewModel = chatViewModel,
                hdMediaMode = hdMediaMode,
                isProcessingFile = isProcessingFile,
                onToggleHdMedia = { hdMediaMode = it },
                onCamera = ::launchCamera,
                onPickSinglePhoto = { singleImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickMultiPhoto = { multiImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickVideo = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                onPickDocs = ::launchDocs,
                onPickAudio = { audioPicker.launch(arrayOf("audio/*")) },
                onOpenChannelsSheet = { showChannelPicker = true },
                onOpenMessageAttachment = { msg ->
                    if (msg.attachmentTypeEnum() == ChatAttachmentType.IMAGE) {
                        scope.launch {
                            val bmp = ChatFileManager.loadOrDownloadMessageBitmap(context, msg, companyId, maxEdgePx = 1400)
                            if (bmp != null) {
                                previewImageMessage = msg to bmp.asImageBitmap()
                            } else {
                                ChatFileManager.resolveOrDownloadAttachment(context, msg, companyId)
                                    .onSuccess { file -> ChatFileManager.openOrShareAttachment(context, file, msg.attachmentMimeType, msg.attachmentName) }
                                    .onFailure { err -> onReportMessage(err.message ?: "Unable to open image.", true) }
                            }
                        }
                    } else {
                        scope.launch {
                            ChatFileManager.resolveOrDownloadAttachment(context, msg, companyId)
                                .onSuccess { file ->
                                    ChatFileManager.openOrShareAttachment(context, file, msg.attachmentMimeType, msg.attachmentName.ifBlank { file.name })
                                }
                                .onFailure { err -> onReportMessage(err.message ?: "Attachment unavailable.", true) }
                        }
                    }
                },
                onDownloadAttachment = { msg ->
                    scope.launch {
                        ChatFileManager.downloadAttachmentToDevice(context, msg, companyId)
                            .onSuccess { savedPath -> onReportMessage("Saved ${msg.attachmentName.ifBlank { "file" }} to $savedPath", false) }
                            .onFailure { err -> onReportMessage(err.message ?: "Download failed.", true) }
                    }
                },
                onDeleteChannel = {
                    onDeleteChannel(activeChannel.id) {
                        activeChannelId = ChatRepository.GENERAL_CHANNEL_ID
                        mode = ChatScreenMode.HUB
                    }
                },
                onBackToHub = {
                    ChatAudioManager.stopPlayback()
                    mode = ChatScreenMode.HUB
                },
                onClose = onCloseChat
            )
        }
    }

    if (showChannelPicker) {
        ChannelPickerBottomSheet(
            channels = state.chatChannels,
            activeChannelId = activeChannel.id,
            isSuperAdmin = state.isSuperAdmin,
            onSelectChannel = { ch ->
                activeChannelId = ch.id
                showChannelPicker = false
                mode = ChatScreenMode.THREAD
            },
            onDeleteChannel = { chId ->
                onDeleteChannel(chId) {
                    if (activeChannelId == chId) activeChannelId = ChatRepository.GENERAL_CHANNEL_ID
                }
            },
            onNewChannel = {
                showChannelPicker = false
                showNewChannelDialog = true
            },
            onDismiss = { showChannelPicker = false }
        )
    }

    if (showNewChannelDialog) {
        CreateChatChannelDialog(
            projects = state.projects.filter { !it.deleted },
            onCreate = { title, topic, type, project, onCreated, onError ->
                onCreateChannel(
                    title,
                    topic,
                    type,
                    project,
                    { created ->
                        onCreated()
                        showNewChannelDialog = false
                        activeChannelId = created.id
                        mode = ChatScreenMode.THREAD
                    },
                    { err ->
                        onError(err)
                        onReportMessage("Unable to create channel: $err", true)
                    }
                )
            },
            onDismiss = { showNewChannelDialog = false }
        )
    }

    if (showCreateGroupDialog) {
        CreateGroupSheet(
            colleagues = state.members.filter { it.statusEnum() == MembershipStatus.APPROVED && it.active && it.uid != member.uid },
            onCreateGroup = { title, desc, memberUids, photoB64, announcementOnly, requireApproval ->
                scope.launch {
                    runCatching {
                        chatViewModel.chatRepo.createGroupChannel(
                            title = title,
                            description = desc,
                            memberUids = memberUids,
                            photoBase64 = photoB64,
                            isAnnouncement = announcementOnly,
                            actor = member,
                            requireAdminApproval = requireApproval
                        )
                    }.onSuccess { group ->
                        showCreateGroupDialog = false
                        activeChannelId = group.id
                        mode = ChatScreenMode.THREAD
                    }.onFailure { err ->
                        onReportMessage(err.message ?: "Unable to create group.", true)
                    }
                }
            },
            onDismiss = { showCreateGroupDialog = false }
        )
    }

    if (showJoinInviteDialog) {
        JoinInviteLinkDialog(
            onJoin = { code ->
                chatViewModel.joinByInviteCode(code, member) { joined ->
                    showJoinInviteDialog = false
                    activeChannelId = joined.id
                    mode = ChatScreenMode.THREAD
                }
            },
            onDismiss = { showJoinInviteDialog = false }
        )
    }

    if (showCreateStatusDialog) {
        CreateStatusDialog(
            onPost = { text, preset, imageB64 ->
                chatViewModel.postStatus(text, preset, imageB64, member) {
                    showCreateStatusDialog = false
                }
            },
            onDismiss = { showCreateStatusDialog = false }
        )
    }

    viewingStatuses?.let { list ->
        FullScreenStatusViewer(
            statuses = list,
            myUid = member.uid,
            allMembers = state.members,
            onMarkViewed = { sId -> chatViewModel.markStatusViewed(sId, member.uid) },
            onDeleteStatus = { sId -> chatViewModel.deleteStatus(sId) },
            onReplyDm = { ownerUid, replyMsg ->
                val target = state.members.firstOrNull { it.uid == ownerUid }
                if (target != null) {
                    onOpenDirectChat(
                        target,
                        { ch ->
                            activeChannelId = ch.id
                            mode = ChatScreenMode.THREAD
                            chatViewModel.sendMessage(ch.id, replyMsg, member)
                        },
                        { err -> onReportMessage(err, true) }
                    )
                }
            },
            onDismiss = { viewingStatuses = null }
        )
    }

    previewImageMessage?.let { (msg, img) ->
        val title = msg.attachmentName.ifBlank { "Image" }
        Dialog(onDismissRequest = { previewImageMessage = null }) {
            Surface(shape = RoundedCornerShape(24.dp), color = ObsidianCharcoal, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = PureWhite, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                scope.launch {
                                    ChatFileManager.downloadAttachmentToDevice(context, msg, companyId)
                                        .onSuccess { savedPath -> onReportMessage("Saved $title to $savedPath", false) }
                                        .onFailure { err -> onReportMessage(err.message ?: "Failed to save image.", true) }
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("preview_download_image_button")
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = "Save image to device", tint = PureWhite)
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    ChatFileManager.resolveOrDownloadAttachment(context, msg, companyId)
                                        .onSuccess { file -> ChatFileManager.openOrShareAttachment(context, file, msg.attachmentMimeType, title) }
                                        .onFailure { err -> onReportMessage(err.message ?: "File unavailable.", true) }
                                }
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "Share image", tint = PureWhite)
                        }
                        IconButton(onClick = { previewImageMessage = null }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Close preview", tint = PureWhite)
                        }
                    }
                    Image(
                        bitmap = img,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun EnhancedChatThreadView(
    state: AgencyUiState,
    companyId: String,
    member: Member,
    channel: ChatChannel,
    messages: List<ChatMessage>,
    hasPendingWrites: Boolean,
    chatViewModel: ChatViewModel,
    hdMediaMode: Boolean,
    isProcessingFile: Boolean,
    onToggleHdMedia: (Boolean) -> Unit,
    onCamera: () -> Unit,
    onPickSinglePhoto: () -> Unit,
    onPickMultiPhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onPickDocs: () -> Unit,
    onPickAudio: () -> Unit,
    onOpenChannelsSheet: () -> Unit,
    onOpenMessageAttachment: (ChatMessage) -> Unit,
    onDownloadAttachment: (ChatMessage) -> Unit,
    onDeleteChannel: () -> Unit,
    onBackToHub: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by ChatSettingsStore.prefsFlow.collectAsStateWithLifecycle()

    val ephemeral by chatViewModel.ephemeralActivity.collectAsStateWithLifecycle()
    val replyTarget by chatViewModel.replyTarget.collectAsStateWithLifecycle()
    val editingMsg by chatViewModel.editingMessage.collectAsStateWithLifecycle()
    val stagedAttachments by chatViewModel.stagedAttachments.collectAsStateWithLifecycle()
    val isRecordingAudio by chatViewModel.isRecordingAudio.collectAsStateWithLifecycle()
    val recordingSec by chatViewModel.recordingDurationSec.collectAsStateWithLifecycle()
    val voicePreview by chatViewModel.voicePreview.collectAsStateWithLifecycle()
    val isTranscribing by chatViewModel.isTranscribing.collectAsStateWithLifecycle()
    val isUploading by chatViewModel.isUploading.collectAsStateWithLifecycle()
    val failedMessages by chatViewModel.failedMessages.collectAsStateWithLifecycle()

    var composerText by remember(channel.id) { mutableStateOf("") }
    LaunchedEffect(editingMsg?.id) {
        editingMsg?.let { composerText = it.body }
    }

    var viewOnceMode by remember { mutableStateOf(false) }
    var showInThreadSearch by remember { mutableStateOf(false) }
    var threadSearchQuery by remember { mutableStateOf("") }
    var dateFilterIndex by remember { mutableStateOf(0) } // 0=All, 1=Today, 2=Yesterday, 3=Week
    var showInfoSheet by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var showEmojiStickerSheet by remember { mutableStateOf(false) }
    var emojiPickerTargetMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var showCreatePoll by remember { mutableStateOf(false) }
    var showCreateEvent by remember { mutableStateOf(false) }
    var showBusinessPicker by remember { mutableStateOf(false) }
    var showStarredSheet by remember { mutableStateOf(false) }
    var forwardTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var messageInfoTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var reportTargetPair by remember { mutableStateOf<Pair<ChatChannel, ChatMessage?>?>(null) }
    var showPinSetupDialog by remember { mutableStateOf(false) }
    var showDeleteThreadConfirm by remember { mutableStateOf(false) }
    var pendingLocationAction by remember { mutableStateOf<Int?>(null) } // null=none, 0=current, 15/60=live

    val audioPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            chatViewModel.startVoiceRecording(context, channel.id, member.uid, member.fullName)
        }
    }

    val locationPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val ok = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val modeMinutes = pendingLocationAction
        pendingLocationAction = null
        if (ok && modeMinutes != null) {
            if (modeMinutes == 0) {
                scope.launch {
                    ChatLocationManager.fetchCurrentLocation(context).onSuccess { loc ->
                        chatViewModel.sendRichOptions(
                            channel.id,
                            "Location: ${loc.label}",
                            member,
                            SendMessageOptions(
                                kind = ChatMessageKind.LOCATION,
                                locationLat = loc.latitude,
                                locationLng = loc.longitude,
                                locationLabel = loc.label
                            )
                        )
                    }
                }
            } else {
                chatViewModel.startLiveLocationSharing(context, channel.id, modeMinutes, member)
            }
        }
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) {
            val picked = ChatFileManager.extractPickedContact(context, uri)
            if (picked != null) {
                chatViewModel.sendRichOptions(
                    channel.id,
                    "Contact: ${picked.name}",
                    member,
                    SendMessageOptions(
                        kind = ChatMessageKind.CONTACT,
                        contactName = picked.name,
                        contactPhone = picked.phone
                    )
                )
            }
        }
    }

    fun requestOrRunLocation(durationMinutes: Int) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            if (durationMinutes == 0) {
                scope.launch {
                    ChatLocationManager.fetchCurrentLocation(context).onSuccess { loc ->
                        chatViewModel.sendRichOptions(
                            channel.id,
                            "Location: ${loc.label}",
                            member,
                            SendMessageOptions(
                                kind = ChatMessageKind.LOCATION,
                                locationLat = loc.latitude,
                                locationLng = loc.longitude,
                                locationLabel = loc.label
                            )
                        )
                    }
                }
            } else {
                chatViewModel.startLiveLocationSharing(context, channel.id, durationMinutes, member)
            }
        } else {
            pendingLocationAction = durationMinutes
            locationPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    // Peer online & last-seen presence for 1:1 DMs
    val peerUid = remember(channel.participantUids, member.uid, channel.channelType) {
        if (channel.channelTypeEnum() == ChatChannelType.DIRECT && !channel.isSelfNotes(member.uid)) {
            channel.participantUids.firstOrNull { it != member.uid }.orEmpty()
        } else ""
    }
    val peerPresence by remember(peerUid) {
        chatViewModel.observeMemberPresence(peerUid)
    }.collectAsStateWithLifecycle(initialValue = MemberPresenceState())

    val filteredMessages = remember(messages, threadSearchQuery, dateFilterIndex) {
        val q = threadSearchQuery.trim().lowercase()
        val calStartToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val startYesterday = calStartToday - 86_400_000L
        val startWeek = calStartToday - 6 * 86_400_000L
        messages.filter { m ->
            val ms = m.createdAt?.toDate()?.time ?: System.currentTimeMillis()
            val matchQ = q.isBlank() ||
                m.body.lowercase().contains(q) ||
                m.senderName.lowercase().contains(q) ||
                m.attachmentName.lowercase().contains(q)
            val matchDate = when (dateFilterIndex) {
                1 -> ms >= calStartToday
                2 -> ms in startYesterday until calStartToday
                3 -> ms >= startWeek
                else -> true
            }
            matchQ && matchDate
        }
    }

    val channelFailed = remember(failedMessages, channel.id) {
        failedMessages.filter { it.channelId == channel.id }
    }
    val pinnedMessages = remember(messages) { messages.filter { it.pinned && !it.isDeletedForEveryone } }

    val listState = rememberLazyListState()
    LaunchedEffect(filteredMessages.size) {
        if (filteredMessages.isNotEmpty()) {
            listState.animateScrollToItem(filteredMessages.lastIndex)
        }
    }

    val threadWallpaper = prefs.channelWallpapers[channel.id] ?: prefs.globalWallpaperId
    val otherParticipantsCount = (channel.participantUids.size - 1).coerceAtLeast(0)
    val canPostInChannel = !channel.onlyAdminsCanMessage || channel.isChannelAdmin(member.uid, state.isSuperAdmin)
    val mentionCandidates = remember(composerText, state.members) {
        val afterAt = composerText.substringAfterLast("@", "")
        if (composerText.contains("@") && !afterAt.contains(" ") && afterAt.length <= 16) {
            state.members.filter {
                it.uid != member.uid && it.active && it.fullName.contains(afterAt, ignoreCase = true)
            }.take(5)
        } else emptyList()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureWhite)
            .animatedChatGlowBackdrop(isThread = true, wallpaperId = threadWallpaper)
            .testTag("chat_thread_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .widthIn(max = 640.dp)
                .align(Alignment.Center)
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onBackToHub,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("chat_thread_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Chat Hub", tint = ObsidianCharcoal)
                }

                val otherPeer = remember(channel, state.members, member.uid) {
                    if (channel.channelTypeEnum() == ChatChannelType.DIRECT && !channel.isSelfNotes(member.uid)) {
                        state.members.firstOrNull { it.uid != member.uid && it.uid in channel.participantUids }
                    } else null
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showInfoSheet = true }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag(if (channel.channelTypeEnum() == ChatChannelType.DIRECT) "chat_direct_header" else "chat_thread_header")
                ) {
                    StudioAvatar(
                        name = when {
                            channel.isSelfNotes(member.uid) -> "Saved"
                            otherPeer != null -> otherPeer.fullName
                            else -> channel.title
                        },
                        size = 38.dp,
                        borderColor = PureWhite,
                        photoBase64 = otherPeer?.photoBase64?.ifBlank { channel.photoBase64 } ?: channel.photoBase64
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                channel.isSelfNotes(member.uid) -> "Message Yourself (Saved)"
                                otherPeer != null -> formatTitleCase(otherPeer.fullName)
                                channel.channelTypeEnum() == ChatChannelType.DIRECT -> formatTitleCase(channel.title)
                                channel.channelTypeEnum() == ChatChannelType.DEPARTMENT -> "Dept: ${formatTitleCase(channel.title)}"
                                else -> formatTitleCase(channel.title)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = ObsidianCharcoal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitleText = when {
                            ephemeral.recordingNames.isNotEmpty() -> "${ephemeral.recordingNames.first().substringBefore(" ")} recording audio…"
                            ephemeral.typingNames.isNotEmpty() -> "${ephemeral.typingNames.first().substringBefore(" ")} typing…"
                            hasPendingWrites -> "Syncing updates…"
                            peerPresence.online -> "Online"
                            peerPresence.lastSeenMillis > 0L -> {
                                val ls = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(peerPresence.lastSeenMillis))
                                "Last seen $ls"
                            }
                            else -> channel.topic.ifBlank { "Tap for group & media info" }
                        }
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ephemeral.typingNames.isNotEmpty() || ephemeral.recordingNames.isNotEmpty() || peerPresence.online) EmeraldApproved else StudioTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showInThreadSearch = !showInThreadSearch }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Search, contentDescription = "Search thread", tint = ObsidianCharcoal, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { showStarredSheet = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.StarBorder, contentDescription = "Starred messages", tint = ObsidianCharcoal, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { showInfoSheet = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Info, contentDescription = "Chat info", tint = ObsidianCharcoal, modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("chat_thread_close_button")
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close Chat", tint = ObsidianCharcoal, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Search-within-conversation + Search-by-Date bar
            AnimatedVisibility(visible = showInThreadSearch) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = threadSearchQuery,
                        onValueChange = { threadSearchQuery = it },
                        placeholder = { Text("Find message or attachment…") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = PureWhite, unfocusedContainerColor = PureWhite),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("All Dates", "Today", "Yesterday", "7 Days").forEachIndexed { idx, lbl ->
                            FilterChip(
                                selected = dateFilterIndex == idx,
                                onClick = { dateFilterIndex = idx },
                                label = { Text(lbl, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            // Pinned Message Banner
            if (pinnedMessages.isNotEmpty()) {
                val topPinned = pinnedMessages.last()
                Surface(
                    onClick = {
                        val idx = filteredMessages.indexOfFirst { it.id == topPinned.id }
                        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                    },
                    color = PureWhite.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, SubtleOutline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.PushPin, null, tint = VioletPrimary, modifier = Modifier.size(16.dp))
                        Text(
                            text = topPinned.body.ifBlank { topPinned.attachmentName.ifBlank { topPinned.kindEnum().label } },
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { chatViewModel.togglePin(channel.id, topPinned.id) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Unpin", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // Disappearing Messages Indicator
            if (channel.disappearingSeconds > 0L) {
                val timerText = when (channel.disappearingSeconds) {
                    86_400L -> "24 hours"
                    604_800L -> "7 days"
                    else -> "90 days"
                }
                Surface(color = Color(0xFFFFFBEB), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Timer, null, tint = Color(0xFFD97706), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Disappearing messages active ($timerText)", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB45309))
                    }
                }
            }

            // Messages Stream
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (filteredMessages.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (messages.isEmpty()) "No messages yet. Say hello or share media below!" else "No messages match your search filter.",
                            style = MaterialTheme.typography.titleMedium,
                            color = ObsidianCharcoal.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("chat_messages_list"),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(filteredMessages, key = { _, m -> m.id }) { idx, msg ->
                            val prev = filteredMessages.getOrNull(idx - 1)
                            val curDay = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(msg.createdAt?.toDate()?.time ?: System.currentTimeMillis()))
                            val prevDay = prev?.let { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it.createdAt?.toDate()?.time ?: System.currentTimeMillis())) }
                            if (prev == null || curDay != prevDay) {
                                DateSeparatorPill(label = curDay)
                            }
                            RichChatMessageBubble(
                                message = msg,
                                companyId = companyId,
                                isMe = msg.senderUid == member.uid,
                                myUid = member.uid,
                                bubbleThemeId = prefs.bubbleThemeId,
                                showSenderHeader = (prev == null || prev.senderUid != msg.senderUid) && channel.channelTypeEnum() != ChatChannelType.DIRECT,
                                otherParticipantCount = otherParticipantsCount,
                                onReply = { chatViewModel.setReply(msg) },
                                onForward = { forwardTarget = msg },
                                onEdit = { chatViewModel.setEditing(msg) },
                                onDeleteForMe = { chatViewModel.deleteForMe(channel.id, msg.id, member.uid) },
                                onDeleteForEveryone = { chatViewModel.deleteForEveryone(channel.id, msg.id) },
                                onToggleStar = { chatViewModel.toggleStar(channel.id, msg.id, member.uid) },
                                onTogglePin = { chatViewModel.togglePin(channel.id, msg.id) },
                                onReact = { emoji -> chatViewModel.reactToMessage(channel.id, msg.id, emoji, member.uid) },
                                onOpenEmojiPickerForReaction = {
                                    emojiPickerTargetMessage = msg
                                    showEmojiStickerSheet = true
                                },
                                onVotePoll = { optIdx -> chatViewModel.votePoll(channel.id, msg.id, optIdx, member.uid) },
                                onRsvpEvent = { rsvp -> chatViewModel.rsvpEvent(channel.id, msg.id, member.uid, rsvp) },
                                onRevealViewOnce = { chatViewModel.markViewedOnce(channel.id, msg.id, member.uid) },
                                onStopLiveLocation = { chatViewModel.stopLiveLocation(channel.id, msg.id) },
                                onOpenAttachment = { onOpenMessageAttachment(msg) },
                                onDownloadAttachment = { onDownloadAttachment(msg) },
                                onShowMessageInfo = { messageInfoTarget = msg },
                                onReportMessage = { reportTargetPair = channel to msg },
                                onReportStatusError = {}
                            )
                        }
                    }
                }
            }

            // Failed Outgoing Retry Banner
            channelFailed.forEach { failItem ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SoftBlush,
                    border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.ErrorOutline, null, tint = CrimsonBlocked, modifier = Modifier.size(18.dp))
                        Text("Failed: ${failItem.body.ifBlank { failItem.reason }}", style = MaterialTheme.typography.labelSmall, color = DeepBlushInk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextButton(onClick = { chatViewModel.retryFailedMessage(failItem, member) }) { Text("Retry", fontWeight = FontWeight.Bold) }
                        IconButton(onClick = { chatViewModel.dismissFailedMessage(failItem.id) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // Bottom Composer Stack
            if (!canPostInChannel) {
                Surface(
                    color = MutedSurface.copy(alpha = 0.94f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "Only group administrators can send messages in this channel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isProcessingFile || isUploading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)), color = VioletPrimary)
                    }

                    // @Mention Autocomplete Bar
                    if (mentionCandidates.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            mentionCandidates.forEach { cand ->
                                AssistChip(
                                    onClick = {
                                        val prefix = composerText.substringBeforeLast("@")
                                        val handle = cand.fullName.trim().replace(" ", "")
                                        composerText = "$prefix@$handle "
                                    },
                                    label = { Text("@${formatTitleCase(cand.fullName)}") }
                                )
                            }
                        }
                    }

                    // Reply / Edit Preview Banner
                    val bannerTarget = replyTarget ?: editingMsg
                    if (bannerTarget != null) {
                        Surface(shape = RoundedCornerShape(12.dp), color = PureWhite.copy(alpha = 0.92f), border = BorderStroke(1.dp, SubtleOutline), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(if (editingMsg != null) Icons.Default.Edit else Icons.AutoMirrored.Filled.Reply, null, tint = VioletPrimary, modifier = Modifier.size(16.dp))
                                Text(
                                    text = if (editingMsg != null) "Editing: ${editingMsg!!.body}" else "Replying to ${bannerTarget.senderName.substringBefore(" ")}: ${bannerTarget.body.ifBlank { bannerTarget.attachmentName }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        chatViewModel.setReply(null)
                                        if (editingMsg != null) {
                                            chatViewModel.setEditing(null)
                                            composerText = ""
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    // Staged Attachments Strip (Multi-photo / HD / Docs)
                    if (stagedAttachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            stagedAttachments.forEach { att ->
                                Surface(shape = RoundedCornerShape(12.dp), color = PureWhite, border = BorderStroke(1.dp, SubtleOutline)) {
                                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("${att.fileName.take(18)} (${ChatFileManager.formatFileSize(att.sizeBytes)})", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        if (att.isHd) Text("HD", fontSize = 10.sp, color = VioletPrimary, fontWeight = FontWeight.ExtraBold)
                                        IconButton(onClick = { chatViewModel.removeStagedAttachment(att.id) }, modifier = Modifier.size(20.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Active Voice Recording OR Voice Preview & Transcription Bar
                    if (isRecordingAudio) {
                        Surface(shape = RoundedCornerShape(18.dp), color = SoftBlush, border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.Mic, null, tint = CrimsonBlocked)
                                Text(
                                    text = "Recording ${String.format("%02d:%02d", recordingSec / 60, recordingSec % 60)}…",
                                    fontWeight = FontWeight.Bold,
                                    color = DeepBlushInk,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { chatViewModel.stopRecordingForPreview(channel.id, member.uid, member.fullName) },
                                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                                ) { Text("Stop & Preview") }
                            }
                        }
                    } else if (voicePreview != null) {
                        val draft = voicePreview!!
                        val playback by ChatAudioManager.playbackState.collectAsStateWithLifecycle()
                        val previewPlaying = playback.activeKey == "draft_preview" && playback.isPlaying
                        Surface(shape = RoundedCornerShape(18.dp), color = PureWhite, border = BorderStroke(1.dp, VioletPrimary.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconButton(onClick = { ChatAudioManager.togglePlay("draft_preview", draft.file) }) {
                                        Icon(if (previewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Preview", tint = EmeraldApproved)
                                    }
                                    Text("Voice Preview (${(draft.durationMs / 1000L).coerceAtLeast(1L)}s)", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { chatViewModel.transcribeVoiceIntoDraft(context) }, enabled = !isTranscribing) {
                                        Text(if (isTranscribing) "Listening…" else "Transcribe")
                                    }
                                    IconButton(onClick = { chatViewModel.discardVoicePreview() }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Discard", tint = CrimsonBlocked)
                                    }
                                    Surface(
                                        onClick = {
                                            chatViewModel.sendVoicePreview(channel.id, member, viewOnce = viewOnceMode) {
                                                viewOnceMode = false
                                                AppHapticsAndNotifier.triggerCompletionVibration(context)
                                            }
                                        },
                                        shape = CircleShape,
                                        color = ObsidianCharcoal,
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Voice Note", tint = PureWhite, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                                if (draft.transcript.isNotBlank()) {
                                    Text("Transcript: ${draft.transcript}", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                                }
                            }
                        }
                    }

                    // Main Input Capsule
                    val canSend = composerText.isNotBlank() || stagedAttachments.isNotEmpty()
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = PureWhite.copy(alpha = 0.96f),
                        border = BorderStroke(1.dp, SubtleOutline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    emojiPickerTargetMessage = null
                                    showEmojiStickerSheet = true
                                },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(Icons.Default.EmojiEmotions, contentDescription = "Emoji, Stickers & GIFs", tint = ObsidianCharcoal, modifier = Modifier.size(20.dp))
                            }

                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (composerText.isEmpty()) {
                                    Text("Write a message…", style = MaterialTheme.typography.bodyMedium, color = StudioTextSecondary)
                                }
                                BasicTextField(
                                    value = composerText,
                                    onValueChange = { next ->
                                        composerText = next
                                        if (next.isNotBlank()) chatViewModel.notifyTyping(channel.id, member.uid, member.fullName)
                                    },
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ObsidianCharcoal),
                                    cursorBrush = SolidColor(ObsidianCharcoal),
                                    maxLines = 4,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("chat_thread_input")
                                )
                            }

                            IconButton(
                                onClick = { showAttachmentMenu = true },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("chat_thread_attach_button")
                            ) {
                                Icon(Icons.Default.AddCircleOutline, contentDescription = "More attachments", tint = ObsidianCharcoal, modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = onCamera, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Camera", tint = ObsidianCharcoal, modifier = Modifier.size(19.dp))
                            }

                            if (!canSend && editingMsg == null) {
                                IconButton(
                                    onClick = {
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                            chatViewModel.startVoiceRecording(context, channel.id, member.uid, member.fullName)
                                        } else {
                                            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(Icons.Default.Mic, contentDescription = "Record Voice", tint = VioletPrimary, modifier = Modifier.size(20.dp))
                                }
                            }

                            Surface(
                                onClick = {
                                    if (canSend && !isProcessingFile && !isUploading) {
                                        val mentionUids = mentionCandidates.map { it.uid }
                                        chatViewModel.sendMessage(
                                            channelId = channel.id,
                                            body = composerText,
                                            actor = member,
                                            viewOnce = viewOnceMode,
                                            mentions = mentionUids
                                        ) {
                                            AppHapticsAndNotifier.triggerCompletionVibration(context)
                                            composerText = ""
                                            viewOnceMode = false
                                        }
                                    }
                                },
                                enabled = canSend && !isProcessingFile && !isUploading,
                                shape = CircleShape,
                                color = if (canSend) ObsidianCharcoal else MutedSurface,
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("chat_thread_send_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.ArrowUpward,
                                        contentDescription = "Send",
                                        tint = if (canSend) PureWhite else ObsidianCharcoal,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Quick Attach Bar (Preserves quick_attach_* testTags + adds Poll/ViewOnce toggles)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuickAttachPill(
                            label = "Attach File",
                            icon = Icons.Outlined.Description,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_attach_file_button"),
                            onClick = onPickDocs
                        )
                        QuickAttachPill(
                            label = "Photo",
                            icon = Icons.Outlined.Image,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_attach_photo_button"),
                            onClick = onPickSinglePhoto
                        )
                        QuickAttachPill(
                            label = "Audio",
                            icon = Icons.Outlined.Mic,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_attach_audio_button"),
                            onClick = onPickAudio
                        )
                    }
                }
            }
        }
    }

    // Sub-sheets and Modals
    if (showEmojiStickerSheet) {
        EmojiStickerGifSheet(
            onPickEmoji = { emoji ->
                val target = emojiPickerTargetMessage
                if (target != null) {
                    chatViewModel.reactToMessage(channel.id, target.id, emoji, member.uid)
                    emojiPickerTargetMessage = null
                    showEmojiStickerSheet = false
                } else {
                    composerText += emoji
                }
            },
            onPickSticker = { stk ->
                showEmojiStickerSheet = false
                chatViewModel.sendRichOptions(
                    channel.id,
                    stk.title,
                    member,
                    SendMessageOptions(kind = ChatMessageKind.STICKER, stickerId = stk.id)
                )
            },
            onPickGif = { gif ->
                showEmojiStickerSheet = false
                chatViewModel.sendRichOptions(
                    channel.id,
                    gif.badgeText,
                    member,
                    SendMessageOptions(kind = ChatMessageKind.GIF, gifId = gif.id, gifTitle = gif.badgeText)
                )
            },
            onDismiss = {
                emojiPickerTargetMessage = null
                showEmojiStickerSheet = false
            }
        )
    }

    if (showAttachmentMenu) {
        AttachmentMenuBottomSheet(
            hdMedia = hdMediaMode,
            viewOnce = viewOnceMode,
            onToggleHd = onToggleHdMedia,
            onToggleViewOnce = { viewOnceMode = it },
            onCamera = { showAttachmentMenu = false; onCamera() },
            onGalleryPhoto = { showAttachmentMenu = false; onPickSinglePhoto() },
            onMultiPhoto = { showAttachmentMenu = false; onPickMultiPhoto() },
            onVideo = { showAttachmentMenu = false; onPickVideo() },
            onDocument = { showAttachmentMenu = false; onPickDocs() },
            onAudioFile = { showAttachmentMenu = false; onPickAudio() },
            onContact = { showAttachmentMenu = false; contactPickerLauncher.launch(null) },
            onCurrentLocation = { showAttachmentMenu = false; requestOrRunLocation(0) },
            onLiveLocation = { mins -> showAttachmentMenu = false; requestOrRunLocation(mins) },
            onCreatePoll = { showAttachmentMenu = false; showCreatePoll = true },
            onCreateEvent = { showAttachmentMenu = false; showCreateEvent = true },
            onBusinessRecord = { showAttachmentMenu = false; showBusinessPicker = true },
            onDismiss = { showAttachmentMenu = false }
        )
    }

    if (showCreatePoll) {
        CreatePollDialog(
            onSubmit = { q, opts ->
                chatViewModel.sendPoll(channel.id, q, opts, member) { showCreatePoll = false }
            },
            onDismiss = { showCreatePoll = false }
        )
    }

    if (showCreateEvent) {
        CreateEventDialog(
            onCreate = { title, loc, ms ->
                showCreateEvent = false
                chatViewModel.sendRichOptions(
                    channel.id,
                    title,
                    member,
                    SendMessageOptions(
                        kind = ChatMessageKind.EVENT,
                        eventTitle = title,
                        eventLocation = loc,
                        eventTimeMillis = ms
                    )
                )
            },
            onDismiss = { showCreateEvent = false }
        )
    }

    if (showBusinessPicker) {
        ShareBusinessRecordDialog(
            state = state,
            onShare = { kind, refId, title, sub, st, amt ->
                chatViewModel.sendBusinessCard(channel.id, kind, refId, title, sub, st, amt, member) {
                    showBusinessPicker = false
                }
            },
            onDismiss = { showBusinessPicker = false }
        )
    }

    if (showInfoSheet) {
        EnhancedConversationInfoSheet(
            channel = channel,
            messages = messages,
            allMembers = state.members,
            myUid = member.uid,
            isSuperAdmin = state.isSuperAdmin,
            prefs = prefs,
            onUpdateInfo = { title, desc, photoB64 -> chatViewModel.updateChannelInfo(channel.id, title, desc, photoB64) },
            onUpdatePermissions = { msgAdm, editAdm, app -> chatViewModel.updateGroupPermissions(channel.id, msgAdm, editAdm, app) },
            onUpdateDisappearing = { sec -> chatViewModel.updateDisappearingTimer(channel.id, sec) },
            onMuteToggle = { chatViewModel.toggleChannelMute(channel.id, member.uid) },
            onPinToggle = { chatViewModel.toggleChannelPin(channel.id, member.uid) },
            onArchiveToggle = { chatViewModel.toggleChannelArchive(channel.id, member.uid) },
            onFavouriteToggle = { chatViewModel.toggleChannelFavourite(channel.id, member.uid) },
            onLockToggle = {
                if (!prefs.hasSecretCode) showPinSetupDialog = true
                else ChatSettingsStore.toggleChannelLocked(context, channel.id)
            },
            onResetInviteLink = { chatViewModel.resetInviteLink(channel.id) },
            onApprovePending = { uid -> chatViewModel.approvePendingMember(channel.id, uid) },
            onDeclinePending = { uid -> chatViewModel.declinePendingMember(channel.id, uid) },
            onAddMember = { uid -> chatViewModel.addChannelMember(channel.id, uid) },
            onRemoveMember = { uid -> chatViewModel.removeChannelMember(channel.id, uid) },
            onToggleAdmin = { uid -> chatViewModel.toggleChannelAdmin(channel.id, uid) },
            onClearChat = { chatViewModel.clearChatHistory(channel.id, member.uid); showInfoSheet = false },
            onExitGroup = {
                chatViewModel.exitGroup(channel.id, member.uid) {
                    showInfoSheet = false
                    onBackToHub()
                }
            },
            onReportGroup = {
                showInfoSheet = false
                reportTargetPair = channel to null
            },
            onOpenAttachment = { msg -> showInfoSheet = false; onOpenMessageAttachment(msg) },
            onDismiss = { showInfoSheet = false }
        )
    }

    if (showStarredSheet) {
        StarredMessagesSheet(
            messages = messages,
            myUid = member.uid,
            onUnstar = { msg -> chatViewModel.toggleStar(channel.id, msg.id, member.uid) },
            onDismiss = { showStarredSheet = false }
        )
    }

    forwardTarget?.let { msg ->
        ForwardMessageDialog(
            channels = state.chatChannels,
            myUid = member.uid,
            onSelectChannel = { target ->
                chatViewModel.forwardMessage(msg, target, member) { forwardTarget = null }
            },
            onDismiss = { forwardTarget = null }
        )
    }

    messageInfoTarget?.let { msg ->
        MessageInfoBottomSheet(
            message = msg,
            allMembers = state.members,
            onDismiss = { messageInfoTarget = null }
        )
    }

    reportTargetPair?.let { (ch, msg) ->
        ReportContentDialog(
            title = if (msg == null) "Report Group" else "Report Message",
            onSubmit = { reason ->
                chatViewModel.reportContent(ch, msg, reason, member)
                reportTargetPair = null
            },
            onDismiss = { reportTargetPair = null }
        )
    }

    if (showPinSetupDialog) {
        PinUnlockDialog(
            title = "Set Secret Code",
            subtitle = "Set a PIN first to lock this chat.",
            confirmSetupMode = true,
            onVerified = {
                ChatSettingsStore.toggleChannelLocked(context, channel.id)
                showPinSetupDialog = false
            },
            onDismiss = { showPinSetupDialog = false }
        )
    }

    if (showDeleteThreadConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteThreadConfirm = false },
            containerColor = PureWhite,
            title = { Text("Delete chat thread?", fontWeight = FontWeight.Bold) },
            text = { Text("Permanently delete ${formatTitleCase(channel.title)} and every message in it?") },
            confirmButton = {
                Button(
                    onClick = { showDeleteThreadConfirm = false; onDeleteChannel() },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) { Text("Delete Thread") }
            },
            dismissButton = { TextButton(onClick = { showDeleteThreadConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun QuickAttachPill(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MutedSurface,
        modifier = modifier.height(42.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)
        ) {
            Icon(icon, contentDescription = label, tint = ObsidianCharcoal, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = ObsidianCharcoal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelPickerBottomSheet(
    channels: List<ChatChannel>,
    activeChannelId: String,
    isSuperAdmin: Boolean,
    onSelectChannel: (ChatChannel) -> Unit,
    onDeleteChannel: (String) -> Unit,
    onNewChannel: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        contentColor = ObsidianCharcoal
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Workspace Channels", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text("General, groups, projects, departments & DMs", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                }
                FilledTonalButton(
                    onClick = onNewChannel,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New", fontWeight = FontWeight.Bold)
                }
            }

            channels.forEach { ch ->
                val selected = ch.id == activeChannelId
                val symbol = when (ch.channelTypeEnum()) {
                    ChatChannelType.DIRECT -> "@"
                    ChatChannelType.DEPARTMENT -> "D"
                    ChatChannelType.SAVED -> "★"
                    else -> "#"
                }
                Surface(
                    onClick = { onSelectChannel(ch) },
                    shape = RoundedCornerShape(18.dp),
                    color = if (selected) VioletSoftBg else MutedSurface,
                    border = if (selected) BorderStroke(1.dp, VioletPrimary.copy(alpha = 0.45f)) else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (selected) ObsidianCharcoal else PureWhite),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = if (selected) PureWhite else ObsidianCharcoal)
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(formatTitleCase(ch.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(ch.lastMessagePreview.ifBlank { ch.topic }, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (ch.messageCount > 0) {
                            StatusPill(label = "${ch.messageCount}", containerColor = PureWhite, contentColor = ObsidianCharcoal)
                        }
                        if (isSuperAdmin && ch.id != ChatRepository.GENERAL_CHANNEL_ID) {
                            IconButton(
                                onClick = { onDeleteChannel(ch.id) },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("sheet_delete_channel_${ch.id}")
                            ) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete Channel", tint = CrimsonBlocked, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun CreateChatChannelDialog(
    projects: List<Project>,
    onCreate: (title: String, topic: String, type: ChatChannelType, project: Project?, onCreated: () -> Unit, onError: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var selectedProject by remember { mutableStateOf<Project?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        containerColor = PureWhite,
        title = { Text("New Team Channel", fontWeight = FontWeight.Bold, color = ObsidianCharcoal) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!errorMessage.isNullOrBlank()) {
                    Surface(shape = RoundedCornerShape(12.dp), color = SoftBlush, modifier = Modifier.fillMaxWidth()) {
                        Text(errorMessage.orEmpty(), style = MaterialTheme.typography.bodySmall, color = DeepBlushInk, modifier = Modifier.padding(10.dp))
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; errorMessage = null },
                    label = { Text("Channel Name") },
                    placeholder = { Text("e.g. Design Critiques") },
                    singleLine = true,
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth().testTag("new_channel_title_input")
                )
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it; errorMessage = null },
                    label = { Text("Topic / Description") },
                    placeholder = { Text("Deliverables, briefs & shared updates") },
                    maxLines = 2,
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth().testTag("new_channel_topic_input")
                )
                if (projects.isNotEmpty()) {
                    Text("Or link a Project:", style = MaterialTheme.typography.labelMedium, color = StudioTextSecondary)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        projects.take(6).forEach { proj ->
                            val isSel = selectedProject?.id == proj.id
                            FilterChip(
                                selected = isSel,
                                onClick = {
                                    if (!isSubmitting) {
                                        selectedProject = if (isSel) null else proj
                                        if (!isSel && title.isBlank()) title = proj.title
                                        errorMessage = null
                                    }
                                },
                                label = { Text(proj.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val proj = selectedProject
                    val type = if (proj != null) ChatChannelType.PROJECT else ChatChannelType.GENERAL
                    isSubmitting = true
                    errorMessage = null
                    onCreate(
                        title.ifBlank { proj?.title ?: "Team Thread" },
                        topic,
                        type,
                        proj,
                        { isSubmitting = false },
                        { err -> isSubmitting = false; errorMessage = err }
                    )
                },
                enabled = !isSubmitting && (title.isNotBlank() || selectedProject != null),
                colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                modifier = Modifier.testTag("confirm_create_channel_button")
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = PureWhite)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (isSubmitting) "Creating…" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Cancel") }
        }
    )
}
