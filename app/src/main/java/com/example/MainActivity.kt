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
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    REQUESTS("Requests", Icons.Filled.DateRange, Icons.Outlined.DateRange),
    PROJECTS("Projects", Icons.Filled.Inventory2, Icons.Outlined.Inventory2),
    MY_WORK("My Work", Icons.Filled.Person, Icons.Outlined.Person)
}

enum class SecondaryDestination(val label: String, val icon: ImageVector) {
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
        Surface(modifier = Modifier.fillMaxSize(), color = CrispCanvas) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ObsidianCharcoal)
            }
        }
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

    val visibleTabs = remember(isStaffOnly, canSeeCommercial, canSeeProjects) {
        buildList {
            if (isStaffOnly) {
                add(PrimaryTab.MY_WORK)
                if (canSeeCommercial) add(PrimaryTab.REQUESTS)
                if (canSeeProjects) add(PrimaryTab.PROJECTS)
            } else {
                add(PrimaryTab.HOME)
                add(PrimaryTab.REQUESTS)
                add(PrimaryTab.PROJECTS)
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
                                        activeSecondary.label.uppercase(),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = ObsidianCharcoal
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { secondaryDest = null }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ObsidianCharcoal)
                                    }
                                },
                                actions = {
                                    SyncFeedbackChip(state.syncFeedback, state.deliverablesPendingSync)
                                    Spacer(Modifier.width(6.dp))
                                    CircleHeaderIconButton(
                                        icon = Icons.Filled.Menu,
                                        contentDescription = "Open Menu Panel",
                                        modifier = Modifier.testTag("more_menu_button"),
                                        onClick = { scope.launch { drawerState.open() } }
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }
                            )
                        } else {
                            // Reference Welcome Header (matching dashboard_UI_referance_a887d8be.jpg)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(horizontal = 18.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    StudioAvatar(
                                        name = member.fullName,
                                        photoBase64 = member.photoBase64,
                                        size = 46.dp,
                                        borderColor = PureWhite,
                                        showEditBadge = true,
                                        modifier = Modifier.testTag("header_profile_avatar"),
                                        onClick = { showProfilePhotoDialog = true }
                                    )
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(1.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { scope.launch { drawerState.open() } }
                                            .padding(vertical = 2.dp, horizontal = 2.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "WELCOME",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = StudioTextSecondary
                                            )
                                            val companyName = state.company?.name?.trim().orEmpty()
                                            if (companyName.isBlank() || companyName.equals("X.MAX", ignoreCase = true)) {
                                                XMaxLogoImage(tint = StudioTextSecondary.copy(alpha = 0.72f), height = 8.dp)
                                            }
                                        }
                                        Text(
                                            text = member.fullName.ifBlank { "MEMBER" }.uppercase(),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = ObsidianCharcoal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (state.syncFeedback != com.example.ui.SyncFeedback.IDLE || state.deliverablesPendingSync) {
                                        SyncFeedbackChip(state.syncFeedback, state.deliverablesPendingSync)
                                    }
                                    CircleHeaderIconButton(
                                        icon = Icons.Outlined.ChatBubbleOutline,
                                        contentDescription = "Team Chat & Files",
                                        modifier = Modifier.testTag("open_chat_button"),
                                        onClick = {
                                            chatInitialChannelId = null
                                            secondaryDest = SecondaryDestination.CHAT
                                        }
                                    )
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
                                                    .padding(top = 4.dp, end = 4.dp)
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
                                    CircleHeaderIconButton(
                                        icon = Icons.Filled.Menu,
                                        contentDescription = "Open Menu Panel",
                                        modifier = Modifier.testTag("more_menu_button"),
                                        onClick = { scope.launch { drawerState.open() } }
                                    )
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
                            val dest = secondaryDest
                            if (dest != null) {
                                when (dest) {
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
                                when (activeTab) {
                                    PrimaryTab.HOME -> HomeScreen(
                                        state = state,
                                        onOpenNewQuote = { openQuote() },
                                        onNavigateRequests = { activeTab = PrimaryTab.REQUESTS },
                                        onNavigateProjects = { activeTab = PrimaryTab.PROJECTS },
                                        onNavigateMyWork = { activeTab = PrimaryTab.MY_WORK },
                                        onNavigateTeam = { secondaryDest = SecondaryDestination.TEAM },
                                        onNavigatePayments = { secondaryDest = SecondaryDestination.PAYMENTS },
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
                                    PrimaryTab.PROJECTS -> ProjectsScreen(
                                        state = state,
                                        onOpenDeliverable = { activeDeliverable = it },
                                        onAssignDeliverable = { d, m, due -> viewModel.assignDeliverable(d, m, due) },
                                        onOpenAddOnQuote = { proj -> openQuote(proj = proj) },
                                        onToggleDeleteProject = { proj, del -> viewModel.toggleDeleteProject(proj, del) },
                                        onPermanentlyDeleteProject = { proj -> viewModel.permanentlyDeleteProject(proj) },
                                        onCleanupOldDoneProjects = { viewModel.cleanupOldDoneProjects() }
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

                // 5-Slot Floating White Pill Bottom Dock with Centered Jet-Black + FAB
                if (!isWide && secondaryDest == null) {
                    FloatingStudioDock(
                        visibleTabs = visibleTabs,
                        activeTab = activeTab,
                        onSelectTab = { activeTab = it },
                        onQuickCreate = { showQuickCreateSheet = true },
                        modifier = Modifier.align(Alignment.BottomCenter)
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
                secondaryDest = null
                activeTab = PrimaryTab.PROJECTS
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
            },
            onOpenProfilePhoto = {
                showQuickCreateSheet = false
                showProfilePhotoDialog = true
            }
        )
    }

    if (showProfilePhotoDialog) {
        ProfilePhotoDialog(
            member = member,
            onPickPhoto = { uri ->
                viewModel.updateMyProfilePhoto(context, uri) { showProfilePhotoDialog = false }
            },
            onRemovePhoto = {
                viewModel.removeMyProfilePhoto { showProfilePhotoDialog = false }
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
    Surface(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = PureWhite,
        border = BorderStroke(1.dp, SubtleOutline)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = ObsidianCharcoal,
                modifier = Modifier.size(20.dp)
            )
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
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) VioletSoftBg else Color.Transparent,
        modifier = Modifier
            .size(48.dp)
            .testTag("nav_tab_${tab.name.lowercase()}")
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (selected) tab.icon else tab.inactiveIcon,
                contentDescription = tab.label,
                tint = if (selected) VioletPrimary else DockIconInactive,
                modifier = Modifier.size(22.dp)
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
    onOpenSecondary: (SecondaryDestination) -> Unit,
    onOpenProfilePhoto: () -> Unit = {}
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
                    icon = Icons.Filled.DateRange,
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
                    icon = Icons.Filled.Inventory2,
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
                    showEditBadge = true,
                    onClick = onOpenProfilePhoto
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "HI, ${firstName.uppercase()}!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = StudioTextSecondary
                    )
                    Text(
                        text = "QUICK WORKSPACE ACTIONS",
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

