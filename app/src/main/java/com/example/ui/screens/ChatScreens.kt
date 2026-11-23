package com.example.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.chat.ChatFileManager
import com.example.chat.StagedChatAttachment
import com.example.data.ChatRepository
import com.example.data.SyncedData
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioAvatar
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
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
    onCreateChannel: (title: String, topic: String, type: ChatChannelType, project: Project?, onReady: (ChatChannel) -> Unit) -> Unit,
    onSendMessage: (channelId: String, body: String, parent: ChatMessage?, attachment: StagedChatAttachment?, onSent: () -> Unit) -> Unit,
    onReportMessage: (String, Boolean) -> Unit,
    onOpenDirectChat: (Member, (ChatChannel) -> Unit) -> Unit = { _, _ -> },
    onOpenDepartmentChat: (Department, (ChatChannel) -> Unit) -> Unit = { _, _ -> },
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
    var replyTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var showChannelPicker by remember { mutableStateOf(false) }
    var showNewChannelDialog by remember { mutableStateOf(false) }
    var previewImageBitmap by remember { mutableStateOf<Pair<String, ImageBitmap>?>(null) }

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

    val messagesFlow = remember(activeChannel.id) { observeMessages(activeChannel.id) }
    val syncedMessages by messagesFlow.collectAsStateWithLifecycle(initialValue = SyncedData(emptyList(), false))

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

    if (mode == ChatScreenMode.HUB) {
        ChatHubView(
            member = member,
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
            onClearAttachment = { stagedAttachment = null },
            onOpenChannelsSheet = { showChannelPicker = true },
            onPickAudio = ::launchAudioPicker,
            onPickFiles = ::launchDocumentPicker,
            onPickImages = ::launchImagePicker,
            onSelectChannel = { ch ->
                activeChannelId = ch.id
                mode = ChatScreenMode.THREAD
            },
            onOpenDirectChat = { colleague ->
                onOpenDirectChat(colleague) { ch ->
                    activeChannelId = ch.id
                    mode = ChatScreenMode.THREAD
                }
            },
            onOpenDepartmentChat = { dept ->
                onOpenDepartmentChat(dept) { ch ->
                    activeChannelId = ch.id
                    mode = ChatScreenMode.THREAD
                }
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
            onClose = onCloseChat
        )
    } else {
        ChatThreadView(
            member = member,
            isSuperAdmin = state.isSuperAdmin,
            memberPhotoByUid = memberPhotoByUid,
            channel = activeChannel,
            messages = syncedMessages.data,
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
                            previewImageBitmap = msg.attachmentName.ifBlank { "Image" } to bmp.asImageBitmap()
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
            onDeleteChannel = {
                onDeleteChannel(activeChannel.id) {
                    activeChannelId = ChatRepository.GENERAL_CHANNEL_ID
                    mode = ChatScreenMode.HUB
                }
            },
            onDeleteMessage = { msg -> onDeleteMessage(activeChannel.id, msg.id) },
            onSend = { sendCurrentMessage(openThreadAfter = false) },
            onBackToHub = { mode = ChatScreenMode.HUB },
            onClose = onCloseChat
        )
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
            onCreate = { title, topic, type, project ->
                onCreateChannel(title, topic, type, project) { created ->
                    showNewChannelDialog = false
                    activeChannelId = created.id
                    mode = ChatScreenMode.THREAD
                }
            },
            onDismiss = { showNewChannelDialog = false }
        )
    }

    previewImageBitmap?.let { (title, img) ->
        Dialog(onDismissRequest = { previewImageBitmap = null }) {
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
                        IconButton(onClick = { previewImageBitmap = null }, modifier = Modifier.size(40.dp)) {
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
private fun ChatHubView(
    member: Member,
    isSuperAdmin: Boolean,
    channels: List<ChatChannel>,
    colleagues: List<Member>,
    departments: List<Department>,
    activeChannel: ChatChannel,
    composerText: String,
    onComposerTextChange: (String) -> Unit,
    stagedAttachment: StagedChatAttachment?,
    isProcessingFile: Boolean,
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
    onClose: () -> Unit
) {
    val firstName = member.fullName.trim().substringBefore(" ").ifBlank { "Member" }
    var deleteChannelTarget by remember { mutableStateOf<ChatChannel?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureWhite)
            .drawBehind {
                // Soft pastel mint + lavender + warm blush ambient glow matching left screen of chat_page_ui_6bb989de.jpg
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFD5EDE0).copy(alpha = 0.72f), Color.Transparent),
                        center = Offset(size.width * 0.50f, size.height * 0.44f),
                        radius = size.width * 0.78f
                    ),
                    radius = size.width * 0.78f,
                    center = Offset(size.width * 0.50f, size.height * 0.44f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFE7E2FC).copy(alpha = 0.42f), Color.Transparent),
                        center = Offset(size.width * 0.16f, size.height * 0.30f),
                        radius = size.width * 0.55f
                    ),
                    radius = size.width * 0.55f,
                    center = Offset(size.width * 0.16f, size.height * 0.30f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFFEAE0).copy(alpha = 0.48f), Color.Transparent),
                        center = Offset(size.width * 0.88f, size.height * 0.56f),
                        radius = size.width * 0.52f
                    ),
                    radius = size.width * 0.52f,
                    center = Offset(size.width * 0.88f, size.height * 0.56f)
                )
            }
            .testTag("chat_hub_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .widthIn(max = 620.dp)
                .align(Alignment.Center)
        ) {
            // Top Bar: <-  New Chat  X
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("chat_hub_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = ObsidianCharcoal
                    )
                }
                Text(
                    text = "NEW CHAT",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = ObsidianCharcoal
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("chat_hub_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close Chat",
                        tint = ObsidianCharcoal
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    StudioAvatar(
                        name = member.fullName,
                        size = 56.dp,
                        borderColor = PureWhite,
                        photoBase64 = member.photoBase64
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "HI, ${firstName.uppercase()}!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = StudioTextSecondary
                        )
                        Text(
                            text = "SHARE UPDATES & FILES",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = ObsidianCharcoal
                        )
                    }
                }

                // 2x2 Frosted Bento Grid (matching left screen of chat_page_ui_6bb989de.jpg)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_bento_grid"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FrostedChatBentoTile(
                            label = "Channels",
                            icon = Icons.AutoMirrored.Filled.Chat,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_tile_channels"),
                            onClick = onOpenChannelsSheet
                        ) {
                            ChannelsBentoArtwork()
                        }

                        FrostedChatBentoTile(
                            label = "Audio Clip",
                            icon = Icons.Filled.Mic,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_tile_audio"),
                            onClick = onPickAudio
                        ) {
                            AudioWaveformArtwork()
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FrostedChatBentoTile(
                            label = "Chat Files",
                            icon = Icons.Filled.FolderCopy,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_tile_files"),
                            onClick = onPickFiles
                        ) {
                            ChatFilesBentoArtwork()
                        }

                        FrostedChatBentoTile(
                            label = "Images",
                            icon = Icons.Filled.Image,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_tile_images"),
                            onClick = onPickImages
                        ) {
                            ImagesBentoArtwork()
                        }
                    }
                }

                // 1:1 Direct Message Section for Everyone
                if (colleagues.isNotEmpty()) {
                    Column(
                        modifier = Modifier.testTag("chat_dm_section"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "DIRECT MESSAGES (1:1)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = StudioTextSecondary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            colleagues.forEach { person ->
                                Surface(
                                    onClick = { onOpenDirectChat(person) },
                                    shape = RoundedCornerShape(18.dp),
                                    color = PureWhite.copy(alpha = 0.85f),
                                    border = BorderStroke(1.dp, SubtleOutline),
                                    modifier = Modifier
                                        .widthIn(min = 128.dp, max = 180.dp)
                                        .testTag("dm_person_${person.uid}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        StudioAvatar(
                                            name = person.fullName,
                                            size = 34.dp,
                                            borderColor = PureWhite,
                                            photoBase64 = person.photoBase64
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = person.fullName.ifBlank { "MEMBER" }.uppercase(),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = ObsidianCharcoal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = person.jobRoleName.ifBlank { person.departmentName }.uppercase(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = StudioTextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Department Chat Section for Everyone
                if (departments.isNotEmpty()) {
                    Column(
                        modifier = Modifier.testTag("chat_departments_section"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "DEPARTMENT CHATS",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = StudioTextSecondary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            departments.forEach { dept ->
                                Surface(
                                    onClick = { onOpenDepartmentChat(dept) },
                                    shape = RoundedCornerShape(999.dp),
                                    color = KpiLavenderBg,
                                    border = BorderStroke(1.dp, VioletPrimary.copy(alpha = 0.25f)),
                                    modifier = Modifier
                                        .height(40.dp)
                                        .testTag("dept_chat_${dept.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Filled.Groups, contentDescription = null, tint = VioletPrimary, modifier = Modifier.size(16.dp))
                                        Text(
                                            text = dept.name.uppercase(),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = ObsidianCharcoal
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Recent Channels Strip for 1-Tap Thread Switching
                Column(
                    modifier = Modifier.testTag("chat_threads_section"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TEAM THREADS",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = StudioTextSecondary
                        )
                        TextButton(
                            onClick = onCreateNewChannelClick,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.testTag("create_channel_button")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = ObsidianCharcoal)
                            Spacer(Modifier.width(4.dp))
                            Text("NEW CHANNEL", color = ObsidianCharcoal, fontWeight = FontWeight.Bold)
                        }
                    }

                    val displayChannels = if (channels.isEmpty()) listOf(activeChannel) else channels
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        displayChannels.forEach { ch ->
                            val isSelected = ch.id == activeChannel.id
                            val prefix = when (ch.channelTypeEnum()) {
                                ChatChannelType.DIRECT -> "@"
                                ChatChannelType.DEPARTMENT -> "DEPT:"
                                else -> "#"
                            }
                            Surface(
                                onClick = { onSelectChannel(ch) },
                                shape = RoundedCornerShape(18.dp),
                                color = if (isSelected) PureWhite.copy(alpha = 0.94f) else PureWhite.copy(alpha = 0.65f),
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) ObsidianCharcoal else SubtleOutline
                                ),
                                modifier = Modifier
                                    .widthIn(min = 148.dp, max = 230.dp)
                                    .testTag("channel_chip_${ch.id}")
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "$prefix ${ch.title.uppercase()}",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = ObsidianCharcoal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (ch.messageCount > 0) {
                                            StatusPill(
                                                label = "${ch.messageCount}",
                                                containerColor = MutedSurface,
                                                contentColor = ObsidianCharcoal
                                            )
                                        }
                                        if (isSuperAdmin && ch.id != ChatRepository.GENERAL_CHANNEL_ID) {
                                            IconButton(
                                                onClick = { deleteChannelTarget = ch },
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .testTag("delete_channel_${ch.id}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.DeleteOutline,
                                                    contentDescription = "Delete Channel",
                                                    tint = CrimsonBlocked,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = ch.lastMessagePreview.ifBlank { ch.topic },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Staged File Preview + Target Pill + Frosted Pill Composer
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Surface(
                        onClick = onOpenChannelsSheet,
                        shape = RoundedCornerShape(999.dp),
                        color = PureWhite.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, SubtleOutline)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Sending to #${activeChannel.title}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = ObsidianCharcoal
                            )
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(14.dp), tint = StudioTextSecondary)
                        }
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
                                    text = "Write a message…",
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
            title = { Text("DELETE CHAT THREAD?", fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal) },
            text = { Text("Permanently delete ${target.title.uppercase()} and all its messages?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteChannel(target.id)
                        deleteChannelTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) { Text("DELETE", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { deleteChannelTarget = null }) { Text("CANCEL") }
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
        color = PureWhite.copy(alpha = 0.56f),
        border = BorderStroke(1.dp, PureWhite.copy(alpha = 0.85f)),
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

@Composable
private fun ChatThreadView(
    member: Member,
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
    onDeleteChannel: () -> Unit,
    onDeleteMessage: (ChatMessage) -> Unit,
    onSend: () -> Unit,
    onBackToHub: () -> Unit,
    onClose: () -> Unit
) {
    var showDeleteThreadConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureWhite)
            .drawBehind {
                // Signature centered soft peach & blush-lavender glow matching right screen of chat_page_ui_6bb989de.jpg
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFFD8CC).copy(alpha = 0.50f), Color.Transparent),
                        center = Offset(size.width * 0.52f, size.height * 0.48f),
                        radius = size.width * 0.68f
                    ),
                    radius = size.width * 0.68f,
                    center = Offset(size.width * 0.52f, size.height * 0.48f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFF3D5EC).copy(alpha = 0.40f), Color.Transparent),
                        center = Offset(size.width * 0.44f, size.height * 0.58f),
                        radius = size.width * 0.58f
                    ),
                    radius = size.width * 0.58f,
                    center = Offset(size.width * 0.44f, size.height * 0.58f)
                )
            }
            .testTag("chat_thread_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .widthIn(max = 620.dp)
                .align(Alignment.Center)
        ) {
            // Clean Thread Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onOpenChannelsSheet)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = when (channel.channelTypeEnum()) {
                                ChatChannelType.DIRECT -> "@ ${channel.title.uppercase()}"
                                ChatChannelType.DEPARTMENT -> "DEPT: ${channel.title.uppercase()}"
                                else -> "# ${channel.title.uppercase()}"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = ObsidianCharcoal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Switch Channel", modifier = Modifier.size(16.dp), tint = StudioTextSecondary)
                    }
                    Text(
                        text = if (hasPendingWrites) "Syncing updates…" else channel.topic.ifBlank { "Team channel & shared files" },
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSuperAdmin && channel.id != ChatRepository.GENERAL_CHANNEL_ID) {
                        IconButton(
                            onClick = { showDeleteThreadConfirm = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("thread_delete_channel_button")
                        ) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete Thread", tint = CrimsonBlocked)
                        }
                    }
                    IconButton(
                        onClick = onPickFiles,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("chat_thread_attach_button")
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach File", tint = ObsidianCharcoal)
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("chat_thread_close_button")
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close Chat", tint = ObsidianCharcoal)
                    }
                }
            }

            // Messages Stream or Centered Empty Prompt
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (messages.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Share project files, briefs, or quick updates with your team.",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Normal,
                            color = ObsidianCharcoal.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Tap Attach File, Photo, or Audio below to upload.",
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
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(messages, key = { _, m -> m.id }) { idx, msg ->
                            val prev = messages.getOrNull(idx - 1)
                            val showSenderHeader = prev == null || prev.senderUid != msg.senderUid
                            val isThreadedFollowUp = msg.parentMessageId.isNotBlank() ||
                                (prev != null && prev.senderUid == msg.senderUid)
                            val isReplyBranch = msg.parentMessageId.isNotBlank()

                            ThreadedMessageRow(
                                message = msg,
                                isMe = msg.senderUid == member.uid,
                                isSuperAdmin = isSuperAdmin,
                                senderPhotoBase64 = memberPhotoByUid[msg.senderUid].orEmpty(),
                                showSenderHeader = showSenderHeader,
                                isThreadedFollowUp = isThreadedFollowUp,
                                isReplyBranch = isReplyBranch,
                                onReply = { onSetReply(msg) },
                                onDelete = { onDeleteMessage(msg) },
                                onOpenAttachment = { onOpenMessageAttachment(msg) }
                            )
                        }
                    }
                }
            }

            // Bottom Composer Box + Pill Quick-Attach Row (matching right screen of chat_page_ui_6bb989de.jpg)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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

                replyTarget?.let { target ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MutedSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(16.dp), tint = VioletPrimary)
                            Text(
                                text = "Replying to ${target.senderName.substringBefore(" ")}: ${target.body.ifBlank { target.attachmentName }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = ObsidianCharcoal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onClearReply, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "Cancel reply", modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }

                stagedAttachment?.let { att ->
                    StagedAttachmentBanner(attachment = att, onRemove = onClearAttachment)
                }

                val canSend = composerText.isNotBlank() || stagedAttachment != null
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = PureWhite,
                    border = BorderStroke(1.dp, SubtleOutline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 58.dp)
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (composerText.isEmpty()) {
                                Text(
                                    text = "Write a message…",
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
                                    .testTag("chat_thread_input")
                            )
                        }

                        Surface(
                            onClick = onSend,
                            enabled = canSend && !isProcessingFile,
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

                // Soft full-width pill quick-attach bar directly under input (matching bottom pill style of reference)
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
                        onClick = onPickFiles
                    )
                    QuickAttachPill(
                        label = "Photo",
                        icon = Icons.Outlined.Image,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_attach_photo_button"),
                        onClick = onPickImages
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

    if (showDeleteThreadConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteThreadConfirm = false },
            containerColor = PureWhite,
            title = { Text("DELETE CHAT THREAD?", fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal) },
            text = { Text("Permanently delete ${channel.title.uppercase()} and every message in it?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteThreadConfirm = false
                        onDeleteChannel()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) { Text("DELETE THREAD", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteThreadConfirm = false }) { Text("CANCEL") }
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
    onOpenAttachment: () -> Unit
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
                    text = if (isMe) "${message.senderName.uppercase()} (YOU)" else message.senderName.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = ObsidianCharcoal
                )
                if (message.senderRole.isNotBlank()) {
                    Text(
                        text = "• ${message.senderRole.uppercase()}",
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
                            onClick = onOpenAttachment
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
    onClick: () -> Unit
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
            modifier = Modifier.widthIn(min = 180.dp, max = 260.dp)
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
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.attachmentName.ifBlank { "Image attachment" },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ObsidianCharcoal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = ChatFileManager.formatFileSize(message.attachmentSizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
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
            modifier = Modifier.widthIn(min = 210.dp, max = 300.dp)
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open file",
                    tint = StudioTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun StagedAttachmentBanner(
    attachment: StagedChatAttachment,
    onRemove: () -> Unit
) {
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
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
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
                    Text("WORKSPACE CHANNELS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text("General, project, department & 1:1 DM chats", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                }
                FilledTonalButton(
                    onClick = onNewChannel,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("NEW", fontWeight = FontWeight.Bold)
                }
            }

            channels.forEach { ch ->
                val selected = ch.id == activeChannelId
                val symbol = when (ch.channelTypeEnum()) {
                    ChatChannelType.DIRECT -> "@"
                    ChatChannelType.DEPARTMENT -> "D"
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
                            Text(
                                text = symbol,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (selected) PureWhite else ObsidianCharcoal
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = ch.title.uppercase(),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
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

            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun CreateChatChannelDialog(
    projects: List<Project>,
    onCreate: (title: String, topic: String, type: ChatChannelType, project: Project?) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var selectedProject by remember { mutableStateOf<Project?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = { Text("New Team Channel", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Channel Name") },
                    placeholder = { Text("e.g. Design Critiques") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("Topic / Description") },
                    placeholder = { Text("Deliverables, PDFs & feedback") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
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
                                    selectedProject = if (isSel) null else proj
                                    if (!isSel && title.isBlank()) {
                                        title = proj.title
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
                    onCreate(title.ifBlank { proj?.title ?: "Team Thread" }, topic, type, proj)
                },
                enabled = title.isNotBlank() || selectedProject != null,
                colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
