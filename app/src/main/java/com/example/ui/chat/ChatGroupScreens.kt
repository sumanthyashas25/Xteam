package com.example.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.chat.ChatFileManager
import com.example.chat.ChatQrGenerator
import com.example.chat.ChatSettingsStore
import com.example.chat.ChatUserPreferences
import com.example.model.*
import com.example.ui.components.StudioAvatar
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun CreateGroupSheet(
    colleagues: List<Member>,
    onCreateGroup: (
        title: String,
        description: String,
        memberUids: List<String>,
        photoBase64: String,
        announcementOnly: Boolean,
        requireApproval: Boolean
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var photoBase64 by remember { mutableStateOf("") }
    var announcementOnly by remember { mutableStateOf(false) }
    var requireApproval by remember { mutableStateOf(false) }
    val selectedUids = remember { mutableStateListOf<String>() }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                ChatFileManager.prepareProfilePhotoBase64(context, uri).onSuccess { photoBase64 = it }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = PureWhite,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("New Group / Community", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MutedSurface)
                            .clickable {
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (photoBase64.isNotBlank()) {
                            StudioAvatar(name = title.ifBlank { "G" }, size = 56.dp, borderColor = PureWhite, photoBase64 = photoBase64)
                        } else {
                            Icon(Icons.Default.AddAPhoto, contentDescription = "Group icon", tint = VioletPrimary)
                        }
                    }
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it; errorMsg = null },
                        label = { Text("Group Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Group Description") },
                    placeholder = { Text("Purpose, guidelines, or campaign brief") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Surface(shape = RoundedCornerShape(14.dp), color = MutedSurface, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Announcements Only (Admin post)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Switch(checked = announcementOnly, onCheckedChange = { announcementOnly = it })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Approve New Members", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Switch(checked = requireApproval, onCheckedChange = { requireApproval = it })
                        }
                    }
                }

                Text("Add Members (${selectedUids.size} selected)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = StudioTextSecondary)
                colleagues.forEach { person ->
                    val checked = person.uid in selectedUids
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                if (checked) selectedUids.remove(person.uid) else selectedUids.add(person.uid)
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(checked = checked, onCheckedChange = {
                            if (checked) selectedUids.remove(person.uid) else selectedUids.add(person.uid)
                        })
                        StudioAvatar(name = person.fullName, size = 32.dp, borderColor = PureWhite, photoBase64 = person.photoBase64)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(formatTitleCase(person.fullName), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(person.jobRoleName.ifBlank { person.departmentName }, style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                        }
                    }
                }

                if (!errorMsg.isNullOrBlank()) {
                    Text(errorMsg.orEmpty(), color = CrimsonBlocked, style = MaterialTheme.typography.labelSmall)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.trim().isBlank()) {
                                errorMsg = "Group name is required."
                            } else {
                                onCreateGroup(title.trim(), description.trim(), selectedUids.toList(), photoBase64, announcementOnly, requireApproval)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                    ) {
                        Text("Create Group")
                    }
                }
            }
        }
    }
}

@Composable
fun JoinInviteLinkDialog(
    onJoin: (code: String) -> Unit,
    onDismiss: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = PureWhite, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Join Group by Invite", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Invite Link or Code") },
                    placeholder = { Text("https://xmax.app/join/AB12CD34 or AB12CD34") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { if (code.trim().isNotBlank()) onJoin(code.trim()) },
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                    ) {
                        Text("Join")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedConversationInfoSheet(
    channel: ChatChannel,
    messages: List<ChatMessage>,
    allMembers: List<Member>,
    myUid: String,
    isSuperAdmin: Boolean,
    prefs: ChatUserPreferences,
    onUpdateInfo: (title: String, desc: String, photoBase64: String?) -> Unit,
    onUpdatePermissions: (onlyAdminsMsg: Boolean, onlyAdminsEdit: Boolean, requireApproval: Boolean) -> Unit,
    onUpdateDisappearing: (Long) -> Unit,
    onMuteToggle: () -> Unit,
    onPinToggle: () -> Unit,
    onArchiveToggle: () -> Unit,
    onFavouriteToggle: () -> Unit,
    onLockToggle: () -> Unit,
    onResetInviteLink: () -> Unit,
    onApprovePending: (String) -> Unit,
    onDeclinePending: (String) -> Unit,
    onAddMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onToggleAdmin: (String) -> Unit,
    onClearChat: () -> Unit,
    onExitGroup: () -> Unit,
    onReportGroup: () -> Unit,
    onOpenAttachment: (ChatMessage) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDirect = channel.channelTypeEnum() == ChatChannelType.DIRECT || channel.isSelfNotes(myUid)
    val isMuted = channel.isMutedFor(myUid)
    val isPinned = channel.isPinnedFor(myUid)
    val isArchived = channel.isArchivedFor(myUid)
    val isFav = channel.isFavouriteFor(myUid)
    val isLocked = channel.id in prefs.lockedChannelIds
    val isChannelAdmin = channel.isChannelAdmin(myUid, isSuperAdmin)
    val canEditInfo = isChannelAdmin || !channel.onlyAdminsCanEditInfo

    var showEditInfoDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showAddMemberPicker by remember { mutableStateOf(false) }
    var sharedTab by remember { mutableStateOf(0) } // 0=Media, 1=Docs, 2=Links

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && canEditInfo) {
            scope.launch {
                ChatFileManager.prepareProfilePhotoBase64(context, uri).onSuccess { b64 ->
                    onUpdateInfo(channel.title, channel.description.ifBlank { channel.topic }, b64)
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        contentColor = ObsidianCharcoal
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier.clickable(enabled = !isDirect && canEditInfo) {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            ) {
                StudioAvatar(
                    name = channel.title,
                    size = 76.dp,
                    borderColor = PureWhite,
                    photoBase64 = channel.photoBase64
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = formatTitleCase(channel.title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                if (!isDirect && canEditInfo) {
                    IconButton(onClick = { showEditInfoDialog = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit group info", modifier = Modifier.size(16.dp))
                    }
                }
            }

            Text(
                text = channel.description.ifBlank { channel.topic }.ifBlank { "Workspace conversation" },
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextSecondary,
                textAlign = TextAlign.Center
            )

            // Quick toggles row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = isMuted,
                    onClick = onMuteToggle,
                    label = { Text(if (isMuted) "Muted" else "Mute") },
                    leadingIcon = { Icon(if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, null, Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = isPinned,
                    onClick = onPinToggle,
                    label = { Text(if (isPinned) "Pinned" else "Pin") },
                    leadingIcon = { Icon(Icons.Default.PushPin, null, Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = isFav,
                    onClick = onFavouriteToggle,
                    label = { Text(if (isFav) "Favourite" else "Fav") },
                    leadingIcon = { Icon(Icons.Default.Favorite, null, Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = isArchived,
                    onClick = onArchiveToggle,
                    label = { Text(if (isArchived) "Archived" else "Archive") },
                    leadingIcon = { Icon(Icons.Default.Archive, null, Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = isLocked,
                    onClick = onLockToggle,
                    label = { Text(if (isLocked) "Locked" else "Lock") },
                    leadingIcon = { Icon(Icons.Default.Lock, null, Modifier.size(16.dp)) }
                )
            }

            // Custom Lists tagging
            if (prefs.customLists.isNotEmpty()) {
                val activeLists = prefs.channelListsMap[channel.id].orEmpty()
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Custom Lists", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = StudioTextSecondary)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        prefs.customLists.forEach { listName ->
                            FilterChip(
                                selected = listName in activeLists,
                                onClick = { ChatSettingsStore.toggleChannelInCustomList(context, channel.id, listName) },
                                label = { Text(listName) }
                            )
                        }
                    }
                }
            }

            // Disappearing Messages Timer
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Disappearing Messages", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = StudioTextSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0L to "Off", 86_400L to "24 Hours", 604_800L to "7 Days", 7_776_000L to "90 Days").forEach { (sec, label) ->
                        FilterChip(
                            selected = channel.disappearingSeconds == sec,
                            onClick = { onUpdateDisappearing(sec) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // Thread Wallpaper override
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val currentWp = prefs.channelWallpapers[channel.id] ?: "default"
                Text("Chat Wallpaper", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = StudioTextSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = currentWp == "default",
                        onClick = { ChatSettingsStore.setWallpaper(context, "default", channel.id) },
                        label = { Text("Default") }
                    )
                    ChatVisualCatalog.wallpapers.forEach { wp ->
                        FilterChip(
                            selected = currentWp == wp.id,
                            onClick = { ChatSettingsStore.setWallpaper(context, wp.id, channel.id) },
                            label = { Text(wp.label) }
                        )
                    }
                }
            }

            // Group Controls: Permissions, Invite Link + QR, Pending Approvals, Members
            if (!isDirect) {
                if (isChannelAdmin) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MutedSurface, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Group Permissions", fontWeight = FontWeight.Bold)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Only admins can send messages", style = MaterialTheme.typography.bodySmall)
                                Switch(
                                    checked = channel.onlyAdminsCanMessage,
                                    onCheckedChange = { onUpdatePermissions(it, channel.onlyAdminsCanEditInfo, channel.requireAdminApproval) }
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Only admins edit group info", style = MaterialTheme.typography.bodySmall)
                                Switch(
                                    checked = channel.onlyAdminsCanEditInfo,
                                    onCheckedChange = { onUpdatePermissions(channel.onlyAdminsCanMessage, it, channel.requireAdminApproval) }
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Admin approval for new members", style = MaterialTheme.typography.bodySmall)
                                Switch(
                                    checked = channel.requireAdminApproval,
                                    onCheckedChange = { onUpdatePermissions(channel.onlyAdminsCanMessage, channel.onlyAdminsCanEditInfo, it) }
                                )
                            }
                        }
                    }

                    // Invite link & QR code
                    val inviteCode = channel.inviteCode.ifBlank { channel.id.take(8).uppercase() }
                    val inviteUrl = "https://xmax.app/join/$inviteCode"
                    Surface(shape = RoundedCornerShape(16.dp), color = VioletSoftBg, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Group Invite Link", fontWeight = FontWeight.Bold, color = VioletPrimary)
                            Text(inviteUrl, style = MaterialTheme.typography.bodySmall, color = ObsidianCharcoal)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("Group Invite Link", inviteUrl))
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Copy", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = { showQrDialog = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("QR Code", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = onResetInviteLink,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Reset", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // Pending Member Approvals
                    if (channel.pendingMemberUids.isNotEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Pending Join Requests (${channel.pendingMemberUids.size})", fontWeight = FontWeight.Bold, color = CrimsonBlocked)
                            channel.pendingMemberUids.forEach { pUid ->
                                val pm = allMembers.firstOrNull { it.uid == pUid }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(formatTitleCase(pm?.fullName ?: pUid), fontWeight = FontWeight.SemiBold)
                                    Row {
                                        TextButton(onClick = { onApprovePending(pUid) }) { Text("Approve", color = EmeraldApproved) }
                                        TextButton(onClick = { onDeclinePending(pUid) }) { Text("Decline", color = CrimsonBlocked) }
                                    }
                                }
                            }
                        }
                    }
                }

                // Members List + Add Member
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Members (${channel.participantUids.size.coerceAtLeast(1)})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        if (isChannelAdmin) {
                            TextButton(onClick = { showAddMemberPicker = !showAddMemberPicker }) {
                                Text(if (showAddMemberPicker) "Done" else "+ Add Member")
                            }
                        }
                    }
                    if (showAddMemberPicker && isChannelAdmin) {
                        val available = allMembers.filter { it.uid !in channel.participantUids && it.active }
                        available.forEach { cand ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAddMember(cand.uid) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(formatTitleCase(cand.fullName))
                                Text("+ Add", color = VioletPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    channel.participantUids.forEach { uid ->
                        val mem = allMembers.firstOrNull { it.uid == uid }
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
                                    name = mem?.fullName ?: "Member",
                                    size = 34.dp,
                                    borderColor = PureWhite,
                                    photoBase64 = mem?.photoBase64.orEmpty()
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(formatTitleCase(mem?.fullName ?: uid) + if (uid == myUid) " (You)" else "", fontWeight = FontWeight.Bold)
                                    Text(if (isAdmin) "Admin" else "Member", style = MaterialTheme.typography.labelSmall, color = if (isAdmin) EmeraldApproved else StudioTextSecondary)
                                }
                                if (isChannelAdmin && uid != myUid) {
                                    TextButton(onClick = { onToggleAdmin(uid) }) {
                                        Text(if (isAdmin) "Dismiss" else "Admin", fontSize = 11.sp)
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

            // Shared Media / Documents / Links Browser
            val sharedMedia = remember(messages) {
                messages.filter { !it.isDeletedForEveryone && (it.attachmentTypeEnum() == ChatAttachmentType.IMAGE || it.attachmentTypeEnum() == ChatAttachmentType.VIDEO) }
            }
            val sharedDocs = remember(messages) {
                messages.filter { !it.isDeletedForEveryone && (it.attachmentTypeEnum() == ChatAttachmentType.FILE || it.attachmentTypeEnum() == ChatAttachmentType.AUDIO) }
            }
            val sharedLinks = remember(messages) {
                messages.filter { !it.isDeletedForEveryone && (it.linkUrl.isNotBlank() || ChatFileManager.extractHttpsUrls(it.body).isNotEmpty()) }
            }

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shared Media, Docs & Links", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                TabRow(selectedTabIndex = sharedTab, containerColor = MutedSurface, contentColor = ObsidianCharcoal) {
                    Tab(selected = sharedTab == 0, onClick = { sharedTab = 0 }, text = { Text("Media (${sharedMedia.size})") })
                    Tab(selected = sharedTab == 1, onClick = { sharedTab = 1 }, text = { Text("Docs (${sharedDocs.size})") })
                    Tab(selected = sharedTab == 2, onClick = { sharedTab = 2 }, text = { Text("Links (${sharedLinks.size})") })
                }
                when (sharedTab) {
                    0 -> if (sharedMedia.isEmpty()) Text("No photos or videos shared yet.", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                    else sharedMedia.take(10).forEach { m ->
                        Surface(onClick = { onOpenAttachment(m) }, shape = RoundedCornerShape(10.dp), color = MutedSurface, modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(m.attachmentName.ifBlank { m.kindEnum().label }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(ChatFileManager.formatFileSize(m.attachmentSizeBytes), style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                            }
                        }
                    }
                    1 -> if (sharedDocs.isEmpty()) Text("No documents shared yet.", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                    else sharedDocs.take(10).forEach { m ->
                        Surface(onClick = { onOpenAttachment(m) }, shape = RoundedCornerShape(10.dp), color = MutedSurface, modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(m.attachmentName.ifBlank { "Document" }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(ChatFileManager.formatFileSize(m.attachmentSizeBytes), style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                            }
                        }
                    }
                    else -> if (sharedLinks.isEmpty()) Text("No shared links yet.", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                    else sharedLinks.take(10).forEach { m ->
                        val url = m.linkUrl.ifBlank { ChatFileManager.extractHttpsUrls(m.body).firstOrNull().orEmpty() }
                        Surface(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MutedSurface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(url, style = MaterialTheme.typography.bodySmall, color = Color(0xFF0284C7), modifier = Modifier.padding(10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // Clear, Report, Exit Actions
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onClearChat,
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked.copy(alpha = 0.12f), contentColor = CrimsonBlocked),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear Chat", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Button(
                    onClick = onReportGroup,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFEF3C7), contentColor = Color(0xFFD97706)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Report", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                if (!isDirect && channel.id != "ch_general") {
                    Button(
                        onClick = onExitGroup,
                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Exit Group", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showEditInfoDialog) {
        var editTitle by remember { mutableStateOf(channel.title) }
        var editDesc by remember { mutableStateOf(channel.description.ifBlank { channel.topic }) }
        Dialog(onDismissRequest = { showEditInfoDialog = false }) {
            Surface(shape = RoundedCornerShape(22.dp), color = PureWhite, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Edit Group Info", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    OutlinedTextField(value = editTitle, onValueChange = { editTitle = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = editDesc, onValueChange = { editDesc = it }, label = { Text("Description") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showEditInfoDialog = false }) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onUpdateInfo(editTitle.trim(), editDesc.trim(), null)
                                showEditInfoDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                        ) { Text("Save") }
                    }
                }
            }
        }
    }

    if (showQrDialog) {
        val inviteCode = channel.inviteCode.ifBlank { channel.id.take(8).uppercase() }
        val inviteUrl = "https://xmax.app/join/$inviteCode"
        val qrBmp = remember(inviteUrl) { ChatQrGenerator.generateQrBitmap(inviteUrl, 520) }
        Dialog(onDismissRequest = { showQrDialog = false }) {
            Surface(shape = RoundedCornerShape(24.dp), color = PureWhite, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Scan to Join Group", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    Image(bitmap = qrBmp.asImageBitmap(), contentDescription = "Group QR Code", modifier = Modifier.size(220.dp))
                    Text(inviteUrl, style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                    Button(
                        onClick = { showQrDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                    ) { Text("Done") }
                }
            }
        }
    }
}
