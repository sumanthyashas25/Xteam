package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.FirebaseStartup
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.AgencyViewModel
import com.example.ui.UserProfileState
import com.example.ui.UserViewModel
import com.example.ui.components.*
import com.example.ui.navigation.XMaxNavGraph
import com.example.ui.screens.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

enum class PrimaryTab(val label: String, val icon: ImageVector, val inactiveIcon: ImageVector) {
    HOME("Home", Icons.Filled.Home, Icons.Outlined.Home),
    REQUESTS("Requests", Icons.Filled.Inbox, Icons.Outlined.Inbox),
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
    MY_WORK("My Work", Icons.Filled.TaskAlt, Icons.Outlined.TaskAlt)
}

enum class SecondaryDestination(val label: String, val icon: ImageVector) {
    PROJECTS("Projects", Icons.Filled.Work),
    CHAT("Team Chat", Icons.AutoMirrored.Filled.Chat),
    NOTIFICATIONS("Notifications", Icons.Filled.Notifications),
    CLIENTS("Clients", Icons.Filled.Contacts),
    QUOTATIONS("Quotations", Icons.AutoMirrored.Filled.ReceiptLong),
    CATALOGUE("Catalogue", Icons.Filled.Sell),
    PAYMENTS("Payments", Icons.Filled.Payments),
    TEAM("Team & Permissions", Icons.Filled.Group),
    ORG_ROUTING("Departments, Roles & Routing", Icons.Filled.AccountTree),
    COMPANY_SETTINGS("Company Settings", Icons.Filled.Business),
    ACTIVITY("Activity Log", Icons.Filled.History)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseStartup.ensureInitialized(applicationContext)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        setContent {
            XMaxTheme(darkTheme = false) {
                val vm: AgencyViewModel = viewModel()
                val userVm: UserViewModel = viewModel()
                val state by vm.uiState.collectAsStateWithLifecycle()
                val userProfileState by userVm.userProfileState.collectAsStateWithLifecycle()
                XMaxAppRoot(state = state, userProfileState = userProfileState, viewModel = vm)
            }
        }
    }
}

@Composable
private fun XMaxAppRoot(
    state: AgencyUiState,
    userProfileState: UserProfileState,
    viewModel: AgencyViewModel
) {
    XMaxNavGraph(
        state = state,
        viewModel = viewModel,
        userProfileState = userProfileState,
        dashboardShell = {
            ApprovedWorkspace(state = state, viewModel = viewModel)
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApprovedWorkspace(state: AgencyUiState, viewModel: AgencyViewModel) {
    val context = LocalContext.current
    val member = state.myMember ?: run {
        com.example.ui.navigation.LoadingDestinationScreen(
            label = "Syncing workspace session…",
            userName = state.firebaseUser?.displayName ?: "Alex",
            canSignOut = true,
            onSignOut = { viewModel.signOut(context) }
        )
        return
    }
    val isStaffOnly = member.accessLevelEnum() == AccessLevel.STAFF

    val canSeeCommercial = state.isSuperAdmin || state.isAdmin ||
        listOf(
            AppPermission.MANAGE_CLIENTS,
            AppPermission.CREATE_REQUESTS,
            AppPermission.CREATE_QUOTATIONS,
            AppPermission.ISSUE_QUOTATIONS,
            AppPermission.VIEW_ALL_CLIENTS_AND_QUOTES
        ).any { state.hasPerm(it) }

    val canSeeProjects = state.isSuperAdmin || state.isAdmin ||
        state.hasPerm(AppPermission.ASSIGN_WORK) ||
        state.hasPerm(AppPermission.APPROVE_DELIVERABLES)

    val canCreateQuote = state.hasPerm(AppPermission.CREATE_QUOTATIONS) || state.hasPerm(AppPermission.ISSUE_QUOTATIONS)

    val visibleTabs = remember(isStaffOnly, canSeeCommercial) {
        buildList {
            if (isStaffOnly) {
                add(PrimaryTab.MY_WORK)
                if (canSeeCommercial) add(PrimaryTab.REQUESTS)
                add(PrimaryTab.CHAT)
            } else {
                add(PrimaryTab.HOME)
                add(PrimaryTab.REQUESTS)
                add(PrimaryTab.CHAT)
                add(PrimaryTab.MY_WORK)
            }
        }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(member.uid) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var activeTab by remember(member.uid) {
        mutableStateOf(if (isStaffOnly) PrimaryTab.MY_WORK else PrimaryTab.HOME)
    }
    var secondaryDest by remember { mutableStateOf<SecondaryDestination?>(null) }
    var chatInitialChannelId by remember { mutableStateOf<String?>(null) }
    var quoteBuilderMode by remember { mutableStateOf(false) }
    var editingQuote by remember { mutableStateOf<Quotation?>(null) }
    var quoteRequest by remember { mutableStateOf<ProjectRequest?>(null) }
    var quoteLinkedProject by remember { mutableStateOf<Project?>(null) }
    var activeDeliverable by remember { mutableStateOf<Deliverable?>(null) }
    var showQuickCreateSheet by remember { mutableStateOf(false) }
    var showProfilePhotoDialog by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val snackbarHostState = remember { SnackbarHostState() }
    var activeToastTone by remember { mutableStateOf(ToastTone.INFO) }
    LaunchedEffect(state.statusMessage, state.errorMessage) {
        val err = state.errorMessage
        val ok = state.statusMessage
        when {
            !err.isNullOrBlank() -> {
                activeToastTone = ToastTone.ERROR
                viewModel.clearMessages()
                snackbarHostState.showSnackbar(message = err, duration = SnackbarDuration.Short)
            }
            !ok.isNullOrBlank() -> {
                activeToastTone = ToastTone.SUCCESS
                viewModel.clearMessages()
                snackbarHostState.showSnackbar(message = ok, duration = SnackbarDuration.Short)
            }
        }
    }

    if (quoteBuilderMode) {
        BackHandler { quoteBuilderMode = false }
        QuoteBuilderScreen(
            state = state,
            initialQuote = editingQuote,
            initialRequest = quoteRequest,
            linkedProject = quoteLinkedProject,
            onBack = { quoteBuilderMode = false },
            onQuickSaveClient = { cli, cb -> viewModel.saveClient(cli, cb) },
            onCreateRequest = { cli, title, brief, services, target, mgr, cb ->
                viewModel.createRequest(cli, title, brief, services, target, mgr, cb)
            },
            onSaveOrIssueQuote = { draft, issue, cb -> viewModel.saveOrIssueQuote(draft, issue, cb) },
            onCreateRevision = { prev, cb -> viewModel.createQuoteRevision(prev, cb) },
            onGeneratePdf = { q, share, cb -> viewModel.generateAndSavePdf(context, q, share, cb) },
            onMarkSent = { q -> viewModel.markQuoteSent(q) },
            onMarkClientApproved = { q, note -> viewModel.markQuoteClientApproved(q, note) }
        )
        return
    }

    if (drawerState.isOpen) {
        BackHandler { scope.launch { drawerState.close() } }
    } else if (secondaryDest != null) {
        BackHandler { secondaryDest = null }
    }

    fun openQuote(q: Quotation? = null, req: ProjectRequest? = null, proj: Project? = null) {
        editingQuote = q
        quoteRequest = req
        quoteLinkedProject = proj
        quoteBuilderMode = true
    }

    val permittedSecondary = remember(state.effectivePermissions, member.accessLevel) {
        buildList {
            if (canSeeProjects) add(SecondaryDestination.PROJECTS)
            add(SecondaryDestination.CHAT)
            add(SecondaryDestination.NOTIFICATIONS)
            if (canSeeCommercial) add(SecondaryDestination.CLIENTS)
            if (canSeeCommercial) add(SecondaryDestination.QUOTATIONS)
            if (canSeeCommercial || state.hasPerm(AppPermission.EDIT_CATALOGUE_PRICES)) add(SecondaryDestination.CATALOGUE)
            if (state.hasPerm(AppPermission.VIEW_PAYMENTS) || state.hasPerm(AppPermission.RECORD_PAYMENTS)) add(SecondaryDestination.PAYMENTS)
            if (state.isSuperAdmin || state.isAdmin) add(SecondaryDestination.TEAM)
            if (state.isSuperAdmin) add(SecondaryDestination.ORG_ROUTING)
            if (state.isSuperAdmin) add(SecondaryDestination.COMPANY_SETTINGS)
            if (state.isSuperAdmin || state.isAdmin) add(SecondaryDestination.ACTIVITY)
        }
    }

    val pendingBadgeCount = state.members.count { it.statusEnum() == MembershipStatus.PENDING } +
        state.deliverables.count { it.statusEnum() == DeliverableStatus.BLOCKED }
    val notifBadgeCount = state.unreadNotificationCount +
        (if (state.isSuperAdmin || state.isAdmin) pendingBadgeCount else 0)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AgencyMenuDrawerContent(
                state = state,
                visibleTabs = visibleTabs,
                activeTab = activeTab,
                secondaryDest = secondaryDest,
                permittedSecondary = permittedSecondary,
                canCreateQuote = canCreateQuote,
                onSelectTab = { tab ->
                    secondaryDest = null
                    activeTab = tab
                    scope.launch { drawerState.close() }
                },
                onSelectSecondary = { dest ->
                    if (dest != SecondaryDestination.CHAT) chatInitialChannelId = null
                    secondaryDest = dest
                    scope.launch { drawerState.close() }
                },
                onOpenNewQuote = {
                    scope.launch { drawerState.close() }
                    openQuote()
                },
                onOpenProfilePhoto = {
                    scope.launch { drawerState.close() }
                    showProfilePhotoDialog = true
                },
                onSignOut = {
                    scope.launch { drawerState.close() }
                    viewModel.signOut(context)
                },
                onCloseDrawer = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().pastelAmbientBackdrop()) {
            val isWide = maxWidth >= 600.dp

            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    containerColor = Color.Transparent,
                    snackbarHost = {
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.padding(
                                start = 16.dp,
                                end = 16.dp,
                                bottom = if (!isWide && secondaryDest == null) 96.dp else 16.dp
                            )
                        ) { data ->
                            val toastTitle = when (activeToastTone) {
                                ToastTone.ERROR -> "Action Needed"
                                ToastTone.WARNING -> "Heads Up"
                                ToastTone.INFO -> "Workspace Update"
                                ToastTone.SUCCESS -> "Completed"
                            }
                            com.example.ui.components.XMaxToastBanner(
                                title = toastTitle,
                                message = data.visuals.message,
                                tone = activeToastTone,
                                onDismiss = { data.dismiss() }
                            )
                        }
                    },
                    topBar = {
                        val activeSecondary = secondaryDest
                        if (activeSecondary == SecondaryDestination.CHAT) {
                            // TeamChatScreen provides its own full-bleed New Chat / Thread header
                        } else if (activeSecondary != null) {
                            TopAppBar(
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                                title = {
                                    Text(
                                        formatTitleCase(activeSecondary.label),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = ObsidianCharcoal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { secondaryDest = null }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ObsidianCharcoal)
                                    }
                                },
                                actions = {
                                    SyncFeedbackChip(state.syncFeedback, state.deliverablesPendingSync)
                                    Spacer(Modifier.width(12.dp))
                                }
                            )
                        } else {
                            // Top Welcome Header with uncompressed ExtraBold Title Case name and notifications button
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { scope.launch { drawerState.open() } }
                                        .padding(vertical = 2.dp, horizontal = 2.dp)
                                ) {
                                    StudioAvatar(
                                        name = member.fullName,
                                        photoBase64 = member.photoBase64,
                                        size = 44.dp,
                                        borderColor = PureWhite,
                                        showEditBadge = false,
                                        modifier = Modifier.testTag("header_profile_avatar"),
                                        onClick = { scope.launch { drawerState.open() } }
                                    )
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(1.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Welcome",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                            fontWeight = FontWeight.Medium,
                                            color = StudioTextSecondary,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = formatTitleCase(member.fullName.ifBlank { "Member" }),
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontSize = 16.sp,
                                                lineHeight = 20.sp
                                            ),
                                            fontWeight = FontWeight.ExtraBold,
                                            color = ObsidianCharcoal,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (state.syncFeedback != com.example.ui.SyncFeedback.IDLE || state.deliverablesPendingSync) {
                                        SyncFeedbackChip(state.syncFeedback, state.deliverablesPendingSync)
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Box {
                                        CircleHeaderIconButton(
                                            icon = Icons.Outlined.Notifications,
                                            contentDescription = "Notifications & Sound",
                                            modifier = Modifier.testTag("notifications_button"),
                                            onClick = { secondaryDest = SecondaryDestination.NOTIFICATIONS }
                                        )
                                        if (notifBadgeCount > 0) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(top = 3.dp, end = 3.dp)
                                                    .defaultMinSize(minWidth = 17.dp, minHeight = 17.dp)
                                                    .clip(CircleShape)
                                                    .background(WarmAlarmAmber)
                                                    .padding(horizontal = 4.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = if (notifBadgeCount > 9) "9+" else "$notifBadgeCount",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = PureWhite
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        if (isWide && secondaryDest == null) {
                            NavigationRail(containerColor = PureWhite) {
                                visibleTabs.forEach { tab ->
                                    NavigationRailItem(
                                        selected = activeTab == tab,
                                        onClick = { activeTab = tab },
                                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                                        label = { Text(tab.label) }
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            AnimatedContent(
                                targetState = Pair(secondaryDest, activeTab),
                                transitionSpec = {
                                    (fadeIn(tween(210)) + slideInVertically(tween(220)) { it / 32 })
                                        .togetherWith(fadeOut(tween(140)))
                                },
                                label = "workspace_content_transition"
                            ) { (dest, tab) ->
                                if (dest != null) {
                                    when (dest) {
                                        SecondaryDestination.PROJECTS -> ProjectsScreen(
                                            state = state,
                                            onOpenDeliverable = { activeDeliverable = it },
                                            onAssignDeliverable = { d, m, due -> viewModel.assignDeliverable(d, m, due) },
                                            onOpenAddOnQuote = { proj -> openQuote(proj = proj) },
                                            onToggleDeleteProject = { proj, del -> viewModel.toggleDeleteProject(proj, del) },
                                            onPermanentlyDeleteProject = { proj -> viewModel.permanentlyDeleteProject(proj) },
                                            onCleanupOldDoneProjects = { viewModel.cleanupOldDoneProjects() }
                                        )
                                        SecondaryDestination.CHAT -> TeamChatScreen(
                                            state = state,
                                            initialChannelId = chatInitialChannelId,
                                            observeMessages = { channelId -> viewModel.observeChatMessages(channelId) },
                                            onEnsureGeneralChannel = { viewModel.ensureDefaultGeneralChannel() },
                                            onCreateChannel = { title, topic, type, proj, cb ->
                                                viewModel.createOrOpenChatChannel(title, topic, type, proj, cb)
                                            },
                                            onSendMessage = { chId, body, parent, attachment, cb ->
                                                viewModel.sendChatMessage(chId, body, parent, attachment, cb)
                                            },
                                            onReportMessage = { msg, err -> viewModel.reportChatMessage(msg, err) },
                                            onOpenDirectChat = { targetMember, cb ->
                                                viewModel.openDirectChat(targetMember, cb)
                                            },
                                            onOpenDepartmentChat = { dept, cb ->
                                                viewModel.openDepartmentChat(dept, cb)
                                            },
                                            onDeleteChannel = { channelId, onDone ->
                                                viewModel.deleteChatChannel(channelId, onDone)
                                            },
                                            onDeleteMessage = { channelId, messageId ->
                                                viewModel.deleteChatMessage(channelId, messageId)
                                            },
                                            onCloseChat = {
                                                chatInitialChannelId = null
                                                secondaryDest = null
                                            }
                                        )
                                        SecondaryDestination.NOTIFICATIONS -> NotificationsScreen(
                                            state = state,
                                            onMarkRead = { viewModel.markNotificationRead(it) },
                                            onMarkAllRead = { viewModel.markAllNotificationsRead() },
                                            onToggleSound = { viewModel.toggleNotificationSound(it) },
                                            onTestSound = { viewModel.testNotificationSound() },
                                            onDeleteNotification = { viewModel.deleteNotification(it) },
                                            onOpenChatChannel = { chId ->
                                                chatInitialChannelId = chId
                                                secondaryDest = SecondaryDestination.CHAT
                                            },
                                            onOpenDeliverableById = { delivId ->
                                                val target = state.deliverables.firstOrNull { it.id == delivId }
                                                if (target != null) {
                                                    secondaryDest = null
                                                    activeTab = PrimaryTab.MY_WORK
                                                    activeDeliverable = target
                                                }
                                            }
                                        )
                                        SecondaryDestination.CLIENTS -> ClientsScreen(
                                            state = state,
                                            onSaveClient = { viewModel.saveClient(it) },
                                            onDeleteClient = { viewModel.deleteClient(it) }
                                        )
                                        SecondaryDestination.QUOTATIONS -> QuotationsScreen(state, onOpenQuote = { openQuote(q = it) }, onDeleteQuotation = { viewModel.deleteQuotation(it) })
                                        SecondaryDestination.CATALOGUE -> CatalogueScreen(
                                            state = state,
                                            onSaveItem = { viewModel.saveCatalogueItem(it) },
                                            onToggleArchive = { item, arch -> viewModel.setCatalogueArchived(item, arch) }
                                        )
                                        SecondaryDestination.PAYMENTS -> PaymentsScreen(
                                            state = state,
                                            onRecordPayment = { proj, amt, date, method, ref, note ->
                                                viewModel.recordPayment(proj, amt, date, method, ref, note)
                                            }
                                        )
                                        SecondaryDestination.TEAM -> TeamGovernanceScreen(
                                            state = state,
                                            onInviteMember = { email, lvl, dept, role, sc, scIds, perms ->
                                                viewModel.inviteMember(email, lvl, dept, role, sc, scIds, perms)
                                            },
                                            onConfigureMember = { m, st, lvl, dept, role, sc, scIds, perms ->
                                                viewModel.configureMember(m, st, lvl, dept, role, sc, scIds, perms)
                                            },
                                            onUpdateStaffDelegation = { staff, grants -> viewModel.updateStaffDelegation(staff, grants) }
                                        )
                                        SecondaryDestination.ORG_ROUTING -> OrgAndRoutingScreen(
                                            state = state,
                                            onSaveDepartment = { id, name, d -> viewModel.saveDepartment(id, name, d) },
                                            onSaveJobRole = { id, name, dId -> viewModel.saveJobRole(id, name, dId) },
                                            onRetireDepartment = { ret, repD, repR -> viewModel.retireDepartment(ret, repD, repR) },
                                            onRetireJobRole = { ret, repD, repR -> viewModel.retireJobRole(ret, repD, repR) },
                                            onSaveRoutingRule = { rule -> viewModel.saveRoutingRule(rule) }
                                        )
                                        SecondaryDestination.COMPANY_SETTINGS -> CompanySettingsScreen(
                                            state = state,
                                            onSaveCompany = { viewModel.updateCompanyProfile(it) }
                                        )
                                        SecondaryDestination.ACTIVITY -> ActivityLogScreen(
                                            state = state,
                                            onDeleteLog = { viewModel.deleteActivityLog(it) },
                                            onClearAllLogs = { viewModel.clearAllActivityLogs() }
                                        )
                                    }
                                } else {
                                    when (tab) {
                                        PrimaryTab.HOME -> HomeScreen(
                                            state = state,
                                            onOpenNewQuote = { openQuote() },
                                            onNavigateRequests = { activeTab = PrimaryTab.REQUESTS },
                                            onNavigateProjects = { secondaryDest = SecondaryDestination.PROJECTS },
                                            onNavigateMyWork = { activeTab = PrimaryTab.MY_WORK },
                                            onNavigateTeam = { secondaryDest = SecondaryDestination.TEAM },
                                            onNavigatePayments = { secondaryDest = SecondaryDestination.PAYMENTS },
                                            onNavigateQuotations = { secondaryDest = SecondaryDestination.QUOTATIONS },
                                            onNavigateCompanySettings = { secondaryDest = SecondaryDestination.COMPANY_SETTINGS },
                                            onNavigateOrgRouting = { secondaryDest = SecondaryDestination.ORG_ROUTING },
                                            onOpenMenuPanel = { scope.launch { drawerState.open() } },
                                            onOpenDeliverable = { activeDeliverable = it },
                                            onOpenProjectQuote = { proj -> openQuote(proj = proj) },
                                            onToggleDeleteProject = { proj, del -> viewModel.toggleDeleteProject(proj, del) },
                                            onCleanupOldDoneProjects = { viewModel.cleanupOldDoneProjects() }
                                        )
                                        PrimaryTab.REQUESTS -> RequestsScreen(
                                            state = state,
                                            onOpenQuoteBuilder = { req, q -> openQuote(q = q, req = req) },
                                            onCreateRequest = { cli, t, b, s, d, mgr -> viewModel.createRequest(cli, t, b, s, d, mgr) },
                                            onAcceptRequest = { req, q, drafts -> viewModel.acceptRequest(req, q, drafts) },
                                            onDeclineRequest = { req, note -> viewModel.declineRequest(req, note) }
                                        )
                                        PrimaryTab.CHAT -> TeamChatScreen(
                                            state = state,
                                            initialChannelId = chatInitialChannelId,
                                            observeMessages = { channelId -> viewModel.observeChatMessages(channelId) },
                                            onEnsureGeneralChannel = { viewModel.ensureDefaultGeneralChannel() },
                                            onCreateChannel = { title, topic, type, proj, cb ->
                                                viewModel.createOrOpenChatChannel(title, topic, type, proj, cb)
                                            },
                                            onSendMessage = { chId, body, parent, attachment, cb ->
                                                viewModel.sendChatMessage(chId, body, parent, attachment, cb)
                                            },
                                            onReportMessage = { msg, err -> viewModel.reportChatMessage(msg, err) },
                                            onOpenDirectChat = { targetMember, cb ->
                                                viewModel.openDirectChat(targetMember, cb)
                                            },
                                            onOpenDepartmentChat = { dept, cb ->
                                                viewModel.openDepartmentChat(dept, cb)
                                            },
                                            onDeleteChannel = { channelId, onDone ->
                                                viewModel.deleteChatChannel(channelId, onDone)
                                            },
                                            onDeleteMessage = { channelId, messageId ->
                                                viewModel.deleteChatMessage(channelId, messageId)
                                            },
                                            onCloseChat = {
                                                chatInitialChannelId = null
                                                activeTab = PrimaryTab.HOME
                                            }
                                        )
                                        PrimaryTab.MY_WORK -> MyWorkScreen(
                                            state = state,
                                            onOpenDeliverable = { activeDeliverable = it },
                                            onStartDeliverable = { viewModel.startDeliverableWork(it) },
                                            onClaimDepartmentTask = { viewModel.claimDepartmentDeliverable(it) },
                                            onToggleTimer = { viewModel.toggleDeliverableTimer(it) },
                                            onQuickSubmitReview = { deliv ->
                                                val completedChecklist = deliv.checklist.map { it.copy(done = true) }
                                                viewModel.submitStaffProgress(
                                                    deliverable = deliv,
                                                    status = DeliverableStatus.READY_FOR_REVIEW,
                                                    checklist = completedChecklist,
                                                    note = "Submitted directly for review",
                                                    previewUrl = deliv.previewUrl,
                                                    blockedReason = "",
                                                    helpNeeded = ""
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 5-Slot Floating White Pill Bottom Dock with Centered Jet-Black + FAB
                AnimatedVisibility(
                    visible = !isWide && secondaryDest == null,
                    enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
                    exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { it / 2 },
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    FloatingStudioDock(
                        visibleTabs = visibleTabs,
                        activeTab = activeTab,
                        onSelectTab = { activeTab = it },
                        onQuickCreate = { showQuickCreateSheet = true }
                    )
                }
            }
        }
    }

    if (showQuickCreateSheet) {
        QuickWorkspaceSheet(
            member = member,
            canCreateQuote = canCreateQuote,
            canSeeCommercial = canSeeCommercial,
            canSeeProjects = canSeeProjects,
            isAdmin = state.isSuperAdmin || state.isAdmin,
            canSeePayments = state.hasPerm(AppPermission.VIEW_PAYMENTS) || state.hasPerm(AppPermission.RECORD_PAYMENTS),
            onDismiss = { showQuickCreateSheet = false },
            onNewQuote = {
                showQuickCreateSheet = false
                openQuote()
            },
            onOpenRequests = {
                showQuickCreateSheet = false
                secondaryDest = null
                activeTab = PrimaryTab.REQUESTS
            },
            onOpenProjects = {
                showQuickCreateSheet = false
                secondaryDest = SecondaryDestination.PROJECTS
            },
            onOpenMyWork = {
                showQuickCreateSheet = false
                secondaryDest = null
                activeTab = PrimaryTab.MY_WORK
            },
            onOpenSecondary = { dest ->
                showQuickCreateSheet = false
                if (dest != SecondaryDestination.CHAT) chatInitialChannelId = null
                secondaryDest = dest
            }
        )
    }

    if (showProfilePhotoDialog) {
        ProfileOverviewDialog(
            member = member,
            emailOrPhone = state.firebaseUser?.email ?: state.firebaseUser?.phoneNumber ?: "",
            onPickPhoto = { uri ->
                viewModel.updateMyProfilePhoto(context, uri) { showProfilePhotoDialog = false }
            },
            onSaveCroppedPhoto = { bitmap, zoom, offX, offY, onDone, onError ->
                viewModel.updateMyCroppedProfilePhoto(
                    bitmap = bitmap,
                    zoom = zoom,
                    offsetXFrac = offX,
                    offsetYFrac = offY,
                    onDone = onDone,
                    onError = onError
                )
            },
            onRemovePhoto = {
                viewModel.removeMyProfilePhoto(onDone = { showProfilePhotoDialog = false })
            },
            onDismiss = { showProfilePhotoDialog = false }
        )
    }

    activeDeliverable?.let { d ->
        val fresh = state.deliverables.firstOrNull { it.id == d.id } ?: d
        DeliverableDetailDialog(
            state = state,
            deliverable = fresh,
            updatesFlow = viewModel.observeDeliverableUpdates(fresh.id),
            onSubmitProgress = { deliv, status, checklist, note, url, reason, help ->
                viewModel.submitStaffProgress(deliv, status, checklist, note, url, reason, help)
            },
            onReviewDeliverable = { deliv, action, fb ->
                viewModel.reviewDeliverable(deliv, action, fb)
            },
            onStartWork = { viewModel.startDeliverableWork(it) },
            onToggleTimer = { viewModel.toggleDeliverableTimer(it) },
            onResetTimer = { viewModel.resetDeliverableTimer(it) },
            onDismiss = { activeDeliverable = null }
        )
    }
}

@Composable
private fun CircleHeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = PureWhite,
            border = BorderStroke(1.dp, SubtleOutline),
            shadowElevation = 1.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = ObsidianCharcoal,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun FloatingStudioDock(
    visibleTabs: List<PrimaryTab>,
    activeTab: PrimaryTab,
    onSelectTab: (PrimaryTab) -> Unit,
    onQuickCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val splitIndex = (visibleTabs.size + 1) / 2
    val leftTabs = visibleTabs.take(splitIndex)
    val rightTabs = visibleTabs.drop(splitIndex)

    Surface(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        shape = RoundedCornerShape(999.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, SubtleOutline),
        shadowElevation = 14.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .animateContentSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leftTabs.forEach { tab ->
                DockTabIconButton(
                    tab = tab,
                    selected = activeTab == tab,
                    onClick = { onSelectTab(tab) }
                )
            }

            // Centered Large Jet-Black Circle '+' Quick Workspace FAB
            Surface(
                onClick = onQuickCreate,
                shape = CircleShape,
                color = ObsidianCharcoal,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .size(52.dp)
                    .testTag("dock_quick_create_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Quick Actions",
                        tint = PureWhite,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            rightTabs.forEach { tab ->
                DockTabIconButton(
                    tab = tab,
                    selected = activeTab == tab,
                    onClick = { onSelectTab(tab) }
                )
            }
        }
    }
}

@Composable
private fun DockTabIconButton(
    tab: PrimaryTab,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) VioletSoftBg else Color.Transparent,
        animationSpec = tween(200),
        label = "dock_bg_${tab.name}"
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) VioletPrimary else DockIconInactive,
        animationSpec = tween(200),
        label = "dock_tint_${tab.name}"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
        label = "dock_scale_${tab.name}"
    )
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bgColor,
        modifier = Modifier
            .size(48.dp)
            .testTag("nav_tab_${tab.name.lowercase()}")
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (selected) tab.icon else tab.inactiveIcon,
                contentDescription = tab.label,
                tint = iconTint,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    }
            )
        }
    }
}

private data class QuickActionTile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val bg: Color,
    val tint: Color,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickWorkspaceSheet(
    member: Member,
    canCreateQuote: Boolean,
    canSeeCommercial: Boolean,
    canSeeProjects: Boolean,
    isAdmin: Boolean,
    canSeePayments: Boolean,
    onDismiss: () -> Unit,
    onNewQuote: () -> Unit,
    onOpenRequests: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenMyWork: () -> Unit,
    onOpenSecondary: (SecondaryDestination) -> Unit
) {
    val firstName = member.fullName.trim().substringBefore(" ").ifBlank { "Member" }
    val tiles = buildList {
        add(
            QuickActionTile(
                title = "Team Chat",
                subtitle = "Files & updates",
                icon = Icons.AutoMirrored.Filled.Chat,
                bg = KpiMintBg,
                tint = KpiMintStroke,
                onClick = { onOpenSecondary(SecondaryDestination.CHAT) }
            )
        )
        if (canCreateQuote) {
            add(
                QuickActionTile(
                    title = "New Quote",
                    subtitle = "56-service PDF",
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    bg = KpiPeachBg,
                    tint = KpiPeachStroke,
                    onClick = onNewQuote
                )
            )
        }
        if (canSeeCommercial) {
            add(
                QuickActionTile(
                    title = "Requests",
                    subtitle = "Client pipeline",
                    icon = Icons.Filled.Inbox,
                    bg = KpiLavenderBg,
                    tint = KpiLavenderStroke,
                    onClick = onOpenRequests
                )
            )
        }
        if (canSeeProjects) {
            add(
                QuickActionTile(
                    title = "Projects",
                    subtitle = "Live board",
                    icon = Icons.Filled.Work,
                    bg = KpiSkyBg,
                    tint = KpiSkyStroke,
                    onClick = onOpenProjects
                )
            )
        }
        add(
            QuickActionTile(
                title = "My Tasks",
                subtitle = "Schedule & checklists",
                icon = Icons.Filled.TaskAlt,
                bg = KpiMintBg,
                tint = KpiMintStroke,
                onClick = onOpenMyWork
            )
        )
        if (canSeeCommercial) {
            add(
                QuickActionTile(
                    title = "Clients",
                    subtitle = "Accounts",
                    icon = Icons.Filled.Contacts,
                    bg = KpiSkyBg,
                    tint = KpiSkyStroke,
                    onClick = { onOpenSecondary(SecondaryDestination.CLIENTS) }
                )
            )
        }
        if (canSeePayments) {
            add(
                QuickActionTile(
                    title = "Payments",
                    subtitle = "Invoices & dues",
                    icon = Icons.Filled.Payments,
                    bg = KpiMintBg,
                    tint = KpiMintStroke,
                    onClick = { onOpenSecondary(SecondaryDestination.PAYMENTS) }
                )
            )
        }
        if (isAdmin) {
            add(
                QuickActionTile(
                    title = "Team & Access",
                    subtitle = "Approvals & roles",
                    icon = Icons.Filled.Group,
                    bg = KpiLavenderBg,
                    tint = KpiLavenderStroke,
                    onClick = { onOpenSecondary(SecondaryDestination.TEAM) }
                )
            )
        }
    }.take(4)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        contentColor = ObsidianCharcoal
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StudioAvatar(
                    name = member.fullName,
                    photoBase64 = member.photoBase64,
                    size = 48.dp,
                    borderColor = SubtleOutline,
                    showEditBadge = false
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hi, ${formatTitleCase(firstName)}!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = StudioTextSecondary
                    )
                    Text(
                        text = "Quick Workspace Actions",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = ObsidianCharcoal
                    )
                }
            }

            tiles.chunked(2).forEach { rowTiles ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowTiles.forEach { tile ->
                        Surface(
                            onClick = tile.onClick,
                            shape = RoundedCornerShape(22.dp),
                            color = tile.bg,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = PureWhite,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = tile.icon,
                                            contentDescription = tile.title,
                                            tint = tile.tint,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = tile.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = ObsidianCharcoal
                                    )
                                    Text(
                                        text = tile.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    if (rowTiles.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

