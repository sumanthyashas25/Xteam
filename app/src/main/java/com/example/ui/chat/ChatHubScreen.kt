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

    var showSearchBar by remember { mutableStateOf(false) }
    var showNewActionOverlay by remember { mutableStateOf(false) }
    var showHubOverflowMenu by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var pinUnlockTarget by remember { mutableStateOf<ChatChannel?>(null) }
    var pinUnlockCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var channelActionTarget by remember { mutableStateOf<ChatChannel?>(null) }
    var deleteChannelTarget by remember { mutableStateOf<ChatChannel?>(null) }

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

    // Main visible group/project/saved/announcement channels
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
                filterTab == ChatFilterTab.UNREAD -> ch.unreadCountFor(member.uid) > 0 && !isArchived && !isLocked
                filterTab == ChatFilterTab.GROUPS -> (ch.channelTypeEnum() == ChatChannelType.GROUP || ch.channelTypeEnum() == ChatChannelType.GENERAL || ch.channelTypeEnum() == ChatChannelType.DEPARTMENT || ch.channelTypeEnum() == ChatChannelType.PROJECT || ch.channelTypeEnum() == ChatChannelType.ANNOUNCEMENT) && !isArchived && !isLocked
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
                filterTab == ChatFilterTab.UNREAD -> (ch?.unreadCountFor(member.uid) ?: 0) > 0 && !isArchived && !isLocked
                filterTab == ChatFilterTab.GROUPS -> false
                filterTab == ChatFilterTab.FAVOURITES -> ch?.isFavouriteFor(member.uid) == true && !isArchived
                filterTab == ChatFilterTab.ARCHIVED -> isArchived
                filterTab == ChatFilterTab.LOCKED -> isLocked
                else -> !isArchived && !isLocked
            }
            matchesQuery && matchesTab
        }.sortedByDescending { directChannelsByPeer[it.uid]?.lastMessageAtMillis ?: 0L }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureWhite)
            .animatedChatGlowBackdrop(isThread = false, wallpaperId = prefs.globalWallpaperId)
            .testTag("chat_hub_screen")
    ) {
        // Main Mengrobol-style Chat Hub Content (blurred smoothly when "+ New Chat" menu opens)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .then(if (showNewActionOverlay) Modifier.blur(12.dp).alpha(0.6f) else Modifier)
        ) {
            // Clean Header Row matching Mengrobol reference: Back | Chats | Search & Overflow
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("chat_hub_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ObsidianCharcoal)
                    }
                    Text(
                        text = "Chats",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = ObsidianCharcoal
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showSearchBar = !showSearchBar },
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("chat_search_button")
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search chats", tint = ObsidianCharcoal)
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("chat_hub_close_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close chat", tint = ObsidianCharcoal)
                    }
                    Box {
                        IconButton(
                            onClick = { showHubOverflowMenu = true },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Chat options", tint = ObsidianCharcoal)
                        }
                        DropdownMenu(
                            expanded = showHubOverflowMenu,
                            onDismissRequest = { showHubOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.Bookmark, null) },
                                text = { Text("Message Yourself (Saved)") },
                                onClick = {
                                    showHubOverflowMenu = false
                                    onOpenSavedNotes()
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.GroupAdd, null) },
                                text = { Text("New Group") },
                                onClick = {
                                    showHubOverflowMenu = false
                                    onOpenCreateGroup()
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.QrCode, null) },
                                text = { Text("Join via Invite Link") },
                                onClick = {
                                    showHubOverflowMenu = false
                                    onOpenJoinByInvite()
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.Tune, null) },
                                text = { Text("Chat Settings & Lock") },
                                onClick = {
                                    showHubOverflowMenu = false
                                    showSettingsSheet = true
                                }
                            )
                        }
                    }
                }
            }

            // Expandable Search Box
            AnimatedVisibility(
                visible = showSearchBar,
                enter = fadeIn(tween(180)) + expandVertically(tween(200)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(180))
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search chats, people or messages…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = PureWhite.copy(alpha = 0.88f),
                        unfocusedContainerColor = PureWhite.copy(alpha = 0.88f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            AnimatedVisibility(visible = !openingThreadLabel.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = ObsidianCharcoal,
                        modifier = Modifier.testTag("chat_opening_indicator")
                    ) {
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

            // Scrollable Body (keeps all test nodes in tree reliably)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Status / Stories Strip matching reference layout
                StatusAvatarBar(
                    myMember = member,
                    statuses = statuses,
                    colleagues = colleagues,
                    onAddStatus = onOpenCreateStatus,
                    onOpenStatusesForOwner = onOpenStatusViewer
                )

                // "Chats" subheader + "..." + filter chips
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Conversations",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = ObsidianCharcoal
                        )
                        IconButton(onClick = { showSettingsSheet = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.MoreHoriz, contentDescription = "Filter & privacy settings", tint = ObsidianCharcoal)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                onClick = {
                                    onSelectCustomList(if (selectedCustomList == customList) null else customList)
                                },
                                label = { Text(customList, fontSize = 12.sp) }
                            )
                        }
                    }
                }

                // 1:1 Colleague Rows (Direct Chats) — clean Mengrobol row style
                if (filteredColleagues.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .testTag("chat_dm_section"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
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
                                if (ms > 0L) SimpleDateFormat("hh:mm", Locale.getDefault()).format(Date(ms)) else ""
                            }

                            Surface(
                                onClick = {
                                    if (dmChannel != null) {
                                        handleProtectedOpen(dmChannel) { onOpenDirectChat(person) }
                                    } else {
                                        onOpenDirectChat(person)
                                    }
                                },
                                shape = RoundedCornerShape(18.dp),
                                color = PureWhite.copy(alpha = 0.78f),
                                border = BorderStroke(1.dp, PureWhite),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dm_person_${person.uid}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
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

                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                text = formatTitleCase(person.fullName.ifBlank { "Member" }),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = ObsidianCharcoal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (isPinned) Icon(Icons.Default.PushPin, null, tint = VioletPrimary, modifier = Modifier.size(12.dp))
                                            if (isMuted) Icon(Icons.Default.VolumeOff, null, tint = StudioTextSecondary, modifier = Modifier.size(12.dp))
                                            if (isLocked) Icon(Icons.Default.Lock, null, tint = ObsidianCharcoal, modifier = Modifier.size(12.dp))
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (dmChannel != null && dmChannel.lastSenderUid == member.uid) {
                                                DeliveryStatusIcon(status = dmChannel.lastMessageStatus, modifier = Modifier.size(14.dp))
                                            }
                                            Text(
                                                text = dmChannel?.lastMessagePreview?.takeIf { it.isNotBlank() }
                                                    ?: formatTitleCase(person.jobRoleName.ifBlank { person.departmentName }),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = StudioTextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (timeStr.isNotBlank()) {
                                            Text(timeStr, style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (unread > 0) {
                                                Surface(shape = CircleShape, color = Color(0xFF38BDF8)) {
                                                    Text(
                                                        text = unread.toString(),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = PureWhite,
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            if (dmChannel != null) {
                                                IconButton(onClick = { channelActionTarget = dmChannel }, modifier = Modifier.size(24.dp)) {
                                                    Icon(Icons.Default.MoreVert, contentDescription = "Chat actions", tint = StudioTextSecondary, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Group / Project / Announcements / Saved Conversations List Rows
                if (filteredNonDirectChannels.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        filteredNonDirectChannels.forEach { ch ->
                            val unread = ch.unreadCountFor(member.uid)
                            val isMuted = ch.isMutedFor(member.uid)
                            val isPinned = ch.isPinnedFor(member.uid)
                            val isLocked = ch.id in prefs.lockedChannelIds
                            val isSaved = ch.isSelfNotes(member.uid)
                            val timeStr = remember(ch.lastMessageAtMillis) {
                                if (ch.lastMessageAtMillis > 0L) SimpleDateFormat("hh:mm", Locale.getDefault()).format(Date(ch.lastMessageAtMillis)) else ""
                            }

                            Surface(
                                onClick = { handleProtectedOpen(ch) { onSelectChannel(ch) } },
                                shape = RoundedCornerShape(18.dp),
                                color = PureWhite.copy(alpha = 0.78f),
                                border = BorderStroke(1.dp, PureWhite),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("chat_row_${ch.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
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

                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                text = if (isSaved) "Message Yourself" else formatTitleCase(ch.title),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = ObsidianCharcoal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (isPinned) Icon(Icons.Default.PushPin, null, tint = VioletPrimary, modifier = Modifier.size(12.dp))
                                            if (isMuted) Icon(Icons.Default.VolumeOff, null, tint = StudioTextSecondary, modifier = Modifier.size(12.dp))
                                            if (isLocked) Icon(Icons.Default.Lock, null, tint = ObsidianCharcoal, modifier = Modifier.size(12.dp))
                                        }
                                        Text(
                                            text = ch.lastMessagePreview.ifBlank { ch.topic.ifBlank { ch.channelTypeEnum().label } },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = StudioTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (timeStr.isNotBlank()) {
                                            Text(timeStr, style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (unread > 0) {
                                                Surface(shape = CircleShape, color = Color(0xFF38BDF8)) {
                                                    Text(
                                                        text = unread.toString(),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = PureWhite,
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            IconButton(onClick = { channelActionTarget = ch }, modifier = Modifier.size(24.dp)) {
                                                Icon(Icons.Default.MoreVert, contentDescription = "Channel actions", tint = StudioTextSecondary, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Department Quick Chats
                if (departments.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .testTag("chat_departments_section"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Department Chats", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = StudioTextSecondary)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            departments.forEach { dept ->
                                Surface(
                                    onClick = { onOpenDepartmentChat(dept) },
                                    shape = RoundedCornerShape(999.dp),
                                    color = KpiLavenderBg.copy(alpha = 0.92f),
                                    border = BorderStroke(1.dp, VioletPrimary.copy(alpha = 0.28f)),
                                    modifier = Modifier
                                        .height(38.dp)
                                        .testTag("dept_chat_${dept.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Filled.Groups, contentDescription = null, tint = VioletPrimary, modifier = Modifier.size(16.dp))
                                        Text(formatTitleCase(dept.name), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // Team Threads Strip (explicitly excludes DIRECT channels per test contract)
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .testTag("chat_threads_section"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Team Threads", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = StudioTextSecondary)
                        TextButton(
                            onClick = onCreateNewChannelClick,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.testTag("create_channel_button")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = ObsidianCharcoal)
                            Spacer(Modifier.width(4.dp))
                            Text("New Channel", color = ObsidianCharcoal, fontWeight = FontWeight.Bold)
                        }
                    }

                    val displayChannels = remember(channels, activeChannel) {
                        val filtered = channels.filter { it.channelTypeEnum() != ChatChannelType.DIRECT }
                        if (filtered.isEmpty()) listOf(activeChannel) else filtered
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        displayChannels.forEach { ch ->
                            Surface(
                                onClick = { handleProtectedOpen(ch) { onSelectChannel(ch) } },
                                shape = RoundedCornerShape(999.dp),
                                color = PureWhite.copy(alpha = 0.88f),
                                border = BorderStroke(1.dp, SubtleOutline),
                                modifier = Modifier
                                    .height(38.dp)
                                    .testTag("channel_chip_${ch.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("#${ch.title}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Quick Send Pill (keeps quick compose seamless + preserves chat_hub_send_button)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (stagedAttachment != null || isProcessingFile) {
                    Surface(shape = RoundedCornerShape(12.dp), color = PureWhite.copy(alpha = 0.92f), border = BorderStroke(1.dp, SubtleOutline)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isProcessingFile) "Preparing file…" else "${stagedAttachment?.fileName} (${ChatFileManager.formatFileSize(stagedAttachment?.sizeBytes ?: 0L)})",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (stagedAttachment != null) {
                                IconButton(onClick = onClearAttachment, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove attachment", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = PureWhite.copy(alpha = 0.94f),
                    border = BorderStroke(1.dp, PureWhite),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(onClick = onPickImages, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Outlined.Image, contentDescription = "Attach image", tint = ObsidianCharcoal, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onPickFiles, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Outlined.Description, contentDescription = "Attach file", tint = ObsidianCharcoal, modifier = Modifier.size(18.dp))
                        }
                        BasicTextField(
                            value = composerText,
                            onValueChange = onComposerTextChange,
                            cursorBrush = SolidColor(ObsidianCharcoal),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = ObsidianCharcoal),
                            decorationBox = { inner ->
                                if (composerText.isEmpty()) {
                                    Text("Quick message to #${activeChannel.title}…", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                inner()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        val canSend = composerText.isNotBlank() || stagedAttachment != null
                        Surface(
                            onClick = { if (canSend) onSendQuickCompose() },
                            shape = CircleShape,
                            color = if (canSend) ObsidianCharcoal else MutedSurface,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("chat_hub_send_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ArrowUpward,
                                    contentDescription = "Send quick message",
                                    tint = if (canSend) PureWhite else StudioTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Floating Dock matching Mengrobol UI: Home | [+ New Chat] / [Cancel] | Saved/Settings
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = PureWhite.copy(alpha = 0.94f),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Home, contentDescription = "Home", tint = ObsidianCharcoal)
                    }

                    Surface(
                        onClick = { showNewActionOverlay = !showNewActionOverlay },
                        shape = RoundedCornerShape(999.dp),
                        color = ObsidianCharcoal,
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("chat_new_action_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (!showNewActionOverlay) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = PureWhite, modifier = Modifier.size(18.dp))
                            }
                            Text(
                                text = if (showNewActionOverlay) "Cancel" else "New Chat",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = PureWhite
                            )
                        }
                    }

                    IconButton(onClick = { showSettingsSheet = true }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.PersonOutline, contentDescription = "Chat settings", tint = ObsidianCharcoal)
                    }
                }
            }
        }

        // Blurred Floating Action Sheet Overlay matching Mengrobol right-hand screen
        if (showNewActionOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.25f))
                    .clickable { showNewActionOverlay = false }
            ) {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = PureWhite,
                    shadowElevation = 14.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 24.dp, end = 24.dp, bottom = 88.dp)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .fillMaxWidth()
                        .clickable(enabled = false) {}
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
                        HorizontalDivider(color = SubtleOutline)
                        OverlayMenuRow(
                            icon = Icons.Outlined.BookmarkBorder,
                            title = "Message Yourself",
                            subtitle = "Save personal notes, links, and files",
                            onClick = {
                                showNewActionOverlay = false
                                onOpenSavedNotes()
                            }
                        )
                        HorizontalDivider(color = SubtleOutline)
                        OverlayMenuRow(
                            icon = Icons.Outlined.Groups,
                            title = "New Community / Group",
                            subtitle = "Create a group with admins, polls & invites",
                            onClick = {
                                showNewActionOverlay = false
                                onOpenCreateGroup()
                            }
                        )
                        HorizontalDivider(color = SubtleOutline)
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

    // Channel Quick Actions Sheet (Pin, Mute, Archive, Favourite, Lock, Read/Unread, Delete)
    channelActionTarget?.let { ch ->
        ModalBottomSheet(onDismissRequest = { channelActionTarget = null }, containerColor = PureWhite) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(formatTitleCase(ch.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.PushPin, null) },
                    text = { Text(if (ch.isPinnedFor(member.uid)) "Unpin Chat" else "Pin Chat") },
                    onClick = { onTogglePinChannel(ch); channelActionTarget = null }
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(if (ch.isMutedFor(member.uid)) Icons.Default.VolumeUp else Icons.Default.VolumeOff, null) },
                    text = { Text(if (ch.isMutedFor(member.uid)) "Unmute Notifications" else "Mute Notifications") },
                    onClick = { onToggleMuteChannel(ch); channelActionTarget = null }
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Favorite, null) },
                    text = { Text(if (ch.isFavouriteFor(member.uid)) "Remove from Favourites" else "Add to Favourites") },
                    onClick = { onToggleFavouriteChannel(ch); channelActionTarget = null }
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Archive, null) },
                    text = { Text(if (ch.isArchivedFor(member.uid)) "Unarchive Chat" else "Archive Chat") },
                    onClick = { onToggleArchiveChannel(ch); channelActionTarget = null }
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.MarkChatRead, null) },
                    text = { Text(if (ch.unreadCountFor(member.uid) > 0) "Mark as Read" else "Mark as Unread") },
                    onClick = { onMarkReadUnread(ch, ch.unreadCountFor(member.uid) == 0); channelActionTarget = null }
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    text = { Text(if (ch.id in prefs.lockedChannelIds) "Unlock Chat" else "Lock Chat") },
                    onClick = {
                        if (!prefs.hasSecretCode) {
                            showSettingsSheet = true
                        } else {
                            ChatSettingsStore.toggleChannelLocked(context, ch.id)
                        }
                        channelActionTarget = null
                    }
                )
                if (isSuperAdmin && ch.id != "ch_general") {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.DeleteForever, null, tint = CrimsonBlocked) },
                        text = { Text("Delete Conversation", color = CrimsonBlocked, fontWeight = FontWeight.Bold) },
                        onClick = { deleteChannelTarget = ch; channelActionTarget = null }
                    )
                }
            }
        }
    }

    deleteChannelTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteChannelTarget = null },
            title = { Text("Delete ${target.title}?", fontWeight = FontWeight.Bold) },
            text = { Text("Removes this conversation and its messages from the workspace.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteChannel(target.id)
                        deleteChannelTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteChannelTarget = null }) { Text("Cancel") } }
        )
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
        Surface(shape = CircleShape, color = MutedSurface, modifier = Modifier.size(40.dp)) {
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
