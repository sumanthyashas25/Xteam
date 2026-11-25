package com.example.ui.status

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chat.ChatAudioManager
import com.example.chat.ChatFileManager
import com.example.chat.ChatSettingsStore
import com.example.chat.RecordedVoiceDraft
import com.example.data.NewStatusSubmission
import com.example.model.Member
import com.example.model.MembershipStatus
import com.example.model.StatusKind
import com.example.model.StatusPrivacyMode
import com.example.ui.chat.ChatVisualCatalog
import com.example.ui.components.StudioAvatar
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class PhotoStatusDraftItem(
    val id: String,
    val uri: Uri,
    val bitmap: Bitmap,
    var zoom: Float = 1f,
    var offsetXFrac: Float = 0f,
    var offsetYFrac: Float = 0f,
    var targetAspect: Float = 0f,
    var caption: String = "",
    var textOverlay: String = "",
    var overlayX: Float = 0.5f,
    var overlayY: Float = 0.5f,
    var overlayColorHex: Long = 0xFFFFFFFFL
)

private val OverlayColors = listOf(
    0xFFFFFFFFL,
    0xFF111318L,
    0xFF10B981L,
    0xFFF59E0BL,
    0xFFEC4899L,
    0xFF38BDF8L
)

private val StatusFonts = listOf("BOLD", "SERIF", "MONO", "CAPS")

fun statusFontFamily(style: String): FontFamily = when (style.uppercase()) {
    "SERIF" -> FontFamily.Serif
    "MONO" -> FontFamily.Monospace
    else -> FontFamily.Default
}

fun formatDurationShort(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return "%d:%02d".format(mins, secs)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateStatusStudioDialog(
    initialMode: StatusKind = StatusKind.TEXT,
    myMember: Member,
    allMembers: List<Member>,
    onPublishQueue: (List<NewStatusSubmission>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by ChatSettingsStore.prefsFlow.collectAsStateWithLifecycle()

    var selectedMode by remember(initialMode) { mutableStateOf(initialMode) }

    // Text mode state
    var statusText by remember { mutableStateOf("") }
    var presetIdx by remember { mutableStateOf(0) }
    var fontStyle by remember { mutableStateOf("BOLD") }

    // Photo mode state (single or multi-photo batch)
    val photoDrafts = remember { mutableStateListOf<PhotoStatusDraftItem>() }
    var activePhotoIndex by remember { mutableStateOf(0) }
    var isProcessingMedia by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    // Video mode state
    var videoInspect by remember { mutableStateOf<ChatFileManager.VideoInspectResult?>(null) }
    var videoStartMs by remember { mutableStateOf(0L) }
    var videoEndMs by remember { mutableStateOf(15_000L) }
    var videoCaption by remember { mutableStateOf("") }

    // Voice mode state
    var isRecordingVoice by remember { mutableStateOf(false) }
    var voiceRecordSec by remember { mutableStateOf(0) }
    var voiceDraft by remember { mutableStateOf<RecordedVoiceDraft?>(null) }
    var voiceCaption by remember { mutableStateOf("") }
    val liveAmp by ChatAudioManager.liveAmplitude.collectAsStateWithLifecycle()
    val audioPlayback by ChatAudioManager.playbackState.collectAsStateWithLifecycle()

    // Privacy state (initialized from persisted user preference)
    var privacyMode by remember(prefs.statusPrivacyMode) {
        mutableStateOf(
            runCatching { StatusPrivacyMode.valueOf(prefs.statusPrivacyMode) }
                .getOrDefault(StatusPrivacyMode.ALL_APPROVED)
        )
    }
    var allowedUids by remember(prefs.statusAllowedUids) { mutableStateOf(prefs.statusAllowedUids) }
    var excludedUids by remember(prefs.statusExcludedUids) { mutableStateOf(prefs.statusExcludedUids) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showPreviewDialog by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isRecordingVoice) {
        if (isRecordingVoice) {
            voiceRecordSec = 0
            while (isRecordingVoice) {
                delay(1000L)
                voiceRecordSec += 1
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isRecordingVoice) ChatAudioManager.cancelRecording()
            ChatAudioManager.stopPlayback()
        }
    }

    fun loadPhotosFromUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            isProcessingMedia = true
            errorMsg = null
            var loadedAny = false
            uris.forEach { uri ->
                ChatFileManager.loadStatusBitmapForEdit(context, uri)
                    .onSuccess { bmp ->
                        photoDrafts += PhotoStatusDraftItem(
                            id = "ph_${System.currentTimeMillis()}_${photoDrafts.size}",
                            uri = uri,
                            bitmap = bmp
                        )
                        loadedAny = true
                    }
                    .onFailure { err ->
                        errorMsg = err.message ?: "Could not load photo."
                    }
            }
            if (loadedAny) {
                selectedMode = StatusKind.IMAGE
                activePhotoIndex = (photoDrafts.lastIndex).coerceAtLeast(0)
            }
            isProcessingMedia = false
        }
    }

    val singlePhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadPhotosFromUris(listOf(uri))
    }

    val multiPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) loadPhotosFromUris(uris)
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                isProcessingMedia = true
                errorMsg = null
                ChatFileManager.inspectStatusVideo(context, uri)
                    .onSuccess { info ->
                        videoInspect = info
                        videoStartMs = 0L
                        videoEndMs = info.durationMs.coerceAtMost(30_000L).coerceAtLeast(1000L)
                        selectedMode = StatusKind.VIDEO
                    }
                    .onFailure { err ->
                        errorMsg = err.message ?: "Unable to read selected video."
                    }
                isProcessingMedia = false
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val camUri = pendingCameraUri
        pendingCameraUri = null
        if (ok && camUri != null) loadPhotosFromUris(listOf(camUri))
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = ChatFileManager.createCameraTempUri(context)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        } else {
            errorMsg = "Camera permission is required to capture a photo."
        }
    }

    val audioPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            ChatAudioManager.startRecording(context)
                .onSuccess {
                    isRecordingVoice = true
                    errorMsg = null
                }
                .onFailure { err -> errorMsg = err.message ?: "Microphone unavailable." }
        } else {
            errorMsg = "Microphone permission is required to record voice status."
        }
    }

    suspend fun buildSubmissions(): Result<List<NewStatusSubmission>> = runCatching {
        val blocked = prefs.blockedUserUids.toList()
        val allowList = allowedUids.toList()
        val exclList = excludedUids.toList()
        when (selectedMode) {
            StatusKind.TEXT -> {
                val clean = statusText.trim()
                require(clean.isNotBlank()) { "Enter status text before publishing." }
                listOf(
                    NewStatusSubmission(
                        kind = StatusKind.TEXT,
                        text = clean,
                        fontStyle = fontStyle,
                        backgroundPreset = presetIdx,
                        privacyMode = privacyMode,
                        allowedUids = allowList,
                        excludedUids = exclList,
                        ownerBlockedUids = blocked
                    )
                )
            }
            StatusKind.IMAGE -> {
                require(photoDrafts.isNotEmpty()) { "Select or capture at least one photo." }
                photoDrafts.map { draft ->
                    val prepared = ChatFileManager.cropAndPrepareStatusImage(
                        context = context,
                        bitmap = draft.bitmap,
                        zoom = draft.zoom,
                        offsetXFrac = draft.offsetXFrac,
                        offsetYFrac = draft.offsetYFrac,
                        targetAspect = draft.targetAspect
                    ).getOrThrow()
                    NewStatusSubmission(
                        kind = StatusKind.IMAGE,
                        text = draft.caption.trim(),
                        caption = draft.caption.trim(),
                        textOverlay = draft.textOverlay.trim(),
                        textOverlayX = draft.overlayX,
                        textOverlayY = draft.overlayY,
                        overlayColorHex = draft.overlayColorHex,
                        fontStyle = fontStyle,
                        backgroundPreset = presetIdx,
                        mediaFile = prepared.file,
                        mediaMimeType = "image/jpeg",
                        mediaSha256 = prepared.sha256,
                        imageThumbnailBase64 = prepared.thumbnailBase64,
                        privacyMode = privacyMode,
                        allowedUids = allowList,
                        excludedUids = exclList,
                        ownerBlockedUids = blocked
                    )
                }
            }
            StatusKind.VIDEO -> {
                val info = videoInspect ?: error("Pick a video first.")
                val trimmedFile = ChatFileManager.trimStatusVideo(
                    context = context,
                    sourceFile = info.file,
                    startMs = videoStartMs,
                    endMs = videoEndMs
                ).getOrThrow()
                listOf(
                    NewStatusSubmission(
                        kind = StatusKind.VIDEO,
                        text = videoCaption.trim(),
                        caption = videoCaption.trim(),
                        backgroundPreset = presetIdx,
                        mediaFile = trimmedFile,
                        mediaMimeType = info.mimeType,
                        durationMs = (videoEndMs - videoStartMs).coerceAtLeast(1000L),
                        trimStartMs = videoStartMs,
                        trimEndMs = videoEndMs,
                        privacyMode = privacyMode,
                        allowedUids = allowList,
                        excludedUids = exclList,
                        ownerBlockedUids = blocked
                    )
                )
            }
            StatusKind.VOICE -> {
                val draft = voiceDraft ?: error("Record a voice note first.")
                listOf(
                    NewStatusSubmission(
                        kind = StatusKind.VOICE,
                        text = voiceCaption.trim(),
                        caption = voiceCaption.trim(),
                        backgroundPreset = presetIdx,
                        mediaFile = draft.file,
                        mediaMimeType = "audio/mp4",
                        durationMs = draft.durationMs,
                        audioWaveform = draft.waveform,
                        privacyMode = privacyMode,
                        allowedUids = allowList,
                        excludedUids = exclList,
                        ownerBlockedUids = blocked
                    )
                )
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = PureWhite,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .testTag("status_creator_studio")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("status_creator_close")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close Status Creator", tint = ObsidianCharcoal)
                    }
                    Text(
                        text = "Create 24h Status",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = ObsidianCharcoal
                    )
                    TextButton(
                        onClick = { showPreviewDialog = true },
                        modifier = Modifier.testTag("status_creator_preview_btn")
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp), tint = VioletPrimary)
                        Spacer(Modifier.width(4.dp))
                        Text("Preview", color = VioletPrimary, fontWeight = FontWeight.Bold)
                    }
                }

                // Mode Switcher Pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusKind.entries.forEach { kind ->
                        val icon = when (kind) {
                            StatusKind.TEXT -> Icons.Default.Title
                            StatusKind.IMAGE -> Icons.Default.PhotoLibrary
                            StatusKind.VIDEO -> Icons.Default.Videocam
                            StatusKind.VOICE -> Icons.Default.Mic
                        }
                        FilterChip(
                            selected = selectedMode == kind,
                            onClick = {
                                selectedMode = kind
                                errorMsg = null
                            },
                            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text(kind.label, fontWeight = FontWeight.SemiBold) },
                            modifier = Modifier.testTag("status_mode_${kind.name.lowercase()}")
                        )
                    }
                }

                // Privacy Chip
                Surface(
                    onClick = { showPrivacyDialog = true },
                    shape = RoundedCornerShape(14.dp),
                    color = MutedSurface,
                    border = BorderStroke(1.dp, SubtleOutline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("status_creator_privacy_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = VioletPrimary, modifier = Modifier.size(16.dp))
                            Column {
                                Text("Who can view", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                                val summary = when (privacyMode) {
                                    StatusPrivacyMode.ALL_APPROVED -> "All approved company members"
                                    StatusPrivacyMode.SELECTED_ONLY -> "Selected only (${allowedUids.size} members)"
                                    StatusPrivacyMode.ALL_EXCEPT -> "All except ${excludedUids.size} hidden"
                                }
                                Text(summary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                            }
                        }
                        Text("Change", style = MaterialTheme.typography.labelMedium, color = VioletPrimary, fontWeight = FontWeight.Bold)
                    }
                }

                // Main Content Area
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val grad = ChatVisualCatalog.statusGradients[presetIdx % ChatVisualCatalog.statusGradients.size]

                    when (selectedMode) {
                        StatusKind.TEXT -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Brush.linearGradient(grad)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (fontStyle == "CAPS") statusText.ifBlank { "Type a status update…" }.uppercase()
                                    else statusText.ifBlank { "Type a status update…" },
                                    fontFamily = statusFontFamily(fontStyle),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = PureWhite,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(20.dp)
                                )
                            }

                            // Gradient & Font selectors
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ChatVisualCatalog.statusGradients.forEachIndexed { idx, colors ->
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(Brush.linearGradient(colors))
                                            .border(
                                                width = if (presetIdx == idx) 3.dp else 1.dp,
                                                color = if (presetIdx == idx) ObsidianCharcoal else SubtleOutline,
                                                shape = CircleShape
                                            )
                                            .clickable { presetIdx = idx }
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                StatusFonts.forEach { f ->
                                    FilterChip(
                                        selected = fontStyle == f,
                                        onClick = { fontStyle = f },
                                        label = { Text(f, fontSize = 11.sp) }
                                    )
                                }
                            }

                            QuickEmojiRow(onPick = { emoji -> statusText = (statusText + emoji).take(500) })

                            OutlinedTextField(
                                value = statusText,
                                onValueChange = { statusText = it.take(500) },
                                label = { Text("Status Message") },
                                placeholder = { Text("Share an update with your team…") },
                                minLines = 3,
                                maxLines = 5,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("status_text_input")
                            )
                        }

                        StatusKind.IMAGE -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        singlePhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Icon(Icons.Default.Photo, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Photo")
                                }
                                OutlinedButton(
                                    onClick = {
                                        multiPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("status_multi_photo_btn")
                                ) {
                                    Icon(Icons.Default.Collections, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Multi")
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                            val uri = ChatFileManager.createCameraTempUri(context)
                                            pendingCameraUri = uri
                                            cameraLauncher.launch(uri)
                                        } else {
                                            cameraPermLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Camera")
                                }
                            }

                            if (photoDrafts.isEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MutedSurface,
                                    border = BorderStroke(1.dp, SubtleOutline),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(210.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = StudioTextSecondary, modifier = Modifier.size(40.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text("Pick single or multiple photos", fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                                        Text("Supports interactive crop, text overlay & captions", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                    }
                                }
                            } else {
                                if (photoDrafts.size > 1) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        photoDrafts.forEachIndexed { idx, item ->
                                            val isSel = idx == activePhotoIndex
                                            Box(
                                                modifier = Modifier
                                                    .size(62.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .border(
                                                        width = if (isSel) 2.5.dp else 1.dp,
                                                        color = if (isSel) VioletPrimary else SubtleOutline,
                                                        shape = RoundedCornerShape(12.dp)
                                                    )
                                                    .clickable { activePhotoIndex = idx }
                                            ) {
                                                Image(
                                                    bitmap = item.bitmap.asImageBitmap(),
                                                    contentDescription = "Photo ${idx + 1}",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                                Surface(
                                                    onClick = {
                                                        photoDrafts.removeAt(idx)
                                                        activePhotoIndex = activePhotoIndex.coerceAtMost((photoDrafts.lastIndex).coerceAtLeast(0))
                                                    },
                                                    shape = CircleShape,
                                                    color = Color.Black.copy(alpha = 0.65f),
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .align(Alignment.TopEnd)
                                                        .padding(2.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = PureWhite, modifier = Modifier.size(12.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                val currentIdx = activePhotoIndex.coerceIn(0, photoDrafts.lastIndex)
                                val activeDraft = photoDrafts[currentIdx]
                                var boxSize by remember { mutableStateOf(IntSize(1, 1)) }

                                // Interactive Crop + Draggable Text Overlay Canvas
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(270.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color(0xFF111318))
                                        .onSizeChanged { boxSize = it }
                                        .pointerInput(activeDraft.id) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                val item = photoDrafts[currentIdx]
                                                val nextX = (item.offsetXFrac + (dragAmount.x / (boxSize.width.coerceAtLeast(1) / 2f))).coerceIn(-1f, 1f)
                                                val nextY = (item.offsetYFrac + (dragAmount.y / (boxSize.height.coerceAtLeast(1) / 2f))).coerceIn(-1f, 1f)
                                                photoDrafts[currentIdx] = item.copy(offsetXFrac = nextX, offsetYFrac = nextY)
                                            }
                                        }
                                ) {
                                    Image(
                                        bitmap = activeDraft.bitmap.asImageBitmap(),
                                        contentDescription = "Crop preview",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                scaleX = activeDraft.zoom
                                                scaleY = activeDraft.zoom
                                                translationX = activeDraft.offsetXFrac * (boxSize.width * 0.25f)
                                                translationY = activeDraft.offsetYFrac * (boxSize.height * 0.25f)
                                            }
                                    )

                                    if (activeDraft.textOverlay.isNotBlank()) {
                                        Box(
                                            modifier = Modifier
                                                .offset {
                                                    IntOffset(
                                                        x = ((activeDraft.overlayX - 0.5f) * boxSize.width * 0.7f).roundToInt(),
                                                        y = ((activeDraft.overlayY - 0.5f) * boxSize.height * 0.7f).roundToInt()
                                                    )
                                                }
                                                .align(Alignment.Center)
                                                .pointerInput("overlay_${activeDraft.id}") {
                                                    detectDragGestures { change, drag ->
                                                        change.consume()
                                                        val item = photoDrafts[currentIdx]
                                                        val nx = (item.overlayX + drag.x / boxSize.width.coerceAtLeast(1).toFloat()).coerceIn(0.1f, 0.9f)
                                                        val ny = (item.overlayY + drag.y / boxSize.height.coerceAtLeast(1).toFloat()).coerceIn(0.1f, 0.9f)
                                                        photoDrafts[currentIdx] = item.copy(overlayX = nx, overlayY = ny)
                                                    }
                                                }
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = Color.Black.copy(alpha = 0.45f)
                                            ) {
                                                Text(
                                                    text = activeDraft.textOverlay,
                                                    color = Color(activeDraft.overlayColorHex),
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 18.sp,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                                )
                                            }
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = Color.Black.copy(alpha = 0.55f),
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(10.dp)
                                    ) {
                                        Text(
                                            text = "Drag photo to pan • Drag text to move",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = PureWhite,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                // Crop Zoom & Aspect Controls
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Crop, contentDescription = "Crop zoom", tint = ObsidianCharcoal, modifier = Modifier.size(18.dp))
                                    Text("Zoom", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    Slider(
                                        value = activeDraft.zoom,
                                        onValueChange = { z -> photoDrafts[currentIdx] = activeDraft.copy(zoom = z) },
                                        valueRange = 1f..3f,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(
                                        0f to "Original",
                                        (9f / 16f) to "9:16 Story",
                                        1f to "1:1 Square",
                                        (4f / 3f) to "4:3 Wide"
                                    ).forEach { (aspect, label) ->
                                        FilterChip(
                                            selected = kotlin.math.abs(activeDraft.targetAspect - aspect) < 0.01f,
                                            onClick = { photoDrafts[currentIdx] = activeDraft.copy(targetAspect = aspect) },
                                            label = { Text(label, fontSize = 12.sp) }
                                        )
                                    }
                                }

                                // Text Overlay Editor
                                OutlinedTextField(
                                    value = activeDraft.textOverlay,
                                    onValueChange = { v -> photoDrafts[currentIdx] = activeDraft.copy(textOverlay = v.take(100)) },
                                    label = { Text("Text Overlay (draggable on photo)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Overlay color:", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                                    OverlayColors.forEach { hex ->
                                        Box(
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clip(CircleShape)
                                                .background(Color(hex))
                                                .border(
                                                    width = if (activeDraft.overlayColorHex == hex) 2.5.dp else 1.dp,
                                                    color = if (activeDraft.overlayColorHex == hex) VioletPrimary else SubtleOutline,
                                                    shape = CircleShape
                                                )
                                                .clickable { photoDrafts[currentIdx] = activeDraft.copy(overlayColorHex = hex) }
                                        )
                                    }
                                }

                                QuickEmojiRow(onPick = { emoji ->
                                    val cur = photoDrafts[currentIdx]
                                    photoDrafts[currentIdx] = cur.copy(caption = (cur.caption + emoji).take(300))
                                })

                                OutlinedTextField(
                                    value = activeDraft.caption,
                                    onValueChange = { v -> photoDrafts[currentIdx] = activeDraft.copy(caption = v.take(300)) },
                                    label = { Text("Caption") },
                                    placeholder = { Text("Add a caption for photo ${currentIdx + 1}…") },
                                    maxLines = 3,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        StatusKind.VIDEO -> {
                            OutlinedButton(
                                onClick = {
                                    videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("status_pick_video_btn")
                            ) {
                                Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (videoInspect == null) "Select Video from Gallery" else "Replace Video")
                            }

                            val info = videoInspect
                            if (info != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(Color.Black)
                                ) {
                                    AndroidView(
                                        factory = { ctx ->
                                            VideoView(ctx).apply {
                                                setVideoPath(info.file.absolutePath)
                                                setOnPreparedListener { mp ->
                                                    mp.isLooping = true
                                                    seekTo(videoStartMs.toInt())
                                                    start()
                                                }
                                            }
                                        },
                                        update = { vv ->
                                            if (vv.currentPosition < videoStartMs || vv.currentPosition > videoEndMs) {
                                                vv.seekTo(videoStartMs.toInt())
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                // Filmstrip Thumbnails + Trimmer RangeSlider
                                if (info.frames.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        info.frames.forEach { frame ->
                                            Image(
                                                bitmap = frame.asImageBitmap(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight()
                                            )
                                        }
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val totalSec = info.durationMs.coerceAtLeast(1000L).toFloat()
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Trim: ${formatDurationShort(videoStartMs)} – ${formatDurationShort(videoEndMs)}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = ObsidianCharcoal
                                        )
                                        Text(
                                            text = "Clip ${formatDurationShort(videoEndMs - videoStartMs)} / ${formatDurationShort(info.durationMs)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = VioletPrimary
                                        )
                                    }
                                    RangeSlider(
                                        value = videoStartMs.toFloat()..videoEndMs.toFloat(),
                                        onValueChange = { range ->
                                            val start = range.start.toLong().coerceAtLeast(0L)
                                            val end = range.endInclusive.toLong().coerceAtLeast(start + 1000L)
                                                .coerceAtMost(info.durationMs)
                                            videoStartMs = start
                                            videoEndMs = end
                                        },
                                        valueRange = 0f..totalSec
                                    )
                                }

                                QuickEmojiRow(onPick = { emoji -> videoCaption = (videoCaption + emoji).take(300) })

                                OutlinedTextField(
                                    value = videoCaption,
                                    onValueChange = { videoCaption = it.take(300) },
                                    label = { Text("Video Caption") },
                                    maxLines = 3,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        StatusKind.VOICE -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(190.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Brush.linearGradient(grad))
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (isRecordingVoice) {
                                        Text("Recording Voice Status… ${formatDurationShort(voiceRecordSec * 1000L)}", color = PureWhite, fontWeight = FontWeight.ExtraBold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            repeat(12) { idx ->
                                                val barH = ((liveAmp + idx * 3) % 24 + 8).dp
                                                Box(
                                                    modifier = Modifier
                                                        .width(5.dp)
                                                        .height(barH)
                                                        .clip(RoundedCornerShape(999.dp))
                                                        .background(PureWhite)
                                                )
                                            }
                                        }
                                        Button(
                                            onClick = {
                                                isRecordingVoice = false
                                                voiceDraft = ChatAudioManager.stopRecording()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                                        ) {
                                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Stop Recording")
                                        }
                                    } else if (voiceDraft != null) {
                                        val draft = voiceDraft!!
                                        val isPlaying = audioPlayback.activeKey == "status_voice_preview" && audioPlayback.isPlaying
                                        Text("Voice Status Ready (${formatDurationShort(draft.durationMs)})", color = PureWhite, fontWeight = FontWeight.Bold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            FilledTonalButton(
                                                onClick = { ChatAudioManager.togglePlay("status_voice_preview", draft.file) }
                                            ) {
                                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Preview voice")
                                                Spacer(Modifier.width(4.dp))
                                                Text(if (isPlaying) "Pause" else "Play Preview")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    ChatAudioManager.stopPlayback()
                                                    voiceDraft = null
                                                },
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = PureWhite),
                                                border = BorderStroke(1.dp, PureWhite.copy(alpha = 0.6f))
                                            ) {
                                                Text("Re-record")
                                            }
                                        }
                                    } else {
                                        Icon(Icons.Default.Mic, contentDescription = null, tint = PureWhite, modifier = Modifier.size(38.dp))
                                        Button(
                                            onClick = {
                                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                                    ChatAudioManager.startRecording(context)
                                                        .onSuccess { isRecordingVoice = true; errorMsg = null }
                                                        .onFailure { errorMsg = it.message ?: "Mic failed" }
                                                } else {
                                                    audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = ObsidianCharcoal),
                                            modifier = Modifier.testTag("status_voice_record_btn")
                                        ) {
                                            Text("Tap to Record Voice", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ChatVisualCatalog.statusGradients.forEachIndexed { idx, colors ->
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(Brush.linearGradient(colors))
                                            .border(if (presetIdx == idx) 3.dp else 1.dp, if (presetIdx == idx) ObsidianCharcoal else SubtleOutline, CircleShape)
                                            .clickable { presetIdx = idx }
                                    )
                                }
                            }

                            QuickEmojiRow(onPick = { emoji -> voiceCaption = (voiceCaption + emoji).take(240) })

                            OutlinedTextField(
                                value = voiceCaption,
                                onValueChange = { voiceCaption = it.take(240) },
                                label = { Text("Voice Caption (optional)") },
                                maxLines = 2,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (isProcessingMedia) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    if (!errorMsg.isNullOrBlank()) {
                        Surface(shape = RoundedCornerShape(12.dp), color = SoftBlush, modifier = Modifier.fillMaxWidth()) {
                            Text(errorMsg.orEmpty(), color = DeepBlushInk, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                        }
                    }
                }

                // Bottom Publish Bar
                Surface(color = PureWhite, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val countLabel = if (selectedMode == StatusKind.IMAGE && photoDrafts.size > 1) {
                            "Publish ${photoDrafts.size} Photos"
                        } else {
                            "Publish Status (24h)"
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    isProcessingMedia = true
                                    errorMsg = null
                                    buildSubmissions()
                                        .onSuccess { list ->
                                            isProcessingMedia = false
                                            onPublishQueue(list)
                                            onDismiss()
                                        }
                                        .onFailure { err ->
                                            isProcessingMedia = false
                                            errorMsg = err.message ?: "Unable to prepare status."
                                        }
                                }
                            },
                            enabled = !isProcessingMedia && !isRecordingVoice,
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("status_creator_publish_btn")
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(countLabel, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }
    }

    if (showPrivacyDialog) {
        StatusPrivacySelectorDialog(
            allMembers = allMembers.filter { it.statusEnum() == MembershipStatus.APPROVED && it.active && it.uid != myMember.uid },
            initialMode = privacyMode,
            initialAllowedUids = allowedUids,
            initialExcludedUids = excludedUids,
            onSave = { mode, allow, excl ->
                privacyMode = mode
                allowedUids = allow
                excludedUids = excl
                ChatSettingsStore.updateStatusPrivacy(context, mode.name, allow, excl)
                showPrivacyDialog = false
            },
            onDismiss = { showPrivacyDialog = false }
        )
    }

    if (showPreviewDialog) {
        val grad = ChatVisualCatalog.statusGradients[presetIdx % ChatVisualCatalog.statusGradients.size]
        Dialog(onDismissRequest = { showPreviewDialog = false }) {
            Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF111318), modifier = Modifier.fillMaxWidth().height(420.dp)) {
                Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(grad))) {
                    when (selectedMode) {
                        StatusKind.TEXT -> {
                            Text(
                                text = statusText.ifBlank { "No text entered yet" },
                                color = PureWhite,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.Center).padding(20.dp)
                            )
                        }
                        StatusKind.IMAGE -> {
                            val d = photoDrafts.getOrNull(activePhotoIndex)
                            if (d != null) {
                                Image(
                                    bitmap = d.bitmap.asImageBitmap(),
                                    contentDescription = "Preview photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (d.textOverlay.isNotBlank()) {
                                    Text(
                                        text = d.textOverlay,
                                        color = Color(d.overlayColorHex),
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 18.sp,
                                        modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).padding(8.dp)
                                    )
                                }
                                if (d.caption.isNotBlank()) {
                                    Surface(color = Color.Black.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                                        Text(d.caption, color = PureWhite, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                                    }
                                }
                            }
                        }
                        StatusKind.VIDEO -> {
                            Text("Video clip (${formatDurationShort(videoEndMs - videoStartMs)})", color = PureWhite, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                            if (videoCaption.isNotBlank()) {
                                Surface(color = Color.Black.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                                    Text(videoCaption, color = PureWhite, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                                }
                            }
                        }
                        StatusKind.VOICE -> {
                            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = PureWhite, modifier = Modifier.size(48.dp))
                                Text("Voice Status (${formatDurationShort(voiceDraft?.durationMs ?: 0L)})", color = PureWhite, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    IconButton(onClick = { showPreviewDialog = false }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close preview", tint = PureWhite)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickEmojiRow(onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf("🔥", "🚀", "✅", "💡", "👏", "❤️", "😂", "✨", "🙌", "💼", "🎨", "☕").forEach { emoji ->
            Surface(
                onClick = { onPick(emoji) },
                shape = RoundedCornerShape(10.dp),
                color = MutedSurface,
                modifier = Modifier.defaultMinSize(minHeight = 36.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(emoji, fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
fun StatusPrivacySelectorDialog(
    allMembers: List<Member>,
    initialMode: StatusPrivacyMode,
    initialAllowedUids: Set<String>,
    initialExcludedUids: Set<String>,
    onSave: (StatusPrivacyMode, Set<String>, Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(initialMode) }
    var allowed by remember { mutableStateOf(initialAllowedUids) }
    var excluded by remember { mutableStateOf(initialExcludedUids) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = PureWhite,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .testTag("status_privacy_dialog")
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Status Privacy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("Choose which approved company members can see your statuses and ring indicator.", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)

                StatusPrivacyMode.entries.forEach { m ->
                    Surface(
                        onClick = { mode = m },
                        shape = RoundedCornerShape(14.dp),
                        color = if (mode == m) VioletSoftBg else MutedSurface,
                        border = if (mode == m) BorderStroke(1.5.dp, VioletPrimary) else null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RadioButton(selected = mode == m, onClick = { mode = m })
                            Column {
                                Text(m.label, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                                Text(m.subtitle, style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                            }
                        }
                    }
                }

                if (mode != StatusPrivacyMode.ALL_APPROVED) {
                    val activeSet = if (mode == StatusPrivacyMode.SELECTED_ONLY) allowed else excluded
                    Text(
                        text = if (mode == StatusPrivacyMode.SELECTED_ONLY) {
                            "Select members who CAN view (${activeSet.size}):"
                        } else {
                            "Hide status from (${activeSet.size}):"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = ObsidianCharcoal
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(allMembers, key = { it.uid }) { mem ->
                            val isChecked = mem.uid in activeSet
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        if (mode == StatusPrivacyMode.SELECTED_ONLY) {
                                            allowed = if (isChecked) allowed - mem.uid else allowed + mem.uid
                                        } else {
                                            excluded = if (isChecked) excluded - mem.uid else excluded + mem.uid
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = {
                                        if (mode == StatusPrivacyMode.SELECTED_ONLY) {
                                            allowed = if (isChecked) allowed - mem.uid else allowed + mem.uid
                                        } else {
                                            excluded = if (isChecked) excluded - mem.uid else excluded + mem.uid
                                        }
                                    }
                                )
                                StudioAvatar(name = mem.fullName, size = 32.dp, photoBase64 = mem.photoBase64)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(formatTitleCase(mem.fullName), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(mem.jobRoleName.ifBlank { mem.departmentName }, style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                                }
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(mode, allowed, excluded) },
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                        modifier = Modifier.testTag("status_privacy_save_btn")
                    ) {
                        Text("Save Privacy")
                    }
                }
            }
        }
    }
}
