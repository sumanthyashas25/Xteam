package com.example.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chat.ChatSettingsStore
import com.example.chat.StagedChatAttachment
import com.example.chat.StatusPrivacyEngine
import com.example.chat.realtime.MemberAvailability
import com.example.chat.realtime.MemberPresenceState
import com.example.chat.realtime.RtdbPresenceManager
import com.example.domain.CatalogueSeedData
import com.example.model.*
import com.example.ui.ChatFilterTab
import com.example.ui.components.AvailabilityStatusDot
import com.example.ui.components.AvatarWithAvailabilityBadge
import com.example.ui.components.SegmentedStatusAvatar
import com.example.ui.components.StudioAvatar
import com.example.ui.components.availabilityDotColor
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    onClose: () -> Unit = onBack
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ChatSettingsStore.ensureLoaded(context)
    }
    val prefs by ChatSettingsStore.prefsFlow.collectAsStateWithLifecycle()
    val companyId = CatalogueSeedData.DEFAULT_COMPANY_ID
    val companyPresence by RtdbPresenceManager.observeCompanyPresence(companyId)
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    var liveNowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(20_000L)
            liveNowMillis = System.currentTimeMillis()
        }
    }
    val visibleStatusesByOwner = remember(statuses, member.uid, prefs.blockedUserUids, liveNowMillis) {
        StatusPrivacyEngine.categorizeStatuses(
            statuses = statuses,
            myMember = member,
            allMembers = colleagues + member,
            mutedOwnerUids = prefs.mutedStatusOwnerUids,
            blockedByViewerUids = prefs.blockedUserUids,
            nowMillis = liveNowMillis
        ).visibleByOwner
    }

    var activeBottomTab by remember { mutableStateOf(ChatHubBottomTab.CHATS) }
    var showFilterChips by remember { mutableStateOf(false) }
    var showHubOverflowMenu by remember { mutableStateOf(false) }
    var showPinSetupDialog by remember { mutableStateOf(false) }
    var pendingPinSetupAfterLockTarget by remember { mutableStateOf<ChatChannel?>(null) }
    var pendingPinSetupSwitchLockedTab by remember { mutableStateOf(false) }
    var lockedTabUnlocked by remember { mutableStateOf(false) }
    var pinUnlockTarget by remember { mutableStateOf<ChatChannel?>(null) }
    var pinUnlockCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var channelActionTarget by remember { mutableStateOf<ChatChannel?>(null) }
    var directorySearchQuery by remember { mutableStateOf("") }
    var directoryFilterOnlineOnly by remember { mutableStateOf(false) }
    var newCustomListInput by remember { mutableStateOf("") }

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

    val colleaguesByUid = remember(colleagues, member) {
        (colleagues + member).associateBy { it.uid }
    }

    val totalUnreadCount = remember(channels, member.uid) {
        channels.sumOf { it.unreadCountFor(member.uid) }
    }

    @Composable
    fun HubOverflowDropdown() {
        DropdownMenu(
            expanded = showHubOverflowMenu,
            onDismissRequest = { showHubOverflowMenu = false },
            containerColor = PureWhite
        ) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
                text = { Text("New Chat") },
                onClick = { showHubOverflowMenu = false; onOpenChannelsSheet() }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Outlined.BookmarkBorder, null) },
                text = { Text("Message Yourself") },
                onClick = { showHubOverflowMenu = false; onOpenSavedNotes() }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Outlined.GroupAdd, null) },
                text = { Text("New Group") },
                onClick = { showHubOverflowMenu = false; onOpenCreateGroup() }
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Outlined.QrCode, null) },
                text = { Text("Join Group by Link") },
                onClick = { showHubOverflowMenu = false; onOpenJoinByInvite() }
            )
            if (activeBottomTab != ChatHubBottomTab.SETTINGS) {
                HorizontalDivider(color = Color(0xFFECEFF2))
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                    text = { Text("Chat Settings") },
                    onClick = {
                        showHubOverflowMenu = false
                        activeBottomTab = ChatHubBottomTab.SETTINGS
                    }
                )
            }
        }
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
        ) {
            when (activeBottomTab) {
                ChatHubBottomTab.CHATS -> {
                    // Header: Left Back Arrow, Right Three-Dots Overflow (no Cross, no Pen)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("chat_hub_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ObsidianCharcoal)
                        }

                        Box {
                            Surface(
                                onClick = { showHubOverflowMenu = true },
                                shape = CircleShape,
                                color = PureWhite,
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("chat_hub_overflow_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Chat Options", tint = ObsidianCharcoal, modifier = Modifier.size(20.dp))
                                }
                            }
                            HubOverflowDropdown()
                        }
                    }

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
                                IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = StudioTextSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                            IconButton(onClick = { showFilterChips = !showFilterChips }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = "Filters",
                                    tint = if (showFilterChips || filterTab != ChatFilterTab.ALL) StudioBlueAccent else StudioTextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Optional Filter Pills Row
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
                                        if (tab == ChatFilterTab.LOCKED) {
                                            if (!prefs.hasSecretCode) {
                                                pendingPinSetupSwitchLockedTab = true
                                                showPinSetupDialog = true
                                            } else if (!lockedTabUnlocked) {
                                                pinUnlockTarget = null
                                                pinUnlockCallback = {
                                                    lockedTabUnlocked = true
                                                    onSelectFilterTab(tab)
                                                }
                                            } else {
                                                onSelectFilterTab(tab)
                                            }
                                        } else {
                                            lockedTabUnlocked = false
                                            onSelectFilterTab(tab)
                                        }
                                    },
                                    label = { Text(tab.label, fontSize = 12.sp) }
                                )
                            }
                            prefs.customLists.forEach { customList ->
                                FilterChip(
                                    selected = selectedCustomList == customList,
                                    onClick = {
                                        lockedTabUnlocked = false
                                        onSelectCustomList(if (selectedCustomList == customList) null else customList)
                                    },
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
                                    val presence = companyPresence[person.uid]
                                    val availability = RtdbPresenceManager.resolveAvailability(person, presence)
                                    val unread = dmChannel?.unreadCountFor(member.uid) ?: 0
                                    val isMuted = dmChannel?.isMutedFor(member.uid) == true
                                    val isPinned = dmChannel?.isPinnedFor(member.uid) == true
                                    val isLocked = dmChannel != null && dmChannel.id in prefs.lockedChannelIds
                                    val timeStr = remember(dmChannel?.lastMessageAtMillis) {
                                        val ms = dmChannel?.lastMessageAtMillis ?: 0L
                                        if (ms > 0L) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms)) else ""
                                    }

                                    Surface(
                                        color = PureWhite,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = {
                                                    if (dmChannel != null) {
                                                        handleProtectedOpen(dmChannel) { onOpenDirectChat(person) }
                                                    } else {
                                                        onOpenDirectChat(person)
                                                    }
                                                },
                                                onLongClick = {
                                                    if (dmChannel != null) {
                                                        channelActionTarget = dmChannel
                                                    }
                                                }
                                            )
                                            .testTag("dm_person_${person.uid}")
                                    ) {
                                        Column {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                                            ) {
                                                val peerStatuses = visibleStatusesByOwner[person.uid].orEmpty()
                                                SegmentedStatusAvatar(
                                                    name = person.fullName,
                                                    availability = availability,
                                                    showAvailabilityBadge = true,
                                                    size = 48.dp,
                                                    borderColor = PureWhite,
                                                    photoBase64 = person.photoBase64,
                                                    dotSize = 13.dp,
                                                    activeStatuses = peerStatuses,
                                                    viewerUid = member.uid,
                                                    isOwnAvatar = false,
                                                    onAvatarClick = if (peerStatuses.isNotEmpty()) {
                                                        { onOpenStatusViewer(person.uid, peerStatuses) }
                                                    } else null,
                                                    modifier = Modifier.testTag("dm_avatar_${person.uid}")
                                                )

                                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Text(
                                                            text = formatTitleCase(person.fullName.ifBlank { "Colleague" }),
                                                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                                                            fontWeight = FontWeight.Bold,
                                                            color = ObsidianCharcoal,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f, fill = false)
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
                                                                ?: "No messages yet",
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
                                    val groupMemberAvails = remember(ch.participantUids, companyPresence, colleaguesByUid, member.uid) {
                                        val otherUids = ch.participantUids.filter { it != member.uid }.ifEmpty {
                                            colleagues.map { it.uid }
                                        }
                                        otherUids.map { uid ->
                                            RtdbPresenceManager.resolveAvailability(colleaguesByUid[uid], companyPresence[uid])
                                        }
                                    }
                                    val groupBadgeAvail = remember(groupMemberAvails) {
                                        when {
                                            MemberAvailability.ACTIVE in groupMemberAvails -> MemberAvailability.ACTIVE
                                            MemberAvailability.BUSY in groupMemberAvails -> MemberAvailability.BUSY
                                            else -> MemberAvailability.AWAY
                                        }
                                    }
                                    val activeMembersCount = remember(groupMemberAvails) {
                                        groupMemberAvails.count { it == MemberAvailability.ACTIVE }
                                    }

                                    Surface(
                                        color = PureWhite,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { handleProtectedOpen(ch) { onSelectChannel(ch) } },
                                                onLongClick = { channelActionTarget = ch }
                                            )
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
                                                    AvatarWithAvailabilityBadge(
                                                        name = ch.title,
                                                        availability = groupBadgeAvail,
                                                        size = 48.dp,
                                                        borderColor = PureWhite,
                                                        photoBase64 = ch.photoBase64,
                                                        dotSize = 13.dp
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
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f, fill = false)
                                                        )
                                                        if (isPinned) Icon(Icons.Default.PushPin, null, tint = StudioBlueAccent, modifier = Modifier.size(13.dp))
                                                        if (isMuted) Icon(Icons.Default.VolumeOff, null, tint = StudioTextSecondary, modifier = Modifier.size(13.dp))
                                                        if (isLocked) Icon(Icons.Default.Lock, null, tint = ObsidianCharcoal, modifier = Modifier.size(13.dp))
                                                    }
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Text(
                                                            text = ch.lastMessagePreview.ifBlank { ch.topic.ifBlank { "Group conversation" } },
                                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                                            color = StudioTextSecondary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f, fill = false)
                                                        )
                                                        if (!isSaved && activeMembersCount > 0) {
                                                            Text(
                                                                text = "• $activeMembersCount active",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = EmeraldApproved,
                                                                maxLines = 1
                                                            )
                                                        }
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

                        // Preserves quick send composer if text/attachment staged
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
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = StudioBlueAccent)
                                    }
                                }
                            }
                        }
                    }
                }

                ChatHubBottomTab.DIRECTORY -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("chat_directory_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ObsidianCharcoal)
                        }
                        Box {
                            Surface(
                                onClick = { showHubOverflowMenu = true },
                                shape = CircleShape,
                                color = PureWhite,
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Chat Options", tint = ObsidianCharcoal, modifier = Modifier.size(20.dp))
                                }
                            }
                            HubOverflowDropdown()
                        }
                    }

                    Text(
                        text = "Find People",
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp),
                        fontWeight = FontWeight.ExtraBold,
                        color = ObsidianCharcoal,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                    )

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
                            label = { Text("Active & Busy") }
                        )
                        FilterChip(
                            selected = false,
                            onClick = { onOpenCreateGroup() },
                            leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp)) },
                            label = { Text("New Group") }
                        )
                    }

                    val dirFiltered = remember(colleagues, directorySearchQuery) {
                        val q = directorySearchQuery.trim().lowercase()
                        colleagues.filter { c ->
                            q.isBlank() || c.fullName.lowercase().contains(q) || c.jobRoleName.lowercase().contains(q) || c.departmentName.lowercase().contains(q)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        items(dirFiltered, key = { it.uid }) { person ->
                            val availability = RtdbPresenceManager.resolveAvailability(person, companyPresence[person.uid])
                            if (!directoryFilterOnlineOnly || availability != MemberAvailability.AWAY) {
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
                                            val peerStatuses = visibleStatusesByOwner[person.uid].orEmpty()
                                            SegmentedStatusAvatar(
                                                name = person.fullName,
                                                availability = availability,
                                                showAvailabilityBadge = true,
                                                size = 48.dp,
                                                borderColor = PureWhite,
                                                photoBase64 = person.photoBase64,
                                                dotSize = 13.dp,
                                                activeStatuses = peerStatuses,
                                                viewerUid = member.uid,
                                                isOwnAvatar = false,
                                                onAvatarClick = if (peerStatuses.isNotEmpty()) {
                                                    { onOpenStatusViewer(person.uid, peerStatuses) }
                                                } else null,
                                                modifier = Modifier.testTag("dir_avatar_${person.uid}")
                                            )
                                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Text(formatTitleCase(person.fullName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                                                    Text(
                                                        text = "• ${availability.label}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = availabilityDotColor(availability)
                                                    )
                                                }
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
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(
                                onClick = { activeBottomTab = ChatHubBottomTab.CHATS },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("chat_settings_back_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Chats", tint = ObsidianCharcoal)
                            }
                            Text(
                                text = "Chat Settings & Privacy",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = ObsidianCharcoal
                            )
                        }

                        // Chat Lock Secret Code
                        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFF2F4F7), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Secret Code for Locked Chats", fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (prefs.hasSecretCode) "Secret PIN configured (${prefs.lockedChannelIds.size} locked)" else "Set a 4+ digit PIN to lock sensitive chats",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioTextSecondary
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { showPinSetupDialog = true },
                                        modifier = Modifier.testTag("settings_set_pin_button")
                                    ) {
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
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .clip(CircleShape)
                                                .background(bt.outgoingBg)
                                                .border(1.dp, SubtleOutline, CircleShape)
                                        )
                                    },
                                    label = { Text(bt.label) }
                                )
                            }
                        }

                        // Chat Wallpaper
                        Text("Chat Wallpaper", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChatVisualCatalog.wallpapers.forEach { wp ->
                                FilterChip(
                                    selected = prefs.globalWallpaperId == wp.id,
                                    onClick = { ChatSettingsStore.setWallpaper(context, wp.id) },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .clip(CircleShape)
                                                .background(wp.baseColors.lastOrNull() ?: PureWhite)
                                                .border(1.dp, Color(0xFF94A3B8), CircleShape)
                                        )
                                    },
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
                                    Text("Auto-download documents", style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = prefs.autoDownloadDocs, onCheckedChange = { ChatSettingsStore.updateMediaSettings(context, prefs.autoDownloadPhotos, prefs.autoDownloadAudio, it, prefs.mediaVisibleInGallery) })
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Show media in device gallery", style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = prefs.mediaVisibleInGallery, onCheckedChange = { ChatSettingsStore.updateMediaSettings(context, prefs.autoDownloadPhotos, prefs.autoDownloadAudio, prefs.autoDownloadDocs, it) })
                                }
                            }
                        }

                        // Custom Chat Lists
                        Text("Custom Chat Lists", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newCustomListInput,
                                onValueChange = { newCustomListInput = it },
                                placeholder = { Text("New list name…") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    ChatSettingsStore.addCustomList(context, newCustomListInput)
                                    newCustomListInput = ""
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                            ) {
                                Text("Add")
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            prefs.customLists.forEach { listName ->
                                InputChip(
                                    selected = false,
                                    onClick = {},
                                    label = { Text(listName) },
                                    trailingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Delete list",
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable { ChatSettingsStore.deleteCustomList(context, listName) }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 3-Tab Bottom Navigation Bar (Directory | Chats | Settings)
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
    }

    // Long-press quick conversation actions (Lock/Unlock, Pin, Mute, Archive, Favourite, Read/Unread)
    channelActionTarget?.let { target ->
        val isLocked = target.id in prefs.lockedChannelIds
        val isPinned = target.isPinnedFor(member.uid)
        val isMuted = target.isMutedFor(member.uid)
        val isArchived = target.isArchivedFor(member.uid)
        val isFav = target.isFavouriteFor(member.uid)
        val hasUnread = target.unreadCountFor(member.uid) > 0

        Dialog(onDismissRequest = { channelActionTarget = null }) {
            Surface(shape = RoundedCornerShape(22.dp), color = PureWhite, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = formatTitleCase(target.title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = ObsidianCharcoal
                    )
                    HorizontalDivider(color = Color(0xFFECEFF2))
                    ActionDialogRow(
                        icon = if (isLocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                        label = if (isLocked) "Unlock Chat" else "Lock Chat"
                    ) {
                        channelActionTarget = null
                        if (!prefs.hasSecretCode) {
                            pendingPinSetupAfterLockTarget = target
                            showPinSetupDialog = true
                        } else {
                            ChatSettingsStore.toggleChannelLocked(context, target.id)
                        }
                    }
                    ActionDialogRow(
                        icon = Icons.Outlined.PushPin,
                        label = if (isPinned) "Unpin Chat" else "Pin Chat"
                    ) {
                        channelActionTarget = null
                        onTogglePinChannel(target)
                    }
                    ActionDialogRow(
                        icon = if (isMuted) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                        label = if (isMuted) "Unmute Notifications" else "Mute Notifications"
                    ) {
                        channelActionTarget = null
                        onToggleMuteChannel(target)
                    }
                    ActionDialogRow(
                        icon = if (isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        label = if (isFav) "Remove from Favourites" else "Add to Favourites"
                    ) {
                        channelActionTarget = null
                        onToggleFavouriteChannel(target)
                    }
                    ActionDialogRow(
                        icon = Icons.Outlined.Archive,
                        label = if (isArchived) "Unarchive Chat" else "Archive Chat"
                    ) {
                        channelActionTarget = null
                        onToggleArchiveChannel(target)
                    }
                    ActionDialogRow(
                        icon = if (hasUnread) Icons.Outlined.MarkChatRead else Icons.Outlined.MarkChatUnread,
                        label = if (hasUnread) "Mark as Read" else "Mark as Unread"
                    ) {
                        channelActionTarget = null
                        onMarkReadUnread(target, !hasUnread)
                    }
                }
            }
        }
    }

    if (showPinSetupDialog) {
        PinUnlockDialog(
            title = if (prefs.hasSecretCode) "Change Secret PIN" else "Set Secret Code",
            subtitle = "Use this PIN to hide and unlock protected conversations.",
            confirmSetupMode = true,
            onVerified = {
                pendingPinSetupAfterLockTarget?.let { ch ->
                    ChatSettingsStore.toggleChannelLocked(context, ch.id)
                }
                pendingPinSetupAfterLockTarget = null
                if (pendingPinSetupSwitchLockedTab) {
                    lockedTabUnlocked = true
                    onSelectFilterTab(ChatFilterTab.LOCKED)
                    pendingPinSetupSwitchLockedTab = false
                }
                showPinSetupDialog = false
            },
            onDismiss = {
                pendingPinSetupAfterLockTarget = null
                pendingPinSetupSwitchLockedTab = false
                showPinSetupDialog = false
            }
        )
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
private fun ActionDialogRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = ObsidianCharcoal, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ObsidianCharcoal)
    }
}
