package com.example.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chat.ChatFileManager
import com.example.chat.ChatSettingsStore
import com.example.chat.ChatUserPreferences
import com.example.chat.StagedChatAttachment
import com.example.chat.realtime.MemberPresenceState
import com.example.chat.realtime.RtdbPresenceManager
import com.example.domain.CatalogueSeedData
import com.example.model.*
import com.example.ui.ChatFilterTab
import com.example.ui.components.StudioAvatar
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ChatHubBottomTab {
    DIRECTORY,
    CHATS,
    SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHubScreen(
    member: Member,
    isSuperAdmin: Boolean,
    channels: List<ChatChannel>,
    statuses: List<ChatStatus>,
    colleagues: List<Member>,
    departments: List<Department>,
    activeChannel: ChatChannel,
    searchQuery: String,
    filterTab: ChatFilterTab,
    selectedCustomList: String?,
    composerText: String,
    stagedAttachment: StagedChatAttachment?,
    isProcessingFile: Boolean,
    openingThreadLabel: String?,
    onSearchQueryChange: (String) -> Unit,
    onSelectFilterTab: (ChatFilterTab) -> Unit,
    onSelectCustomList: (String?) -> Unit,
    onComposerTextChange: (String) -> Unit,
    onClearAttachment: () -> Unit,
    onOpenChannelsSheet: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFiles: () -> Unit,
    onPickImages: () -> Unit,
    onSelectChannel: (ChatChannel) -> Unit,
    onOpenDirectChat: (Member) -> Unit,
    onOpenDepartmentChat: (Department) -> Unit,
    onOpenSavedNotes: () -> Unit,
    onOpenCreateGroup: () -> Unit,
    onOpenJoinByInvite: () -> Unit,
    onOpenCreateStatus: () -> Unit,
    onOpenStatusViewer: (ownerUid: String, list: List<ChatStatus>) -> Unit,
    onTogglePinChannel: (ChatChannel) -> Unit,
    onToggleMuteChannel: (ChatChannel) -> Unit,
    onToggleArchiveChannel: (ChatChannel) -> Unit,
    onToggleFavouriteChannel: (ChatChannel) -> Unit,
    onMarkReadUnread: (ChatChannel, Boolean) -> Unit,
    onDeleteChannel: (String) -> Unit,
    onCreateNewChannelClick: () -> Unit,
    onSendQuickCompose: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val prefs by ChatSettingsStore.prefsFlow.collectAsStateWithLifecycle()
    val companyId = CatalogueSeedData.DEFAULT_COMPANY_ID

    var activeBottomTab by remember { mutableStateOf(ChatHubBottomTab.CHATS) }
    var showFilterChips by remember { mutableStateOf(false) }
    var showNewActionOverlay by remember { mutableStateOf(false) }
    var showHubOverflowMenu by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var pinUnlockTarget by remember { mutableStateOf<ChatChannel?>(null) }
    var pinUnlockCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var channelActionTarget by remember { mutableStateOf<ChatChannel?>(null) }
    var deleteChannelTarget by remember { mutableStateOf<ChatChannel?>(null) }
    var directorySearchQuery by remember { mutableStateOf("") }
    var directoryFilterOnlineOnly by remember { mutableStateOf(false) }

    fun handleProtectedOpen(channel: ChatChannel, onOpen: () -> Unit) {
        val isLocked = channel.id in prefs.lockedChannelIds && channel.id !in prefs.unlockedThisSessionIds
        if (isLocked && prefs.hasSecretCode) {
            pinUnlockTarget = channel
            pinUnlockCallback = onOpen
        } else {
            onOpen()
        }
    }

    val directChannelsByPeer = remember(channels, member.uid) {
        channels.filter { it.channelTypeEnum() == ChatChannelType.DIRECT && !it.isSelfNotes(member.uid) }
            .mapNotNull { ch ->
                val other = ch.participantUids.firstOrNull { it != member.uid }
                if (other != null) other to ch else null
            }.toMap()
    }

    // All group, general, announcement and saved note channels
    val nonDirectChannels = remember(channels, member.uid) {
        channels.filter { it.channelTypeEnum() != ChatChannelType.DIRECT || it.isSelfNotes(member.uid) }
    }

    val filteredNonDirectChannels = remember(nonDirectChannels, searchQuery, filterTab, selectedCustomList, prefs, member.uid) {
        val q = searchQuery.trim().lowercase()
        nonDirectChannels.filter { ch ->
            val matchesQuery = q.isBlank() ||
                ch.title.lowercase().contains(q) ||
                ch.lastMessagePreview.lowercase().contains(q) ||
                ch.topic.lowercase().contains(q)
            val isLocked = ch.id in prefs.lockedChannelIds
            val isArchived = ch.isArchivedFor(member.uid)
            val customSet = prefs.channelListsMap[ch.id].orEmpty()
            val matchesTab = when {
                selectedCustomList != null -> selectedCustomList in customSet
                filterTab == ChatFilterTab.DIRECT -> false
                filterTab == ChatFilterTab.UNREAD -> ch.unreadCountFor(member.uid) > 0 && !isArchived && !isLocked
                filterTab == ChatFilterTab.GROUPS -> !isArchived && !isLocked
                filterTab == ChatFilterTab.FAVOURITES -> ch.isFavouriteFor(member.uid) && !isArchived
                filterTab == ChatFilterTab.ARCHIVED -> isArchived
                filterTab == ChatFilterTab.LOCKED -> isLocked
                else -> !isArchived && !isLocked
            }
            matchesQuery && matchesTab
        }.sortedWith(
            compareByDescending<ChatChannel> { it.isPinnedFor(member.uid) }
                .thenByDescending { it.lastMessageAtMillis }
        )
    }

    val filteredColleagues = remember(colleagues, directChannelsByPeer, searchQuery, filterTab, selectedCustomList, prefs, member.uid) {
        val q = searchQuery.trim().lowercase()
        colleagues.filter { person ->
            val ch = directChannelsByPeer[person.uid]
            val matchesQuery = q.isBlank() ||
                person.fullName.lowercase().contains(q) ||
                person.jobRoleName.lowercase().contains(q) ||
                (ch?.lastMessagePreview?.lowercase()?.contains(q) == true)
            val isLocked = ch != null && ch.id in prefs.lockedChannelIds
            val isArchived = ch?.isArchivedFor(member.uid) == true
            val customSet = ch?.let { prefs.channelListsMap[it.id] }.orEmpty()
            val matchesTab = when {
                selectedCustomList != null -> selectedCustomList in customSet
                filterTab == ChatFilterTab.GROUPS -> false
                filterTab == ChatFilterTab.DIRECT -> !isArchived && !isLocked
                filterTab == ChatFilterTab.UNREAD -> (ch?.unreadCountFor(member.uid) ?: 0) > 0 && !isArchived && !isLocked
                filterTab == ChatFilterTab.FAVOURITES -> ch?.isFavouriteFor(member.uid) == true && !isArchived
                filterTab == ChatFilterTab.ARCHIVED -> isArchived
                filterTab == ChatFilterTab.LOCKED -> isLocked
                else -> !isArchived && !isLocked
            }
            matchesQuery && matchesTab
        }.sortedByDescending { directChannelsByPeer[it.uid]?.lastMessageAtMillis ?: 0L }
    }

    val totalUnreadCount = remember(channels, member.uid) {
        channels.sumOf { it.unreadCountFor(member.uid) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureWhite)
            .testTag("chat_hub_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .then(if (showNewActionOverlay) Modifier.blur(12.dp).alpha(0.6f) else Modifier)
        ) {
            when (activeBottomTab) {
                ChatHubBottomTab.CHATS -> {
                    // Top Bar matching reference: Left '...' circle, Right New Action + Close
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Surface(
                            onClick = { showHubOverflowMenu = true },
                            shape = CircleShape,
                            color = PureWhite,
                            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MoreHoriz, contentDescription = "Chat Options", tint = ObsidianCharcoal, modifier = Modifier.size(18.dp))
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("chat_hub_back_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ObsidianCharcoal)
                            }
                            IconButton(
                                onClick = { showNewActionOverlay = true },
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("create_channel_button")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Compose or new group", tint = StudioBlueAccent, modifier = Modifier.size(22.dp))
                            }
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("chat_hub_close_button")
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close chat", tint = ObsidianCharcoal, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    // Large Bold "Chats" Header matching reference
                    Text(
                        text = "Chats",
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp),
                        fontWeight = FontWeight.ExtraBold,
                        color = ObsidianCharcoal,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                    )

                    // Rounded Search Input with Filter Toggle Icon
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFF2F4F7),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = StudioTextSecondary, modifier = Modifier.size(18.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChange,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = ObsidianCharcoal),
                                cursorBrush = SolidColor(ObsidianCharcoal),
                                decorationBox = { inner ->
                                    if (searchQuery.isEmpty()) {
                                        Text("Search", style = MaterialTheme.typography.bodyMedium, color = StudioTextSecondary)
                                    }
                                    inner()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("chat_search_button")
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = StudioTextSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                            IconButton(onClick = { showFilterChips = !showFilterChips }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = "Filters",
                                    tint = if (showFilterChips || filterTab != ChatFilterTab.ALL) StudioBlueAccent else StudioTextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Optional Filter Pills Row (when toggled or active)
                    AnimatedVisibility(
                        visible = showFilterChips || filterTab != ChatFilterTab.ALL,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ChatFilterTab.entries.forEach { tab ->
                                val selected = selectedCustomList == null && filterTab == tab
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        if (tab == ChatFilterTab.LOCKED && prefs.hasSecretCode) {
                                            pinUnlockTarget = null
                                            pinUnlockCallback = { onSelectFilterTab(tab) }
                                        } else {
                                            onSelectFilterTab(tab)
                                        }
                                    },
                                    label = { Text(tab.label, fontSize = 12.sp) }
                                )
                            }
                            prefs.customLists.forEach { customList ->
                                FilterChip(
                                    selected = selectedCustomList == customList,
                                    onClick = { onSelectCustomList(if (selectedCustomList == customList) null else customList) },
                                    label = { Text(customList, fontSize = 12.sp) }
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = !openingThreadLabel.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(shape = RoundedCornerShape(999.dp), color = ObsidianCharcoal, modifier = Modifier.testTag("chat_opening_indicator")) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = PureWhite)
                                    Text(openingThreadLabel.orEmpty(), style = MaterialTheme.typography.labelMedium, color = PureWhite)
                                }
                            }
                        }
                    }

                    // Main Scrollable Conversations List
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 8.dp)
                    ) {
                        // Status / Stories Strip
                        StatusAvatarBar(
                            myMember = member,
                            statuses = statuses,
                            colleagues = colleagues,
                            onAddStatus = onOpenCreateStatus,
                            onOpenStatusesForOwner = onOpenStatusViewer
                        )

                        // Direct Messages (1:1 Person-to-person chats)
                        if (filteredColleagues.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("chat_dm_section")
                            ) {
                                filteredColleagues.forEach { person ->
                                    val dmChannel = directChannelsByPeer[person.uid]
                                    val presence by RtdbPresenceManager.observeMemberPresence(companyId, person.uid)
                                        .collectAsStateWithLifecycle(initialValue = MemberPresenceState(uid = person.uid))
                                    val unread = dmChannel?.unreadCountFor(member.uid) ?: 0
                                    val isMuted = dmChannel?.isMutedFor(member.uid) == true
                                    val isPinned = dmChannel?.isPinnedFor(member.uid) == true
                                    val isLocked = dmChannel != null && dmChannel.id in prefs.lockedChannelIds
                                    val timeStr = remember(dmChannel?.lastMessageAtMillis) {
                                        val ms = dmChannel?.lastMessageAtMillis ?: 0L
                                        if (ms > 0L) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms)) else ""
                                    }

                                    Surface(
                                        onClick = {
                                            if (dmChannel != null) {
                                                handleProtectedOpen(dmChannel) { onOpenDirectChat(person) }
                                            } else {
                                                onOpenDirectChat(person)
                                            }
                                        },
                                        color = PureWhite,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("dm_person_${person.uid}")
                                    ) {
                                        Column {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                                            ) {
                                                Box {
                                                    StudioAvatar(
                                                        name = person.fullName,
                                                        size = 48.dp,
                                                        borderColor = PureWhite,
                                                        photoBase64 = person.photoBase64
                                                    )
                                                    if (presence.online) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(13.dp)
                                                                .align(Alignment.BottomEnd)
                                                                .clip(CircleShape)
                                                                .background(EmeraldApproved)
                                                                .border(2.dp, PureWhite, CircleShape)
                                                        )
                                                    }
                                                }

                                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Text(
                                                            text = formatTitleCase(person.fullName.ifBlank { "Colleague" }),
                                                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                                                            fontWeight = FontWeight.Bold,
                                                            color = ObsidianCharcoal,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (isPinned) Icon(Icons.Default.PushPin, null, tint = StudioBlueAccent, modifier = Modifier.size(13.dp))
                                                        if (isMuted) Icon(Icons.Default.VolumeOff, null, tint = StudioTextSecondary, modifier = Modifier.size(13.dp))
                                                        if (isLocked) Icon(Icons.Default.Lock, null, tint = ObsidianCharcoal, modifier = Modifier.size(13.dp))
                                                    }
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        if (dmChannel != null && dmChannel.lastSenderUid == member.uid) {
                                                            DeliveryStatusIcon(status = dmChannel.lastMessageStatus, modifier = Modifier.size(14.dp))
                                                        }
                                                        Text(
                                                            text = dmChannel?.lastMessagePreview?.takeIf { it.isNotBlank() }
                                                                ?: formatTitleCase(person.jobRoleName.ifBlank { "Personal Chat" }),
                                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                                            color = StudioTextSecondary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }

                                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    if (timeStr.isNotBlank()) {
                                                        Text(timeStr, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = StudioTextSecondary)
                                                    }
                                                    if (unread > 0) {
                                                        Surface(shape = CircleShape, color = StudioBlueAccent) {
                                                            Text(
                                                                text = unread.toString(),
                                                                style = MaterialTheme.typography.labelSmall,
                                                                fontWeight = FontWeight.Bold,
                                                                color = PureWhite,
                                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            HorizontalDivider(color = Color(0xFFECEFF2), modifier = Modifier.padding(start = 76.dp, end = 18.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // Groups & Communities List
                        if (filteredNonDirectChannels.isNotEmpty()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                filteredNonDirectChannels.forEach { ch ->
                                    val unread = ch.unreadCountFor(member.uid)
                                    val isMuted = ch.isMutedFor(member.uid)
                                    val isPinned = ch.isPinnedFor(member.uid)
                                    val isLocked = ch.id in prefs.lockedChannelIds
                                    val isSaved = ch.isSelfNotes(member.uid)
                                    val timeStr = remember(ch.lastMessageAtMillis) {
                                        if (ch.lastMessageAtMillis > 0L) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ch.lastMessageAtMillis)) else ""
                                    }

                                    Surface(
                                        onClick = { handleProtectedOpen(ch) { onSelectChannel(ch) } },
                                        color = PureWhite,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("chat_row_${ch.id}")
                                            .testTag("channel_chip_${ch.id}")
                                    ) {
                                        Column {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                                            ) {
                                                if (isSaved) {
                                                    Surface(shape = CircleShape, color = VioletSoftBg, modifier = Modifier.size(48.dp)) {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Icon(Icons.Default.Bookmark, contentDescription = "Saved Notes", tint = VioletPrimary)
                                                        }
                                                    }
                                                } else {
                                                    StudioAvatar(
                                                        name = ch.title,
                                                        size = 48.dp,
                                                        borderColor = PureWhite,
                                                        photoBase64 = ch.photoBase64
                                                    )
                                                }

                                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Text(
                                                            text = if (isSaved) "Message Yourself" else formatTitleCase(ch.title),
                                                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                                                            fontWeight = FontWeight.Bold,
                                                            color = ObsidianCharcoal,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (isPinned) Icon(Icons.Default.PushPin, null, tint = StudioBlueAccent, modifier = Modifier.size(13.dp))
                                                        if (isMuted) Icon(Icons.Default.VolumeOff, null, tint = StudioTextSecondary, modifier = Modifier.size(13.dp))
                                                        if (isLocked) Icon(Icons.Default.Lock, null, tint = ObsidianCharcoal, modifier = Modifier.size(13.dp))
                                                    }
                                                    Text(
                                                        text = ch.lastMessagePreview.ifBlank { ch.topic.ifBlank { "Group conversation" } },
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                                        color = StudioTextSecondary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }

                                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    if (timeStr.isNotBlank()) {
                                                        Text(timeStr, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = StudioTextSecondary)
                                                    }
                                                    if (unread > 0) {
                                                        Surface(shape = CircleShape, color = StudioBlueAccent) {
                                                            Text(
                                                                text = unread.toString(),
                                                                style = MaterialTheme.typography.labelSmall,
                                                                fontWeight = FontWeight.Bold,
                                                                color = PureWhite,
                                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            HorizontalDivider(color = Color(0xFFECEFF2), modifier = Modifier.padding(start = 76.dp, end = 18.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // Preserves quick send composer if user typed text
                        if (composerText.isNotEmpty() || stagedAttachment != null) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFFF2F4F7),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    BasicTextField(
                                        value = composerText,
                                        onValueChange = onComposerTextChange,
                                        cursorBrush = SolidColor(ObsidianCharcoal),
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = ObsidianCharcoal),
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = onSendQuickCompose,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("chat_hub_send_button")
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Send", tint = StudioBlueAccent)
                                    }
                                }
                            }
                        }
                    }
                }

                ChatHubBottomTab.DIRECTORY -> {
                    // "Find People" Tab matching center screen in reference image
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ObsidianCharcoal)
                        }
                        IconButton(onClick = onClose, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = ObsidianCharcoal)
                        }
                    }

                    Text(
                        text = "Find People",
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp),
                        fontWeight = FontWeight.ExtraBold,
                        color = ObsidianCharcoal,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                    )

                    // Search input + Cancel button matching reference
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFFF2F4F7),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, tint = StudioTextSecondary, modifier = Modifier.size(18.dp))
                                BasicTextField(
                                    value = directorySearchQuery,
                                    onValueChange = { directorySearchQuery = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ObsidianCharcoal),
                                    cursorBrush = SolidColor(ObsidianCharcoal),
                                    decorationBox = { inner ->
                                        if (directorySearchQuery.isEmpty()) {
                                            Text("Search colleagues…", style = MaterialTheme.typography.bodyMedium, color = StudioTextSecondary)
                                        }
                                        inner()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                if (directorySearchQuery.isNotEmpty()) {
                                    IconButton(onClick = { directorySearchQuery = "" }, modifier = Modifier.size(20.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = StudioTextSecondary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { directorySearchQuery = "" }) {
                            Text("Cancel", color = StudioBlueAccent, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Filter Pills matching reference
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !directoryFilterOnlineOnly,
                            onClick = { directoryFilterOnlineOnly = false },
                            label = { Text("All Colleagues") }
                        )
                        FilterChip(
                            selected = directoryFilterOnlineOnly,
                            onClick = { directoryFilterOnlineOnly = true },
                            label = { Text("Online Now") }
                        )
                        FilterChip(
                            selected = false,
                            onClick = { onOpenCreateGroup() },
                            leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp)) },
                            label = { Text("New Group") }
                        )
                    }

                    // Directory Colleagues List
                    val dirFiltered = remember(colleagues, directorySearchQuery, directoryFilterOnlineOnly) {
                        val q = directorySearchQuery.trim().lowercase()
                        colleagues.filter { c ->
                            (q.isBlank() || c.fullName.lowercase().contains(q) || c.jobRoleName.lowercase().contains(q) || c.departmentName.lowercase().contains(q))
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        items(dirFiltered, key = { it.uid }) { person ->
                            val presence by RtdbPresenceManager.observeMemberPresence(companyId, person.uid)
                                .collectAsStateWithLifecycle(initialValue = MemberPresenceState(uid = person.uid))
                            if (!directoryFilterOnlineOnly || presence.online) {
                                Surface(
                                    onClick = { onOpenDirectChat(person) },
                                    color = PureWhite,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                                        ) {
                                            Box {
                                                StudioAvatar(name = person.fullName, size = 48.dp, borderColor = PureWhite, photoBase64 = person.photoBase64)
                                                if (presence.online) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(13.dp)
                                                            .align(Alignment.BottomEnd)
                                                            .clip(CircleShape)
                                                            .background(EmeraldApproved)
                                                            .border(2.dp, PureWhite, CircleShape)
                                                    )
                                                }
                                            }
                                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(formatTitleCase(person.fullName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                                                Text(formatTitleCase(person.jobRoleName.ifBlank { "Team Member" }), style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                                Text("📍 ${formatTitleCase(person.departmentName.ifBlank { "Workspace" })}", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                                            }
                                            IconButton(onClick = { onOpenDirectChat(person) }) {
                                                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Chat", tint = StudioBlueAccent)
                                            }
                                        }
                                        HorizontalDivider(color = Color(0xFFECEFF2), modifier = Modifier.padding(start = 76.dp, end = 18.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                ChatHubBottomTab.SETTINGS -> {
                    // Clean Settings Tab Content directly rendered
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Chat Settings & Privacy", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                            IconButton(onClick = onClose) { Icon(Icons.Default.Close, null) }
                        }

                        // Chat Lock Secret Code
                        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFF2F4F7), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Secret Code for Locked Chats", fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (prefs.hasSecretCode) "Secret PIN configured (${prefs.lockedChannelIds.size} locked)" else "Not set",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioTextSecondary
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { showSettingsSheet = true }) {
                                        Text(if (prefs.hasSecretCode) "Change PIN" else "Set Secret PIN")
                                    }
                                    if (prefs.hasSecretCode) {
                                        TextButton(onClick = { ChatSettingsStore.clearSecretCode(context) }) {
                                            Text("Remove PIN", color = CrimsonBlocked)
                                        }
                                    }
                                }
                            }
                        }

                        // Bubble Theme Preset
                        Text("Chat Bubble Appearance", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChatVisualCatalog.bubbleThemes.forEach { bt ->
                                FilterChip(
                                    selected = prefs.bubbleThemeId == bt.id,
                                    onClick = { ChatSettingsStore.setBubbleTheme(context, bt.id) },
                                    label = { Text(bt.label) }
                                )
                            }
                        }

                        // Canvas Tint / Wallpapers
                        Text("Canvas Tone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChatVisualCatalog.wallpapers.forEach { wp ->
                                FilterChip(
                                    selected = prefs.globalWallpaperId == wp.id,
                                    onClick = { ChatSettingsStore.setWallpaper(context, wp.id) },
                                    label = { Text(wp.label) }
                                )
                            }
                        }

                        // Media Settings
                        Text("Media & Auto-Download", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFF2F4F7), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Auto-download photos", style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = prefs.autoDownloadPhotos, onCheckedChange = { ChatSettingsStore.updateMediaSettings(context, it, prefs.autoDownloadAudio, prefs.autoDownloadDocs, prefs.mediaVisibleInGallery) })
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Auto-download audio & voice", style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = prefs.autoDownloadAudio, onCheckedChange = { ChatSettingsStore.updateMediaSettings(context, prefs.autoDownloadPhotos, it, prefs.autoDownloadDocs, prefs.mediaVisibleInGallery) })
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Show media in device gallery", style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = prefs.mediaVisibleInGallery, onCheckedChange = { ChatSettingsStore.updateMediaSettings(context, prefs.autoDownloadPhotos, prefs.autoDownloadAudio, prefs.autoDownloadDocs, it) })
                                }
                            }
                        }
                    }
                }
            }

            // Reference Image 3-Tab Bottom Navigation Bar (Directory | Chats | Settings)
            Surface(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = PureWhite,
                border = BorderStroke(1.dp, Color(0xFFECEFF2)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Tab 1: Directory (Find People)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { activeBottomTab = ChatHubBottomTab.DIRECTORY }
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = if (activeBottomTab == ChatHubBottomTab.DIRECTORY) Icons.Filled.People else Icons.Outlined.People,
                            contentDescription = "Directory",
                            tint = if (activeBottomTab == ChatHubBottomTab.DIRECTORY) StudioBlueAccent else Color(0xFF94A3B8),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Directory",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            fontWeight = if (activeBottomTab == ChatHubBottomTab.DIRECTORY) FontWeight.Bold else FontWeight.Medium,
                            color = if (activeBottomTab == ChatHubBottomTab.DIRECTORY) StudioBlueAccent else Color(0xFF94A3B8)
                        )
                    }

                    // Tab 2: Chats (Active default with unread badge)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { activeBottomTab = ChatHubBottomTab.CHATS }
                            .padding(8.dp)
                    ) {
                        BadgedBox(
                            badge = {
                                if (totalUnreadCount > 0) {
                                    Badge(containerColor = StudioBlueAccent) {
                                        Text("$totalUnreadCount", color = PureWhite)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (activeBottomTab == ChatHubBottomTab.CHATS) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline,
                                contentDescription = "Chats",
                                tint = if (activeBottomTab == ChatHubBottomTab.CHATS) StudioBlueAccent else Color(0xFF94A3B8),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Text(
                            text = "Chats",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            fontWeight = if (activeBottomTab == ChatHubBottomTab.CHATS) FontWeight.Bold else FontWeight.Medium,
                            color = if (activeBottomTab == ChatHubBottomTab.CHATS) StudioBlueAccent else Color(0xFF94A3B8)
                        )
                    }

                    // Tab 3: Settings
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { activeBottomTab = ChatHubBottomTab.SETTINGS }
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = if (activeBottomTab == ChatHubBottomTab.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            tint = if (activeBottomTab == ChatHubBottomTab.SETTINGS) StudioBlueAccent else Color(0xFF94A3B8),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            fontWeight = if (activeBottomTab == ChatHubBottomTab.SETTINGS) FontWeight.Bold else FontWeight.Medium,
                            color = if (activeBottomTab == ChatHubBottomTab.SETTINGS) StudioBlueAccent else Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }

        // New Chat / Group Action Sheet Modal (Overlay Menu)
        if (showNewActionOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable { showNewActionOverlay = false }
            ) {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = PureWhite,
                    shadowElevation = 14.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 20.dp, end = 20.dp, bottom = 80.dp)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .fillMaxWidth()
                        .clickable(enabled = false) {}
                        .testTag("chat_new_action_pill")
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OverlayMenuRow(
                            icon = Icons.Outlined.ChatBubbleOutline,
                            title = "New Chat",
                            subtitle = "Browse channels or send a direct message",
                            onClick = {
                                showNewActionOverlay = false
                                onOpenChannelsSheet()
                            }
                        )
                        HorizontalDivider(color = Color(0xFFECEFF2))
                        OverlayMenuRow(
                            icon = Icons.Outlined.BookmarkBorder,
                            title = "Message Yourself",
                            subtitle = "Save personal notes, links, and files",
                            onClick = {
                                showNewActionOverlay = false
                                onOpenSavedNotes()
                            }
                        )
                        HorizontalDivider(color = Color(0xFFECEFF2))
                        OverlayMenuRow(
                            icon = Icons.Outlined.Groups,
                            title = "New Group",
                            subtitle = "Create a group with admins, polls & invites",
                            onClick = {
                                showNewActionOverlay = false
                                onOpenCreateGroup()
                            }
                        )
                        HorizontalDivider(color = Color(0xFFECEFF2))
                        OverlayMenuRow(
                            icon = Icons.Outlined.QrCode,
                            title = "Join Group by Link",
                            subtitle = "Enter an invite code to join a workspace group",
                            onClick = {
                                showNewActionOverlay = false
                                onOpenJoinByInvite()
                            }
                        )
                    }
                }
            }
        }
    }

    // Overflow & Dialog handlers
    if (showHubOverflowMenu) {
        DropdownMenu(expanded = showHubOverflowMenu, onDismissRequest = { showHubOverflowMenu = false }) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.Bookmark, null) },
                text = { Text("Message Yourself (Saved)") },
                onClick = { showHubOverflowMenu = false; onOpenSavedNotes() }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.GroupAdd, null) },
                text = { Text("New Group") },
                onClick = { showHubOverflowMenu = false; onOpenCreateGroup() }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.QrCode, null) },
                text = { Text("Join via Invite Link") },
                onClick = { showHubOverflowMenu = false; onOpenJoinByInvite() }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.Tune, null) },
                text = { Text("Chat Settings & Lock") },
                onClick = { showHubOverflowMenu = false; showSettingsSheet = true }
            )
        }
    }

    if (showSettingsSheet) {
        ChatSettingsAndSecuritySheet(prefs = prefs, onDismiss = { showSettingsSheet = false })
    }

    if (pinUnlockCallback != null) {
        PinUnlockDialog(
            onVerified = {
                pinUnlockTarget?.let { ChatSettingsStore.markUnlockedForSession(it.id) }
                val cb = pinUnlockCallback
                pinUnlockTarget = null
                pinUnlockCallback = null
                cb?.invoke()
            },
            onDismiss = {
                pinUnlockTarget = null
                pinUnlockCallback = null
            }
        )
    }
}

@Composable
private fun OverlayMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(shape = CircleShape, color = Color(0xFFF2F4F7), modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = ObsidianCharcoal, modifier = Modifier.size(20.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
        }
    }
}
