package com.example.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.chat.ChatFileManager
import com.example.chat.StagedChatAttachment
import com.example.domain.MoneyFormatter
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.ChatViewModel
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioAvatar
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val WhatsAppOutgoingGreen = Color(0xFFE7F8EE)
val WhatsAppOutgoingBorder = Color(0xFFBCE7CB)
val WhatsAppIncomingBg = PureWhite
val WhatsAppTickBlue = Color(0xFF0288D1)
val WhatsAppTickGray = Color(0xFF94A3B8)

@Composable
fun DateSeparatorPill(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MutedSurface.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, SubtleOutline)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = StudioTextSecondary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
fun DeliveryStatusIcon(status: String, modifier: Modifier = Modifier) {
    when (status.uppercase()) {
        "READ" -> Icon(
            imageVector = Icons.Default.DoneAll,
            contentDescription = "Read",
            tint = WhatsAppTickBlue,
            modifier = modifier.size(15.dp)
        )
        "DELIVERED" -> Icon(
            imageVector = Icons.Default.DoneAll,
            contentDescription = "Delivered",
            tint = WhatsAppTickGray,
            modifier = modifier.size(15.dp)
        )
        "PENDING" -> Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = "Pending",
            tint = WhatsAppTickGray,
            modifier = modifier.size(13.dp)
        )
        "FAILED" -> Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = "Failed",
            tint = CrimsonBlocked,
            modifier = modifier.size(14.dp)
        )
        else -> Icon( // SENT
            imageVector = Icons.Default.Check,
            contentDescription = "Sent",
            tint = WhatsAppTickGray,
            modifier = modifier.size(14.dp)
        )
    }
}

@Composable
fun InteractivePollCard(
    message: ChatMessage,
    myUid: String,
    onVote: (Int) -> Unit
) {
    val totalVotes = remember(message.pollVotes) {
        message.pollVotes.values.flatten().distinct().size
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = PureWhite.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, SubtleOutline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Poll, contentDescription = null, tint = VioletPrimary, modifier = Modifier.size(18.dp))
                Text(
                    text = message.pollQuestion,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ObsidianCharcoal
                )
            }
            Text(
                text = if (totalVotes == 1) "1 vote" else "$totalVotes votes",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextSecondary
            )

            message.pollOptions.forEachIndexed { idx, opt ->
                val voters = message.pollVotes[idx.toString()] ?: emptyList()
                val isMyVote = myUid in voters
                val pct = if (totalVotes > 0) (voters.size.toFloat() / totalVotes.toFloat()) else 0f

                Surface(
                    onClick = { onVote(idx) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isMyVote) VioletSoftBg else MutedSurface.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, if (isMyVote) VioletPrimary.copy(alpha = 0.5f) else SubtleOutline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (isMyVote) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (isMyVote) VioletPrimary else StudioTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = opt,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isMyVote) FontWeight.Bold else FontWeight.Medium,
                                    color = ObsidianCharcoal
                                )
                            }
                            Text(
                                text = "${voters.size}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = ObsidianCharcoal
                            )
                        }

                        if (totalVotes > 0) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { pct },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = if (isMyVote) VioletPrimary else EmeraldApproved,
                                trackColor = Color(0xFFE2E8F0)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BusinessReferenceCard(
    message: ChatMessage,
    onOpen: (String, String) -> Unit = { _, _ -> }
) {
    val kind = message.businessRefKind.uppercase()
    val (icon, tint, bg) = when (kind) {
        "INVOICE" -> Triple(Icons.Default.Receipt, EmeraldApproved, Color(0xFFECFDF5))
        "QUOTATION" -> Triple(Icons.Default.Work, VioletPrimary, Color(0xFFF5F3FF))
        "TASK" -> Triple(Icons.Default.TaskAlt, Color(0xFF0288D1), Color(0xFFF0F9FF))
        "PROJECT" -> Triple(Icons.Default.Folder, Color(0xFFD97706), Color(0xFFFFFBEB))
        "PAYMENT" -> Triple(Icons.Default.Payment, EmeraldApproved, Color(0xFFECFDF5))
        else -> Triple(Icons.Default.Bookmark, ObsidianCharcoal, MutedSurface)
    }

    Surface(
        onClick = { onOpen(kind, message.businessRefId) },
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    Text(
                        text = kind,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = tint
                    )
                }
                if (message.businessRefStatus.isNotBlank()) {
                    StatusPill(
                        label = message.businessRefStatus,
                        containerColor = PureWhite,
                        contentColor = tint
                    )
                }
            }

            Text(
                text = message.businessRefTitle.ifBlank { message.body },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = ObsidianCharcoal
            )

            if (message.businessRefSubtitle.isNotBlank()) {
                Text(
                    text = message.businessRefSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextSecondary
                )
            }

            if (message.businessRefAmountPaise > 0L) {
                Text(
                    text = MoneyFormatter.formatPaise(message.businessRefAmountPaise),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = ObsidianCharcoal
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "View details →",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = tint
                )
            }
        }
    }
}

@Composable
fun VoiceNoteBubble(
    message: ChatMessage,
    onPlay: () -> Unit = {}
) {
    var isPlaying by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = PureWhite.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, SubtleOutline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                onClick = {
                    isPlaying = !isPlaying
                    onPlay()
                },
                shape = CircleShape,
                color = EmeraldApproved,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play voice note",
                        tint = PureWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Pseudo waveform visualizer
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val heights = listOf(8, 14, 22, 12, 18, 24, 16, 20, 10, 15, 22, 8, 16, 12)
                heights.forEachIndexed { i, h ->
                    val color = if (isPlaying && i < 7) EmeraldApproved else StudioTextSecondary.copy(alpha = 0.5f)
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(h.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(color)
                    )
                }
            }

            Text(
                text = if (message.audioDurationMs > 0L) {
                    val sec = (message.audioDurationMs / 1000).toInt()
                    String.format("%02d:%02d", sec / 60, sec % 60)
                } else "Voice Note",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextSecondary
            )
        }
    }
}

@Composable
fun WhatsAppMessageBubble(
    message: ChatMessage,
    isMe: Boolean,
    myUid: String,
    senderPhotoBase64: String,
    showSenderHeader: Boolean,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onToggleStar: () -> Unit,
    onTogglePin: () -> Unit,
    onReact: (String) -> Unit,
    onVotePoll: (Int) -> Unit,
    onOpenAttachment: () -> Unit,
    onDownloadAttachment: () -> Unit
) {
    if (message.isDeletedFor(myUid)) return

    val context = LocalContext.current
    var showActionMenu by remember { mutableStateOf(false) }

    val timeLabel = remember(message.createdAt) {
        val millis = message.createdAt?.toDate()?.time ?: System.currentTimeMillis()
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
    }

    val bubbleShape = if (isMe) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }

    val bubbleColor = if (isMe) WhatsAppOutgoingGreen else WhatsAppIncomingBg
    val bubbleBorder = if (isMe) WhatsAppOutgoingBorder else SubtleOutline

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
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Sender Header (if not me and in group)
                    if (!isMe && showSenderHeader) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = formatTitleCase(message.senderName),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = VioletPrimary
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

                    // Reply target box
                    if (message.parentPreview.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = PureWhite.copy(alpha = 0.6f),
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
                                        .height(24.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(VioletPrimary)
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

                    // Deleted for everyone notice
                    if (message.isDeletedForEveryone) {
                        Text(
                            text = "🚫 This message was deleted",
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                            color = StudioTextSecondary
                        )
                    } else {
                        // Poll
                        if (message.isPoll) {
                            InteractivePollCard(message = message, myUid = myUid, onVote = onVotePoll)
                        }

                        // Business Card
                        if (message.hasBusinessRef) {
                            BusinessReferenceCard(message = message)
                        }

                        // Voice Note
                        if (message.attachmentTypeEnum() == ChatAttachmentType.AUDIO || message.body.startsWith("Voice message")) {
                            VoiceNoteBubble(message = message)
                        }

                        // Text body
                        if (message.body.isNotBlank() && !message.body.startsWith("Voice message") && !message.isPoll && !message.hasBusinessRef) {
                            Text(
                                text = message.body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ObsidianCharcoal
                            )
                        }

                        // Attachment preview
                        if (message.hasAttachment && message.attachmentTypeEnum() != ChatAttachmentType.AUDIO) {
                            Surface(
                                onClick = onOpenAttachment,
                                shape = RoundedCornerShape(10.dp),
                                color = PureWhite.copy(alpha = 0.7f),
                                border = BorderStroke(1.dp, SubtleOutline),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = if (message.attachmentTypeEnum() == ChatAttachmentType.IMAGE) Icons.Default.Image else Icons.Default.Description,
                                        contentDescription = null,
                                        tint = ObsidianCharcoal,
                                        modifier = Modifier.size(24.dp)
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
                                        if (message.attachmentSizeBytes > 0L) {
                                            Text(
                                                text = ChatFileManager.formatFileSize(message.attachmentSizeBytes),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = StudioTextSecondary
                                            )
                                        }
                                    }
                                    IconButton(onClick = onDownloadAttachment, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Download, contentDescription = "Download", tint = ObsidianCharcoal, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    // Metadata Row: Pinned, Starred, Edited, Timestamp, Delivery Ticks
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (message.pinned) {
                            Icon(Icons.Default.PushPin, contentDescription = "Pinned", tint = VioletPrimary, modifier = Modifier.size(12.dp))
                        }
                        if (message.isStarredBy(myUid)) {
                            Icon(Icons.Default.Star, contentDescription = "Starred", tint = Color(0xFFF59E0B), modifier = Modifier.size(12.dp))
                        }
                        if (message.isEdited) {
                            Text(
                                text = "edited",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = StudioTextSecondary
                            )
                        }
                        Text(
                            text = timeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = StudioTextSecondary
                        )
                        if (isMe) {
                            DeliveryStatusIcon(status = message.deliveryStatus)
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
                            border = BorderStroke(1.dp, if (isMyReact) VioletPrimary else SubtleOutline),
                            shadowElevation = 0.5.dp
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

    // Message Options & Quick Emoji Reaction Bar (Bottom Sheet or Dialog)
    if (showActionMenu) {
        MessageActionsBottomSheet(
            message = message,
            isMe = isMe,
            myUid = myUid,
            onDismiss = { showActionMenu = false },
            onReact = { emoji ->
                onReact(emoji)
                showActionMenu = false
            },
            onReply = {
                onReply()
                showActionMenu = false
            },
            onEdit = {
                onEdit()
                showActionMenu = false
            },
            onCopy = {
                val clip = ClipData.newPlainText("Chat message", message.body)
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(clip)
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
fun MessageActionsBottomSheet(
    message: ChatMessage,
    isMe: Boolean,
    myUid: String,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onToggleStar: () -> Unit,
    onTogglePin: () -> Unit,
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Quick Emoji Reaction Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .background(MutedSurface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🔥").forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onReact(emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = emoji, fontSize = 22.sp)
                    }
                }
            }

            // Actions List
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = ObsidianCharcoal) },
                    text = { Text("Reply", fontWeight = FontWeight.Medium) },
                    onClick = onReply
                )
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            imageVector = if (message.isStarredBy(myUid)) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = null,
                            tint = if (message.isStarredBy(myUid)) Color(0xFFF59E0B) else ObsidianCharcoal
                        )
                    },
                    text = { Text(if (message.isStarredBy(myUid)) "Unstar" else "Star", fontWeight = FontWeight.Medium) },
                    onClick = onToggleStar
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null, tint = ObsidianCharcoal) },
                    text = { Text(if (message.pinned) "Unpin" else "Pin", fontWeight = FontWeight.Medium) },
                    onClick = onTogglePin
                )
                if (message.body.isNotBlank()) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = ObsidianCharcoal) },
                        text = { Text("Copy", fontWeight = FontWeight.Medium) },
                        onClick = onCopy
                    )
                }
                if (isMe && !message.isDeletedForEveryone) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = ObsidianCharcoal) },
                        text = { Text("Edit", fontWeight = FontWeight.Medium) },
                        onClick = onEdit
                    )
                }
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = CrimsonBlocked) },
                    text = { Text("Delete for me", fontWeight = FontWeight.Medium, color = CrimsonBlocked) },
                    onClick = onDeleteForMe
                )
                if (isMe) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = CrimsonBlocked) },
                        text = { Text("Delete for everyone", fontWeight = FontWeight.Bold, color = CrimsonBlocked) },
                        onClick = onDeleteForEveryone
                    )
                }
            }
        }
    }
}

@Composable
fun CreatePollDialog(
    onSubmit: (String, List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = PureWhite,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Create Poll",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = ObsidianCharcoal
                )

                if (!errorMsg.isNullOrBlank()) {
                    Text(text = errorMsg.orEmpty(), color = CrimsonBlocked, style = MaterialTheme.typography.labelSmall)
                }

                OutlinedTextField(
                    value = question,
                    onValueChange = {
                        question = it
                        errorMsg = null
                    },
                    label = { Text("Poll Question") },
                    placeholder = { Text("Ask a question…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Options",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = StudioTextSecondary
                )

                options.forEachIndexed { i, opt ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = opt,
                            onValueChange = {
                                options[i] = it
                                errorMsg = null
                            },
                            label = { Text("Option ${i + 1}") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        if (options.size > 2) {
                            IconButton(onClick = { options.removeAt(i) }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove option", tint = StudioTextSecondary)
                            }
                        }
                    }
                }

                if (options.size < 6) {
                    TextButton(onClick = { options.add("") }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Option")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val cleanQ = question.trim()
                            val cleanOpts = options.map { it.trim() }.filter { it.isNotBlank() }
                            if (cleanQ.isBlank()) {
                                errorMsg = "Please enter a question."
                            } else if (cleanOpts.size < 2) {
                                errorMsg = "Please provide at least 2 options."
                            } else {
                                onSubmit(cleanQ, cleanOpts)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                    ) {
                        Text("Create Poll")
                    }
                }
            }
        }
    }
}

@Composable
fun ShareBusinessRecordDialog(
    state: AgencyUiState,
    onShare: (kind: String, refId: String, title: String, subtitle: String, status: String, amountPaise: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf("INVOICE") } // INVOICE, QUOTATION, TASK, PROJECT, PAYMENT

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = PureWhite,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Share Business Record",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = ObsidianCharcoal
                )

                // Category selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("INVOICE", "QUOTATION", "TASK", "PROJECT", "PAYMENT").forEach { cat ->
                        val isSel = selectedCategory == cat
                        Surface(
                            onClick = { selectedCategory = cat },
                            shape = RoundedCornerShape(999.dp),
                            color = if (isSel) ObsidianCharcoal else MutedSurface
                        ) {
                            Text(
                                text = cat,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isSel) PureWhite else ObsidianCharcoal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // List of items
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (selectedCategory) {
                        "INVOICE" -> {
                            val activeQuotes = state.quotations.filter { it.statusEnum() == QuotationStatus.ACCEPTED }
                            if (activeQuotes.isEmpty()) {
                                Text("No approved invoices to share.", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                            }
                            activeQuotes.forEach { q ->
                                Surface(
                                    onClick = {
                                        onShare("INVOICE", q.id, "Invoice #${q.referenceCode}", "Client: ${q.clientCompanyName}", "DUE", q.totalAmountPaise)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MutedSurface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("Invoice #${q.referenceCode}", fontWeight = FontWeight.Bold)
                                        Text(q.clientCompanyName, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                        Text(MoneyFormatter.formatPaise(q.totalAmountPaise), fontWeight = FontWeight.ExtraBold, color = EmeraldApproved)
                                    }
                                }
                            }
                        }
                        "QUOTATION" -> {
                            state.quotations.take(15).forEach { q ->
                                Surface(
                                    onClick = {
                                        onShare("QUOTATION", q.id, "Quotation: ${q.clientCompanyName}", q.referenceCode, q.status, q.totalAmountPaise)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MutedSurface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(q.clientCompanyName, fontWeight = FontWeight.Bold)
                                        Text("Ref: ${q.referenceCode} • ${q.status}", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                    }
                                }
                            }
                        }
                        "TASK" -> {
                            state.deliverables.take(15).forEach { d ->
                                Surface(
                                    onClick = {
                                        onShare("TASK", d.id, d.title, "Due in ${d.daysToComplete}d", d.status, 0L)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MutedSurface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(d.title, fontWeight = FontWeight.Bold)
                                        Text(d.status, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                    }
                                }
                            }
                        }
                        "PROJECT" -> {
                            state.projects.take(15).forEach { p ->
                                Surface(
                                    onClick = {
                                        onShare("PROJECT", p.id, p.title, p.clientCompanyName, p.status, 0L)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MutedSurface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(p.title, fontWeight = FontWeight.Bold)
                                        Text(p.clientCompanyName, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                    }
                                }
                            }
                        }
                        "PAYMENT" -> {
                            state.payments.take(15).forEach { pay ->
                                Surface(
                                    onClick = {
                                        onShare("PAYMENT", pay.id, "Payment: ${MoneyFormatter.formatPaise(pay.amountPaise)}", pay.method, "RECEIVED", pay.amountPaise)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MutedSurface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(MoneyFormatter.formatPaise(pay.amountPaise), fontWeight = FontWeight.ExtraBold, color = EmeraldApproved)
                                        Text("${pay.method} • ${pay.referenceId}", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationInfoSheet(
    channel: ChatChannel,
    allMembers: List<Member>,
    myUid: String,
    isSuperAdmin: Boolean,
    onMuteToggle: () -> Unit,
    onAddMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onToggleAdmin: (String) -> Unit,
    onClearChat: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDirect = channel.channelTypeEnum() == ChatChannelType.DIRECT
    val isMuted = channel.isMutedFor(myUid)
    val isChannelAdmin = isSuperAdmin || myUid in channel.adminUids || myUid == channel.createdByUid

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
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Large Avatar & Title
            StudioAvatar(
                name = channel.title,
                size = 72.dp,
                borderColor = PureWhite,
                photoBase64 = ""
            )

            Text(
                text = formatTitleCase(channel.title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = ObsidianCharcoal
            )
            Text(
                text = channel.topic.ifBlank { "Team workspace channel" },
                style = MaterialTheme.typography.bodyMedium,
                color = StudioTextSecondary,
                textAlign = TextAlign.Center
            )

            // Quick Actions: Mute Switch
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MutedSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = ObsidianCharcoal
                        )
                        Text(
                            text = "Mute Notifications",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Switch(checked = isMuted, onCheckedChange = { onMuteToggle() })
                }
            }

            // Members section (if group channel)
            if (!isDirect) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Members (${channel.participantUids.size.coerceAtLeast(1)})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = ObsidianCharcoal
                        )
                    }

                    channel.participantUids.forEach { uid ->
                        val member = allMembers.firstOrNull { it.uid == uid }
                        val name = member?.fullName ?: "Member"
                        val isAdmin = uid in channel.adminUids || uid == channel.createdByUid

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PureWhite,
                            border = BorderStroke(1.dp, SubtleOutline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                StudioAvatar(
                                    name = name,
                                    size = 36.dp,
                                    borderColor = PureWhite,
                                    photoBase64 = member?.photoBase64.orEmpty()
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = formatTitleCase(name) + if (uid == myUid) " (You)" else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (isAdmin) "Admin" else "Member",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isAdmin) EmeraldApproved else StudioTextSecondary
                                    )
                                }
                                if (isChannelAdmin && uid != myUid) {
                                    TextButton(onClick = { onToggleAdmin(uid) }) {
                                        Text(if (isAdmin) "Dismiss Admin" else "Make Admin", fontSize = 11.sp)
                                    }
                                    IconButton(onClick = { onRemoveMember(uid) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Remove", tint = CrimsonBlocked, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Destructive Action: Clear Chat
            Button(
                onClick = onClearChat,
                colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked.copy(alpha = 0.12f), contentColor = CrimsonBlocked),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Clear Chat History", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}
