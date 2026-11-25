package com.example.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.chat.ChatFileManager
import com.example.model.ChatStatus
import com.example.model.Member
import com.example.ui.components.StudioAvatar
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatusAvatarBar(
    myMember: Member,
    statuses: List<ChatStatus>,
    colleagues: List<Member>,
    onAddStatus: () -> Unit,
    onOpenStatusesForOwner: (ownerUid: String, items: List<ChatStatus>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        com.example.chat.ChatSettingsStore.ensureLoaded(context)
    }
    val prefs by com.example.chat.ChatSettingsStore.prefsFlow.collectAsState()

    var liveNowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000L)
            liveNowMillis = System.currentTimeMillis()
        }
    }

    val categorized = remember(statuses, myMember, colleagues, prefs.mutedStatusOwnerUids, prefs.blockedUserUids, liveNowMillis) {
        com.example.chat.StatusPrivacyEngine.categorizeStatuses(
            statuses = statuses,
            myMember = myMember,
            allMembers = colleagues + myMember,
            mutedOwnerUids = prefs.mutedStatusOwnerUids,
            blockedByViewerUids = prefs.blockedUserUids,
            nowMillis = liveNowMillis
        )
    }

    val myStatuses = categorized.myStatuses
    val activeOtherSummaries = categorized.recentUpdates + categorized.viewedUpdates

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        // My Status / Add Status button
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .width(64.dp)
                .testTag("status_add_button")
        ) {
            Box(
                modifier = Modifier.size(58.dp),
                contentAlignment = Alignment.Center
            ) {
                if (myStatuses.isNotEmpty()) {
                    com.example.ui.components.SegmentedStatusAvatar(
                        name = myMember.fullName,
                        size = 50.dp,
                        borderColor = PureWhite,
                        photoBase64 = myMember.photoBase64,
                        activeStatuses = myStatuses,
                        viewerUid = myMember.uid,
                        isOwnAvatar = true,
                        onAvatarClick = { onOpenStatusesForOwner(myMember.uid, myStatuses) }
                    )
                    Surface(
                        onClick = onAddStatus,
                        shape = CircleShape,
                        color = ObsidianCharcoal,
                        border = BorderStroke(1.5.dp, PureWhite),
                        modifier = Modifier
                            .size(20.dp)
                            .align(Alignment.BottomEnd)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, contentDescription = "Add another status", tint = PureWhite, modifier = Modifier.size(12.dp))
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(PureWhite.copy(alpha = 0.6f))
                            .clickable { onAddStatus() },
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = ObsidianCharcoal.copy(alpha = 0.35f),
                                style = Stroke(
                                    width = 2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                                )
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Post status",
                            tint = ObsidianCharcoal,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            Text(
                text = if (myStatuses.isNotEmpty()) "My Status" else "Add Status",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        // Active status posters with segmented indicators
        activeOtherSummaries.forEach { summary ->
            val person = summary.owner
            val list = summary.statuses
            val hasUnseen = summary.hasUnviewed
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .width(64.dp)
                    .clickable { onOpenStatusesForOwner(person.uid, list) }
                    .testTag("status_avatar_${person.uid}")
            ) {
                com.example.ui.components.SegmentedStatusAvatar(
                    name = person.fullName,
                    size = 50.dp,
                    borderColor = PureWhite,
                    photoBase64 = person.photoBase64,
                    activeStatuses = list,
                    viewerUid = myMember.uid,
                    isOwnAvatar = false,
                    onAvatarClick = { onOpenStatusesForOwner(person.uid, list) }
                )
                Text(
                    text = formatTitleCase(person.fullName.substringBefore(" ")),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (hasUnseen) FontWeight.Bold else FontWeight.Medium,
                    color = ObsidianCharcoal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun CreateStatusDialog(
    onPost: (text: String, preset: Int, imageBase64: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var presetIdx by remember { mutableStateOf(0) }
    var imageBase64 by remember { mutableStateOf("") }
    var isProcessingPhoto by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun encodeStatusPhoto(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            isProcessingPhoto = true
            val res = ChatFileManager.prepareProfilePhotoBase64(context, uri)
            isProcessingPhoto = false
            res.onSuccess { b64 ->
                imageBase64 = b64
                errorMsg = null
            }.onFailure { err ->
                errorMsg = err.message ?: "Unable to prepare image."
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        encodeStatusPhoto(uri)
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) encodeStatusPhoto(pendingCameraUri)
        pendingCameraUri = null
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = ChatFileManager.createCameraTempUri(context)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        } else {
            errorMsg = "Camera permission is required to capture a status photo."
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = PureWhite,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "New 24h Status",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = ObsidianCharcoal
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                // Live Preview Box
                val grad = ChatVisualCatalog.statusGradients[presetIdx % ChatVisualCatalog.statusGradients.size]
                val previewBmp = remember(imageBase64) {
                    if (imageBase64.isNotBlank()) ChatFileManager.decodeBase64Bitmap(imageBase64, 512) else null
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(grad)),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewBmp != null) {
                        Image(
                            bitmap = previewBmp.asImageBitmap(),
                            contentDescription = "Status preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.28f))
                        )
                    }
                    Text(
                        text = text.ifBlank { if (previewBmp != null) "Add caption…" else "Share an update with your team…" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = PureWhite,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                    if (isProcessingPhoto) {
                        CircularProgressIndicator(color = PureWhite, modifier = Modifier.size(28.dp))
                    }
                }

                // Color preset dots (when text status)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ChatVisualCatalog.statusGradients.forEachIndexed { idx, colors ->
                        Box(
                            modifier = Modifier
                                .size(30.dp)
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
                    IconButton(onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Pick photo", tint = ObsidianCharcoal)
                    }
                    IconButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            val uri = ChatFileManager.createCameraTempUri(context)
                            pendingCameraUri = uri
                            cameraLauncher.launch(uri)
                        } else {
                            cameraPermLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Camera", tint = ObsidianCharcoal)
                    }
                    if (imageBase64.isNotBlank()) {
                        IconButton(onClick = { imageBase64 = "" }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Remove photo", tint = CrimsonBlocked)
                        }
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(240) },
                    label = { Text(if (imageBase64.isNotBlank()) "Caption" else "Status text") },
                    placeholder = { Text("What are you working on today?") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!errorMsg.isNullOrBlank()) {
                    Text(errorMsg.orEmpty(), color = CrimsonBlocked, style = MaterialTheme.typography.labelSmall)
                }

                Button(
                    onClick = {
                        if (text.trim().isBlank() && imageBase64.isBlank()) {
                            errorMsg = "Enter a status update or choose a photo."
                        } else {
                            onPost(text.trim(), presetIdx, imageBase64)
                        }
                    },
                    enabled = !isProcessingPhoto,
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Share Status (24h)", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun FullScreenStatusViewer(
    statuses: List<ChatStatus>,
    myUid: String,
    allMembers: List<Member>,
    onMarkViewed: (statusId: String) -> Unit,
    onDeleteStatus: (statusId: String) -> Unit,
    onReplyDm: (ownerUid: String, replyText: String) -> Unit,
    onDismiss: () -> Unit
) {
    if (statuses.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    var index by remember(statuses) { mutableStateOf(0) }
    val safeIndex = index.coerceIn(0, statuses.lastIndex)
    val current = statuses[safeIndex]
    var replyText by remember { mutableStateOf("") }
    var isPaused by remember { mutableStateOf(false) }
    var showViewersSheet by remember { mutableStateOf(false) }

    LaunchedEffect(current.id) {
        if (current.ownerUid != myUid && myUid !in current.viewerUids) {
            onMarkViewed(current.id)
        }
    }

    LaunchedEffect(current.id, isPaused, showViewersSheet) {
        if (!isPaused && !showViewersSheet) {
            delay(5500L)
            if (safeIndex < statuses.lastIndex) index = safeIndex + 1 else onDismiss()
        }
    }

    val grad = ChatVisualCatalog.statusGradients[current.backgroundPreset % ChatVisualCatalog.statusGradients.size]
    val photoBmp = remember(current.imageDataBase64) {
        if (current.imageDataBase64.isNotBlank()) ChatFileManager.decodeBase64Bitmap(current.imageDataBase64, 900) else null
    }
    val timeAgo = remember(current.createdAtMillis) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(current.createdAtMillis))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(grad))
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            if (photoBmp != null) {
                Image(
                    bitmap = photoBmp.asImageBitmap(),
                    contentDescription = current.text.ifBlank { "Status image" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Left / Right tap areas for stepping
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable {
                            if (safeIndex > 0) index = safeIndex - 1
                        }
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable {
                            isPaused = !isPaused
                        }
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable {
                            if (safeIndex < statuses.lastIndex) index = safeIndex + 1 else onDismiss()
                        }
                )
            }

            // Center text status / caption
            if (current.text.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(if (photoBmp != null) Alignment.BottomCenter else Alignment.Center)
                        .padding(horizontal = 24.dp, vertical = if (photoBmp != null) 96.dp else 24.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (photoBmp != null) Color.Black.copy(alpha = 0.55f) else Color.Transparent
                    ) {
                        Text(
                            text = current.text,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = PureWhite,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            // Top progress strips + header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    statuses.forEachIndexed { i, _ ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (i <= safeIndex) PureWhite else PureWhite.copy(alpha = 0.35f)
                                )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StudioAvatar(
                        name = current.ownerName,
                        size = 38.dp,
                        borderColor = PureWhite,
                        photoBase64 = current.ownerPhotoBase64
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = formatTitleCase(current.ownerName),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = PureWhite
                        )
                        Text(
                            text = "${current.ownerRole.ifBlank { "Team" }} • $timeAgo",
                            style = MaterialTheme.typography.labelSmall,
                            color = PureWhite.copy(alpha = 0.85f)
                        )
                    }
                    if (current.ownerUid == myUid) {
                        IconButton(onClick = { showViewersSheet = true }) {
                            Icon(Icons.Default.Visibility, contentDescription = "Viewers", tint = PureWhite)
                        }
                        IconButton(onClick = {
                            onDeleteStatus(current.id)
                            if (statuses.size <= 1) onDismiss()
                        }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete status", tint = PureWhite)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close viewer", tint = PureWhite)
                    }
                }
            }

            // Bottom reply bar (for others' statuses) or viewers pill (for my status)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                if (current.ownerUid == myUid) {
                    Surface(
                        onClick = { showViewersSheet = true },
                        shape = RoundedCornerShape(999.dp),
                        color = Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, tint = PureWhite, modifier = Modifier.size(16.dp))
                            Text(
                                text = "Viewed by ${current.viewerUids.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = PureWhite,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color.Black.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, PureWhite.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextField(
                                value = replyText,
                                onValueChange = { replyText = it },
                                placeholder = { Text("Reply to ${formatTitleCase(current.ownerName.substringBefore(" "))}…", color = PureWhite.copy(alpha = 0.7f)) },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = PureWhite,
                                    unfocusedTextColor = PureWhite
                                ),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    val clean = replyText.trim()
                                    if (clean.isNotEmpty()) {
                                        onReplyDm(current.ownerUid, "Status reply: $clean")
                                        replyText = ""
                                        onDismiss()
                                    }
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send reply", tint = PureWhite)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showViewersSheet) {
        Dialog(onDismissRequest = { showViewersSheet = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = PureWhite,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Viewed by (${current.viewerUids.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (current.viewerUids.isEmpty()) {
                        Text("No viewers yet.", color = StudioTextSecondary, style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(current.viewerUids) { vUid ->
                                val m = allMembers.firstOrNull { it.uid == vUid }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    StudioAvatar(
                                        name = m?.fullName ?: "Member",
                                        size = 34.dp,
                                        borderColor = PureWhite,
                                        photoBase64 = m?.photoBase64.orEmpty()
                                    )
                                    Text(formatTitleCase(m?.fullName ?: vUid), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
