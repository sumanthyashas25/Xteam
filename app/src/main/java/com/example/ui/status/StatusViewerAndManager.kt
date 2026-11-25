package com.example.ui.status

import android.content.Intent
import android.graphics.Bitmap
import android.widget.VideoView
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chat.ChatAudioManager
import com.example.chat.ChatFileManager
import com.example.domain.CatalogueSeedData
import com.example.model.ChatStatus
import com.example.model.Member
import com.example.model.StatusKind
import com.example.ui.chat.ChatVisualCatalog
import com.example.ui.components.StudioAvatar
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val StatusReactionEmojis = listOf("👍", "❤️", "🔥", "👏", "😂", "😮", "🚀")

@Composable
fun EnhancedFullScreenStatusViewer(
    statusGroups: List<Pair<Member, List<ChatStatus>>>,
    initialOwnerUid: String = "",
    initialStatusId: String = "",
    myMember: Member,
    allMembers: List<Member>,
    companyId: String = CatalogueSeedData.DEFAULT_COMPANY_ID,
    mutedOwnerUids: Set<String> = emptySet(),
    blockedUserUids: Set<String> = emptySet(),
    onMarkViewed: (statusId: String) -> Unit,
    onReact: (status: ChatStatus, owner: Member, emoji: String) -> Unit,
    onReplyDm: (status: ChatStatus, owner: Member, replyText: String) -> Unit,
    onToggleMute: (ownerUid: String) -> Unit,
    onToggleBlock: (ownerUid: String) -> Unit,
    onReportStatus: (status: ChatStatus, reason: String) -> Unit,
    onDeleteStatus: (status: ChatStatus) -> Unit,
    onDeleteAllMyStatuses: (List<ChatStatus>) -> Unit = {},
    onOpenResponderChat: (Member) -> Unit = {},
    onDismiss: () -> Unit
) {
    if (statusGroups.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val startGroupIdx = remember(initialOwnerUid, statusGroups) {
        if (initialOwnerUid.isBlank()) 0
        else statusGroups.indexOfFirst { it.first.uid == initialOwnerUid }.coerceAtLeast(0)
    }

    var groupIdx by remember(statusGroups.size, initialOwnerUid) { mutableIntStateOf(startGroupIdx) }
    val safeGroupIdx = groupIdx.coerceIn(0, statusGroups.lastIndex)
    val currentPair = statusGroups[safeGroupIdx]
    val owner = currentPair.first
    val ownerStatuses = currentPair.second

    if (ownerStatuses.isEmpty()) {
        LaunchedEffect(safeGroupIdx) {
            if (safeGroupIdx < statusGroups.lastIndex) {
                groupIdx = safeGroupIdx + 1
            } else {
                onDismiss()
            }
        }
        return
    }

    val defaultStatusIdxForGroup = remember(owner.uid, ownerStatuses, initialStatusId, myMember.uid) {
        if (initialStatusId.isNotBlank()) {
            val found = ownerStatuses.indexOfFirst { it.id == initialStatusId }
            if (found >= 0) return@remember found
        }
        if (owner.uid == myMember.uid) 0
        else ownerStatuses.indexOfFirst { !it.isViewedBy(myMember.uid) }.coerceAtLeast(0)
    }

    var statusIdx by remember(owner.uid, initialStatusId) { mutableIntStateOf(defaultStatusIdxForGroup) }
    val safeStatusIdx = statusIdx.coerceIn(0, ownerStatuses.lastIndex)
    val current = ownerStatuses[safeStatusIdx]
    val isMine = owner.uid == myMember.uid

    var isPaused by remember { mutableStateOf(false) }
    var replyText by remember(current.id) { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showManageSheet by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    // Media loading state
    var fullBitmap by remember(current.id) { mutableStateOf<Bitmap?>(null) }
    var mediaFile by remember(current.id) { mutableStateOf<File?>(null) }
    var mediaLoading by remember(current.id) { mutableStateOf(false) }
    var mediaError by remember(current.id) { mutableStateOf<String?>(null) }
    val audioPlayback by ChatAudioManager.playbackState.collectAsStateWithLifecycle()

    val thumbBitmap = remember(current.imageDataBase64) {
        if (current.imageDataBase64.isNotBlank()) {
            ChatFileManager.decodeBase64Bitmap(current.imageDataBase64, 720)
        } else null
    }

    fun goNext() {
        ChatAudioManager.stopPlayback()
        if (safeStatusIdx < ownerStatuses.lastIndex) {
            statusIdx = safeStatusIdx + 1
        } else if (safeGroupIdx < statusGroups.lastIndex) {
            val nextOwner = statusGroups[safeGroupIdx + 1]
            groupIdx = safeGroupIdx + 1
            val firstUnseen = nextOwner.second.indexOfFirst { !it.isViewedBy(myMember.uid) }.coerceAtLeast(0)
            statusIdx = if (nextOwner.first.uid == myMember.uid) 0 else firstUnseen
        } else {
            onDismiss()
        }
    }

    fun goPrev() {
        ChatAudioManager.stopPlayback()
        if (safeStatusIdx > 0) {
            statusIdx = safeStatusIdx - 1
        } else if (safeGroupIdx > 0) {
            val prevOwner = statusGroups[safeGroupIdx - 1]
            groupIdx = safeGroupIdx - 1
            statusIdx = prevOwner.second.lastIndex.coerceAtLeast(0)
        }
    }

    // Mark as viewed on render
    LaunchedEffect(current.id) {
        if (!isMine && !current.isViewedBy(myMember.uid)) {
            onMarkViewed(current.id)
        }
    }

    // Load remote R2 media when needed
    LaunchedEffect(current.id) {
        fullBitmap = null
        mediaFile = null
        mediaError = null
        when (current.kindEnum()) {
            StatusKind.IMAGE -> {
                if (current.effectiveStoragePath().isNotBlank() || current.imageDataBase64.isNotBlank()) {
                    mediaLoading = thumbBitmap == null
                    fullBitmap = ChatFileManager.loadOrDownloadStatusBitmap(context, current, companyId)
                    mediaLoading = false
                }
            }
            StatusKind.VIDEO, StatusKind.VOICE -> {
                mediaLoading = true
                ChatFileManager.loadOrDownloadStatusMediaFile(context, current, companyId)
                    .onSuccess { file ->
                        mediaFile = file
                        mediaLoading = false
                        if (current.kindEnum() == StatusKind.VOICE) {
                            ChatAudioManager.togglePlay("st_voice_${current.id}", file)
                        }
                    }
                    .onFailure { err ->
                        mediaLoading = false
                        mediaError = err.message ?: "Unable to load status media."
                    }
            }
            StatusKind.TEXT -> Unit
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ChatAudioManager.stopPlayback()
        }
    }

    // Segment progress animation
    val progressAnim = remember(current.id) { Animatable(0f) }
    val segmentDurationMs = remember(current.id) {
        when (current.kindEnum()) {
            StatusKind.VIDEO -> {
                val clip = (current.trimEndMs - current.trimStartMs).takeIf { it > 0L }
                    ?: current.durationMs.takeIf { it > 0L }
                    ?: 10_000L
                clip.coerceIn(3_000L, 35_000L)
            }
            StatusKind.VOICE -> current.durationMs.coerceIn(3_500L, 60_000L)
            else -> 5_500L
        }
    }

    val effectivePaused = isPaused || showOverflowMenu || showManageSheet || showReportDialog || replyText.isNotEmpty() || mediaLoading

    LaunchedEffect(current.id, effectivePaused) {
        if (effectivePaused) return@LaunchedEffect
        var elapsed = (progressAnim.value * segmentDurationMs).toLong()
        while (elapsed < segmentDurationMs) {
            delay(50L)
            elapsed += 50L
            progressAnim.snapTo((elapsed.toFloat() / segmentDurationMs.toFloat()).coerceIn(0f, 1f))
        }
        goNext()
    }

    val grad = ChatVisualCatalog.statusGradients[current.backgroundPreset % ChatVisualCatalog.statusGradients.size]
    val formattedTime = remember(current.createdAtMillis) {
        formatRelativeStatusTime(current.createdAtMillis)
    }

    fun shareCurrentStatus() {
        scope.launch {
            val cachedFile = mediaFile ?: if (current.kindEnum() != StatusKind.TEXT) {
                ChatFileManager.loadOrDownloadStatusMediaFile(context, current, companyId).getOrNull()
            } else null

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                if (cachedFile != null && cachedFile.exists()) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cachedFile)
                    type = current.mediaMimeType.ifBlank { "*/*" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    if (current.effectiveDisplayText().isNotBlank()) {
                        putExtra(Intent.EXTRA_TEXT, current.effectiveDisplayText())
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "${formatTitleCase(owner.fullName)}: ${current.effectiveDisplayText()}")
                }
            }
            val chooser = Intent.createChooser(sendIntent, "Share status").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(chooser) }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, dragOffsetY.roundToInt().coerceAtLeast(0)) }
                .background(
                    if (current.kindEnum() == StatusKind.IMAGE || current.kindEnum() == StatusKind.VIDEO) {
                        Brush.verticalGradient(listOf(Color(0xFF0D0F14), Color(0xFF171A22)))
                    } else {
                        Brush.linearGradient(grad)
                    }
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .pointerInput(current.id) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragOffsetY > 180f) {
                                onDismiss()
                            } else {
                                dragOffsetY = 0f
                            }
                        },
                        onDragCancel = { dragOffsetY = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            if (dragAmount > 0f || dragOffsetY > 0f) {
                                dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                            }
                        }
                    )
                }
                .testTag("full_screen_status_viewer")
        ) {
            // Content Canvas (Tap left/right to navigate, hold to pause)
            var containerSize by remember { mutableStateOf(IntSize(1, 1)) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { containerSize = it }
                    .pointerInput(safeGroupIdx, safeStatusIdx, statusGroups.size) {
                        detectTapGestures(
                            onPress = {
                                isPaused = true
                                tryAwaitRelease()
                                isPaused = false
                            },
                            onTap = { offset ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                if (offset.x < w * 0.30f) {
                                    goPrev()
                                } else {
                                    goNext()
                                }
                            }
                        )
                    }
            ) {
                when (current.kindEnum()) {
                    StatusKind.TEXT -> {
                        val display = if (current.fontStyle.equals("CAPS", ignoreCase = true)) {
                            current.text.uppercase()
                        } else {
                            current.text
                        }
                        Text(
                            text = display,
                            fontFamily = statusFontFamily(current.fontStyle),
                            fontSize = 26.sp,
                            lineHeight = 34.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = PureWhite,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 28.dp)
                        )
                    }

                    StatusKind.IMAGE -> {
                        val bmp = fullBitmap ?: thumbBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = current.effectiveDisplayText().ifBlank { "Photo status" },
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        if (current.textOverlay.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset {
                                        IntOffset(
                                            x = ((current.textOverlayX - 0.5f) * containerSize.width * 0.7f).roundToInt(),
                                            y = ((current.textOverlayY - 0.5f) * containerSize.height * 0.65f).roundToInt()
                                        )
                                    }
                            ) {
                                Surface(shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.5f)) {
                                    Text(
                                        text = current.textOverlay,
                                        color = Color(current.overlayColorHex),
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 20.sp,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                        if (current.effectiveDisplayText().isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color.Black.copy(alpha = 0.62f),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(start = 20.dp, end = 20.dp, bottom = 128.dp)
                            ) {
                                Text(
                                    text = current.effectiveDisplayText(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = PureWhite,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }

                    StatusKind.VIDEO -> {
                        val vf = mediaFile
                        if (vf != null && vf.exists()) {
                            AndroidView(
                                factory = { ctx ->
                                    VideoView(ctx).apply {
                                        setVideoPath(vf.absolutePath)
                                        setOnPreparedListener { mp ->
                                            mp.isLooping = true
                                            val start = current.trimStartMs.toInt().coerceAtLeast(0)
                                            seekTo(start)
                                            start()
                                        }
                                    }
                                },
                                update = { vv ->
                                    runCatching {
                                        if (effectivePaused && vv.isPlaying) {
                                            vv.pause()
                                        } else if (!effectivePaused && !vv.isPlaying) {
                                            vv.start()
                                        }
                                        if (current.trimEndMs > current.trimStartMs && vv.currentPosition > current.trimEndMs) {
                                            vv.seekTo(current.trimStartMs.toInt())
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        if (current.effectiveDisplayText().isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color.Black.copy(alpha = 0.62f),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(start = 20.dp, end = 20.dp, bottom = 128.dp)
                            ) {
                                Text(
                                    text = current.effectiveDisplayText(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = PureWhite,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }

                    StatusKind.VOICE -> {
                        val playingHere = audioPlayback.activeKey == "st_voice_${current.id}" && audioPlayback.isPlaying
                        val waveBars = current.audioWaveform.ifEmpty { listOf(10, 16, 22, 12, 19, 24, 14, 18, 11, 20, 15, 9) }
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            Surface(
                                onClick = { mediaFile?.let { ChatAudioManager.togglePlay("st_voice_${current.id}", it) } },
                                shape = CircleShape,
                                color = PureWhite,
                                modifier = Modifier.size(72.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (playingHere) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (playingHere) "Pause voice status" else "Play voice status",
                                        tint = ObsidianCharcoal,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                waveBars.take(20).forEach { amp ->
                                    Box(
                                        modifier = Modifier
                                            .width(5.dp)
                                            .height((amp * 1.4f).coerceIn(8f, 36f).dp)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(PureWhite.copy(alpha = if (playingHere) 1f else 0.7f))
                                    )
                                }
                            }
                            Text(
                                text = "Voice Status • ${formatDurationShort(current.durationMs)}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = PureWhite
                            )
                            if (current.effectiveDisplayText().isNotBlank()) {
                                Text(
                                    text = current.effectiveDisplayText(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = PureWhite,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                if (mediaLoading) {
                    CircularProgressIndicator(
                        color = PureWhite,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                if (!mediaError.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Black.copy(alpha = 0.72f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    ) {
                        Text(
                            text = mediaError.orEmpty(),
                            color = PureWhite,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            // Top Multi-Segment Progress Indicator & Uploader Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ownerStatuses.forEachIndexed { idx, _ ->
                        val frac = when {
                            idx < safeStatusIdx -> 1f
                            idx == safeStatusIdx -> progressAnim.value
                            else -> 0f
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(PureWhite.copy(alpha = 0.32f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(frac)
                                    .background(PureWhite)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StudioAvatar(
                        name = owner.fullName,
                        size = 40.dp,
                        borderColor = PureWhite,
                        photoBase64 = owner.photoBase64
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isMine) "My Status" else formatTitleCase(owner.fullName),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = PureWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "${owner.jobRoleName.ifBlank { "Team" }} • $formattedTime",
                                style = MaterialTheme.typography.labelSmall,
                                color = PureWhite.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isPaused) {
                                Surface(shape = RoundedCornerShape(999.dp), color = Color.Black.copy(alpha = 0.45f)) {
                                    Text(
                                        text = "Paused",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PureWhite,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (isMine) {
                        IconButton(
                            onClick = { shareCurrentStatus() },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share status", tint = PureWhite)
                        }
                    }

                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("status_viewer_menu_btn")
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Status options", tint = PureWhite)
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            if (isMine) {
                                DropdownMenuItem(
                                    text = { Text("Viewers & Insights") },
                                    leadingIcon = { Icon(Icons.Default.Visibility, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showManageSheet = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Share Status") },
                                    leadingIcon = { Icon(Icons.Default.Share, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        shareCurrentStatus()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete This Status", color = CrimsonBlocked) },
                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = CrimsonBlocked) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onDeleteStatus(current)
                                        if (ownerStatuses.size <= 1) onDismiss()
                                    }
                                )
                            } else {
                                val isMuted = owner.uid in mutedOwnerUids
                                val isBlocked = owner.uid in blockedUserUids
                                DropdownMenuItem(
                                    text = { Text(if (isMuted) "Unmute ${owner.fullName.substringBefore(" ")}" else "Mute Statuses") },
                                    leadingIcon = { Icon(if (isMuted) Icons.Default.VolumeUp else Icons.Default.VolumeOff, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onToggleMute(owner.uid)
                                        onDismiss()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (isBlocked) "Unblock User" else "Block User") },
                                    leadingIcon = { Icon(Icons.Default.Block, null, tint = CrimsonBlocked) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onToggleBlock(owner.uid)
                                        onDismiss()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Report Status", color = CrimsonBlocked) },
                                    leadingIcon = { Icon(Icons.Default.Flag, null, tint = CrimsonBlocked) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showReportDialog = true
                                    }
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("status_viewer_close_btn")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close status viewer", tint = PureWhite)
                    }
                }
            }

            // Bottom Footer: Owner Analytics Bar OR Viewer Reactions + Private Reply Composer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isMine) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = { showManageSheet = true },
                            shape = RoundedCornerShape(999.dp),
                            color = Color.Black.copy(alpha = 0.58f),
                            border = BorderStroke(1.dp, PureWhite.copy(alpha = 0.25f)),
                            modifier = Modifier.testTag("status_viewer_manage_pill")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.Visibility, contentDescription = null, tint = PureWhite, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "${current.viewerUids.size} views • ${current.reactions.size} reactions • ${current.replyCount} replies",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = PureWhite
                                )
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = PureWhite, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                } else {
                    // Quick Emoji Reactions
                    val myReaction = current.reactions[myMember.uid]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusReactionEmojis.forEach { emoji ->
                            val selected = myReaction == emoji
                            Surface(
                                onClick = { onReact(current, owner, emoji) },
                                shape = CircleShape,
                                color = if (selected) VioletPrimary else Color.Black.copy(alpha = 0.52f),
                                border = BorderStroke(1.dp, if (selected) PureWhite else PureWhite.copy(alpha = 0.24f)),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(emoji, fontSize = 20.sp)
                                }
                            }
                        }
                    }

                    // Private DM Reply Bar
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color.Black.copy(alpha = 0.58f),
                        border = BorderStroke(1.dp, PureWhite.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (replyText.isEmpty()) {
                                    Text(
                                        text = "Reply privately to ${formatTitleCase(owner.fullName.substringBefore(" "))}…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = PureWhite.copy(alpha = 0.72f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = replyText,
                                    onValueChange = { replyText = it.take(400) },
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = PureWhite),
                                    cursorBrush = SolidColor(PureWhite),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("status_viewer_reply_input")
                                )
                            }
                            IconButton(
                                onClick = {
                                    val clean = replyText.trim()
                                    if (clean.isNotEmpty()) {
                                        onReplyDm(current, owner, clean)
                                        replyText = ""
                                    }
                                },
                                enabled = replyText.isNotBlank(),
                                modifier = Modifier
                                    .size(42.dp)
                                    .testTag("status_viewer_reply_send_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send reply",
                                    tint = if (replyText.isNotBlank()) PureWhite else PureWhite.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showManageSheet) {
        MyStatusManagementSheet(
            myStatuses = ownerStatuses,
            allMembers = allMembers,
            companyId = companyId,
            onShareStatus = { shareCurrentStatus() },
            onDeleteStatus = { st ->
                onDeleteStatus(st)
                if (ownerStatuses.size <= 1) {
                    showManageSheet = false
                    onDismiss()
                }
            },
            onDeleteAll = {
                onDeleteAllMyStatuses(ownerStatuses)
                showManageSheet = false
                onDismiss()
            },
            onOpenResponderChat = { target ->
                showManageSheet = false
                onDismiss()
                onOpenResponderChat(target)
            },
            onDismiss = { showManageSheet = false }
        )
    }

    if (showReportDialog) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            containerColor = PureWhite,
            title = { Text("Report Status", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Report this status update from ${formatTitleCase(owner.fullName)} to workspace admins.")
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(300) },
                        label = { Text("Reason") },
                        placeholder = { Text("Describe the issue…") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onReportStatus(current, reason.ifBlank { "Inappropriate status update" })
                        showReportDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) {
                    Text("Submit Report")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyStatusManagementSheet(
    myStatuses: List<ChatStatus>,
    allMembers: List<Member>,
    companyId: String = CatalogueSeedData.DEFAULT_COMPANY_ID,
    onShareStatus: (ChatStatus) -> Unit = {},
    onDeleteStatus: (ChatStatus) -> Unit,
    onDeleteAll: () -> Unit,
    onOpenResponderChat: (Member) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var selectedStatusId by remember(myStatuses) { mutableStateOf(myStatuses.firstOrNull()?.id.orEmpty()) }
    val activeStatus = myStatuses.firstOrNull { it.id == selectedStatusId } ?: myStatuses.firstOrNull()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        contentColor = ObsidianCharcoal
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .testTag("my_status_management_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "My Status (${myStatuses.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = ObsidianCharcoal
                    )
                    Text(
                        text = "Viewers, reactions, replies & expiry tracking",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                }
                if (myStatuses.isNotEmpty()) {
                    TextButton(
                        onClick = { showDeleteAllConfirm = true },
                        modifier = Modifier.testTag("my_status_delete_all_btn")
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = CrimsonBlocked, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete All", color = CrimsonBlocked, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (myStatuses.isEmpty()) {
                Text(
                    text = "You have no active status updates.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioTextSecondary,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                // Status selector strip when multiple statuses exist
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(myStatuses, key = { it.id }) { st ->
                        val isExpanded = st.id == activeStatus?.id
                        val hoursLeft = ((st.expiresAtMillis - System.currentTimeMillis()) / 3_600_000L).coerceAtLeast(0L)
                        Surface(
                            onClick = { selectedStatusId = st.id },
                            shape = RoundedCornerShape(18.dp),
                            color = if (isExpanded) VioletSoftBg else MutedSurface,
                            border = BorderStroke(1.dp, if (isExpanded) VioletPrimary else SubtleOutline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = ObsidianCharcoal,
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            val icon = when (st.kindEnum()) {
                                                StatusKind.TEXT -> Icons.Default.Title
                                                StatusKind.IMAGE -> Icons.Default.Image
                                                StatusKind.VIDEO -> Icons.Default.Videocam
                                                StatusKind.VOICE -> Icons.Default.Mic
                                            }
                                            Icon(icon, contentDescription = st.kindEnum().label, tint = PureWhite, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = st.effectiveDisplayText().ifBlank { "${st.kindEnum().label} Status" },
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${formatRelativeStatusTime(st.createdAtMillis)} • ${hoursLeft}h remaining • ${st.privacyModeEnum().label}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StudioTextSecondary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                val file = if (st.kindEnum() != StatusKind.TEXT) {
                                                    ChatFileManager.loadOrDownloadStatusMediaFile(context, st, companyId).getOrNull()
                                                } else null
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    if (file != null && file.exists()) {
                                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                                        type = st.mediaMimeType.ifBlank { "*/*" }
                                                        putExtra(Intent.EXTRA_STREAM, uri)
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    } else {
                                                        type = "text/plain"
                                                        putExtra(Intent.EXTRA_TEXT, st.effectiveDisplayText())
                                                    }
                                                }
                                                runCatching { context.startActivity(Intent.createChooser(shareIntent, "Share status")) }
                                                onShareStatus(st)
                                            }
                                        },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = "Share status", tint = ObsidianCharcoal, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = { onDeleteStatus(st) },
                                        modifier = Modifier
                                            .size(40.dp)
                                            .testTag("delete_status_${st.id}")
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete status", tint = CrimsonBlocked, modifier = Modifier.size(18.dp))
                                    }
                                }

                                // Summary KPI badges
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    InsightBadge(icon = Icons.Default.Visibility, label = "${st.viewerUids.size} views")
                                    InsightBadge(icon = Icons.Default.EmojiEmotions, label = "${st.reactions.size} reactions")
                                    InsightBadge(icon = Icons.Default.ChatBubbleOutline, label = "${st.replyCount} replies")
                                }

                                if (isExpanded) {
                                    HorizontalDivider(color = SubtleOutline)

                                    // Individual Viewer Names + Viewing Timestamps
                                    Text(
                                        text = "Viewers (${st.viewerUids.size})",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = ObsidianCharcoal
                                    )
                                    if (st.viewerUids.isEmpty()) {
                                        Text("No team views yet.", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                    } else {
                                        st.viewerUids.forEach { vUid ->
                                            val vm = allMembers.firstOrNull { it.uid == vUid }
                                            val viewedAt = st.viewerTimestamps[vUid]
                                            val timeText = viewedAt?.let { formatRelativeStatusTime(it) } ?: "Viewed"
                                            val reactionEmoji = st.reactions[vUid]
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .clickable(enabled = vm != null) { vm?.let(onOpenResponderChat) }
                                                    .padding(vertical = 4.dp)
                                            ) {
                                                StudioAvatar(
                                                    name = vm?.fullName ?: vUid,
                                                    size = 34.dp,
                                                    photoBase64 = vm?.photoBase64.orEmpty()
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(formatTitleCase(vm?.fullName ?: vUid), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                                    Text(timeText, style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                                                }
                                                if (!reactionEmoji.isNullOrBlank()) {
                                                    Surface(shape = CircleShape, color = PureWhite) {
                                                        Text(reactionEmoji, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Reactions recorded by users who may or may not also appear in viewers
                                    val extraReactors = st.reactions.entries.filter { (uid, _) -> uid !in st.viewerUids }
                                    if (extraReactors.isNotEmpty() || st.reactions.isNotEmpty()) {
                                        Text(
                                            text = "Reactions (${st.reactions.size})",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = ObsidianCharcoal
                                        )
                                        st.reactions.forEach { (rUid, emoji) ->
                                            val rm = allMembers.firstOrNull { it.uid == rUid }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(formatTitleCase(rm?.fullName ?: rUid), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                                Text(emoji, fontSize = 18.sp)
                                            }
                                        }
                                    }

                                    if (st.replyCount > 0L) {
                                        Text(
                                            text = "${st.replyCount} private status ${if (st.replyCount == 1L) "reply" else "replies"} delivered to Personal Chats.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = VioletPrimary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            containerColor = PureWhite,
            title = { Text("Delete All Active Statuses?", fontWeight = FontWeight.Bold) },
            text = { Text("This will permanently delete all ${myStatuses.size} active status updates and remove your ring indicator.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAllConfirm = false
                        onDeleteAll()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun InsightBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, SubtleOutline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = ObsidianCharcoal, modifier = Modifier.size(13.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = ObsidianCharcoal)
        }
    }
}

fun formatRelativeStatusTime(createdAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (createdAtMillis <= 0L) return "Just now"
    val diff = (nowMillis - createdAtMillis).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "Just now"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(createdAtMillis))
        else -> SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()).format(Date(createdAtMillis))
    }
}
