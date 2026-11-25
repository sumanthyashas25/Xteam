package com.example.ui.status

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chat.ChatSettingsStore
import com.example.chat.StatusOwnerSummary
import com.example.chat.StatusPrivacyEngine
import com.example.domain.CatalogueSeedData
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.ChatViewModel
import com.example.ui.StatusUploadJob
import com.example.ui.StatusUploadJobStatus
import com.example.ui.components.SegmentedStatusAvatar
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioAvatar
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun StatusTabScreen(
    state: AgencyUiState,
    chatViewModel: ChatViewModel,
    onOpenDirectChannel: (ChatChannel) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val member = state.myMember ?: return
    val companyId = state.company?.id?.ifBlank { CatalogueSeedData.DEFAULT_COMPANY_ID }
        ?: CatalogueSeedData.DEFAULT_COMPANY_ID

    LaunchedEffect(Unit) {
        ChatSettingsStore.ensureLoaded(context)
    }

    val prefs by ChatSettingsStore.prefsFlow.collectAsStateWithLifecycle()
    val uploadJobs by chatViewModel.statusUploadJobs.collectAsStateWithLifecycle()

    // Live ticker so expired statuses disappear automatically
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    val categorized = remember(
        state.chatStatuses,
        member,
        state.members,
        prefs.mutedStatusOwnerUids,
        prefs.blockedUserUids,
        nowMillis
    ) {
        StatusPrivacyEngine.categorizeStatuses(
            statuses = state.chatStatuses,
            myMember = member,
            allMembers = state.members,
            mutedOwnerUids = prefs.mutedStatusOwnerUids,
            blockedByViewerUids = prefs.blockedUserUids,
            nowMillis = nowMillis
        )
    }

    var creatorInitialMode by remember { mutableStateOf<StatusKind?>(null) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showBlockedDialog by remember { mutableStateOf(false) }
    var showManageMySheet by remember { mutableStateOf(false) }
    var showMutedSection by remember { mutableStateOf(false) }

    // Viewer state: list of (Member, List<ChatStatus>) + starting ownerUid
    var viewerGroups by remember { mutableStateOf<List<Pair<Member, List<ChatStatus>>>?>(null) }
    var viewerStartOwnerUid by remember { mutableStateOf("") }

    val approvedPeers = remember(state.members, member.uid) {
        state.members.filter {
            it.statusEnum() == MembershipStatus.APPROVED && it.active && it.uid != member.uid
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("status_tab_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section Header + Add & Privacy Controls
        item(key = "status_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Status Updates",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = ObsidianCharcoal
                    )
                    Text(
                        text = "24-hour team updates • ${categorized.totalUnviewedContacts} unread",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { showPrivacyDialog = true },
                        shape = CircleShape,
                        color = PureWhite,
                        border = BorderStroke(1.dp, SubtleOutline),
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("status_privacy_btn")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Lock, contentDescription = "Status Privacy", tint = ObsidianCharcoal, modifier = Modifier.size(18.dp))
                        }
                    }
                    Surface(
                        onClick = { showBlockedDialog = true },
                        shape = CircleShape,
                        color = PureWhite,
                        border = BorderStroke(1.dp, SubtleOutline),
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("status_blocked_users_btn")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Block, contentDescription = "Blocked Contacts", tint = ObsidianCharcoal, modifier = Modifier.size(18.dp))
                        }
                    }
                    FilledTonalButton(
                        onClick = { creatorInitialMode = StatusKind.TEXT },
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = ObsidianCharcoal,
                            contentColor = PureWhite
                        ),
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("status_tab_add_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Active Upload Queue (Progress, Cancel, Retry)
        if (uploadJobs.isNotEmpty()) {
            items(uploadJobs, key = { it.id }) { job ->
                StatusUploadJobCard(
                    job = job,
                    onCancel = { chatViewModel.cancelStatusUpload(job.id) },
                    onRetry = { chatViewModel.retryStatusUpload(job.id, member) },
                    onDismiss = { chatViewModel.dismissStatusUploadJob(job.id) }
                )
            }
        }

        // My Status Card
        item(key = "my_status_card") {
            val myActive = categorized.myStatuses
            val latest = myActive.maxByOrNull { it.createdAtMillis }
            val totalViews = myActive.sumOf { it.viewerUids.size }
            val totalReactions = myActive.sumOf { it.reactions.size }

            Surface(
                onClick = {
                    if (myActive.isNotEmpty()) {
                        viewerGroups = listOf(member to myActive)
                        viewerStartOwnerUid = member.uid
                    } else {
                        creatorInitialMode = StatusKind.TEXT
                    }
                },
                shape = RoundedCornerShape(22.dp),
                color = PureWhite,
                border = BorderStroke(1.dp, SubtleOutline),
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("my_status_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        SegmentedStatusAvatar(
                            name = member.fullName,
                            size = 54.dp,
                            borderColor = PureWhite,
                            photoBase64 = member.photoBase64,
                            activeStatuses = myActive,
                            viewerUid = member.uid,
                            isOwnAvatar = true,
                            onAvatarClick = if (myActive.isNotEmpty()) {
                                {
                                    viewerGroups = listOf(member to myActive)
                                    viewerStartOwnerUid = member.uid
                                }
                            } else null
                        )
                        Surface(
                            onClick = { creatorInitialMode = StatusKind.IMAGE },
                            shape = CircleShape,
                            color = VioletPrimary,
                            border = BorderStroke(2.dp, PureWhite),
                            modifier = Modifier.size(22.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, contentDescription = "Quick add photo status", tint = PureWhite, modifier = Modifier.size(13.dp))
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "My Status",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = ObsidianCharcoal
                        )
                        Text(
                            text = if (myActive.isEmpty()) {
                                "Tap to share a 24h text, photo, video or voice update"
                            } else {
                                "${myActive.size} active • $totalViews views • $totalReactions reactions • ${formatRelativeStatusTime(latest?.createdAtMillis ?: 0L, nowMillis)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (myActive.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showManageMySheet = true },
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier
                                .height(38.dp)
                                .testTag("my_status_manage_btn")
                        ) {
                            Text("Manage", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Quick Creation Format Row
        item(key = "quick_create_strip") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusKind.entries.forEach { kind ->
                    val icon = when (kind) {
                        StatusKind.TEXT -> Icons.Default.Title
                        StatusKind.IMAGE -> Icons.Default.PhotoCamera
                        StatusKind.VIDEO -> Icons.Default.Videocam
                        StatusKind.VOICE -> Icons.Default.Mic
                    }
                    Surface(
                        onClick = { creatorInitialMode = kind },
                        shape = RoundedCornerShape(14.dp),
                        color = PureWhite,
                        border = BorderStroke(1.dp, SubtleOutline),
                        modifier = Modifier.testTag("status_quick_${kind.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(icon, contentDescription = null, tint = VioletPrimary, modifier = Modifier.size(18.dp))
                            Text(kind.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                        }
                    }
                }
            }
        }

        // Recent Status Updates (Unviewed)
        item(key = "recent_header") {
            Text(
                text = "Recent Updates (${categorized.recentUpdates.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = ObsidianCharcoal,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (categorized.recentUpdates.isEmpty()) {
            item(key = "recent_empty") {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = PureWhite,
                    border = BorderStroke(1.dp, SubtleOutline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "All caught up! New team updates will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioTextSecondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(categorized.recentUpdates, key = { "recent_${it.owner.uid}" }) { summary ->
                StatusOwnerRowCard(
                    summary = summary,
                    viewerUid = member.uid,
                    nowMillis = nowMillis,
                    onClick = {
                        viewerGroups = categorized.recentUpdates.map { it.owner to it.statuses }
                        viewerStartOwnerUid = summary.owner.uid
                    }
                )
            }
        }

        // Viewed Status Updates
        if (categorized.viewedUpdates.isNotEmpty()) {
            item(key = "viewed_header") {
                Text(
                    text = "Viewed Updates (${categorized.viewedUpdates.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = StudioTextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            items(categorized.viewedUpdates, key = { "viewed_${it.owner.uid}" }) { summary ->
                StatusOwnerRowCard(
                    summary = summary,
                    viewerUid = member.uid,
                    nowMillis = nowMillis,
                    onClick = {
                        viewerGroups = categorized.viewedUpdates.map { it.owner to it.statuses }
                        viewerStartOwnerUid = summary.owner.uid
                    }
                )
            }
        }

        // Muted Status Updates (Collapsible)
        if (categorized.mutedUpdates.isNotEmpty()) {
            item(key = "muted_header") {
                Surface(
                    onClick = { showMutedSection = !showMutedSection },
                    shape = RoundedCornerShape(14.dp),
                    color = MutedSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("muted_updates_toggle")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Muted Updates (${categorized.mutedUpdates.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = StudioTextSecondary
                        )
                        Icon(
                            imageVector = if (showMutedSection) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Toggle muted updates",
                            tint = StudioTextSecondary
                        )
                    }
                }
            }
            if (showMutedSection) {
                items(categorized.mutedUpdates, key = { "muted_${it.owner.uid}" }) { summary ->
                    StatusOwnerRowCard(
                        summary = summary,
                        viewerUid = member.uid,
                        nowMillis = nowMillis,
                        trailingActionLabel = "Unmute",
                        onTrailingAction = {
                            ChatSettingsStore.setMutedStatusOwner(context, summary.owner.uid, false)
                        },
                        onClick = {
                            viewerGroups = listOf(summary.owner to summary.statuses)
                            viewerStartOwnerUid = summary.owner.uid
                        }
                    )
                }
            }
        }
    }

    // Create Status Studio Dialog
    creatorInitialMode?.let { mode ->
        CreateStatusStudioDialog(
            initialMode = mode,
            myMember = member,
            allMembers = state.members,
            onPublishQueue = { submissions ->
                chatViewModel.enqueueStatusUploads(submissions, member)
            },
            onDismiss = { creatorInitialMode = null }
        )
    }

    // Full-Screen Status Viewer
    viewerGroups?.let { groups ->
        EnhancedFullScreenStatusViewer(
            statusGroups = groups,
            initialOwnerUid = viewerStartOwnerUid,
            myMember = member,
            allMembers = state.members,
            companyId = companyId,
            mutedOwnerUids = prefs.mutedStatusOwnerUids,
            blockedUserUids = prefs.blockedUserUids,
            onMarkViewed = { sId -> chatViewModel.markStatusViewed(sId, member.uid) },
            onReact = { st, owner, emoji ->
                chatViewModel.reactToStatusAndSendDm(st, owner, member, emoji)
            },
            onReplyDm = { st, owner, reply ->
                chatViewModel.replyToStatusInDm(st, owner, member, reply)
            },
            onToggleMute = { ownerUid ->
                ChatSettingsStore.toggleMuteStatusOwner(context, ownerUid)
            },
            onToggleBlock = { ownerUid ->
                ChatSettingsStore.toggleBlockUser(context, ownerUid)
                val nextBlocked = ChatSettingsStore.ensureLoaded(context).blockedUserUids.toList()
                val pMode = runCatching { StatusPrivacyMode.valueOf(prefs.statusPrivacyMode) }
                    .getOrDefault(StatusPrivacyMode.ALL_APPROVED)
                chatViewModel.syncBlockedAndPrivacyToMyStatuses(
                    activeMyStatuses = categorized.myStatuses,
                    privacyMode = pMode,
                    allowedUids = prefs.statusAllowedUids.toList(),
                    excludedUids = prefs.statusExcludedUids.toList(),
                    blockedUids = nextBlocked
                )
            },
            onReportStatus = { st, reason ->
                chatViewModel.reportStatus(st, reason, member)
            },
            onDeleteStatus = { st ->
                chatViewModel.deleteStatus(st.id, st.effectiveStoragePath())
            },
            onDeleteAllMyStatuses = { list ->
                chatViewModel.deleteAllMyStatuses(list)
            },
            onOpenResponderChat = { target ->
                chatViewModel.openOrStartDm(target, member) { ch ->
                    onOpenDirectChannel(ch)
                }
            },
            onDismiss = { viewerGroups = null }
        )
    }

    // My Status Management Sheet
    if (showManageMySheet) {
        MyStatusManagementSheet(
            myStatuses = categorized.myStatuses,
            allMembers = state.members,
            companyId = companyId,
            onDeleteStatus = { st -> chatViewModel.deleteStatus(st.id, st.effectiveStoragePath()) },
            onDeleteAll = {
                chatViewModel.deleteAllMyStatuses(categorized.myStatuses)
                showManageMySheet = false
            },
            onOpenResponderChat = { target ->
                showManageMySheet = false
                chatViewModel.openOrStartDm(target, member) { ch ->
                    onOpenDirectChannel(ch)
                }
            },
            onDismiss = { showManageMySheet = false }
        )
    }

    // Status Privacy Selector Dialog (syncs live to active statuses + saved default)
    if (showPrivacyDialog) {
        val currentMode = runCatching { StatusPrivacyMode.valueOf(prefs.statusPrivacyMode) }
            .getOrDefault(StatusPrivacyMode.ALL_APPROVED)
        StatusPrivacySelectorDialog(
            allMembers = approvedPeers,
            initialMode = currentMode,
            initialAllowedUids = prefs.statusAllowedUids,
            initialExcludedUids = prefs.statusExcludedUids,
            onSave = { mode, allow, excl ->
                ChatSettingsStore.updateStatusPrivacy(context, mode.name, allow, excl)
                chatViewModel.syncBlockedAndPrivacyToMyStatuses(
                    activeMyStatuses = categorized.myStatuses,
                    privacyMode = mode,
                    allowedUids = allow.toList(),
                    excludedUids = excl.toList(),
                    blockedUids = prefs.blockedUserUids.toList()
                )
                showPrivacyDialog = false
            },
            onDismiss = { showPrivacyDialog = false }
        )
    }

    // Blocked Users Dialog (enforces mutual invisibility for statuses & rings)
    if (showBlockedDialog) {
        Dialog(onDismissRequest = { showBlockedDialog = false }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = PureWhite,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Blocked Users (${prefs.blockedUserUids.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text(
                        text = "Blocked users cannot view your statuses or status indicators, and their updates remain hidden from you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(approvedPeers, key = { it.uid }) { peer ->
                            val isBlocked = peer.uid in prefs.blockedUserUids
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        ChatSettingsStore.setBlockedUser(context, peer.uid, !isBlocked)
                                        val nextBlocked = ChatSettingsStore.ensureLoaded(context).blockedUserUids.toList()
                                        val pMode = runCatching { StatusPrivacyMode.valueOf(prefs.statusPrivacyMode) }
                                            .getOrDefault(StatusPrivacyMode.ALL_APPROVED)
                                        chatViewModel.syncBlockedAndPrivacyToMyStatuses(
                                            activeMyStatuses = categorized.myStatuses,
                                            privacyMode = pMode,
                                            allowedUids = prefs.statusAllowedUids.toList(),
                                            excludedUids = prefs.statusExcludedUids.toList(),
                                            blockedUids = nextBlocked
                                        )
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Checkbox(
                                    checked = isBlocked,
                                    onCheckedChange = { checked ->
                                        ChatSettingsStore.setBlockedUser(context, peer.uid, checked)
                                        val nextBlocked = ChatSettingsStore.ensureLoaded(context).blockedUserUids.toList()
                                        val pMode = runCatching { StatusPrivacyMode.valueOf(prefs.statusPrivacyMode) }
                                            .getOrDefault(StatusPrivacyMode.ALL_APPROVED)
                                        chatViewModel.syncBlockedAndPrivacyToMyStatuses(
                                            activeMyStatuses = categorized.myStatuses,
                                            privacyMode = pMode,
                                            allowedUids = prefs.statusAllowedUids.toList(),
                                            excludedUids = prefs.statusExcludedUids.toList(),
                                            blockedUids = nextBlocked
                                        )
                                    }
                                )
                                StudioAvatar(name = peer.fullName, size = 34.dp, photoBase64 = peer.photoBase64)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(formatTitleCase(peer.fullName), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(peer.jobRoleName.ifBlank { peer.departmentName }, style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                                }
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = { showBlockedDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                        ) {
                            Text("Done")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusOwnerRowCard(
    summary: StatusOwnerSummary,
    viewerUid: String,
    nowMillis: Long,
    trailingActionLabel: String? = null,
    onTrailingAction: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val latest = summary.statuses.maxByOrNull { it.createdAtMillis }
    val previewLabel = latest?.let { st ->
        val timeText = formatRelativeStatusTime(st.createdAtMillis, nowMillis)
        val kindTag = st.kindEnum().label
        if (summary.statuses.size > 1) "${summary.statuses.size} updates ($kindTag) • $timeText"
        else "$kindTag • $timeText"
    } ?: "Active update"

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, SubtleOutline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_owner_row_${summary.owner.uid}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SegmentedStatusAvatar(
                name = summary.owner.fullName,
                size = 48.dp,
                borderColor = PureWhite,
                photoBase64 = summary.owner.photoBase64,
                activeStatuses = summary.statuses,
                viewerUid = viewerUid,
                onAvatarClick = onClick
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = formatTitleCase(summary.owner.fullName),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (summary.hasUnviewed) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = ObsidianCharcoal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = previewLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (trailingActionLabel != null && onTrailingAction != null) {
                TextButton(onClick = onTrailingAction) {
                    Text(trailingActionLabel, fontWeight = FontWeight.Bold)
                }
            } else if (summary.unviewedCount > 0) {
                StatusPill(
                    label = "${summary.unviewedCount} new",
                    containerColor = Color(0xFFECFDF5),
                    contentColor = EmeraldApproved
                )
            }
        }
    }
}

@Composable
private fun StatusUploadJobCard(
    job: StatusUploadJob,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (job.status == StatusUploadJobStatus.FAILED) SoftBlush else VioletSoftBg,
        border = BorderStroke(
            1.dp,
            if (job.status == StatusUploadJobStatus.FAILED) CrimsonBlocked.copy(alpha = 0.4f) else VioletPrimary.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_upload_job_${job.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = when (job.status) {
                        StatusUploadJobStatus.FAILED -> Icons.Default.ErrorOutline
                        StatusUploadJobStatus.DONE -> Icons.Default.CheckCircle
                        else -> Icons.Default.CloudUpload
                    },
                    contentDescription = null,
                    tint = if (job.status == StatusUploadJobStatus.FAILED) CrimsonBlocked else VioletPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${job.kind.label}: ${job.label}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = ObsidianCharcoal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = when (job.status) {
                        StatusUploadJobStatus.QUEUED -> "Queued for upload…"
                        StatusUploadJobStatus.UPLOADING -> "Uploading ${(job.progress * 100).toInt()}%…"
                        StatusUploadJobStatus.DONE -> "Published!"
                        StatusUploadJobStatus.FAILED -> job.errorMessage.ifBlank { "Upload failed" }
                        StatusUploadJobStatus.CANCELLED -> "Cancelled"
                    }
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                }

                when (job.status) {
                    StatusUploadJobStatus.QUEUED, StatusUploadJobStatus.UPLOADING -> {
                        TextButton(onClick = onCancel) {
                            Text("Cancel", color = CrimsonBlocked, fontWeight = FontWeight.Bold)
                        }
                    }
                    StatusUploadJobStatus.FAILED -> {
                        TextButton(onClick = onRetry) {
                            Text("Retry", fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss failed upload", modifier = Modifier.size(16.dp))
                        }
                    }
                    else -> Unit
                }
            }
            if (job.status == StatusUploadJobStatus.QUEUED || job.status == StatusUploadJobStatus.UPLOADING) {
                LinearProgressIndicator(
                    progress = { job.progress.coerceIn(0.05f, 1f) },
                    color = VioletPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(999.dp))
                )
            }
        }
    }
}
