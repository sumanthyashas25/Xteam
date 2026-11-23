package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.chat.ChatFileManager
import com.example.model.Member
import com.example.system.AppHapticsAndNotifier
import com.example.ui.SyncFeedback
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun XMaxLogoImage(
    modifier: Modifier = Modifier,
    tint: Color? = ObsidianCharcoal,
    height: Dp = 20.dp,
    contentDescription: String? = "X.MAX Logo"
) {
    Image(
        painter = painterResource(id = R.drawable.logo_xmax),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        colorFilter = tint?.let { ColorFilter.tint(it) },
        modifier = modifier.height(height)
    )
}

@Composable
fun XMaxWordmarkBadge(
    companyName: String,
    modifier: Modifier = Modifier,
    tint: Color = ObsidianCharcoal
) {
    val clean = companyName.trim()
    val suffix = when {
        clean.isBlank() || clean.equals("X.MAX", ignoreCase = true) -> ""
        clean.startsWith("X.MAX ", ignoreCase = true) -> clean.removePrefix("X.MAX ").trim()
        else -> clean
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        XMaxLogoImage(tint = tint, height = 18.dp)
        if (suffix.isNotEmpty()) {
            Text(
                text = suffix.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.6.sp,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val AvatarPalette = listOf(
    Color(0xFF1D1F26) to PureWhite,
    InfoPillBg to InfoPillInk,
    SoftMint to DeepMintInk,
    ButterYellow to DeepAmberInk,
    MutedSurface to ObsidianCharcoal
)

@Composable
fun StudioAvatar(
    name: String,
    size: Dp = 38.dp,
    borderColor: Color = PureWhite,
    photoBase64: String = "",
    showEditBadge: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val clean = name.trim().ifBlank { "X" }
    val parts = clean.split(" ").filter { it.isNotBlank() }
    val initials = when {
        parts.size >= 2 -> "${parts.first().first().uppercaseChar()}${parts.last().first().uppercaseChar()}"
        clean.length >= 2 -> clean.take(2).uppercase()
        else -> clean.uppercase()
    }
    val (bg, fg) = AvatarPalette[abs(clean.hashCode()) % AvatarPalette.size]
    val decodedBitmap = remember(photoBase64) {
        ChatFileManager.decodeBase64Bitmap(photoBase64)?.asImageBitmap()
    }

    Box(
        modifier = modifier
            .size(size)
            .let { base -> if (onClick != null) base.clip(CircleShape).clickable(onClick = onClick) else base },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(bg)
                .border(1.5.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (decodedBitmap != null) {
                Image(
                    bitmap = decodedBitmap,
                    contentDescription = clean.uppercase(),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = initials,
                    style = if (size >= 36.dp) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = fg
                )
            }
        }
    }
}

@Composable
fun OverlappingAvatarRow(
    names: List<String>,
    avatarSize: Dp = 28.dp,
    borderColor: Color = PureWhite,
    maxVisible: Int = 3
) {
    OverlappingAvatarMembers(
        members = names.filter { it.isNotBlank() }.distinct().map { it to "" },
        avatarSize = avatarSize,
        borderColor = borderColor,
        maxVisible = maxVisible
    )
}

@Composable
fun OverlappingAvatarMembers(
    members: List<Pair<String, String>>,
    avatarSize: Dp = 28.dp,
    borderColor: Color = PureWhite,
    maxVisible: Int = 3
) {
    val cleanMembers = members.filter { it.first.isNotBlank() }.distinctBy { it.first }
    if (cleanMembers.isEmpty()) return
    val shown = cleanMembers.take(maxVisible)
    val overflow = cleanMembers.size - shown.size

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.width((shown.size * (avatarSize.value * 0.68f) + avatarSize.value * 0.35f).dp),
            contentAlignment = Alignment.CenterStart
        ) {
            shown.forEachIndexed { idx, (name, photo) ->
                StudioAvatar(
                    name = name,
                    size = avatarSize,
                    borderColor = borderColor,
                    photoBase64 = photo,
                    modifier = Modifier.offset(x = (idx * (avatarSize.value * 0.65f)).dp)
                )
            }
        }
        if (overflow > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = "+$overflow",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = ObsidianCharcoal
            )
        }
    }
}

/**
 * Interactive slide-to-action pill matching reference UI:
 * Dark frosted capsule, bright white circular handle with blue chevron, shimmering uppercase label,
 * progressive haptic ticks while dragging, and distinct completion vibration on slide completion.
 */
@Composable
fun SlideToActionButton(
    label: String,
    enabled: Boolean = true,
    onSlideComplete: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "slide_to_action_button",
    containerColor: Color = Color(0xFF434752),
    thumbColor: Color = PureWhite,
    chevronColor: Color = Color(0xFF4767C9)
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val trackHeight = 58.dp
    val thumbSize = 50.dp
    val thumbPadding = 4.dp

    val shimmerTransition = rememberInfiniteTransition(label = "slide_shimmer")
    val shimmerProgress by shimmerTransition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "slide_shimmer_progress"
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) containerColor else containerColor.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
            .testTag(testTag)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(label.uppercase()) {
                        if (enabled) {
                            AppHapticsAndNotifier.triggerCompletionVibration(context)
                            onSlideComplete()
                        }
                        enabled
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val maxDragPx = with(density) {
            (maxWidth - thumbSize - thumbPadding * 2).toPx().coerceAtLeast(1f)
        }
        val dragOffsetPx = remember { Animatable(0f) }
        var lastTickStep by remember { mutableStateOf(0) }
        var completed by remember { mutableStateOf(false) }

        val dragFraction = (dragOffsetPx.value / maxDragPx).coerceIn(0f, 1f)
        val textAlpha = (1f - dragFraction * 1.45f).coerceIn(0f, 1f)

        // Centered fading & shimmering text
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = thumbSize + 6.dp, end = 16.dp)
                .alpha(textAlpha),
            contentAlignment = Alignment.Center
        ) {
            val shimmerBrush = remember(shimmerProgress) {
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0xFF14161D),
                        (shimmerProgress - 0.18f).coerceIn(0.01f, 0.98f) to Color(0xFF1C1F27),
                        shimmerProgress.coerceIn(0.02f, 0.99f) to Color(0xFFE7EAF2),
                        (shimmerProgress + 0.18f).coerceIn(0.03f, 0.995f) to Color(0xFF1C1F27),
                        1.0f to Color(0xFF14161D)
                    )
                )
            }
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.titleSmall.copy(
                    brush = if (enabled) shimmerBrush else null
                ),
                color = if (enabled) Color.Unspecified else PureWhite.copy(alpha = 0.55f),
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Draggable white circular thumb with blue '>' chevron
        Box(
            modifier = Modifier
                .padding(start = thumbPadding)
                .offset { IntOffset(dragOffsetPx.value.roundToInt(), 0) }
                .size(thumbSize)
                .clip(CircleShape)
                .background(if (enabled) thumbColor else thumbColor.copy(alpha = 0.65f))
                .pointerInput(enabled, maxDragPx) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            completed = false
                            lastTickStep = 0
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = (dragOffsetPx.value + dragAmount).coerceIn(0f, maxDragPx)
                            scope.launch { dragOffsetPx.snapTo(next) }
                            val ratio = next / maxDragPx
                            val step = when {
                                ratio >= 0.75f -> 3
                                ratio >= 0.50f -> 2
                                ratio >= 0.25f -> 1
                                else -> 0
                            }
                            if (step > lastTickStep) {
                                lastTickStep = step
                                AppHapticsAndNotifier.triggerTickHaptic(context)
                            }
                        },
                        onDragEnd = {
                            val ratio = dragOffsetPx.value / maxDragPx
                            scope.launch {
                                if (ratio >= 0.80f && !completed) {
                                    completed = true
                                    dragOffsetPx.animateTo(maxDragPx, tween(90))
                                    AppHapticsAndNotifier.triggerCompletionVibration(context)
                                    onSlideComplete()
                                    dragOffsetPx.animateTo(0f, spring(dampingRatio = 0.75f))
                                    completed = false
                                } else {
                                    dragOffsetPx.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 420f))
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                dragOffsetPx.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 420f))
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = label.uppercase(),
                tint = chevronColor,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

@Composable
fun ProfilePhotoDialog(
    member: Member,
    onPickPhoto: (Uri) -> Unit,
    onRemovePhoto: () -> Unit,
    onDismiss: () -> Unit
) {
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onPickPhoto(uri)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = {
            Text(
                text = "PROFILE PHOTO",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = ObsidianCharcoal
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StudioAvatar(
                    name = member.fullName,
                    size = 104.dp,
                    borderColor = SubtleOutline,
                    photoBase64 = member.photoBase64
                )
                Text(
                    text = member.fullName.ifBlank { "MEMBER" }.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = ObsidianCharcoal,
                    textAlign = TextAlign.Center
                )
                if (member.jobRoleName.isNotBlank()) {
                    StatusPill(
                        label = member.jobRoleName.uppercase(),
                        containerColor = MutedSurface,
                        contentColor = ObsidianCharcoal
                    )
                }
                Text(
                    text = "Update your avatar across chats, project cards, and team directories.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextSecondary,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ObsidianCharcoal,
                        contentColor = PureWhite
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("pick_profile_photo_button")
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("CHOOSE PROFILE PHOTO", fontWeight = FontWeight.Bold)
                }
                if (member.photoBase64.isNotBlank()) {
                    OutlinedButton(
                        onClick = onRemovePhoto,
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, DeepBlushInk.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DeepBlushInk),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("remove_profile_photo_button")
                    ) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("REMOVE PHOTO", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
            }
        }
    )
}

@Composable
fun LavenderGridCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        color = MutedSurface,
        border = BorderStroke(1.dp, SubtleOutline),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            content = content
        )
    }
}

@Composable
fun MonochromeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onFilterClick: (() -> Unit)? = null,
    filterActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(999.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, SubtleOutline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Search",
                tint = StudioTextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ObsidianCharcoal),
                    cursorBrush = SolidColor(ObsidianCharcoal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (onFilterClick != null) {
                Box {
                    Surface(
                        onClick = onFilterClick,
                        shape = CircleShape,
                        color = ObsidianCharcoal,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("search_filter_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = "Filter",
                                tint = PureWhite,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (filterActive) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(InfoPillInk)
                        )
                    }
                }
            }
        }
    }
}

data class TimelineStep(
    val label: String,
    val subLabel: String = "",
    val completed: Boolean = false
)

@Composable
fun StepProgressTimeline(
    steps: List<TimelineStep>,
    modifier: Modifier = Modifier
) {
    if (steps.isEmpty()) return
    val lastCompletedIdx = steps.indexOfLast { it.completed }
    val inactiveColor = Color(0xFFD5D8DF)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(24.dp)
            ) {
                val count = steps.size
                if (count > 1) {
                    val cy = size.height / 2f
                    val stepW = size.width / (count - 1).toFloat()
                    for (i in 0 until count - 1) {
                        val x1 = i * stepW
                        val x2 = (i + 1) * stepW
                        val activeSeg = i < lastCompletedIdx
                        drawLine(
                            color = if (activeSeg) ObsidianCharcoal else inactiveColor,
                            start = Offset(x1, cy),
                            end = Offset(x2, cy),
                            strokeWidth = 2.6.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                steps.forEachIndexed { idx, step ->
                    val isDone = step.completed
                    val alignStart = idx == 0
                    val alignEnd = idx == steps.lastIndex
                    Box(
                        modifier = Modifier.padding(
                            start = if (alignStart) 12.dp else 0.dp,
                            end = if (alignEnd) 12.dp else 0.dp
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDone) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(PureWhite)
                                    .border(2.2.dp, ObsidianCharcoal, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(ObsidianCharcoal),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = PureWhite,
                                        modifier = Modifier.size(9.dp)
                                    )
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(inactiveColor)
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            steps.forEachIndexed { idx, step ->
                val alignment = when (idx) {
                    0 -> Alignment.Start
                    steps.lastIndex -> Alignment.End
                    else -> Alignment.CenterHorizontally
                }
                val textAlign = when (idx) {
                    0 -> TextAlign.Start
                    steps.lastIndex -> TextAlign.End
                    else -> TextAlign.Center
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = alignment
                ) {
                    Text(
                        text = step.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (step.completed) FontWeight.Bold else FontWeight.Medium,
                        color = if (step.completed) ObsidianCharcoal else StudioTextSecondary,
                        textAlign = textAlign,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (step.subLabel.isNotBlank()) {
                        Text(
                            text = step.subLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextSecondary,
                            textAlign = textAlign,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

data class DetailKeyValueRow(
    val label: String,
    val value: String,
    val isWarning: Boolean = false
)

@Composable
fun DetailKeyValueCard(
    title: String,
    icon: ImageVector,
    rows: List<DetailKeyValueRow>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MutedSurface
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = StudioTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp,
                    color = ObsidianCharcoal
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = PureWhite
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = row.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            if (row.isWarning) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.WarningAmber,
                                        contentDescription = null,
                                        tint = CrimsonBlocked,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = row.value,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = CrimsonBlocked,
                                        textAlign = TextAlign.End
                                    )
                                }
                            } else {
                                Text(
                                    text = row.value,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ObsidianCharcoal,
                                    textAlign = TextAlign.End,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

data class VerticalTimelineEntry(
    val id: String,
    val timeLabel: String,
    val dateLabel: String,
    val description: String,
    val highlightRed: Boolean = false
)

@Composable
fun VerticalTimelineLog(
    entries: List<VerticalTimelineEntry>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MutedSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFFC5C7CE))
            )

            entries.forEachIndexed { idx, item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.width(72.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = item.timeLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = ObsidianCharcoal
                        )
                        if (item.dateLabel.isNotBlank()) {
                            Text(
                                text = item.dateLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextSecondary
                            )
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(20.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(if (item.highlightRed) CrimsonBlocked else ObsidianCharcoal),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = PureWhite,
                                modifier = Modifier.size(11.dp)
                            )
                        }
                        if (idx < entries.lastIndex) {
                            Canvas(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(34.dp)
                                    .padding(vertical = 3.dp)
                            ) {
                                drawLine(
                                    color = Color(0xFFB4B7C0),
                                    start = Offset(size.width / 2f, 0f),
                                    end = Offset(size.width / 2f, size.height),
                                    strokeWidth = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                                )
                            }
                        }
                    }

                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.highlightRed) DeepBlushInk else ObsidianCharcoal,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun AmberClockEmblem(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(56.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * 0.38f

        drawCircle(
            color = Color(0xFF26272E),
            radius = radius,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = PureWhite,
            radius = radius * 0.74f,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = ObsidianCharcoal,
            radius = radius * 0.74f,
            center = Offset(cx, cy),
            style = Stroke(width = 1.8.dp.toPx())
        )
        drawLine(
            color = ObsidianCharcoal,
            start = Offset(cx, cy),
            end = Offset(cx, cy - radius * 0.44f),
            strokeWidth = 2.4.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = ObsidianCharcoal,
            start = Offset(cx, cy),
            end = Offset(cx + radius * 0.38f, cy + radius * 0.14f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(color = ObsidianCharcoal, radius = 2.5.dp.toPx(), center = Offset(cx, cy))
    }
}

@Composable
fun SegmentedPillTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(999.dp),
        color = MutedSurface
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEachIndexed { idx, label ->
                val selected = idx == selectedIndex
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onSelect(idx) },
                    shape = RoundedCornerShape(999.dp),
                    color = if (selected) ObsidianCharcoal else Color.Transparent
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) PureWhite else StudioTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun SyncFeedbackChip(
    syncFeedback: SyncFeedback,
    hasPendingWrites: Boolean
) {
    val effective = when {
        syncFeedback == SyncFeedback.ERROR -> SyncFeedback.ERROR
        syncFeedback == SyncFeedback.SAVING -> SyncFeedback.SAVING
        hasPendingWrites || syncFeedback == SyncFeedback.PENDING_SYNC -> SyncFeedback.PENDING_SYNC
        syncFeedback == SyncFeedback.SAVED -> SyncFeedback.SAVED
        else -> return
    }

    val (label, icon, bg, fg) = when (effective) {
        SyncFeedback.SAVING -> Quad("Saving", Icons.Filled.HourglassTop, InfoPillBg, InfoPillInk)
        SyncFeedback.SAVED -> Quad("Synced", Icons.Filled.CheckCircle, SoftMint, DeepMintInk)
        SyncFeedback.PENDING_SYNC -> Quad("Pending Sync", Icons.Filled.CloudQueue, ButterYellow, DeepAmberInk)
        SyncFeedback.ERROR -> Quad("Sync Error", Icons.Filled.ErrorOutline, SoftBlush, DeepBlushInk)
        SyncFeedback.IDLE -> return
    }

    Surface(
        shape = CircleShape,
        color = bg,
        contentColor = fg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(13.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
fun MetricStatCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    containerColor: Color = MutedSurface,
    accentColor: Color = ObsidianCharcoal,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier,
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor, disabledContainerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (containerColor == PureWhite) BorderStroke(1.dp, SubtleOutline) else null
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = StudioTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(PureWhite),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(15.dp))
                }
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = ObsidianCharcoal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp,
            color = ObsidianCharcoal
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = actionLabel.uppercase(),
                    color = VioletPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, SubtleOutline),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(KpiLavenderBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = VioletPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(title.uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = StudioTextSecondary,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

fun Modifier.pastelAmbientBackdrop(): Modifier = this
    .background(CrispCanvas)
    .drawBehind {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFE9E6FF).copy(alpha = 0.52f), Color.Transparent),
                center = Offset(size.width * 0.22f, size.height * 0.08f),
                radius = size.width * 0.72f
            ),
            radius = size.width * 0.72f,
            center = Offset(size.width * 0.22f, size.height * 0.08f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFFFEDE4).copy(alpha = 0.56f), Color.Transparent),
                center = Offset(size.width * 0.88f, size.height * 0.14f),
                radius = size.width * 0.65f
            ),
            radius = size.width * 0.65f,
            center = Offset(size.width * 0.88f, size.height * 0.14f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFE5F7EE).copy(alpha = 0.40f), Color.Transparent),
                center = Offset(size.width * 0.55f, size.height * 0.30f),
                radius = size.width * 0.68f
            ),
            radius = size.width * 0.68f,
            center = Offset(size.width * 0.55f, size.height * 0.30f)
        )
    }

@Composable
fun PastelKpiSparklineCard(
    valueText: String,
    label: String,
    containerColor: Color,
    strokeColor: Color,
    sparklinePoints: List<Float>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = ObsidianCharcoal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                WavySparklineCanvas(
                    points = sparklinePoints,
                    strokeColor = strokeColor,
                    modifier = Modifier
                        .width(68.dp)
                        .height(30.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = StudioTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = ObsidianCharcoal,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun WavySparklineCanvas(
    points: List<Float>,
    strokeColor: Color,
    modifier: Modifier = Modifier
) {
    val normalized = if (points.size >= 2) points else listOf(0.4f, 0.7f, 0.3f, 0.85f, 0.55f, 0.9f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padY = 3.dp.toPx()
        val usableH = (h - padY * 2f).coerceAtLeast(1f)
        val stepX = w / (normalized.size - 1).coerceAtLeast(1).toFloat()

        val coords = normalized.mapIndexed { idx, v ->
            val clamped = v.coerceIn(0f, 1f)
            Offset(
                x = idx * stepX,
                y = padY + (1f - clamped) * usableH
            )
        }
        val path = Path().apply {
            moveTo(coords.first().x, coords.first().y)
            for (i in 0 until coords.lastIndex) {
                val p0 = coords[i]
                val p1 = coords[i + 1]
                val midX = (p0.x + p1.x) / 2f
                cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
            }
        }
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                colors = listOf(strokeColor.copy(alpha = 0.25f), strokeColor, strokeColor.copy(alpha = 0.75f))
            ),
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

enum class ToastTone {
    SUCCESS, WARNING, INFO, ERROR
}

@Composable
fun XMaxToastBanner(
    title: String,
    message: String,
    tone: ToastTone,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (bg, border, ink, icon) = when (tone) {
        ToastTone.SUCCESS -> Quad(SoftMint, Color(0xFFA7EBC2), DeepMintInk, Icons.Filled.CheckCircle)
        ToastTone.WARNING -> Quad(ButterYellow, Color(0xFFFDE08D), DeepAmberInk, Icons.Filled.WarningAmber)
        ToastTone.INFO -> Quad(InfoPillBg, Color(0xFFB3D2FD), InfoPillInk, Icons.Filled.Info)
        ToastTone.ERROR -> Quad(SoftBlush, Color(0xFFFBBABA), DeepBlushInk, Icons.Filled.ErrorOutline)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("xmax_toast_banner"),
        shape = RoundedCornerShape(22.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = 0.88f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss message",
                    tint = ink,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
