package com.example.ui.components

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.example.R
import com.example.chat.ChatFileManager
import com.example.chat.ProfilePhotoPreview
import com.example.domain.MoneyFormatter
import com.example.model.CatalogueItem
import com.example.model.CatalogueKind
import com.example.model.Member
import com.example.model.PricingMode
import com.example.system.AppHapticsAndNotifier
import com.example.ui.SyncFeedback
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

fun formatTitleCase(text: String): String {
    val clean = text.trim()
    if (clean.isBlank()) return ""
    return clean.split(Regex("\\s+")).joinToString(" ") { word ->
        val lower = word.lowercase(Locale.getDefault())
        val firstLetterIdx = lower.indexOfFirst { it.isLetter() }
        if (firstLetterIdx < 0) lower
        else {
            lower.substring(0, firstLetterIdx) +
                lower[firstLetterIdx].titlecase(Locale.getDefault()) +
                lower.substring(firstLetterIdx + 1)
        }
    }
}

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
        if (showEditBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(PureWhite)
                    .border(1.dp, SubtleOutline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                    tint = ObsidianCharcoal,
                    modifier = Modifier.size(9.dp)
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
                text = "Profile Photo",
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
                    text = formatTitleCase(member.fullName.ifBlank { "Member" }),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = ObsidianCharcoal,
                    textAlign = TextAlign.Center
                )
                if (member.jobRoleName.isNotBlank()) {
                    StatusPill(
                        label = formatTitleCase(member.jobRoleName),
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
                    Text("Choose Profile Photo", fontWeight = FontWeight.Bold)
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
                        Text("Remove Photo", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
            }
        }
    )
}

private enum class ProfileDialogStep {
    OVERVIEW,
    LOADING_PHOTO,
    CROP_PREVIEW,
    UPLOADING,
    SUCCESS
}

@Composable
fun ProfileOverviewDialog(
    member: Member,
    emailOrPhone: String,
    onPickPhoto: (Uri) -> Unit,
    onRemovePhoto: () -> Unit,
    onDismiss: () -> Unit,
    onSaveCroppedPhoto: ((Bitmap, Float, Float, Float, () -> Unit, (String) -> Unit) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(ProfileDialogStep.OVERVIEW) }
    var loadingSourceLabel by remember { mutableStateOf("image") }
    var showPhotoControls by rememberSaveable { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var cropPreview by remember { mutableStateOf<ProfilePhotoPreview?>(null) }
    var cropZoom by remember { mutableFloatStateOf(1f) }
    var cropOffsetXFrac by remember { mutableFloatStateOf(0f) }
    var cropOffsetYFrac by remember { mutableFloatStateOf(0f) }
    var pendingCameraUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }

    fun handleSelectedUri(uri: Uri, sourceLabel: String) {
        validationError = null
        loadingSourceLabel = sourceLabel
        step = ProfileDialogStep.LOADING_PHOTO
        scope.launch {
            val res = ChatFileManager.loadProfilePhotoPreview(context, uri)
            res.onSuccess { loaded ->
                cropPreview = loaded
                cropZoom = 1f
                cropOffsetXFrac = 0f
                cropOffsetYFrac = 0f
                AppHapticsAndNotifier.triggerCompletionVibration(context)
                step = ProfileDialogStep.CROP_PREVIEW
            }.onFailure { err ->
                validationError = err.message ?: "Selected image could not be loaded."
                step = ProfileDialogStep.OVERVIEW
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            handleSelectedUri(uri, "gallery photo")
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = pendingCameraUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (ok && uri != null) {
            handleSelectedUri(uri, "camera capture")
        } else {
            pendingCameraFilePath?.let { path -> runCatching { File(path).delete() } }
            pendingCameraUriString = null
            pendingCameraFilePath = null
        }
    }

    fun launchCamera() {
        AppHapticsAndNotifier.triggerTickHaptic(context)
        validationError = null
        runCatching {
            val dir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
            val file = File(dir, "profile_${UUID.randomUUID().toString().take(8)}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingCameraFilePath = file.absolutePath
            pendingCameraUriString = uri.toString()
            cameraLauncher.launch(uri)
        }.onFailure {
            validationError = "Camera is unavailable on this device. Please choose from Gallery."
        }
    }

    LaunchedEffect(step) {
        if (step == ProfileDialogStep.SUCCESS) {
            delay(1000L)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        containerColor = PureWhite,
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (step) {
                        ProfileDialogStep.OVERVIEW -> "My Profile"
                        ProfileDialogStep.LOADING_PHOTO -> "Loading Photo"
                        ProfileDialogStep.CROP_PREVIEW -> "Preview & Crop"
                        ProfileDialogStep.UPLOADING -> "Updating Profile"
                        ProfileDialogStep.SUCCESS -> "Photo Updated"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = ObsidianCharcoal
                )
                if (step == ProfileDialogStep.OVERVIEW) {
                    IconButton(
                        onClick = { showPhotoControls = !showPhotoControls },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("profile_edit_pen_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Edit Profile Photo",
                            tint = if (showPhotoControls) VioletPrimary else ObsidianCharcoal,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!validationError.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SoftBlush,
                        border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = CrimsonBlocked, modifier = Modifier.size(16.dp))
                            Text(validationError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = DeepBlushInk)
                        }
                    }
                }

                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        (fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220)))
                            .togetherWith(fadeOut(tween(150)) + scaleOut(targetScale = 0.96f, animationSpec = tween(150)))
                    },
                    label = "profile_photo_step_transition"
                ) { activeStep ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (activeStep) {
                            ProfileDialogStep.OVERVIEW -> {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(104.dp)
                        ) {
                            StudioAvatar(
                                name = member.fullName,
                                size = 96.dp,
                                borderColor = SubtleOutline,
                                photoBase64 = member.photoBase64,
                                onClick = { showPhotoControls = !showPhotoControls }
                            )
                            Surface(
                                onClick = { showPhotoControls = !showPhotoControls },
                                shape = CircleShape,
                                color = ObsidianCharcoal,
                                shadowElevation = 4.dp,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(34.dp)
                                    .testTag("avatar_edit_pen_badge")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.CameraAlt,
                                        contentDescription = "Edit Photo",
                                        tint = PureWhite,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = formatTitleCase(member.fullName.ifBlank { "Member" }),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = ObsidianCharcoal,
                                textAlign = TextAlign.Center
                            )
                            if (emailOrPhone.isNotBlank()) {
                                Text(
                                    text = emailOrPhone,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioTextSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusPill(
                                label = member.accessLevelEnum().label,
                                containerColor = ObsidianCharcoal,
                                contentColor = PureWhite
                            )
                            if (member.jobRoleName.isNotBlank()) {
                                StatusPill(
                                    label = formatTitleCase(member.jobRoleName),
                                    containerColor = KpiLavenderBg,
                                    contentColor = VioletPrimary
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MutedSurface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Department", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                    Text(
                                        member.departmentName.ifBlank { "Studio General" },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ObsidianCharcoal
                                    )
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Workspace Scope", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                    Text(
                                        member.scopeTypeEnum().label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ObsidianCharcoal
                                    )
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Status", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                    Text(
                                        member.statusEnum().label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = DeepMintInk
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = showPhotoControls,
                            enter = fadeIn(tween(180)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                            exit = fadeOut(tween(140)) + shrinkVertically()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { launchCamera() },
                                        shape = RoundedCornerShape(999.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ObsidianCharcoal,
                                            contentColor = PureWhite
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp)
                                            .testTag("camera_profile_photo_button")
                                    ) {
                                        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Camera", fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = {
                                            AppHapticsAndNotifier.triggerTickHaptic(context)
                                            photoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        shape = RoundedCornerShape(999.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = VioletPrimary,
                                            contentColor = PureWhite
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp)
                                            .testTag("pick_profile_photo_button")
                                    ) {
                                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Gallery", fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (member.photoBase64.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = onRemovePhoto,
                                        shape = RoundedCornerShape(999.dp),
                                        border = BorderStroke(1.dp, DeepBlushInk.copy(alpha = 0.4f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DeepBlushInk),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp)
                                            .testTag("remove_profile_photo_button")
                                    ) {
                                        Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(17.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Remove Photo", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                            }

                            ProfileDialogStep.LOADING_PHOTO -> {
                                Spacer(Modifier.height(8.dp))
                                val pulseTransition = rememberInfiniteTransition(label = "photo_prepare_pulse")
                                val pulseAlpha by pulseTransition.animateFloat(
                                    initialValue = 0.45f,
                                    targetValue = 1f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(700),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "pulse_alpha"
                                )
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .testTag("profile_photo_loading_indicator")
                                ) {
                                    CircularProgressIndicator(
                                        color = VioletPrimary,
                                        trackColor = VioletSoftBg,
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.CameraAlt,
                                        contentDescription = null,
                                        tint = ObsidianCharcoal.copy(alpha = pulseAlpha),
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                Text(
                                    text = "Optimizing $loadingSourceLabel…",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = ObsidianCharcoal
                                )
                                Text(
                                    text = "Correcting orientation and preparing square crop",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioTextSecondary,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(6.dp))
                            }

                            ProfileDialogStep.CROP_PREVIEW -> {
                                val preview = cropPreview
                                if (preview != null) {
                                    val imgBitmap = remember(preview.bitmap) { preview.bitmap.asImageBitmap() }
                                    Box(
                                        modifier = Modifier
                                            .size(196.dp)
                                            .clip(RoundedCornerShape(22.dp))
                                            .background(ObsidianCharcoal)
                                            .pointerInput(preview.bitmap) {
                                                detectDragGestures { change, dragAmount ->
                                                    change.consume()
                                                    cropOffsetXFrac = (cropOffsetXFrac + dragAmount.x / 180f).coerceIn(-1f, 1f)
                                                    cropOffsetYFrac = (cropOffsetYFrac + dragAmount.y / 180f).coerceIn(-1f, 1f)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Image(
                                            bitmap = imgBitmap,
                                            contentDescription = "Square crop preview",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .graphicsLayer {
                                                    scaleX = cropZoom
                                                    scaleY = cropZoom
                                                    translationX = cropOffsetXFrac * 42f
                                                    translationY = cropOffsetYFrac * 42f
                                                }
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .border(2.dp, PureWhite.copy(alpha = 0.85f), CircleShape)
                                        )
                                    }

                                    StatusPill(
                                        label = "Square Crop • ${preview.width}×${preview.height} px • Ready",
                                        containerColor = SoftMint,
                                        contentColor = DeepMintInk
                                    )

                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Zoom", style = MaterialTheme.typography.labelMedium, color = StudioTextSecondary)
                                            Text("${"%.1f".format(cropZoom)}×", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                                        }
                                        Slider(
                                            value = cropZoom,
                                            onValueChange = { cropZoom = it },
                                            valueRange = 1f..2.5f,
                                            colors = SliderDefaults.colors(thumbColor = ObsidianCharcoal, activeTrackColor = VioletPrimary)
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                cropPreview = null
                                                step = ProfileDialogStep.OVERVIEW
                                            },
                                            shape = RoundedCornerShape(999.dp),
                                            border = BorderStroke(1.dp, SubtleOutline),
                                            modifier = Modifier.weight(1f).height(46.dp)
                                        ) { Text("Back", fontWeight = FontWeight.SemiBold, color = ObsidianCharcoal) }

                                        Button(
                                            onClick = {
                                                step = ProfileDialogStep.UPLOADING
                                                if (onSaveCroppedPhoto != null) {
                                                    onSaveCroppedPhoto(
                                                        preview.bitmap,
                                                        cropZoom,
                                                        cropOffsetXFrac,
                                                        cropOffsetYFrac,
                                                        {
                                                            AppHapticsAndNotifier.triggerCompletionVibration(context)
                                                            step = ProfileDialogStep.SUCCESS
                                                        },
                                                        { err ->
                                                            validationError = err
                                                            step = ProfileDialogStep.CROP_PREVIEW
                                                        }
                                                    )
                                                } else {
                                                    AppHapticsAndNotifier.triggerCompletionVibration(context)
                                                    step = ProfileDialogStep.SUCCESS
                                                }
                                            },
                                            shape = RoundedCornerShape(999.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(46.dp)
                                                .testTag("save_cropped_photo_button")
                                        ) {
                                            Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Save Photo", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            ProfileDialogStep.UPLOADING -> {
                                Spacer(Modifier.height(8.dp))
                                CircularProgressIndicator(
                                    color = VioletPrimary,
                                    trackColor = VioletSoftBg,
                                    modifier = Modifier.testTag("profile_photo_uploading_indicator")
                                )
                                Text(
                                    text = "Saving your profile photo…",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = ObsidianCharcoal
                                )
                                Text(
                                    text = "Compressing & updating workspace profile record…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioTextSecondary,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(6.dp))
                            }

                            ProfileDialogStep.SUCCESS -> {
                                Surface(
                                    shape = CircleShape,
                                    color = SoftMint,
                                    modifier = Modifier.size(58.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = EmeraldApproved, modifier = Modifier.size(34.dp))
                                    }
                                }
                                Text(
                                    text = "Photo updated successfully",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = DeepMintInk
                                )
                                Text(
                                    text = "Refreshed across your workspace.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioTextSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                ProfileDialogStep.OVERVIEW -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!showPhotoControls) {
                            TextButton(onClick = { showPhotoControls = true }) {
                                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = VioletPrimary)
                                Spacer(Modifier.width(4.dp))
                                Text("Edit Photo", fontWeight = FontWeight.Bold, color = VioletPrimary)
                            }
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Close", fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                        }
                    }
                }
                ProfileDialogStep.LOADING_PHOTO -> {
                    TextButton(onClick = { step = ProfileDialogStep.OVERVIEW }) {
                        Text("Cancel", fontWeight = FontWeight.SemiBold, color = StudioTextSecondary)
                    }
                }
                ProfileDialogStep.UPLOADING -> {
                    TextButton(onClick = { step = ProfileDialogStep.CROP_PREVIEW }) {
                        Text("Cancel", fontWeight = FontWeight.SemiBold, color = StudioTextSecondary)
                    }
                }
                else -> Unit
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
                    text = formatTitleCase(title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
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
            text = formatTitleCase(title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.sp,
            color = ObsidianCharcoal
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = formatTitleCase(actionLabel),
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
            Text(
                formatTitleCase(title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ObsidianCharcoal,
                textAlign = TextAlign.Center
            )
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
        label = "kpi_card_scale"
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        border = BorderStroke(1.dp, strokeColor.copy(alpha = 0.18f)),
        shadowElevation = 4.dp,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
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
                        .width(74.dp)
                        .height(34.dp)
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
    val targetPoints = remember(points) {
        when {
            points.size >= 2 -> points.take(7)
            points.size == 1 -> listOf(points[0], points[0])
            else -> listOf(0.08f, 0.08f, 0.08f, 0.08f, 0.08f, 0.08f)
        }
    }
    val animatedValues = targetPoints.mapIndexed { idx, value ->
        val anim by animateFloatAsState(
            targetValue = value.coerceIn(0.04f, 1f),
            animationSpec = tween(durationMillis = 360 + idx * 35),
            label = "spark_pt_$idx"
        )
        anim
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padX = 3.dp.toPx()
        val padY = 4.dp.toPx()
        val usableW = (w - padX * 2f).coerceAtLeast(1f)
        val usableH = (h - padY * 2f).coerceAtLeast(1f)
        val baselineY = h - padY
        val stepX = usableW / (animatedValues.size - 1).coerceAtLeast(1).toFloat()

        // Subtle chart baseline and mid-grid guide
        drawLine(
            color = strokeColor.copy(alpha = 0.18f),
            start = Offset(padX, baselineY),
            end = Offset(w - padX, baselineY),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = strokeColor.copy(alpha = 0.12f),
            start = Offset(padX, padY + usableH * 0.5f),
            end = Offset(w - padX, padY + usableH * 0.5f),
            strokeWidth = 0.8.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
        )

        val coords = animatedValues.mapIndexed { idx, v ->
            Offset(
                x = padX + idx * stepX,
                y = padY + (1f - v) * usableH
            )
        }

        val linePath = Path().apply {
            moveTo(coords.first().x, coords.first().y)
            for (i in 1 until coords.size) {
                lineTo(coords[i].x, coords[i].y)
            }
        }
        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(coords.last().x, baselineY)
            lineTo(coords.first().x, baselineY)
            close()
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(strokeColor.copy(alpha = 0.22f), strokeColor.copy(alpha = 0.02f)),
                startY = padY,
                endY = baselineY
            )
        )
        drawPath(
            path = linePath,
            color = strokeColor,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
        coords.forEachIndexed { idx, pt ->
            val isLast = idx == coords.lastIndex
            drawCircle(
                color = if (isLast) strokeColor else strokeColor.copy(alpha = 0.55f),
                radius = if (isLast) 3.dp.toPx() else 1.7.dp.toPx(),
                center = pt
            )
        }
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
                    text = formatTitleCase(title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
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

/**
 * Custom Ticket / Voucher shape with smooth rounded corners and a half-circle cutout notch
 * on the left edge right at vertical center, exactly matching catalogue_services_design_5a91a7c5.jpg.
 */
class TicketCardShape(
    val cornerRadius: Dp = 24.dp,
    val notchRadius: Dp = 9.dp
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cr = with(density) { cornerRadius.toPx() }
        val nr = with(density) { notchRadius.toPx() }
        val w = size.width
        val h = size.height
        val centerY = h / 2f

        val path = Path().apply {
            moveTo(cr, 0f)
            lineTo(w - cr, 0f)
            quadraticBezierTo(w, 0f, w, cr)
            lineTo(w, h - cr)
            quadraticBezierTo(w, h, w - cr, h)
            lineTo(cr, h)
            quadraticBezierTo(0f, h, 0f, h - cr)
            lineTo(0f, centerY + nr)
            arcTo(
                rect = Rect(left = -nr, top = centerY - nr, right = nr, bottom = centerY + nr),
                startAngleDegrees = 90f,
                sweepAngleDegrees = -180f,
                forceMoveTo = false
            )
            lineTo(0f, cr)
            quadraticBezierTo(0f, 0f, cr, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

data class TicketPalette(
    val startGradient: Color,
    val endGradient: Color,
    val borderColor: Color,
    val emblemBg: Color,
    val emblemTint: Color
)

fun getCatalogueTicketPalette(index: Int): TicketPalette {
    return when (index % 4) {
        0 -> TicketPalette(
            startGradient = Color(0xFFBFE0FE),
            endGradient = Color(0xFFE5F3FF),
            borderColor = Color(0xFFABD2FC),
            emblemBg = Color(0xFF0067C5),
            emblemTint = PureWhite
        )
        1 -> TicketPalette(
            startGradient = Color(0xFFCEE6F4),
            endGradient = Color(0xFFFDF3C4),
            borderColor = Color(0xFFE6E6C6),
            emblemBg = PureWhite,
            emblemTint = ObsidianCharcoal
        )
        2 -> TicketPalette(
            startGradient = Color(0xFFFBD5C5),
            endGradient = Color(0xFFFDF2DA),
            borderColor = Color(0xFFF6C8B5),
            emblemBg = Color(0xFFF15A1B),
            emblemTint = PureWhite
        )
        else -> TicketPalette(
            startGradient = Color(0xFFC8F3DC),
            endGradient = Color(0xFFEDFCF3),
            borderColor = Color(0xFFB3E8CB),
            emblemBg = Color(0xFF16A34A),
            emblemTint = PureWhite
        )
    }
}

fun getCatalogueCategoryIcon(category: String, kind: CatalogueKind): ImageVector {
    val cat = category.lowercase(Locale.ROOT)
    return when {
        cat.contains("brand") || cat.contains("logo") || cat.contains("design") || cat.contains("creative") -> Icons.Filled.Palette
        cat.contains("web") || cat.contains("code") || cat.contains("app") || cat.contains("tech") || cat.contains("software") -> Icons.Filled.Code
        cat.contains("video") || cat.contains("motion") || cat.contains("reel") || cat.contains("film") || cat.contains("shoot") -> Icons.Filled.Videocam
        cat.contains("market") || cat.contains("campaign") || cat.contains("ad") || cat.contains("growth") -> Icons.Filled.Campaign
        cat.contains("seo") || cat.contains("content") || cat.contains("copy") || cat.contains("article") -> Icons.Filled.AutoGraph
        cat.contains("social") || cat.contains("influencer") || cat.contains("insta") -> Icons.Filled.Share
        kind == CatalogueKind.PACKAGE -> Icons.Filled.Inventory2
        kind == CatalogueKind.ADDON -> Icons.Filled.AddCircleOutline
        else -> Icons.Filled.Sell
    }
}

@Composable
fun FullScreenAgencyDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                )
                @Suppress("DEPRECATION")
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                WindowInsetsControllerCompat(window, view).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
        }
        content()
    }
}

@Composable
fun CatalogueServiceTicketCard(
    item: CatalogueItem,
    index: Int,
    isSelected: Boolean = false,
    selectedCount: Int = 0,
    buttonLabel: String = "Select",
    actionButtonText: String? = null,
    expandable: Boolean = false,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier,
    onCardClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val palette = remember(index) { getCatalogueTicketPalette(index) }
    val categoryIcon = remember(item.category, item.kindEnum()) { getCatalogueCategoryIcon(item.category, item.kindEnum()) }
    val shape = remember { TicketCardShape(cornerRadius = 22.dp, notchRadius = 8.dp) }
    var isExpanded by remember(item.id) { mutableStateOf(false) }
    val hasDetails = item.description.isNotBlank() || item.inclusions.isNotEmpty()

    Surface(
        onClick = {
            if (expandable) {
                AppHapticsAndNotifier.triggerTickHaptic(context)
                isExpanded = !isExpanded
            } else {
                onCardClick?.invoke() ?: onButtonClick()
            }
        },
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(1.2.dp, if (isSelected) VioletPrimary else palette.borderColor),
        shadowElevation = if (isSelected) 4.dp else 1.5.dp,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow))
            .testTag("catalogue_ticket_${item.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(
                    Brush.horizontalGradient(
                        colors = if (isSelected) listOf(Color(0xFFDDD6FE), Color(0xFFF3E8FF))
                        else listOf(palette.startGradient, palette.endGradient)
                    )
                )
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left pastel stub housing the centered circular emblem next to the left-edge notch
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) VioletPrimary else palette.emblemBg,
                    shadowElevation = 2.dp,
                    border = BorderStroke(1.dp, PureWhite.copy(alpha = 0.65f)),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = categoryIcon,
                            contentDescription = item.category,
                            tint = if (isSelected) PureWhite else palette.emblemTint,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            // Right inset rounded white content panel matching catalogue_services_design_5a91a7c5.jpg
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(17.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                PureWhite.copy(alpha = 0.96f),
                                PureWhite.copy(alpha = 0.82f)
                            )
                        )
                    )
                    .padding(horizontal = 15.dp, vertical = 13.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.5.sp, lineHeight = 19.sp),
                            fontWeight = FontWeight.Bold,
                            color = ObsidianCharcoal,
                            maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (expandable && hasDetails) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (isExpanded) VioletSoftBg else MutedSurface
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = if (isExpanded) "Less" else "Details",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isExpanded) VioletPrimary else StudioTextSecondary
                                    )
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                        contentDescription = if (isExpanded) "Collapse details" else "Expand details",
                                        tint = if (isExpanded) VioletPrimary else StudioTextSecondary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }

                    val subline = remember(item) {
                        when (item.pricingModeEnum()) {
                            PricingMode.PERCENTAGE_SURCHARGE -> "+${item.surchargePercent}% Urgent Surcharge"
                            PricingMode.CUSTOM_QUOTE -> "Custom Quote • ${item.category}"
                            PricingMode.ACTUAL_COST -> "Actual Cost • ${item.category}"
                            PricingMode.STARTING_FROM -> "${MoneyFormatter.formatInr(item.pricePaise)} onwards • ${item.category}"
                            PricingMode.PER_UNIT -> "${MoneyFormatter.formatInr(item.pricePaise)} / ${item.unitLabel.ifBlank { "unit" }} • ${item.category}"
                            else -> "${MoneyFormatter.formatInr(item.pricePaise)} • ${item.category}"
                        }
                    }
                    Text(
                        text = subline,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = StudioTextSecondary,
                        maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (item.description.isNotBlank()) {
                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                            color = if (isExpanded) ObsidianCharcoal.copy(alpha = 0.88f) else StudioTextSecondary.copy(alpha = 0.88f),
                            maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                            overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("catalogue_desc_${item.id}")
                        )
                    }

                    if (isExpanded && item.inclusions.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MutedSurface.copy(alpha = 0.72f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "Included Deliverables (${item.inclusions.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = ObsidianCharcoal
                                )
                                item.inclusions.forEach { inc ->
                                    Text(
                                        text = "• ${inc.title} (×${inc.quantity})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioTextSecondary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    val displayLabel = actionButtonText ?: when {
                        selectedCount > 1 -> "Added ($selectedCount)"
                        selectedCount == 1 -> "Added"
                        isSelected -> "Added"
                        else -> buttonLabel
                    }
                    val pillBg = if (isSelected || selectedCount > 0) EmeraldApproved else ObsidianCharcoal

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = onButtonClick,
                            shape = RoundedCornerShape(999.dp),
                            color = pillBg,
                            shadowElevation = 1.dp,
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .testTag("catalogue_check_button_${item.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                if (isSelected || selectedCount > 0) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = PureWhite,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Text(
                                    text = displayLabel,
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = PureWhite
                                )
                            }
                        }
                        if (expandable && !isExpanded && hasDetails) {
                            Text(
                                text = "Tap card for full info",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = StudioTextSecondary.copy(alpha = 0.72f)
                            )
                        }
                    }
                }
            }
        }
    }
}
