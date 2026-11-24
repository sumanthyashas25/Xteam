package com.example.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Reply
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chat.ChatFileManager
import com.example.chat.StagedChatAttachment
import com.example.data.ChatRepository
import com.example.data.SyncedData
import com.example.model.*
import com.example.system.AppHapticsAndNotifier
import com.example.ui.AgencyUiState
import com.example.ui.ChatFilterTab
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
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private fun isSameChatDay(d1: Date?, d2: Date?): Boolean {
    if (d1 == null || d2 == null) return false
    val cal1 = java.util.Calendar.getInstance().apply { time = d1 }
    val cal2 = java.util.Calendar.getInstance().apply { time = d2 }
    return cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
        cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR)
}

private fun formatChatDaySeparator(date: Date?): String {
    if (date == null) return "Today"
    val now = java.util.Calendar.getInstance()
    val msgCal = java.util.Calendar.getInstance().apply { time = date }
    return when {
        now.get(java.util.Calendar.YEAR) == msgCal.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == msgCal.get(java.util.Calendar.DAY_OF_YEAR) -> "Today"
        now.get(java.util.Calendar.YEAR) == msgCal.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) - msgCal.get(java.util.Calendar.DAY_OF_YEAR) == 1 -> "Yesterday"
        else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(date)
    }
}

private enum class ChatScreenMode {
    HUB,
    THREAD
}

private fun resolveDirectPeer(channel: ChatChannel, myUid: String, members: List<Member>): Member? {
    val peerUid = channel.participantUids.firstOrNull { it.isNotBlank() && it != myUid }
    if (!peerUid.isNullOrBlank()) {
        members.firstOrNull { it.uid == peerUid }?.let { return it }
    }
    val parts = channel.title.split("&").map { it.trim() }.filter { it.isNotBlank() }
    if (parts.isNotEmpty()) {
        members.firstOrNull { m ->
            m.uid != myUid && parts.any { p -> m.fullName.equals(p, ignoreCase = true) }
        }?.let { return it }
    }
    return null
}

private fun resolveDirectPeerTitle(channel: ChatChannel, myMember: Member, members: List<Member>): String {
    resolveDirectPeer(channel, myMember.uid, members)?.let { peer ->
        if (peer.fullName.isNotBlank()) return peer.fullName
    }
    val myFirst = myMember.fullName.trim().substringBefore(" ")
    val parts = channel.title.split("&").map { it.trim() }.filter { it.isNotBlank() }
    val otherPart = parts.firstOrNull { part ->
        !part.equals(myMember.fullName.trim(), ignoreCase = true) &&
            (myFirst.isBlank() || !part.equals(myFirst, ignoreCase = true))
    }
    return otherPart ?: channel.title.ifBlank { "Direct Message" }
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
    onCloseChat: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val member = state.myMember ?: return
    val memberPhotoByUid = remember(state.members) { state.members.associate { it.uid to it.photoBase64 } }

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
    var stagedAttachment by remember { mutableStateOf<StagedChatAttachment?>(null) }
    var isProcessingFile by remember { mutableStateOf(false) }
    var openingThreadLabel by remember { mutableStateOf<String?>(null) }
    var replyTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var showChannelPicker by remember { mutableStateOf(false) }
    var showNewChannelDialog by remember { mutableStateOf(false) }
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

    val chatViewModel: ChatViewModel = viewModel()

    LaunchedEffect(activeChannel.id, member.uid) {
        chatViewModel.selectChannel(activeChannel, member.uid)
    }

    val messagesFlow = remember(activeChannel.id) { observeMessages(activeChannel.id) }
    val syncedMessages by messagesFlow.collectAsStateWithLifecycle(initialValue = SyncedData(emptyList(), false))
    val vmMessages by chatViewModel.messages.collectAsStateWithLifecycle()
    val effectiveMessages = if (vmMessages.isNotEmpty()) vmMessages else syncedMessages.data

    fun processPickedUri(uri: Uri?, preferredType: ChatAttachmentType?) {
        if (uri == null) return
        scope.launch {
            isProcessingFile = true
            val res = ChatFileManager.prepareAttachmentFromUri(context, uri, preferredType)
            isProcessingFile = false
            res.onSuccess { staged ->
                stagedAttachment = staged
                onReportMessage("Attached ${staged.fileName} (${ChatFileManager.formatFileSize(staged.sizeBytes)})", false)
            }.onFailure { err ->
                onReportMessage(err.message ?: "Failed to attach file.", true)
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        processPickedUri(uri, ChatAttachmentType.IMAGE)
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        processPickedUri(uri, ChatAttachmentType.FILE)
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        processPickedUri(uri, ChatAttachmentType.AUDIO)
    }

    fun launchImagePicker() {
        imagePickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun launchDocumentPicker() {
        documentPickerLauncher.launch(
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

    fun launchAudioPicker() {
        audioPickerLauncher.launch(arrayOf("audio/*"))
    }

    fun sendCurrentMessage(openThreadAfter: Boolean) {
        val clean = composerText.trim()
        val attachment = stagedAttachment
        if (clean.isEmpty() && attachment == null) return
        val targetId = activeChannel.id
        onSendMessage(targetId, clean, replyTarget, attachment) {
            AppHapticsAndNotifier.triggerCompletionVibration(context)
            composerText = ""
            stagedAttachment = null
            replyTarget = null
            if (openThreadAfter) {
                activeChannelId = targetId
                mode = ChatScreenMode.THREAD
            }
        }
    }

    BackHandler {
        if (mode == ChatScreenMode.THREAD) {
            mode = ChatScreenMode.HUB
        } else {
            onCloseChat()
        }
    }

    AnimatedContent(
        targetState = mode,
        transitionSpec = {
            if (targetState == ChatScreenMode.THREAD) {
                (fadeIn(tween(260, easing = FastOutSlowInEasing)) + slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 4 })
                    .togetherWith(fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -it / 6 })
            } else {
                (fadeIn(tween(260, easing = FastOutSlowInEasing)) + slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 4 })
                    .togetherWith(fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { it / 6 })
            }
        },
        label = "chat_screen_mode_transition"
    ) { currentMode ->
        if (currentMode == ChatScreenMode.HUB) {
            ChatHubView(
                member = member,
                allMembers = state.members,
                isSuperAdmin = state.isSuperAdmin,
                channels = state.chatChannels,
                colleagues = state.members.filter {
                    it.statusEnum() == MembershipStatus.APPROVED && it.active && it.uid != member.uid
                },
                departments = state.departments.filter { it.active },
                activeChannel = activeChannel,
                composerText = composerText,
                onComposerTextChange = { composerText = it },
                stagedAttachment = stagedAttachment,
                isProcessingFile = isProcessingFile,
                openingThreadLabel = openingThreadLabel,
                onClearAttachment = { stagedAttachment = null },
                onOpenChannelsSheet = {
                    AppHapticsAndNotifier.triggerTickHaptic(context)
                    showChannelPicker = true
                },
                onPickAudio = ::launchAudioPicker,
                onPickFiles = ::launchDocumentPicker,
                onPickImages = ::launchImagePicker,
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
                onDeleteChannel = { chId ->
                    onDeleteChannel(chId) {
                        if (activeChannelId == chId) {
                            activeChannelId = ChatRepository.GENERAL_CHANNEL_ID
                        }
                    }
                },
                onCreateNewChannelClick = { showNewChannelDialog = true },
                onSend = { sendCurrentMessage(openThreadAfter = true) },
                onBack = onCloseChat,
                onClose = onCloseChat,
                chatViewModel = chatViewModel
            )
        } else {
            ChatThreadView(
                state = state,
                member = member,
                allMembers = state.members,
                isSuperAdmin = state.isSuperAdmin,
                memberPhotoByUid = memberPhotoByUid,
                channel = activeChannel,
                messages = effectiveMessages,
                hasPendingWrites = syncedMessages.hasPendingWrites,
                composerText = composerText,
                onComposerTextChange = { composerText = it },
                stagedAttachment = stagedAttachment,
                isProcessingFile = isProcessingFile,
                replyTarget = replyTarget,
                onClearReply = { replyTarget = null },
                onSetReply = { replyTarget = it },
                onClearAttachment = { stagedAttachment = null },
                onPickFiles = ::launchDocumentPicker,
                onPickImages = ::launchImagePicker,
                onPickAudio = ::launchAudioPicker,
                onOpenChannelsSheet = { showChannelPicker = true },
                onOpenMessageAttachment = { msg ->
                    if (msg.attachmentTypeEnum() == ChatAttachmentType.IMAGE) {
                        scope.launch {
                            val bmp = withContext(Dispatchers.IO) {
                                ChatFileManager.decodeMessageBitmap(msg, maxEdgePx = 1400)
                            }
                            if (bmp != null) {
                                previewImageMessage = msg to bmp.asImageBitmap()
                            } else {
                                val file = ChatFileManager.resolveLocalFile(context, msg)
                                if (file != null) {
                                    ChatFileManager.openOrShareAttachment(context, file, msg.attachmentMimeType, msg.attachmentName)
                                } else {
                                    onReportMessage("Unable to open image preview.", true)
                                }
                            }
                        }
                    } else {
                        scope.launch {
                            val file = ChatFileManager.resolveLocalFile(context, msg)
                            if (file != null) {
                                ChatFileManager.openOrShareAttachment(
                                    context,
                                    file,
                                    msg.attachmentMimeType,
                                    msg.attachmentName.ifBlank { file.name }
                                )
                            } else {
                                onReportMessage("Attachment file is unavailable offline.", true)
                            }
                        }
                    }
                },
                onDownloadAttachment = { msg ->
                    scope.launch {
                        val res = ChatFileManager.downloadAttachmentToDevice(context, msg)
                        res.onSuccess { savedPath ->
                            onReportMessage("Saved ${msg.attachmentName.ifBlank { "file" }} to $savedPath", false)
                        }.onFailure { err ->
                            onReportMessage(err.message ?: "Failed to save attachment.", true)
                        }
                    }
                },
                onDeleteChannel = {
                    onDeleteChannel(activeChannel.id) {
                        activeChannelId = ChatRepository.GENERAL_CHANNEL_ID
                        mode = ChatScreenMode.HUB
                    }
                },
                onDeleteMessage = { msg -> onDeleteMessage(activeChannel.id, msg.id) },
                onSend = { sendCurrentMessage(openThreadAfter = false) },
                onBackToHub = {
                    if (activeChannel.channelTypeEnum() == ChatChannelType.DIRECT) {
                        activeChannelId = state.chatChannels
                            .firstOrNull { it.channelTypeEnum() != ChatChannelType.DIRECT }?.id
                            ?: ChatRepository.GENERAL_CHANNEL_ID
                    }
                    mode = ChatScreenMode.HUB
                },
                onClose = onCloseChat,
                chatViewModel = chatViewModel
            )
        }
    }

    if (showChannelPicker) {
        ChannelPickerBottomSheet(
            member = member,
            allMembers = state.members,
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
                    if (activeChannelId == chId) {
                        activeChannelId = ChatRepository.GENERAL_CHANNEL_ID
                    }
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

    previewImageMessage?.let { (msg, img) ->
        val title = msg.attachmentName.ifBlank { "Image" }
        Dialog(onDismissRequest = { previewImageMessage = null }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = ObsidianCharcoal,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = PureWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                scope.launch {
                                    ChatFileManager.downloadAttachmentToDevice(context, msg)
                                        .onSuccess { savedPath ->
                                            onReportMessage("Saved $title to $savedPath", false)
                                        }
                                        .onFailure { err ->
                                            onReportMessage(err.message ?: "Failed to save image.", true)
                                        }
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
                                    val file = ChatFileManager.resolveLocalFile(context, msg)
                                    if (file != null) {
                                        ChatFileManager.openOrShareAttachment(context, file, msg.attachmentMimeType, title)
                                    } else {
                                        onReportMessage("File unavailable to share.", true)
                                    }
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
private fun Modifier.animatedChatGlowBackdrop(isThread: Boolean): Modifier {
    val transition = rememberInfiniteTransition(label = "chat_ambient_glow_motion")
    val primaryOrbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glow_primary_orbit"
    )
    val secondaryOrbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 17500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glow_secondary_orbit"
    )
    val driftOrbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glow_drift_orbit"
    )

    return this.drawBehind {
        // Rotating directional ambient wash across full screen
        val sweepStart = Offset(
            x = size.width * (0.5f + 0.5f * cos(primaryOrbit)),
            y = size.height * (0.5f + 0.5f * sin(primaryOrbit))
        )
        val sweepEnd = Offset(
            x = size.width - sweepStart.x,
            y = size.height - sweepStart.y
        )
        drawRect(
            brush = Brush.linearGradient(
                colors = if (isThread) {
                    listOf(
                        Color(0xFFFFE4DA).copy(alpha = 0.95f),
                        Color(0xFFF3E8FF).copy(alpha = 0.95f),
                        Color(0xFFE0F4FF).copy(alpha = 0.95f)
                    )
                } else {
                    listOf(
                        Color(0xFFD6FFF0).copy(alpha = 0.95f),
                        Color(0xFFECE4FF).copy(alpha = 0.95f),
                        Color(0xFFFFE5D9).copy(alpha = 0.95f)
                    )
                },
                start = sweepStart,
                end = sweepEnd
            )
        )

        val c1 = Offset(
            x = size.width * (0.50f + 0.30f * cos(primaryOrbit)),
            y = size.height * (0.42f + 0.24f * sin(primaryOrbit))
        )
        val c2 = Offset(
            x = size.width * (0.24f + 0.30f * sin(secondaryOrbit)),
            y = size.height * (0.28f + 0.24f * cos(secondaryOrbit))
        )
        val c3 = Offset(
            x = size.width * (0.76f - 0.28f * cos(driftOrbit)),
            y = size.height * (0.64f + 0.24f * sin(driftOrbit))
        )
        val c4 = Offset(
            x = size.width * (0.54f - 0.26f * sin(primaryOrbit)),
            y = size.height * (0.78f - 0.20f * cos(secondaryOrbit))
        )

        val primaryColor = if (isThread) Color(0xFFFB7185).copy(alpha = 0.33f) else Color(0xFF10B981).copy(alpha = 0.37f)
        val secondColor = if (isThread) Color(0xFF8B5CF6).copy(alpha = 0.35f) else Color(0xFF8B5CF6).copy(alpha = 0.33f)
        val thirdColor = if (isThread) Color(0xFF38BDF8).copy(alpha = 0.31f) else Color(0xFFFB923C).copy(alpha = 0.35f)
        val fourthColor = if (isThread) Color(0xFFFDBA74).copy(alpha = 0.31f) else Color(0xFF38BDF8).copy(alpha = 0.29f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primaryColor, primaryColor.copy(alpha = 0.09f), Color.Transparent),
                center = c1,
                radius = size.width * 0.90f
            ),
            radius = size.width * 0.90f,
            center = c1
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondColor, secondColor.copy(alpha = 0.07f), Color.Transparent),
                center = c2,
                radius = size.width * 0.78f
            ),
            radius = size.width * 0.78f,
            center = c2
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(thirdColor, thirdColor.copy(alpha = 0.07f), Color.Transparent),
                center = c3,
                radius = size.width * 0.74f
            ),
            radius = size.width * 0.74f,
            center = c3
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(fourthColor, Color.Transparent),
                center = c4,
                radius = size.width * 0.68f
            ),
            radius = size.width * 0.68f,
            center = c4
        )
    }
}

@Composable
private fun ChatHubView(
    member: Member,
    allMembers: List<Member>,
    isSuperAdmin: Boolean,
    channels: List<ChatChannel>,
    colleagues: List<Member>,
    departments: List<Department>,
    activeChannel: ChatChannel,
    composerText: String,
    onComposerTextChange: (String) -> Unit,
    stagedAttachment: StagedChatAttachment?,
    isProcessingFile: Boolean,
    openingThreadLabel: String?,
    onClearAttachment: () -> Unit,
    onOpenChannelsSheet: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFiles: () -> Unit,
    onPickImages: () -> Unit,
    onSelectChannel: (ChatChannel) -> Unit,
    onOpenDirectChat: (Member) -> Unit,
    onOpenDepartmentChat: (Department) -> Unit,
    onDeleteChannel: (String) -> Unit,
    onCreateNewChannelClick: () -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    chatViewModel: ChatViewModel
) {
    val context = LocalContext.current
    val firstName = member.fullName.trim().substringBefore(" ").ifBlank { "Member" }
    var deleteChannelTarget by remember { mutableStateOf<ChatChannel?>(null) }
    var selectedChannelForMenu by remember { mutableStateOf<ChatChannel?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") } // "All", "Unread", "Groups", "Favourites"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureWhite)
            .animatedChatGlowBackdrop(isThread = false)
            .testTag("chat_hub_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .widthIn(max = 620.dp)
                .align(Alignment.Center)
        ) {
            // WhatsApp-Style Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("chat_hub_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = ObsidianCharcoal
                        )
                    }
                    Text(
                        text = "X.MAX Chats",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = ObsidianCharcoal
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onCreateNewChannelClick,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("chat_new_group_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GroupAdd,
                            contentDescription = "New Group or Channel",
                            tint = ObsidianCharcoal
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("chat_hub_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close Chat",
                            tint = ObsidianCharcoal
                        )
                    }
                }
            }

            // Search Bar
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MutedSurface.copy(alpha = 0.90f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = StudioTextSecondary, modifier = Modifier.size(20.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search chats & messages…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = StudioTextSecondary
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = ObsidianCharcoal),
                            cursorBrush = SolidColor(ObsidianCharcoal),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chat_search_input")
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = StudioTextSecondary)
                        }
                    }
                }
            }

            // Filter Chips: All, Unread, Groups, Favourites
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All", "Unread", "Groups", "Favourites").forEach { filter ->
                    val isSelected = selectedFilter == filter
                    Surface(
                        onClick = { selectedFilter = filter },
                        shape = RoundedCornerShape(999.dp),
                        color = if (isSelected) ObsidianCharcoal else MutedSurface.copy(alpha = 0.70f),
                        modifier = Modifier.testTag("filter_chip_$filter")
                    ) {
                        Text(
                            text = filter,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) PureWhite else ObsidianCharcoal,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = !openingThreadLabel.isNullOrBlank(),
                enter = fadeIn(tween(220, easing = FastOutSlowInEasing)) + expandVertically(tween(240, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(180, easing = FastOutSlowInEasing)) + shrinkVertically(tween(200, easing = FastOutSlowInEasing))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = ObsidianCharcoal,
                        shadowElevation = 4.dp,
                        modifier = Modifier.testTag("chat_opening_indicator")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = PureWhite
                            )
                            Text(
                                text = openingThreadLabel.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                color = PureWhite,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Unified Conversation List (LazyColumn)
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // 1. Direct Messages
                val filteredColleagues = colleagues.filter { person ->
                    val name = person.fullName.ifBlank { "Member" }
                    val matchesSearch = searchQuery.isBlank() || name.contains(searchQuery, ignoreCase = true)
                    matchesSearch && (selectedFilter == "All" || selectedFilter == "Unread")
                }

                items(filteredColleagues.size) { index ->
                    val person = filteredColleagues[index]
                    val existingDm = channels.firstOrNull { ch ->
                        ch.channelTypeEnum() == ChatChannelType.DIRECT && (
                            ch.id == ChatRepository.directChannelId(member.uid, person.uid) ||
                                (ch.participantUids.contains(person.uid) && ch.participantUids.contains(member.uid))
                            )
                    }
                    val roleOrDept = formatTitleCase(person.jobRoleName.ifBlank { person.departmentName })
                    val previewLine = existingDm?.lastMessagePreview?.takeIf { it.isNotBlank() } ?: roleOrDept
                    val unreadCount = existingDm?.messageCount ?: 0L

                    Surface(
                        onClick = { onOpenDirectChat(person) },
                        shape = RoundedCornerShape(16.dp),
                        color = PureWhite.copy(alpha = 0.95f),
                        border = BorderStroke(1.dp, SubtleOutline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dm_item_${person.uid}")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box {
                                StudioAvatar(
                                    name = person.fullName,
                                    size = 50.dp,
                                    borderColor = PureWhite,
                                    photoBase64 = person.photoBase64
                                )
                                // Online indicator dot
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .background(EmeraldApproved, CircleShape)
                                        .border(2.dp, PureWhite, CircleShape)
                                        .align(Alignment.BottomEnd)
                                )
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = formatTitleCase(person.fullName.ifBlank { "Member" }),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = ObsidianCharcoal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Active",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = EmeraldApproved
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = previewLine,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (unreadCount > 0L) {
                                        Spacer(Modifier.width(8.dp))
                                        StatusPill(
                                            label = "$unreadCount",
                                            containerColor = VioletPrimary,
                                            contentColor = PureWhite
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Team Channels & Department Chats (Sorted: Pinned first, then by lastMessageAtMillis)
                val sortedChannels = remember(channels, member.uid) {
                    channels.sortedWith(
                        compareByDescending<ChatChannel> { it.isPinnedFor(member.uid) }
                            .thenByDescending { it.lastMessageAtMillis }
                    )
                }
                val teamChannels = sortedChannels.filter { ch ->
                    val name = ch.title
                    val matchesSearch = searchQuery.isBlank() || name.contains(searchQuery, ignoreCase = true) || ch.lastMessagePreview.contains(searchQuery, ignoreCase = true)
                    val isGroup = ch.channelTypeEnum() == ChatChannelType.DEPARTMENT || ch.channelTypeEnum() == ChatChannelType.GENERAL || ch.channelTypeEnum() == ChatChannelType.PROJECT
                    val matchesFilter = when (selectedFilter) {
                        "Unread" -> ch.messageCount > 0L
                        "Groups" -> isGroup
                        "Favourites" -> ch.isFavouriteFor(member.uid)
                        else -> true
                    }
                    matchesSearch && matchesFilter
                }

                items(teamChannels.size) { index ->
                    val ch = teamChannels[index]
                    val isSelected = ch.id == activeChannel.id
                    val prefix = when (ch.channelTypeEnum()) {
                        ChatChannelType.DEPARTMENT -> "Dept:"
                        ChatChannelType.PROJECT -> "Proj:"
                        ChatChannelType.ANNOUNCEMENT -> "📣"
                        ChatChannelType.SAVED -> "💾"
                        else -> "#"
                    }
                    val isPinned = ch.isPinnedFor(member.uid)
                    val isMuted = ch.isMutedFor(member.uid)
                    val isFav = ch.isFavouriteFor(member.uid)

                    val timeLabel = remember(ch.lastMessageAtMillis) {
                        if (ch.lastMessageAtMillis > 0L) {
                            val now = java.util.Calendar.getInstance()
                            val chCal = java.util.Calendar.getInstance().apply { timeInMillis = ch.lastMessageAtMillis }
                            when {
                                now.get(java.util.Calendar.YEAR) == chCal.get(java.util.Calendar.YEAR) &&
                                    now.get(java.util.Calendar.DAY_OF_YEAR) == chCal.get(java.util.Calendar.DAY_OF_YEAR) ->
                                    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ch.lastMessageAtMillis))
                                now.get(java.util.Calendar.YEAR) == chCal.get(java.util.Calendar.YEAR) &&
                                    now.get(java.util.Calendar.DAY_OF_YEAR) - chCal.get(java.util.Calendar.DAY_OF_YEAR) == 1 -> "Yesterday"
                                else -> SimpleDateFormat("d/M/yy", Locale.getDefault()).format(Date(ch.lastMessageAtMillis))
                            }
                        } else ""
                    }

                    Surface(
                        onClick = { onSelectChannel(ch) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) VioletSoftBg.copy(alpha = 0.5f) else PureWhite.copy(alpha = 0.95f),
                        border = BorderStroke(1.dp, if (isSelected) VioletPrimary else SubtleOutline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("channel_item_${ch.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (isPinned) VioletSoftBg else KpiLavenderBg,
                                modifier = Modifier.size(50.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = when (ch.channelTypeEnum()) {
                                            ChatChannelType.DEPARTMENT -> Icons.Filled.Groups
                                            ChatChannelType.PROJECT -> Icons.Filled.Folder
                                            ChatChannelType.ANNOUNCEMENT -> Icons.Filled.Campaign
                                            ChatChannelType.SAVED -> Icons.Filled.Bookmark
                                            else -> Icons.AutoMirrored.Filled.Chat
                                        },
                                        contentDescription = null,
                                        tint = if (isPinned) VioletPrimary else ObsidianCharcoal,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "$prefix ${formatTitleCase(ch.title)}",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = ObsidianCharcoal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isFav) {
                                            Icon(Icons.Default.Star, contentDescription = "Favourite", tint = Color(0xFFF59E0B), modifier = Modifier.size(13.dp))
                                        }
                                        if (isMuted) {
                                            Icon(Icons.Default.VolumeOff, contentDescription = "Muted", tint = StudioTextSecondary, modifier = Modifier.size(13.dp))
                                        }
                                        if (isPinned) {
                                            Icon(Icons.Default.PushPin, contentDescription = "Pinned", tint = VioletPrimary, modifier = Modifier.size(13.dp))
                                        }
                                    }

                                    if (timeLabel.isNotBlank()) {
                                        Text(
                                            text = timeLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StudioTextSecondary
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = ch.lastMessagePreview.ifBlank { ch.topic },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (ch.messageCount > 0) {
                                            Spacer(Modifier.width(6.dp))
                                            StatusPill(
                                                label = "${ch.messageCount}",
                                                containerColor = EmeraldApproved,
                                                contentColor = PureWhite
                                            )
                                        }
                                        IconButton(
                                            onClick = { selectedChannelForMenu = ch },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "Channel options", tint = StudioTextSecondary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Composer for Quick Broadcast / General Note
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isProcessingFile) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(999.dp)),
                        color = ObsidianCharcoal,
                        trackColor = MutedSurface
                    )
                }

                stagedAttachment?.let { att ->
                    StagedAttachmentBanner(attachment = att, onRemove = onClearAttachment)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    IconButton(onClick = onPickImages, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Image, contentDescription = "Attach Image", tint = ObsidianCharcoal)
                    }
                    IconButton(onClick = onPickFiles, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Description, contentDescription = "Attach Document", tint = ObsidianCharcoal)
                    }
                    IconButton(onClick = onPickAudio, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Mic, contentDescription = "Record Voice Note", tint = ObsidianCharcoal)
                    }
                }

                val canSend = composerText.isNotBlank() || stagedAttachment != null
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Color(0xFFE7E8EC).copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, PureWhite.copy(alpha = 0.85f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(start = 20.dp, end = 7.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (composerText.isEmpty()) {
                                Text(
                                    text = "Broadcast to General…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = StudioTextSecondary
                                )
                            }
                            BasicTextField(
                                value = composerText,
                                onValueChange = onComposerTextChange,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = ObsidianCharcoal),
                                cursorBrush = SolidColor(ObsidianCharcoal),
                                maxLines = 4,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("chat_hub_input")
                            )
                        }
                        Surface(
                            onClick = onSend,
                            enabled = canSend && !isProcessingFile,
                            shape = CircleShape,
                            color = if (canSend) ObsidianCharcoal else PureWhite.copy(alpha = 0.85f),
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("chat_hub_send_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowUpward,
                                    contentDescription = "Send Message",
                                    tint = if (canSend) PureWhite else ObsidianCharcoal,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    deleteChannelTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteChannelTarget = null },
            containerColor = PureWhite,
            title = { Text("Delete chat thread?", fontWeight = FontWeight.Bold, color = ObsidianCharcoal) },
            text = { Text("Permanently delete ${formatTitleCase(target.title)} and all its messages?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteChannel(target.id)
                        deleteChannelTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) { Text("Delete", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { deleteChannelTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun FrostedChatBentoTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    artwork: @Composable BoxScope.() -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = PureWhite.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, PureWhite.copy(alpha = 0.92f)),
        modifier = modifier.height(134.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                artwork()
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(ObsidianCharcoal),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = PureWhite,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ObsidianCharcoal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ChannelsBentoArtwork() {
    Box(modifier = Modifier.size(width = 88.dp, height = 66.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFDDE3EB),
            modifier = Modifier
                .size(46.dp)
                .offset(x = 14.dp, y = 4.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "PM",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = StudioTextSecondary
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF3E7BF6),
            shadowElevation = 3.dp,
            modifier = Modifier
                .size(48.dp)
                .offset(x = (-12).dp, y = (-4).dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "#",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = PureWhite
                )
            }
        }
    }
}

@Composable
private fun AudioWaveformArtwork() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = PureWhite.copy(alpha = 0.92f),
        shadowElevation = 1.dp,
        modifier = Modifier.size(width = 86.dp, height = 56.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val heights = listOf(8.dp, 18.dp, 12.dp, 26.dp, 14.dp, 22.dp, 10.dp, 16.dp, 8.dp)
            heights.forEachIndexed { idx, h ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(h)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (idx in 2..5) ObsidianCharcoal else Color(0xFFA0A5B1))
                )
            }
        }
    }
}

@Composable
private fun ChatFilesBentoArtwork() {
    Box(modifier = Modifier.size(width = 96.dp, height = 68.dp), contentAlignment = Alignment.Center) {
        // Blue DOC card behind on right
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF4285F4),
            shadowElevation = 2.dp,
            modifier = Modifier
                .size(width = 46.dp, height = 56.dp)
                .offset(x = 16.dp, y = 2.dp)
                .rotate(8f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                repeat(4) { i ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (i == 3) 0.65f else 1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(PureWhite.copy(alpha = 0.88f))
                    )
                }
            }
        }
        // White PDF card in front on left
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PureWhite,
            border = BorderStroke(1.dp, SubtleOutline),
            shadowElevation = 4.dp,
            modifier = Modifier
                .size(width = 48.dp, height = 58.dp)
                .offset(x = (-14).dp, y = (-2).dp)
                .rotate(-8f)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Canvas(modifier = Modifier.size(22.dp)) {
                    val p = Path().apply {
                        moveTo(size.width * 0.5f, size.height * 0.14f)
                        lineTo(size.width * 0.22f, size.height * 0.78f)
                        cubicTo(
                            size.width * 0.16f, size.height * 0.90f,
                            size.width * 0.84f, size.height * 0.50f,
                            size.width * 0.82f, size.height * 0.72f
                        )
                        cubicTo(
                            size.width * 0.80f, size.height * 0.88f,
                            size.width * 0.24f, size.height * 0.52f,
                            size.width * 0.5f, size.height * 0.14f
                        )
                    }
                    drawPath(
                        path = p,
                        color = Color(0xFFE53935),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "PDF",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE53935)
                )
            }
        }
    }
}

@Composable
private fun ImagesBentoArtwork() {
    Box(modifier = Modifier.size(width = 98.dp, height = 68.dp), contentAlignment = Alignment.Center) {
        // Right warm sand card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFEED3B5),
            border = BorderStroke(1.5.dp, PureWhite),
            shadowElevation = 2.dp,
            modifier = Modifier
                .size(width = 48.dp, height = 54.dp)
                .offset(x = 16.dp, y = (-2).dp)
                .rotate(10f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFFF6C6A5), Color(0xFFE1B18E))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFC58C64))
                )
            }
        }
        // Left vivid landscape card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF9ED5F0),
            border = BorderStroke(1.5.dp, PureWhite),
            shadowElevation = 4.dp,
            modifier = Modifier
                .size(width = 50.dp, height = 56.dp)
                .offset(x = (-14).dp, y = 2.dp)
                .rotate(-8f)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF81D4FA), Color(0xFFE1F5FE))
                    )
                )
                drawCircle(
                    color = Color(0xFFFF8A65),
                    radius = size.minDimension * 0.20f,
                    center = Offset(size.width * 0.34f, size.height * 0.36f)
                )
                val hill = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(0f, size.height * 0.65f)
                    lineTo(size.width * 0.42f, size.height * 0.45f)
                    lineTo(size.width * 0.72f, size.height * 0.62f)
                    lineTo(size.width, size.height * 0.50f)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(hill, color = Color(0xFF4CAF50))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatThreadView(
    state: AgencyUiState,
    member: Member,
    allMembers: List<Member>,
    isSuperAdmin: Boolean,
    memberPhotoByUid: Map<String, String>,
    channel: ChatChannel,
    messages: List<ChatMessage>,
    hasPendingWrites: Boolean,
    composerText: String,
    onComposerTextChange: (String) -> Unit,
    stagedAttachment: StagedChatAttachment?,
    isProcessingFile: Boolean,
    replyTarget: ChatMessage?,
    onClearReply: () -> Unit,
    onSetReply: (ChatMessage) -> Unit,
    onClearAttachment: () -> Unit,
    onPickFiles: () -> Unit,
    onPickImages: () -> Unit,
    onPickAudio: () -> Unit,
    onOpenChannelsSheet: () -> Unit,
    onOpenMessageAttachment: (ChatMessage) -> Unit,
    onDownloadAttachment: (ChatMessage) -> Unit,
    onDeleteChannel: () -> Unit,
    onDeleteMessage: (ChatMessage) -> Unit,
    onSend: () -> Unit,
    onBackToHub: () -> Unit,
    onClose: () -> Unit,
    chatViewModel: ChatViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteThreadConfirm by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showPollDialog by remember { mutableStateOf(false) }
    var showBusinessShareDialog by remember { mutableStateOf(false) }
    var showConversationInfo by remember { mutableStateOf(false) }
    var inChatSearchQuery by remember { mutableStateOf("") }
    var isSearchingInChat by remember { mutableStateOf(false) }

    val ephemeralActivity by chatViewModel.ephemeralActivity.collectAsStateWithLifecycle()
    val isRecordingAudio by chatViewModel.isRecordingAudio.collectAsStateWithLifecycle()
    val recordingDurationSec by chatViewModel.recordingDurationSec.collectAsStateWithLifecycle()
    val editingMessage by chatViewModel.editingMessage.collectAsStateWithLifecycle()

    val filteredMessages = remember(messages, inChatSearchQuery) {
        if (inChatSearchQuery.isBlank()) messages
        else messages.filter {
            it.body.contains(inChatSearchQuery, ignoreCase = true) ||
                it.senderName.contains(inChatSearchQuery, ignoreCase = true) ||
                it.attachmentName.contains(inChatSearchQuery, ignoreCase = true) ||
                it.businessRefTitle.contains(inChatSearchQuery, ignoreCase = true)
        }
    }

    val pinnedMsg = remember(messages) { messages.lastOrNull { it.pinned } }

    val listState = rememberLazyListState()
    LaunchedEffect(filteredMessages.size) {
        if (filteredMessages.isNotEmpty()) {
            listState.animateScrollToItem(filteredMessages.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureWhite)
            .animatedChatGlowBackdrop(isThread = true)
            .testTag("chat_thread_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .widthIn(max = 620.dp)
                .align(Alignment.Center)
        ) {
            // WhatsApp-style Top Header Bar
            val isDirect = channel.channelTypeEnum() == ChatChannelType.DIRECT
            val directPeer = remember(channel, member.uid, allMembers) {
                if (isDirect) resolveDirectPeer(channel, member.uid, allMembers) else null
            }
            val peerDisplayName = remember(channel, member, directPeer, allMembers) {
                if (isDirect) formatTitleCase(resolveDirectPeerTitle(channel, member, allMembers)) else formatTitleCase(channel.title)
            }

            Surface(
                color = PureWhite,
                shadowElevation = 1.dp,
                border = BorderStroke(1.dp, SubtleOutline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBackToHub,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("chat_thread_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Chat Hub", tint = ObsidianCharcoal)
                    }

                    // Clickable Avatar + Details (Opens Conversation Info)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showConversationInfo = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box {
                            StudioAvatar(
                                name = peerDisplayName,
                                size = 40.dp,
                                borderColor = PureWhite,
                                photoBase64 = directPeer?.photoBase64.orEmpty()
                            )
                            if (isDirect) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(EmeraldApproved, CircleShape)
                                        .border(2.dp, PureWhite, CircleShape)
                                        .align(Alignment.BottomEnd)
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = peerDisplayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = ObsidianCharcoal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            // Status subtitle: Typing > Recording > Online / Member Count
                            when {
                                ephemeralActivity.typingNames.isNotEmpty() -> {
                                    val typingLabel = if (isDirect) "typing…" else "${ephemeralActivity.typingNames.first().substringBefore(" ")} is typing…"
                                    Text(
                                        text = typingLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = EmeraldApproved
                                    )
                                }
                                ephemeralActivity.recordingNames.isNotEmpty() -> {
                                    val recLabel = if (isDirect) "recording audio…" else "${ephemeralActivity.recordingNames.first().substringBefore(" ")} is recording audio…"
                                    Text(
                                        text = recLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = CrimsonBlocked
                                    )
                                }
                                isDirect -> {
                                    Text(
                                        text = "Online • Tap for info",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioTextSecondary
                                    )
                                }
                                else -> {
                                    Text(
                                        text = "${channel.participantUids.size.coerceAtLeast(1)} members • Tap for info",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioTextSecondary
                                    )
                                }
                            }
                        }
                    }

                    // Action Icons
                    IconButton(
                        onClick = { isSearchingInChat = !isSearchingInChat },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search messages", tint = ObsidianCharcoal, modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = { showConversationInfo = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = "Conversation info", tint = ObsidianCharcoal, modifier = Modifier.size(20.dp))
                    }
                    if (isSuperAdmin && channel.id != ChatRepository.GENERAL_CHANNEL_ID) {
                        IconButton(
                            onClick = { showDeleteThreadConfirm = true },
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("thread_delete_channel_button")
                        ) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete Thread", tint = CrimsonBlocked, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            // In-Chat Search Bar
            AnimatedVisibility(visible = isSearchingInChat) {
                Surface(
                    color = MutedSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = StudioTextSecondary, modifier = Modifier.size(18.dp))
                        BasicTextField(
                            value = inChatSearchQuery,
                            onValueChange = { inChatSearchQuery = it },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = ObsidianCharcoal),
                            cursorBrush = SolidColor(ObsidianCharcoal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        if (inChatSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { inChatSearchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = StudioTextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            // Pinned Message Banner
            if (pinnedMsg != null) {
                Surface(
                    color = VioletSoftBg,
                    border = BorderStroke(1.dp, VioletPrimary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.PushPin, contentDescription = null, tint = VioletPrimary, modifier = Modifier.size(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Pinned Message",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = VioletPrimary
                            )
                            Text(
                                text = pinnedMsg.body.ifBlank { pinnedMsg.attachmentName },
                                style = MaterialTheme.typography.bodySmall,
                                color = ObsidianCharcoal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { chatViewModel.togglePin(channel.id, pinnedMsg.id) },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Unpin", modifier = Modifier.size(14.dp), tint = StudioTextSecondary)
                        }
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
                            .padding(horizontal = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = if (isDirect) "Private 1:1 chat with $peerDisplayName" else "Welcome to # ${channel.title}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = ObsidianCharcoal,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Send messages, voice notes, polls, photos, documents & business cards securely.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary,
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
                            val showDateSep = prev == null || !isSameChatDay(prev.createdAt?.toDate(), msg.createdAt?.toDate())
                            if (showDateSep) {
                                DateSeparatorPill(label = formatChatDaySeparator(msg.createdAt?.toDate()))
                            }

                            val showSenderHeader = prev == null || prev.senderUid != msg.senderUid

                            WhatsAppMessageBubble(
                                message = msg,
                                isMe = msg.senderUid == member.uid,
                                myUid = member.uid,
                                senderPhotoBase64 = memberPhotoByUid[msg.senderUid].orEmpty(),
                                showSenderHeader = showSenderHeader,
                                onReply = { onSetReply(msg) },
                                onEdit = {
                                    chatViewModel.setEditing(msg)
                                    onComposerTextChange(msg.body)
                                },
                                onDeleteForMe = { chatViewModel.deleteForMe(channel.id, msg.id, member.uid) },
                                onDeleteForEveryone = { chatViewModel.deleteForEveryone(channel.id, msg.id) },
                                onToggleStar = { chatViewModel.toggleStar(channel.id, msg.id, member.uid) },
                                onTogglePin = { chatViewModel.togglePin(channel.id, msg.id) },
                                onReact = { emoji -> chatViewModel.reactToMessage(channel.id, msg.id, emoji, member.uid) },
                                onVotePoll = { optIdx -> chatViewModel.votePoll(channel.id, msg.id, optIdx, member.uid) },
                                onOpenAttachment = { onOpenMessageAttachment(msg) },
                                onDownloadAttachment = { onDownloadAttachment(msg) }
                            )
                        }
                    }
                }
            }

            // Advanced WhatsApp Message Composer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Editing Message Banner
                if (editingMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFEF3C7),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(16.dp))
                            Text(
                                text = "Editing: ${editingMessage?.body}",
                                style = MaterialTheme.typography.bodySmall,
                                color = ObsidianCharcoal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    chatViewModel.setEditing(null)
                                    onComposerTextChange("")
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel edit", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                // Reply Target Banner
                if (replyTarget != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MutedSurface,
                        border = BorderStroke(1.dp, SubtleOutline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = VioletPrimary, modifier = Modifier.size(16.dp))
                            Text(
                                text = "Replying to ${replyTarget.senderName.substringBefore(" ")}: ${replyTarget.body.ifBlank { replyTarget.attachmentName }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = ObsidianCharcoal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onClearReply, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel reply", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                // Staged Attachment Banner
                if (stagedAttachment != null) {
                    StagedAttachmentBanner(attachment = stagedAttachment, onRemove = onClearAttachment)
                }

                // Quick Emoji Picker Row
                AnimatedVisibility(visible = showEmojiPicker) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = PureWhite,
                        border = BorderStroke(1.dp, SubtleOutline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("😊", "👍", "❤️", "🔥", "🎉", "👏", "💼", "📊", "🤝", "✨", "🚀", "💯", "🙏", "✅").forEach { emoji ->
                                Text(
                                    text = emoji,
                                    fontSize = 22.sp,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable { onComposerTextChange(composerText + emoji) }
                                        .padding(4.dp)
                                )
                            }
                        }
                    }
                }

                // Composer Bar or Voice Recording Mode
                if (isRecordingAudio) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = PureWhite,
                        border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.5f)),
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .background(CrimsonBlocked, CircleShape)
                            )
                            val timerText = String.format("%02d:%02d", recordingDurationSec / 60, recordingDurationSec % 60)
                            Text(
                                text = timerText,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = CrimsonBlocked
                            )
                            Text(
                                text = "Recording audio…",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { chatViewModel.cancelRecording(channel.id, member.fullName) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Cancel recording", tint = CrimsonBlocked)
                            }
                            Surface(
                                onClick = { chatViewModel.stopAndSendRecording(channel.id, member.fullName, member) },
                                shape = CircleShape,
                                color = EmeraldApproved,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice note", tint = PureWhite, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = PureWhite,
                        border = BorderStroke(1.dp, SubtleOutline),
                        shadowElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Attachment Paperclip
                            IconButton(
                                onClick = { showAttachMenu = true },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(Icons.Default.AttachFile, contentDescription = "Attach", tint = ObsidianCharcoal, modifier = Modifier.size(22.dp))
                            }

                            // Emoji Mood Toggle
                            IconButton(
                                onClick = { showEmojiPicker = !showEmojiPicker },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = if (showEmojiPicker) Icons.Default.Keyboard else Icons.Default.Mood,
                                    contentDescription = "Emoji",
                                    tint = ObsidianCharcoal,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            // Text Field
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (composerText.isEmpty()) {
                                    Text(
                                        text = "Message…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = StudioTextSecondary
                                    )
                                }
                                BasicTextField(
                                    value = composerText,
                                    onValueChange = {
                                        onComposerTextChange(it)
                                        chatViewModel.notifyTyping(channel.id, member.fullName)
                                    },
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ObsidianCharcoal),
                                    cursorBrush = SolidColor(ObsidianCharcoal),
                                    maxLines = 5,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("chat_thread_input")
                                )
                            }

                            // Send or Mic Button
                            val canSend = composerText.isNotBlank() || stagedAttachment != null || editingMessage != null
                            if (canSend) {
                                Surface(
                                    onClick = onSend,
                                    shape = CircleShape,
                                    color = ObsidianCharcoal,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("chat_thread_send_button")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "Send",
                                            tint = PureWhite,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    onClick = { chatViewModel.startRecording(channel.id, member.fullName) },
                                    shape = CircleShape,
                                    color = MutedSurface,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("chat_mic_record_button")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Mic,
                                            contentDescription = "Voice Record",
                                            tint = ObsidianCharcoal,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Attachment ModalBottomSheet
    if (showAttachMenu) {
        ModalBottomSheet(
            onDismissRequest = { showAttachMenu = false },
            containerColor = PureWhite,
            contentColor = ObsidianCharcoal
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Share Content",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            showAttachMenu = false
                            onPickImages()
                        }
                    ) {
                        Surface(shape = CircleShape, color = Color(0xFFEFF6FF), modifier = Modifier.size(54.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Image, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(26.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Gallery", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            showAttachMenu = false
                            onPickFiles()
                        }
                    ) {
                        Surface(shape = CircleShape, color = Color(0xFFFDF2F8), modifier = Modifier.size(54.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Description, contentDescription = null, tint = Color(0xFFDB2777), modifier = Modifier.size(26.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Document", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            showAttachMenu = false
                            showPollDialog = true
                        }
                    ) {
                        Surface(shape = CircleShape, color = Color(0xFFF5F3FF), modifier = Modifier.size(54.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Poll, contentDescription = null, tint = VioletPrimary, modifier = Modifier.size(26.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Poll", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            showAttachMenu = false
                            showBusinessShareDialog = true
                        }
                    ) {
                        Surface(shape = CircleShape, color = Color(0xFFECFDF5), modifier = Modifier.size(54.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Receipt, contentDescription = null, tint = EmeraldApproved, modifier = Modifier.size(26.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Business", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Create Poll Dialog
    if (showPollDialog) {
        CreatePollDialog(
            onSubmit = { question, options ->
                chatViewModel.sendPoll(channel.id, question, options, member)
                showPollDialog = false
            },
            onDismiss = { showPollDialog = false }
        )
    }

    // Share Business Record Dialog
    if (showBusinessShareDialog) {
        ShareBusinessRecordDialog(
            state = state,
            onShare = { kind, refId, title, sub, status, amt ->
                chatViewModel.sendBusinessCard(channel.id, kind, refId, title, sub, status, amt, member)
                showBusinessShareDialog = false
            },
            onDismiss = { showBusinessShareDialog = false }
        )
    }

    // Conversation Info Bottom Sheet
    if (showConversationInfo) {
        ConversationInfoSheet(
            channel = channel,
            allMembers = allMembers,
            myUid = member.uid,
            isSuperAdmin = isSuperAdmin,
            onMuteToggle = { chatViewModel.toggleChannelMute(channel.id, member.uid) },
            onAddMember = { uid -> chatViewModel.addChannelMember(channel.id, uid) },
            onRemoveMember = { uid -> chatViewModel.removeChannelMember(channel.id, uid) },
            onToggleAdmin = { uid -> chatViewModel.toggleChannelAdmin(channel.id, uid) },
            onClearChat = {
                onDeleteChannel()
                showConversationInfo = false
            },
            onDismiss = { showConversationInfo = false }
        )
    }

    if (showDeleteThreadConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteThreadConfirm = false },
            containerColor = PureWhite,
            title = { Text("Delete chat thread?", fontWeight = FontWeight.Bold, color = ObsidianCharcoal) },
            text = { Text("Permanently delete ${formatTitleCase(channel.title)} and every message in it?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteThreadConfirm = false
                        onDeleteChannel()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) { Text("Delete Thread", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteThreadConfirm = false }) { Text("Cancel") }
            }
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
        shape = RoundedCornerShape(18.dp),
        color = MutedSurface,
        modifier = modifier.height(48.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = ObsidianCharcoal,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = ObsidianCharcoal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ThreadedMessageRow(
    message: ChatMessage,
    isMe: Boolean,
    isSuperAdmin: Boolean,
    senderPhotoBase64: String,
    showSenderHeader: Boolean,
    isThreadedFollowUp: Boolean,
    isReplyBranch: Boolean,
    onReply: () -> Unit,
    onDelete: () -> Unit,
    onOpenAttachment: () -> Unit,
    onDownloadAttachment: () -> Unit
) {
    val timeLabel = remember(message.createdAt) {
        val millis = message.createdAt?.toDate()?.time ?: System.currentTimeMillis()
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (showSenderHeader) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
            ) {
                StudioAvatar(
                    name = message.senderName,
                    size = 26.dp,
                    borderColor = PureWhite,
                    photoBase64 = senderPhotoBase64
                )
                Text(
                    text = if (isMe) "${formatTitleCase(message.senderName)} (You)" else formatTitleCase(message.senderName),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = ObsidianCharcoal
                )
                if (message.senderRole.isNotBlank()) {
                    Text(
                        text = "• ${formatTitleCase(message.senderRole)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextSecondary
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            if (isThreadedFollowUp) {
                // Sleek thread connector matching the right screen of chat_page_ui_6bb989de.jpg
                Canvas(
                    modifier = Modifier
                        .width(26.dp)
                        .height(34.dp)
                        .padding(start = 6.dp)
                ) {
                    val lineColor = Color(0xFFB5B9C4)
                    val stroke = 1.25.dp.toPx()
                    if (isReplyBranch) {
                        val path = Path().apply {
                            moveTo(size.width * 0.25f, 0f)
                            lineTo(size.width * 0.25f, size.height * 0.56f)
                            quadraticTo(
                                size.width * 0.25f, size.height * 0.72f,
                                size.width * 0.45f, size.height * 0.72f
                            )
                            lineTo(size.width * 0.90f, size.height * 0.72f)
                        }
                        drawPath(path = path, color = lineColor, style = Stroke(width = stroke, cap = StrokeCap.Round))
                        // Subtle arrowhead
                        drawLine(
                            color = lineColor,
                            start = Offset(size.width * 0.74f, size.height * 0.58f),
                            end = Offset(size.width * 0.90f, size.height * 0.72f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = lineColor,
                            start = Offset(size.width * 0.74f, size.height * 0.86f),
                            end = Offset(size.width * 0.90f, size.height * 0.72f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                    } else {
                        drawLine(
                            color = lineColor,
                            start = Offset(size.width * 0.55f, 0f),
                            end = Offset(size.width * 0.55f, size.height * 0.75f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (isMe) MutedSurface else PureWhite.copy(alpha = 0.92f),
                border = if (isMe) null else BorderStroke(1.dp, SubtleOutline),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (message.parentPreview.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = PureWhite.copy(alpha = 0.75f),
                            border = BorderStroke(1.dp, SubtleOutline)
                        ) {
                            Text(
                                text = message.parentPreview,
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (message.body.isNotBlank()) {
                        Text(
                            text = message.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ObsidianCharcoal
                        )
                    }

                    if (message.hasAttachment) {
                        MessageAttachmentCard(
                            message = message,
                            onClick = onOpenAttachment,
                            onDownload = onDownloadAttachment
                        )
                    }
                }
            }

            IconButton(
                onClick = onReply,
                modifier = Modifier
                    .size(40.dp)
                    .padding(start = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = "Reply to message",
                    tint = StudioTextSecondary.copy(alpha = 0.72f),
                    modifier = Modifier.size(16.dp)
                )
            }
            if (isSuperAdmin) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("delete_message_${message.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Delete Message",
                        tint = CrimsonBlocked,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageAttachmentCard(
    message: ChatMessage,
    onClick: () -> Unit,
    onDownload: () -> Unit
) {
    val type = message.attachmentTypeEnum()
    if (type == ChatAttachmentType.IMAGE) {
        val decoded by produceState<Bitmap?>(initialValue = null, message.id, message.attachmentDataBase64, message.attachmentLocalPath) {
            value = withContext(Dispatchers.IO) {
                ChatFileManager.decodeMessageBitmap(message, maxEdgePx = 720)
            }
        }

        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(14.dp),
            color = PureWhite,
            border = BorderStroke(1.dp, SubtleOutline),
            modifier = Modifier.widthIn(min = 190.dp, max = 264.dp)
        ) {
            Column {
                val bmp = decoded
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = message.attachmentName.ifBlank { "Uploaded image" },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(156.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .background(KpiSkyBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Image, contentDescription = null, tint = KpiSkyStroke, modifier = Modifier.size(30.dp))
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = message.attachmentName.ifBlank { "Image attachment" },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = ObsidianCharcoal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = ChatFileManager.formatFileSize(message.attachmentSizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextSecondary
                        )
                    }
                    IconButton(
                        onClick = onDownload,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("download_attachment_${message.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = "Download image",
                            tint = VioletPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    } else {
        val isAudio = type == ChatAttachmentType.AUDIO
        val isPdf = message.attachmentName.lowercase().endsWith(".pdf") ||
            message.attachmentMimeType.contains("pdf", ignoreCase = true)
        val badgeBg = when {
            isAudio -> KpiLavenderBg
            isPdf -> SoftBlush
            else -> InfoPillBg
        }
        val badgeTint = when {
            isAudio -> VioletPrimary
            isPdf -> CrimsonBlocked
            else -> InfoPillInk
        }
        val badgeIcon = when {
            isAudio -> Icons.Filled.AudioFile
            isPdf -> Icons.Filled.PictureAsPdf
            else -> Icons.Filled.Description
        }

        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(14.dp),
            color = PureWhite,
            border = BorderStroke(1.dp, SubtleOutline),
            modifier = Modifier.widthIn(min = 216.dp, max = 304.dp)
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(badgeBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(badgeIcon, contentDescription = null, tint = badgeTint, modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = message.attachmentName.ifBlank { "Uploaded file" },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = ObsidianCharcoal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${ChatFileManager.formatFileSize(message.attachmentSizeBytes)} • Tap to open",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
                }
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("download_attachment_${message.id}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = "Download file",
                        tint = VioletPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open file",
                        tint = StudioTextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StagedAttachmentBanner(
    attachment: StagedChatAttachment,
    onRemove: () -> Unit
) {
    val thumb by produceState<Bitmap?>(initialValue = null, attachment.id, attachment.localCachePath) {
        if (attachment.type == ChatAttachmentType.IMAGE) {
            value = withContext(Dispatchers.IO) {
                ChatFileManager.decodeMessageBitmap(
                    ChatMessage(
                        id = attachment.id,
                        attachmentType = ChatAttachmentType.IMAGE.name,
                        attachmentLocalPath = attachment.localCachePath,
                        attachmentDataBase64 = attachment.base64Payload
                    ),
                    maxEdgePx = 180
                )
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, SubtleOutline),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("staged_attachment_banner")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val (bg, tint, icon) = when (attachment.type) {
                ChatAttachmentType.IMAGE -> Triple(KpiSkyBg, KpiSkyStroke, Icons.Filled.Image)
                ChatAttachmentType.AUDIO -> Triple(KpiLavenderBg, VioletPrimary, Icons.Filled.AudioFile)
                else -> Triple(SoftBlush, CrimsonBlocked, Icons.Filled.Description)
            }
            val bmp = thumb
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = attachment.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.fileName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = ObsidianCharcoal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${attachment.type.label} • ${ChatFileManager.formatFileSize(attachment.sizeBytes)} ready to send",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextSecondary
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Remove attachment", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelPickerBottomSheet(
    member: Member,
    allMembers: List<Member>,
    channels: List<ChatChannel>,
    activeChannelId: String,
    isSuperAdmin: Boolean,
    onSelectChannel: (ChatChannel) -> Unit,
    onDeleteChannel: (String) -> Unit,
    onNewChannel: () -> Unit,
    onDismiss: () -> Unit
) {
    val teamChannels = remember(channels) { channels.filter { it.channelTypeEnum() != ChatChannelType.DIRECT } }
    val directChannels = remember(channels) { channels.filter { it.channelTypeEnum() == ChatChannelType.DIRECT } }

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
                    Text("General, project, department & 1:1 DM chats", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
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

            if (teamChannels.isNotEmpty()) {
                Text("Team & Department Channels", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = StudioTextSecondary)
            }
            teamChannels.forEach { ch ->
                val selected = ch.id == activeChannelId
                val symbol = if (ch.channelTypeEnum() == ChatChannelType.DEPARTMENT) "D" else "#"
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
                            Text(
                                text = symbol,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (selected) PureWhite else ObsidianCharcoal
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = formatTitleCase(ch.title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = ObsidianCharcoal
                            )
                            Text(
                                text = ch.lastMessagePreview.ifBlank { ch.topic },
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (ch.messageCount > 0) {
                            StatusPill(
                                label = "${ch.messageCount}",
                                containerColor = PureWhite,
                                contentColor = ObsidianCharcoal
                            )
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

            if (directChannels.isNotEmpty()) {
                Text("Direct Messages (1:1)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = StudioTextSecondary)
                directChannels.forEach { ch ->
                    val selected = ch.id == activeChannelId
                    val peer = resolveDirectPeer(ch, member.uid, allMembers)
                    val peerTitle = formatTitleCase(resolveDirectPeerTitle(ch, member, allMembers))
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
                            StudioAvatar(
                                name = peerTitle,
                                size = 38.dp,
                                borderColor = PureWhite,
                                photoBase64 = peer?.photoBase64.orEmpty()
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = peerTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = ObsidianCharcoal
                                )
                                Text(
                                    text = ch.lastMessagePreview.ifBlank { "Private 1:1 personal chat" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (ch.messageCount > 0) {
                                StatusPill(
                                    label = "${ch.messageCount}",
                                    containerColor = PureWhite,
                                    contentColor = ObsidianCharcoal
                                )
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
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SoftBlush,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = DeepBlushInk,
                            modifier = Modifier.padding(10.dp)
                        )
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
                                        if (!isSel && title.isBlank()) {
                                            title = proj.title
                                        }
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
                        { err ->
                            isSubmitting = false
                            errorMessage = err
                        }
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
