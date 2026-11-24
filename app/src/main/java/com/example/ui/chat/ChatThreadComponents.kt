package com.example.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chat.ChatAudioManager
import com.example.chat.ChatFileManager
import com.example.chat.ChatLocationManager
import com.example.chat.remote.WorkerBridgeClient
import com.example.model.*
import com.example.system.AppHapticsAndNotifier
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SwipeToReplyContainer(
    enabled: Boolean = true,
    onReply: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val maxPx = with(LocalDensity.current) { 72.dp.toPx() }
    val triggerPx = with(LocalDensity.current) { 52.dp.toPx() }
    var triggered by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (offsetX.value > 12f) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Reply,
                contentDescription = "Reply",
                tint = VioletPrimary.copy(alpha = (offsetX.value / triggerPx).coerceIn(0.2f, 1f)),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .size(22.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX.value >= triggerPx) {
                                onReply()
                            }
                            triggered = false
                            scope.launch {
                                offsetX.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 400f))
                            }
                        },
                        onDragCancel = {
                            triggered = false
                            scope.launch { offsetX.animateTo(0f) }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            val next = (offsetX.value + dragAmount).coerceIn(0f, maxPx)
                            if (next >= triggerPx && !triggered) {
                                triggered = true
                                AppHapticsAndNotifier.triggerTickHaptic(context)
                            }
                            scope.launch { offsetX.snapTo(next) }
                        }
                    )
                }
        ) {
            content()
        }
    }
}

@Composable
fun VoiceNotePlayerBubble(
    message: ChatMessage,
    companyId: String,
    accentColor: Color,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playback by ChatAudioManager.playbackState.collectAsStateWithLifecycle()
    val isThisActive = playback.activeKey == message.id
    val isPlaying = isThisActive && playback.isPlaying
    var showTranscript by remember { mutableStateOf(false) }
    var isLoadingFile by remember { mutableStateOf(false) }

    val bars = remember(message.audioWaveform) {
        if (message.audioWaveform.isNotEmpty()) message.audioWaveform
        else listOf(8, 14, 22, 12, 18, 24, 16, 20, 10, 15, 22, 8, 16, 12)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PureWhite.copy(alpha = 0.88f),
            border = BorderStroke(1.dp, SubtleOutline),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = {
                        scope.launch {
                            isLoadingFile = true
                            val res = ChatFileManager.resolveOrDownloadAttachment(context, message, companyId)
                            isLoadingFile = false
                            res.onSuccess { file ->
                                ChatAudioManager.togglePlay(message.id, file)
                            }.onFailure { err ->
                                onError(err.message ?: "Unable to play audio clip.")
                            }
                        }
                    },
                    shape = CircleShape,
                    color = accentColor,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isLoadingFile) {
                            CircularProgressIndicator(color = PureWhite, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = PureWhite,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val progressFrac = if (isThisActive && playback.durationMs > 0L) {
                            (playback.positionMs.toFloat() / playback.durationMs.toFloat()).coerceIn(0f, 1f)
                        } else 0f
                        bars.take(18).forEachIndexed { idx, h ->
                            val frac = idx.toFloat() / bars.take(18).size.coerceAtLeast(1)
                            val played = isThisActive && frac <= progressFrac
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(h.coerceIn(5, 24).dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (played) accentColor else StudioTextSecondary.copy(alpha = 0.4f))
                            )
                        }
                    }
                    val totalSec = ((if (isThisActive && playback.durationMs > 0L) playback.durationMs else message.audioDurationMs) / 1000L).coerceAtLeast(1L)
                    val posSec = if (isThisActive) playback.positionMs / 1000L else 0L
                    Text(
                        text = if (isThisActive) String.format("%02d:%02d / %02d:%02d", posSec / 60, posSec % 60, totalSec / 60, totalSec % 60)
                        else String.format("%02d:%02d", totalSec / 60, totalSec % 60),
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
                }

                // Playback speed control 1x / 1.5x / 2x
                Surface(
                    onClick = { ChatAudioManager.cycleSpeed() },
                    shape = RoundedCornerShape(999.dp),
                    color = MutedSurface
                ) {
                    val speedLabel = when (playback.speed) {
                        1.5f -> "1.5x"
                        2.0f -> "2x"
                        else -> "1x"
                    }
                    Text(
                        text = speedLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ObsidianCharcoal,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        if (message.transcript.isNotBlank()) {
            TextButton(
                onClick = { showTranscript = !showTranscript },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier.height(26.dp)
            ) {
                Icon(Icons.Default.Subtitles, contentDescription = null, modifier = Modifier.size(14.dp), tint = accentColor)
                Spacer(Modifier.width(4.dp))
                Text(if (showTranscript) "Hide Transcript" else "Transcript", fontSize = 11.sp, color = accentColor)
            }
            AnimatedVisibility(visible = showTranscript) {
                Surface(shape = RoundedCornerShape(10.dp), color = MutedSurface, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = message.transcript,
                        style = MaterialTheme.typography.bodySmall,
                        color = ObsidianCharcoal,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun RichChatMessageBubble(
    message: ChatMessage,
    companyId: String,
    isMe: Boolean,
    myUid: String,
    bubbleThemeId: String,
    showSenderHeader: Boolean,
    otherParticipantCount: Int,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onEdit: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onToggleStar: () -> Unit,
    onTogglePin: () -> Unit,
    onReact: (String) -> Unit,
    onOpenEmojiPickerForReaction: () -> Unit,
    onVotePoll: (Int) -> Unit,
    onRsvpEvent: (String) -> Unit,
    onRevealViewOnce: () -> Unit,
    onStopLiveLocation: () -> Unit,
    onOpenAttachment: () -> Unit,
    onDownloadAttachment: () -> Unit,
    onShowMessageInfo: () -> Unit,
    onReportMessage: () -> Unit,
    onReportStatusError: (String) -> Unit
) {
    if (message.isDeletedFor(myUid) || message.isExpired()) return

    val context = LocalContext.current
    var showActionMenu by remember { mutableStateOf(false) }

    // Centered System notification message
    if (message.kindEnum() == ChatMessageKind.SYSTEM) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(shape = RoundedCornerShape(999.dp), color = MutedSurface.copy(alpha = 0.9f), border = BorderStroke(1.dp, SubtleOutline)) {
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
        return
    }

    val theme = ChatVisualCatalog.bubbleTheme(bubbleThemeId)
    val bubbleColor = if (isMe) theme.outgoingBg else theme.incomingBg
    val bubbleBorder = if (isMe) theme.outgoingBorder else SubtleOutline
    val timeLabel = remember(message.createdAt) {
        val millis = message.createdAt?.toDate()?.time ?: System.currentTimeMillis()
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
    }
    val bubbleShape = if (isMe) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }

    val transferMap by WorkerBridgeClient.transferProgress.collectAsStateWithLifecycle()
    val activeTransferProgress = transferMap[message.attachmentStoragePath]

    SwipeToReplyContainer(enabled = !message.isDeletedForEveryone, onReply = onReply) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            contentAlignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Column(
                modifier = Modifier.widthIn(max = 320.dp),
                horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
            ) {
                Surface(
                    onClick = { showActionMenu = true },
                    shape = bubbleShape,
                    color = bubbleColor,
                    border = BorderStroke(1.dp, bubbleBorder),
                    shadowElevation = 0.5.dp,
                    modifier = Modifier.testTag("message_bubble_${message.id}")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (!isMe && showSenderHeader) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = formatTitleCase(message.senderName),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = theme.accent
                                )
                                if (message.senderRole.isNotBlank()) {
                                    Text(
                                        text = "• ${formatTitleCase(message.senderRole)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioTextSecondary
                                    )
                                }
                            }
                        }

                        if (message.forwardedFromName.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null, tint = StudioTextSecondary, modifier = Modifier.size(13.dp))
                                Text(
                                    text = "Forwarded from ${formatTitleCase(message.forwardedFromName)}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                                    color = StudioTextSecondary
                                )
                            }
                        }

                        if (message.parentPreview.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = PureWhite.copy(alpha = 0.65f),
                                border = BorderStroke(1.dp, SubtleOutline),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .height(22.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(theme.accent)
                                    )
                                    Text(
                                        text = message.parentPreview,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ObsidianCharcoal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        if (message.isDeletedForEveryone) {
                            Text(
                                text = "🚫 This message was deleted",
                                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                color = StudioTextSecondary
                            )
                        } else if (message.viewOnce && !isMe && myUid in message.viewedByUids) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.MotionPhotosOff, contentDescription = null, tint = StudioTextSecondary, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "Opened view-once message",
                                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                                    color = StudioTextSecondary
                                )
                            }
                        } else if (message.viewOnce && !isMe && myUid !in message.viewedByUids) {
                            Surface(
                                onClick = {
                                    onRevealViewOnce()
                                    if (message.hasAttachment) onOpenAttachment()
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = VioletSoftBg,
                                border = BorderStroke(1.dp, VioletPrimary.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Default.LooksOne, contentDescription = null, tint = VioletPrimary)
                                    Text("View-Once ${message.kindEnum().label} • Tap to open", fontWeight = FontWeight.Bold, color = VioletPrimary)
                                }
                            }
                        } else {
                            // Stickers
                            if (message.kindEnum() == ChatMessageKind.STICKER) {
                                val stk = ChatVisualCatalog.stickers.firstOrNull { it.id == message.stickerId }
                                    ?: ChatVisualCatalog.stickers.first()
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = stk.bgColor,
                                    border = BorderStroke(1.dp, stk.accentColor.copy(alpha = 0.3f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(stk.emoji, fontSize = 36.sp)
                                        Text(stk.title, fontWeight = FontWeight.ExtraBold, color = stk.accentColor, style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }

                            // Animated GIF reaction card
                            if (message.kindEnum() == ChatMessageKind.GIF) {
                                val gif = ChatVisualCatalog.gifs.firstOrNull { it.id == message.gifId }
                                    ?: ChatVisualCatalog.gifs.first()
                                val pulse = rememberInfiniteTransition(label = "msg_gif_pulse")
                                val sc by pulse.animateFloat(
                                    initialValue = 0.94f,
                                    targetValue = 1.08f,
                                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                                    label = "msg_gif_scale"
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(100.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Brush.linearGradient(listOf(gif.gradientStart, gif.gradientEnd)))
                                        .padding(14.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text(gif.emoji, fontSize = 40.sp, modifier = Modifier.scale(sc))
                                        Column {
                                            Text("GIF REACTION", style = MaterialTheme.typography.labelSmall, color = PureWhite.copy(alpha = 0.85f), fontWeight = FontWeight.Bold)
                                            Text(message.gifTitle.ifBlank { gif.badgeText }, style = MaterialTheme.typography.titleMedium, color = PureWhite, fontWeight = FontWeight.ExtraBold)
                                        }
                                    }
                                }
                            }

                            // Poll
                            if (message.isPoll) {
                                InteractivePollCard(message = message, myUid = myUid, onVote = onVotePoll)
                            }

                            // Business Ref
                            if (message.hasBusinessRef) {
                                BusinessReferenceCard(message = message)
                            }

                            // Event Card
                            if (message.isEvent) {
                                val whenLabel = remember(message.eventTimeMillis) {
                                    SimpleDateFormat("EEE, dd MMM • h:mm a", Locale.getDefault()).format(Date(message.eventTimeMillis))
                                }
                                val myRsvp = message.eventRsvps[myUid].orEmpty()
                                val goingCount = message.eventRsvps.values.count { it == "GOING" }
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color(0xFFFFFBEB),
                                    border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Icon(Icons.Default.Event, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(18.dp))
                                            Text(message.eventTitle, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                                        }
                                        Text(whenLabel, style = MaterialTheme.typography.labelMedium, color = Color(0xFFB45309))
                                        if (message.eventLocation.isNotBlank()) {
                                            Text("📍 ${message.eventLocation}", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                        }
                                        Text("$goingCount going", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            FilterChip(
                                                selected = myRsvp == "GOING",
                                                onClick = { onRsvpEvent("GOING") },
                                                label = { Text("Going", fontSize = 11.sp) }
                                            )
                                            FilterChip(
                                                selected = myRsvp == "MAYBE",
                                                onClick = { onRsvpEvent("MAYBE") },
                                                label = { Text("Maybe", fontSize = 11.sp) }
                                            )
                                            IconButton(
                                                onClick = {
                                                    val calIntent = Intent(Intent.ACTION_INSERT).apply {
                                                        data = CalendarContract.Events.CONTENT_URI
                                                        putExtra(CalendarContract.Events.TITLE, message.eventTitle)
                                                        putExtra(CalendarContract.Events.EVENT_LOCATION, message.eventLocation)
                                                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, message.eventTimeMillis)
                                                        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, message.eventTimeMillis + 3_600_000L)
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    runCatching { context.startActivity(calIntent) }
                                                },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                Icon(Icons.Default.NotificationAdd, contentDescription = "Calendar Reminder", tint = Color(0xFFD97706), modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }

                            // Contact Card
                            if (message.isContactCard) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color(0xFFF0F9FF),
                                    border = BorderStroke(1.dp, Color(0xFFBAE6FD)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(Icons.Default.ContactPhone, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(26.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(message.contactName, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                                            if (message.contactPhone.isNotBlank()) {
                                                Text(message.contactPhone, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                            }
                                        }
                                        if (message.contactPhone.isNotBlank()) {
                                            IconButton(
                                                onClick = {
                                                    runCatching {
                                                        context.startActivity(
                                                            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${message.contactPhone}")).apply {
                                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                            }
                                                        )
                                                    }
                                                }
                                            ) {
                                                Icon(Icons.Default.Call, contentDescription = "Dial contact", tint = EmeraldApproved)
                                            }
                                        }
                                    }
                                }
                            }

                            // Location / Live Location Card
                            if (message.hasLocation) {
                                val isLive = message.kindEnum() == ChatMessageKind.LIVE_LOCATION
                                val stillLive = isLive && message.liveLocationUntilMillis > System.currentTimeMillis()
                                Surface(
                                    onClick = {
                                        ChatLocationManager.openLocationInMaps(
                                            context = context,
                                            lat = message.locationLat,
                                            lng = message.locationLng,
                                            label = message.locationLabel
                                        )
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color(0xFFECFDF5),
                                    border = BorderStroke(1.dp, EmeraldApproved.copy(alpha = 0.35f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Icon(
                                                imageVector = if (isLive) Icons.Default.MyLocation else Icons.Default.LocationOn,
                                                contentDescription = null,
                                                tint = EmeraldApproved,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = if (stillLive) "Live Location (Active)" else if (isLive) "Live Location Ended" else "Shared Location",
                                                fontWeight = FontWeight.Bold,
                                                color = EmeraldApproved
                                            )
                                        }
                                        Text(
                                            text = message.locationLabel.ifBlank { "${message.locationLat}, ${message.locationLng}" },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ObsidianCharcoal
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Tap to open in Maps →", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0284C7), fontWeight = FontWeight.Bold)
                                            if (stillLive && isMe) {
                                                TextButton(onClick = onStopLiveLocation, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                                    Text("Stop Sharing", color = CrimsonBlocked, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Voice / Audio Attachment Player
                            if (message.attachmentTypeEnum() == ChatAttachmentType.AUDIO || message.kindEnum() == ChatMessageKind.VOICE) {
                                VoiceNotePlayerBubble(
                                    message = message,
                                    companyId = companyId,
                                    accentColor = theme.accent,
                                    onError = onReportStatusError
                                )
                            }

                            // Photo / Video / Document Attachment Card + inline image preview + upload/download progress
                            if (message.hasAttachment && message.attachmentTypeEnum() != ChatAttachmentType.AUDIO) {
                                if (message.attachmentTypeEnum() == ChatAttachmentType.IMAGE) {
                                    var inlineBmp by remember(message.id, message.attachmentStoragePath) {
                                        mutableStateOf(ChatFileManager.decodeMessageBitmap(message, 720))
                                    }
                                    LaunchedEffect(message.id, message.attachmentStoragePath) {
                                        if (inlineBmp == null) {
                                            inlineBmp = ChatFileManager.loadOrDownloadMessageBitmap(context, message, companyId, 720)
                                        }
                                    }
                                    if (inlineBmp != null) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { onOpenAttachment() }
                                        ) {
                                            Image(
                                                bitmap = inlineBmp!!.asImageBitmap(),
                                                contentDescription = message.attachmentName.ifBlank { "Image" },
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 120.dp, max = 210.dp)
                                            )
                                            if (message.isHd) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = Color.Black.copy(alpha = 0.65f),
                                                    modifier = Modifier
                                                        .align(Alignment.TopStart)
                                                        .padding(8.dp)
                                                ) {
                                                    Text("HD", color = PureWhite, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                }
                                            }
                                        }
                                    }
                                }

                                Surface(
                                    onClick = onOpenAttachment,
                                    shape = RoundedCornerShape(10.dp),
                                    color = PureWhite.copy(alpha = 0.78f),
                                    border = BorderStroke(1.dp, SubtleOutline),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = when (message.attachmentTypeEnum()) {
                                                    ChatAttachmentType.IMAGE -> Icons.Default.Image
                                                    ChatAttachmentType.VIDEO -> Icons.Default.Videocam
                                                    else -> Icons.Default.Description
                                                },
                                                contentDescription = null,
                                                tint = ObsidianCharcoal,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = message.attachmentName.ifBlank { "Attachment" },
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ObsidianCharcoal,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                val meta = buildString {
                                                    if (message.attachmentSizeBytes > 0L) append(ChatFileManager.formatFileSize(message.attachmentSizeBytes))
                                                    if (message.isHd) append(" • HD")
                                                }
                                                if (meta.isNotBlank()) {
                                                    Text(meta, style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                                                }
                                            }
                                            IconButton(onClick = onDownloadAttachment, modifier = Modifier.size(30.dp)) {
                                                Icon(Icons.Default.Download, contentDescription = "Download", tint = ObsidianCharcoal, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        if (activeTransferProgress != null && activeTransferProgress in 0.01f..0.99f) {
                                            LinearProgressIndicator(
                                                progress = { activeTransferProgress },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(4.dp)
                                                    .clip(RoundedCornerShape(999.dp)),
                                                color = theme.accent
                                            )
                                        }
                                    }
                                }
                            }

                            // Formatted text body + link preview pill
                            val showBody = message.body.isNotBlank() &&
                                !message.isPoll &&
                                !message.hasBusinessRef &&
                                !message.isEvent &&
                                !message.isContactCard &&
                                !message.hasLocation &&
                                message.kindEnum() != ChatMessageKind.STICKER &&
                                message.kindEnum() != ChatMessageKind.GIF &&
                                !(message.kindEnum() == ChatMessageKind.VOICE && message.body.startsWith("Voice message"))

                            if (showBody) {
                                if (message.kindEnum() == ChatMessageKind.ANNOUNCEMENT) {
                                    Surface(shape = RoundedCornerShape(8.dp), color = VioletSoftBg) {
                                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Icon(Icons.Default.Campaign, contentDescription = null, tint = VioletPrimary, modifier = Modifier.size(14.dp))
                                            Text("ANNOUNCEMENT", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = VioletPrimary)
                                        }
                                    }
                                }
                                Text(
                                    text = formatChatRichText(message.body, message.mentions, mentionColor = theme.accent),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ObsidianCharcoal
                                )
                                val firstUrl = message.linkUrl.ifBlank { ChatFileManager.extractHttpsUrls(message.body).firstOrNull().orEmpty() }
                                if (firstUrl.isNotBlank()) {
                                    Surface(
                                        onClick = {
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(firstUrl)).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                })
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = PureWhite.copy(alpha = 0.8f),
                                        border = BorderStroke(1.dp, SubtleOutline),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Icon(Icons.Default.Link, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(18.dp))
                                            Text(firstUrl, style = MaterialTheme.typography.labelSmall, color = Color(0xFF0284C7), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }

                        // Metadata Row: Pinned, Starred, View-Once, Edited, Timestamp, Status ticks
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (message.viewOnce) {
                                Icon(Icons.Default.LooksOne, contentDescription = "View once", tint = VioletPrimary, modifier = Modifier.size(12.dp))
                            }
                            if (message.pinned) {
                                Icon(Icons.Default.PushPin, contentDescription = "Pinned", tint = VioletPrimary, modifier = Modifier.size(12.dp))
                            }
                            if (message.isStarredBy(myUid)) {
                                Icon(Icons.Default.Star, contentDescription = "Starred", tint = Color(0xFFF59E0B), modifier = Modifier.size(12.dp))
                            }
                            if (message.isEdited) {
                                Text("edited", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = StudioTextSecondary)
                            }
                            Text(timeLabel, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = StudioTextSecondary)
                            if (isMe) {
                                DeliveryStatusIcon(status = message.effectiveStatusForSender(otherParticipantCount))
                            }
                        }
                    }
                }

                // Reactions Pills
                if (message.reactions.isNotEmpty()) {
                    val grouped = remember(message.reactions) {
                        message.reactions.values.groupingBy { it }.eachCount()
                    }
                    Row(
                        modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        grouped.forEach { (emoji, count) ->
                            val isMyReact = message.reactions[myUid] == emoji
                            Surface(
                                onClick = { onReact(emoji) },
                                shape = RoundedCornerShape(999.dp),
                                color = if (isMyReact) VioletSoftBg else PureWhite,
                                border = BorderStroke(1.dp, if (isMyReact) VioletPrimary else SubtleOutline)
                            ) {
                                Text(
                                    text = "$emoji $count",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = ObsidianCharcoal,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showActionMenu) {
        RichMessageActionsBottomSheet(
            message = message,
            isMe = isMe,
            myUid = myUid,
            onDismiss = { showActionMenu = false },
            onReact = { emoji ->
                onReact(emoji)
                showActionMenu = false
            },
            onOpenMoreEmojis = {
                showActionMenu = false
                onOpenEmojiPickerForReaction()
            },
            onReply = {
                onReply()
                showActionMenu = false
            },
            onForward = {
                onForward()
                showActionMenu = false
            },
            onEdit = {
                onEdit()
                showActionMenu = false
            },
            onCopy = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Chat message", message.body))
                showActionMenu = false
            },
            onToggleStar = {
                onToggleStar()
                showActionMenu = false
            },
            onTogglePin = {
                onTogglePin()
                showActionMenu = false
            },
            onShowInfo = {
                showActionMenu = false
                onShowMessageInfo()
            },
            onReport = {
                showActionMenu = false
                onReportMessage()
            },
            onDeleteForMe = {
                onDeleteForMe()
                showActionMenu = false
            },
            onDeleteForEveryone = {
                onDeleteForEveryone()
                showActionMenu = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RichMessageActionsBottomSheet(
    message: ChatMessage,
    isMe: Boolean,
    myUid: String,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onOpenMoreEmojis: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onToggleStar: () -> Unit,
    onTogglePin: () -> Unit,
    onShowInfo: () -> Unit,
    onReport: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        contentColor = ObsidianCharcoal
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .background(MutedSurface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .clickable { onReact(emoji) },
                        contentAlignment = Alignment.Center
                    ) { Text(emoji, fontSize = 21.sp) }
                }
                IconButton(onClick = onOpenMoreEmojis, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = "More emojis", tint = ObsidianCharcoal)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, null) },
                    text = { Text("Reply") },
                    onClick = onReply
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Forward, null) },
                    text = { Text("Forward") },
                    onClick = onForward
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(if (message.isStarredBy(myUid)) Icons.Default.Star else Icons.Default.StarBorder, null, tint = if (message.isStarredBy(myUid)) Color(0xFFF59E0B) else ObsidianCharcoal) },
                    text = { Text(if (message.isStarredBy(myUid)) "Unstar" else "Star") },
                    onClick = onToggleStar
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.PushPin, null) },
                    text = { Text(if (message.pinned) "Unpin" else "Pin") },
                    onClick = onTogglePin
                )
                if (message.body.isNotBlank()) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        text = { Text("Copy") },
                        onClick = onCopy
                    )
                }
                if (isMe && !message.isDeletedForEveryone && message.kindEnum() == ChatMessageKind.TEXT) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        text = { Text("Edit") },
                        onClick = onEdit
                    )
                }
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Info, null) },
                    text = { Text("Message Info") },
                    onClick = onShowInfo
                )
                if (!isMe) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Flag, null, tint = Color(0xFFD97706)) },
                        text = { Text("Report Message", color = Color(0xFFD97706)) },
                        onClick = onReport
                    )
                }
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = CrimsonBlocked) },
                    text = { Text("Delete for me", color = CrimsonBlocked) },
                    onClick = onDeleteForMe
                )
                if (isMe && !message.isDeletedForEveryone) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.DeleteForever, null, tint = CrimsonBlocked) },
                        text = { Text("Delete for everyone", color = CrimsonBlocked, fontWeight = FontWeight.Bold) },
                        onClick = onDeleteForEveryone
                    )
                }
            }
        }
    }
}
