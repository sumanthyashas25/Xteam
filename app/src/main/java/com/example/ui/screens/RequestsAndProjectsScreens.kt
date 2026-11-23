package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.domain.DeliverableDraft
import com.example.domain.MoneyFormatter
import com.example.domain.WorkDistributionEngine
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun RequestsScreen(
    state: AgencyUiState,
    onOpenQuoteBuilder: (ProjectRequest?, Quotation?) -> Unit,
    onCreateRequest: (Client, String, String, List<String>, Long, Member) -> Unit,
    onAcceptRequest: (ProjectRequest, Quotation, List<DeliverableDraft>) -> Unit,
    onDeclineRequest: (ProjectRequest, String) -> Unit
) {
    var filter by remember { mutableStateOf<DecisionStatus?>(DecisionStatus.PENDING) }
    var searchQuery by remember { mutableStateOf("") }
    var showNewRequestDialog by remember { mutableStateOf(false) }
    var selectedRequest by remember { mutableStateOf<ProjectRequest?>(null) }

    val canCreateReq = state.hasPerm(AppPermission.CREATE_REQUESTS) || state.hasPerm(AppPermission.CREATE_QUOTATIONS)
    val canAccept = state.hasPerm(AppPermission.ACCEPT_PROJECT)
    val canDecline = state.hasPerm(AppPermission.DECLINE_PROJECT)

    val filtered = remember(state.requests, filter, searchQuery) {
        state.requests.filter { req ->
            val stOk = filter == null || req.decisionStatusEnum() == filter
            val qOk = searchQuery.isBlank() ||
                req.projectTitle.contains(searchQuery, ignoreCase = true) ||
                req.clientCompanyName.contains(searchQuery, ignoreCase = true) ||
                req.latestQuoteNumber.contains(searchQuery, ignoreCase = true)
            stOk && qOk
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().pastelAmbientBackdrop(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Client Requests", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                if (canCreateReq && state.clients.isNotEmpty()) {
                    Button(
                        onClick = { showNewRequestDialog = true },
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                        modifier = Modifier.height(42.dp).testTag("new_request_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New Request", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        item {
            MonochromeSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search client requests or quote ID…"
            )
        }
        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filter == null,
                    onClick = { filter = null },
                    shape = RoundedCornerShape(999.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ObsidianCharcoal,
                        selectedLabelColor = PureWhite,
                        containerColor = PureWhite,
                        labelColor = ObsidianCharcoal
                    ),
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filter == null, borderColor = SubtleOutline),
                    label = { Text("All (${state.requests.size})", fontWeight = FontWeight.SemiBold) }
                )
                DecisionStatus.entries.forEach { d ->
                    FilterChip(
                        selected = filter == d,
                        onClick = { filter = d },
                        shape = RoundedCornerShape(999.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ObsidianCharcoal,
                            selectedLabelColor = PureWhite,
                            containerColor = PureWhite,
                            labelColor = ObsidianCharcoal
                        ),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filter == d, borderColor = SubtleOutline),
                        label = { Text("${d.label} (${state.requests.count { it.decisionStatusEnum() == d }})", fontWeight = FontWeight.SemiBold) }
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyStateCard(
                    Icons.Outlined.Inbox,
                    "No matching requests",
                    "Capture a client request or build a new quotation in a meeting.",
                    actionLabel = if (canCreateReq) "Build Quote" else null,
                    onAction = { onOpenQuoteBuilder(null, null) }
                )
            }
        } else {
            items(filtered, key = { it.id }) { req ->
                val st = req.decisionStatusEnum()
                val (bg, fg) = when (st) {
                    DecisionStatus.ACCEPTED -> SoftMint to DeepMintInk
                    DecisionStatus.DECLINED -> SoftBlush to DeepBlushInk
                    DecisionStatus.PENDING -> KpiLavenderBg to VioletPrimary
                }
                Surface(
                    onClick = { selectedRequest = req },
                    shape = RoundedCornerShape(22.dp),
                    color = PureWhite,
                    border = BorderStroke(1.dp, SubtleOutline),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(formatTitleCase(req.projectTitle), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                                Text(
                                    "${formatTitleCase(req.clientCompanyName)} • Lead: ${formatTitleCase(req.responsibleManagerName.ifBlank { "Unassigned" })}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = StudioTextSecondary
                                )
                            }
                            StatusPill(st.label, bg, fg)
                        }
                        if (req.latestQuoteNumber.isNotBlank() || req.clientApprovedQuoteId.isNotBlank()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (req.latestQuoteNumber.isNotBlank()) {
                                    StatusPill("${req.latestQuoteNumber} v${req.latestQuoteRevision}", KpiLavenderBg, VioletPrimary)
                                }
                                if (req.clientApprovedQuoteId.isNotBlank()) {
                                    StatusPill("Client Approved", SoftMint, DeepMintInk)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedRequest?.let { req ->
        RequestDecisionDialog(
            state = state,
            request = state.requests.firstOrNull { it.id == req.id } ?: req,
            canAccept = canAccept,
            canDecline = canDecline,
            onOpenQuoteBuilder = { q ->
                selectedRequest = null
                onOpenQuoteBuilder(req, q)
            },
            onAccept = { quote, drafts ->
                onAcceptRequest(req, quote, drafts)
                selectedRequest = null
            },
            onDecline = { note ->
                onDeclineRequest(req, note)
                selectedRequest = null
            },
            onDismiss = { selectedRequest = null }
        )
    }

    if (showNewRequestDialog) {
        state.myMember?.let { fallbackMgr ->
            NewRequestDialog(
                clients = state.clients,
                managers = state.members.filter {
                    it.statusEnum() == MembershipStatus.APPROVED &&
                        (it.accessLevelEnum() == AccessLevel.SUPER_ADMIN || it.accessLevelEnum() == AccessLevel.ADMIN)
                },
                fallbackManager = fallbackMgr,
                onCreate = { c, title, brief, target, mgr ->
                    onCreateRequest(c, title, brief, emptyList(), target, mgr)
                    showNewRequestDialog = false
                },
                onDismiss = { showNewRequestDialog = false }
            )
        }
    }
}

@Composable
fun RequestDecisionDialog(
    state: AgencyUiState,
    request: ProjectRequest,
    canAccept: Boolean,
    canDecline: Boolean,
    onOpenQuoteBuilder: (Quotation?) -> Unit,
    onAccept: (Quotation, List<DeliverableDraft>) -> Unit,
    onDecline: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val quotes = state.quotations.filter { it.requestId == request.id }
    val targetQuote = quotes.firstOrNull { it.id == request.clientApprovedQuoteId }
        ?: quotes.firstOrNull { it.statusEnum() == QuotationStatus.CLIENT_APPROVED }
        ?: quotes.firstOrNull { it.id == request.latestQuoteId }
        ?: quotes.firstOrNull()

    var drafts by remember(targetQuote?.id, state.routingRules, state.members) {
        mutableStateOf(
            if (targetQuote != null) {
                WorkDistributionEngine.buildDeliverableDrafts(
                    quoteLines = targetQuote.lines,
                    routingRules = state.routingRules,
                    members = state.members,
                    departments = state.departments,
                    jobRoles = state.jobRoles
                )
            } else emptyList()
        )
    }

    var declineNote by remember { mutableStateOf("") }
    var showDeclineConfirm by remember { mutableStateOf(false) }

    val st = request.decisionStatusEnum()
    val (stBg, stFg) = when (st) {
        DecisionStatus.ACCEPTED -> SoftMint to DeepMintInk
        DecisionStatus.DECLINED -> SoftBlush to DeepBlushInk
        DecisionStatus.PENDING -> InfoPillBg to InfoPillInk
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = CrispCanvas
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Bar anchored safely below status bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ObsidianCharcoal)
                    }
                    Text("Request Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                    Spacer(Modifier.size(48.dp))
                }

                // Scrollable body (summary strip, 3-step timeline, quotation CTA, key/value details, routing drafts)
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(44.dp).clip(CircleShape).background(MutedSurface),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.Description, contentDescription = null, tint = ObsidianCharcoal, modifier = Modifier.size(20.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Request Title", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = StudioTextSecondary)
                                Text(
                                    text = formatTitleCase(request.projectTitle),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = ObsidianCharcoal,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            StatusPill(st.label, stBg, stFg)
                        }
                    }

                    item {
                        StepProgressTimeline(
                            steps = listOf(
                                TimelineStep("Received", "Captured", completed = true),
                                TimelineStep("Quoted", targetQuote?.quoteNumber ?: "Pending", completed = targetQuote != null),
                                TimelineStep("Approved", st.label, completed = st == DecisionStatus.ACCEPTED)
                            )
                        )
                    }

                    item {
                        Button(
                            onClick = { onOpenQuoteBuilder(targetQuote) },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (targetQuote == null) "Build Quotation" else "Open Quotation (${targetQuote.quoteNumber})", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    item {
                        val rows = buildList {
                            add(DetailKeyValueRow("Client", request.clientCompanyName))
                            add(DetailKeyValueRow("Manager", request.responsibleManagerName.ifBlank { "Unassigned" }))
                            if (targetQuote != null) {
                                add(DetailKeyValueRow("Quote Total", MoneyFormatter.formatInr(targetQuote.totalPaise)))
                                add(DetailKeyValueRow("Advance Due", MoneyFormatter.formatInr(targetQuote.advancePaise)))
                            }
                            if (request.brief.isNotBlank()) {
                                add(DetailKeyValueRow("Brief", request.brief))
                            }
                        }
                        DetailKeyValueCard(
                            title = "Request Details",
                            icon = Icons.Outlined.Inventory2,
                            rows = rows
                        )
                    }

                    if (request.decisionStatusEnum() == DecisionStatus.PENDING && targetQuote != null) {
                        item {
                            Text("Deliverables & Routing (${drafts.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                        }
                        items(drafts, key = { it.draftId }) { draft ->
                            var expanded by remember { mutableStateOf(false) }
                            val eligible = state.members.filter { it.statusEnum() == MembershipStatus.APPROVED && it.active }
                            Surface(shape = RoundedCornerShape(20.dp), color = MutedSurface) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("${draft.title} (×${draft.quantity})", fontWeight = FontWeight.Bold, color = ObsidianCharcoal, modifier = Modifier.weight(1f))
                                        StatusPill(
                                            draft.assignmentStatus.label,
                                            if (draft.assignmentStatus == AssignmentStatus.ASSIGNED) SoftMint else ButterYellow,
                                            if (draft.assignmentStatus == AssignmentStatus.ASSIGNED) DeepMintInk else DeepAmberInk
                                        )
                                    }
                                    Text("${draft.departmentName} • ${draft.jobRoleName}", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                    Box {
                                        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(0.dp)) {
                                            Text("Assignee: ${draft.assignedToName.ifBlank { "Pick Employee" }}", color = InfoPillInk, fontWeight = FontWeight.SemiBold)
                                        }
                                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                            DropdownMenuItem(text = { Text("Leave Unassigned") }, onClick = {
                                                drafts = drafts.map { if (it.draftId == draft.draftId) WorkDistributionEngine.applyOverride(it, null) else it }
                                                expanded = false
                                            })
                                            eligible.forEach { m ->
                                                DropdownMenuItem(
                                                    text = { Text("${m.fullName} (${m.departmentName} / ${m.jobRoleName})") },
                                                    onClick = {
                                                        drafts = drafts.map { if (it.draftId == draft.draftId) WorkDistributionEngine.applyOverride(it, m) else it }
                                                        expanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Anchored Safe-Inset Bottom Decision Bar (never clipped behind gesture nav or tall headers)
                if (request.decisionStatusEnum() == DecisionStatus.PENDING && (canAccept || canDecline)) {
                    Surface(
                        color = PureWhite,
                        border = BorderStroke(1.dp, SubtleOutline),
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                        ) {
                            if (canAccept) {
                                SlideToActionButton(
                                    label = "SLIDE TO ACCEPT",
                                    enabled = targetQuote != null && drafts.isNotEmpty(),
                                    onSlideComplete = { if (targetQuote != null) onAccept(targetQuote, drafts) },
                                    modifier = Modifier.fillMaxWidth(),
                                    testTag = "accept_project_button"
                                )
                            }
                            if (canDecline) {
                                OutlinedButton(
                                    onClick = { showDeclineConfirm = true },
                                    shape = RoundedCornerShape(999.dp),
                                    border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("decline_request_button"),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonBlocked)
                                ) {
                                    Text("Decline Request", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeclineConfirm) {
        AlertDialog(
            onDismissRequest = { showDeclineConfirm = false },
            containerColor = PureWhite,
            title = { Text("Decline Request", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(value = declineNote, onValueChange = { declineNote = it }, label = { Text("Reason / Internal Note") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeclineConfirm = false
                        onDecline(declineNote.ifBlank { "Declined internally" })
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) { Text("Confirm Decline") }
            },
            dismissButton = { TextButton(onClick = { showDeclineConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NewRequestDialog(
    clients: List<Client>,
    managers: List<Member>,
    fallbackManager: Member,
    onCreate: (Client, String, String, Long, Member) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedClient by remember { mutableStateOf(clients.firstOrNull()) }
    var title by remember { mutableStateOf("") }
    var brief by remember { mutableStateOf("") }
    var daysStr by remember { mutableStateOf("14") }
    var selectedMgr by remember { mutableStateOf(managers.firstOrNull() ?: fallbackManager) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        title = { Text("Capture Client Request", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    clients.forEach { cli ->
                        FilterChip(selected = selectedClient?.id == cli.id, onClick = { selectedClient = cli }, label = { Text(cli.companyName) })
                    }
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Project Title *") }, singleLine = true)
                OutlinedTextField(value = brief, onValueChange = { brief = it }, label = { Text("Brief / Goals") }, minLines = 2)
                OutlinedTextField(value = daysStr, onValueChange = { daysStr = it.filter(Char::isDigit).take(3) }, label = { Text("Target Turnaround (days)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val days = (daysStr.toLongOrNull() ?: 14L).coerceIn(1L, 365L)
                    selectedClient?.let { c -> onCreate(c, title, brief, System.currentTimeMillis() + days * 86_400_000L, selectedMgr) }
                },
                enabled = selectedClient != null && title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
            ) { Text("Save Request") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ProjectsScreen(
    state: AgencyUiState,
    onOpenDeliverable: (Deliverable) -> Unit,
    onAssignDeliverable: (Deliverable, Member, Long) -> Unit,
    onOpenAddOnQuote: (Project) -> Unit,
    onToggleDeleteProject: (Project, Boolean) -> Unit,
    onPermanentlyDeleteProject: (Project) -> Unit = {},
    onCleanupOldDoneProjects: () -> Unit = {}
) {
    var showDeleted by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedProject by remember { mutableStateOf<Project?>(null) }
    var showCleanupConfirm by remember { mutableStateOf(false) }
    val canDelete = state.isSuperAdmin
    val canAssign = state.hasPerm(AppPermission.ASSIGN_WORK)
    val canQuote = state.hasPerm(AppPermission.CREATE_QUOTATIONS) || state.hasPerm(AppPermission.ISSUE_QUOTATIONS)
    val photoByUid = remember(state.members) { state.members.associate { it.uid to it.photoBase64 } }
    val cleanableCount = remember(state.projects) {
        state.projects.count { it.statusEnum() == ProjectStatus.COMPLETED || it.deleted }
    }

    val list = remember(state.projects, showDeleted, searchQuery) {
        state.projects.filter { proj ->
            proj.deleted == showDeleted && (
                searchQuery.isBlank() ||
                    proj.title.contains(searchQuery, ignoreCase = true) ||
                    proj.clientCompanyName.contains(searchQuery, ignoreCase = true) ||
                    proj.approvedQuoteNumber.contains(searchQuery, ignoreCase = true)
                )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().pastelAmbientBackdrop(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Projects (${list.size})", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                if (canDelete) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (cleanableCount > 0) {
                            OutlinedButton(
                                onClick = { showCleanupConfirm = true },
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.45f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonBlocked),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(38.dp).testTag("cleanup_old_projects_button")
                            ) {
                                Text("CLEAN DONE ($cleanableCount)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                        FilterChip(
                            selected = showDeleted,
                            onClick = { showDeleted = !showDeleted },
                            shape = RoundedCornerShape(999.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ObsidianCharcoal,
                                selectedLabelColor = PureWhite,
                                containerColor = PureWhite,
                                labelColor = ObsidianCharcoal
                            ),
                            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = showDeleted, borderColor = SubtleOutline),
                            label = { Text(if (showDeleted) "DELETED" else "ACTIVE ONLY", fontWeight = FontWeight.Bold) }
                        )
                    }
                }
            }
        }
        item {
            MonochromeSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search projects by title, client, or quote ID…"
            )
        }

        if (list.isEmpty()) {
            item {
                EmptyStateCard(
                    Icons.Outlined.Inventory2,
                    if (showDeleted) "No deleted projects" else "No active projects yet",
                    "Accept an approved client quotation on the Requests tab to spin up a project and distribute work."
                )
            }
        } else {
            items(list, key = { it.id }) { proj ->
                val projDelivs = state.deliverables.filter { it.projectId == proj.id }
                val done = projDelivs.count { it.statusEnum() == DeliverableStatus.APPROVED }
                val started = projDelivs.any { it.statusEnum() != DeliverableStatus.NOT_STARTED }
                val allDone = projDelivs.isNotEmpty() && done == projDelivs.size
                val assigneePairs = projDelivs
                    .filter { it.assignedToName.isNotBlank() }
                    .distinctBy { it.assignedToUid.ifBlank { it.assignedToName } }
                    .map { it.assignedToName to (photoByUid[it.assignedToUid] ?: "") }

                Surface(
                    onClick = { selectedProject = proj },
                    shape = RoundedCornerShape(22.dp),
                    color = PureWhite,
                    border = BorderStroke(1.dp, SubtleOutline),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(formatTitleCase(proj.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                                Text("${formatTitleCase(proj.clientCompanyName)} • ${proj.approvedQuoteNumber} v${proj.approvedQuoteRevision}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = StudioTextSecondary)
                            }
                            StatusPill(
                                proj.statusEnum().label,
                                if (allDone) SoftMint else KpiSkyBg,
                                if (allDone) DeepMintInk else InfoPillInk
                            )
                        }

                        StepProgressTimeline(
                            steps = listOf(
                                TimelineStep("Accepted", "${projDelivs.size} items", completed = true),
                                TimelineStep("In Progress", "$done/${projDelivs.size}", completed = started || allDone),
                                TimelineStep("Delivered", if (allDone) "Done" else "Open", completed = allDone)
                            )
                        )

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            if (assigneePairs.isNotEmpty()) {
                                OverlappingAvatarMembers(members = assigneePairs, avatarSize = 26.dp, borderColor = PureWhite)
                            } else {
                                Text(WorkDistributionEngine.summarizeProjectProgress(projDelivs), style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                            }
                            if (state.hasPerm(AppPermission.VIEW_PAYMENTS) || state.hasPerm(AppPermission.RECORD_PAYMENTS)) {
                                StatusPill(
                                    "BAL: ${MoneyFormatter.formatInr(proj.outstandingPaise)}",
                                    KpiMintBg,
                                    DeepMintInk
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCleanupConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanupConfirm = false },
            containerColor = PureWhite,
            title = { Text("DELETE ALL OLD DONE PROJECTS?", fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal) },
            text = { Text("This permanently deletes $cleanableCount completed or archived project(s) and their deliverables.") },
            confirmButton = {
                Button(
                    onClick = {
                        showCleanupConfirm = false
                        onCleanupOldDoneProjects()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) { Text("DELETE FOREVER", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirm = false }) { Text("CANCEL") }
            }
        )
    }

    selectedProject?.let { p ->
        val fresh = state.projects.firstOrNull { it.id == p.id } ?: p
        val projDelivs = state.deliverables.filter { it.projectId == fresh.id }
        ProjectDetailDialog(
            state = state,
            project = fresh,
            deliverables = projDelivs,
            canAssign = canAssign,
            canQuote = canQuote,
            canDelete = canDelete,
            onOpenDeliverable = { d ->
                selectedProject = null
                onOpenDeliverable(d)
            },
            onAssign = onAssignDeliverable,
            onAddOnQuote = {
                selectedProject = null
                onOpenAddOnQuote(fresh)
            },
            onToggleDelete = { del ->
                onToggleDeleteProject(fresh, del)
                selectedProject = null
            },
            onPermanentDelete = {
                onPermanentlyDeleteProject(fresh)
                selectedProject = null
            },
            onDismiss = { selectedProject = null }
        )
    }
}

@Composable
private fun ProjectDetailDialog(
    state: AgencyUiState,
    project: Project,
    deliverables: List<Deliverable>,
    canAssign: Boolean,
    canQuote: Boolean,
    canDelete: Boolean,
    onOpenDeliverable: (Deliverable) -> Unit,
    onAssign: (Deliverable, Member, Long) -> Unit,
    onAddOnQuote: () -> Unit,
    onToggleDelete: (Boolean) -> Unit,
    onPermanentDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var detailTab by remember { mutableStateOf(0) } // 0 = Details & Deliverables, 1 = Summary & History
    var showPermanentDeleteConfirm by remember { mutableStateOf(false) }
    val done = deliverables.count { it.statusEnum() == DeliverableStatus.APPROVED }
    val allDone = deliverables.isNotEmpty() && done == deliverables.size
    val anyStarted = deliverables.any { it.statusEnum() != DeliverableStatus.NOT_STARTED }
    val anyBlocked = deliverables.any { it.statusEnum() == DeliverableStatus.BLOCKED }
    val earliestDue = deliverables.mapNotNull { it.dueDateMillis.takeIf { d -> d > 0L } }.minOrNull() ?: 0L

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = CrispCanvas
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ObsidianCharcoal)
                    }
                    Text("Project Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                    Spacer(Modifier.size(48.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(MutedSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = ObsidianCharcoal, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Project", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = StudioTextSecondary)
                        Text(
                            text = "${project.approvedQuoteNumber.ifBlank { project.id.take(10) }} • ${formatTitleCase(project.title)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = ObsidianCharcoal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    StatusPill(
                        label = project.statusEnum().label,
                        containerColor = if (allDone) SoftMint else InfoPillBg,
                        contentColor = if (allDone) DeepMintInk else InfoPillInk
                    )
                }

                // 3-Step Horizontal Node Timeline
                StepProgressTimeline(
                    steps = listOf(
                        TimelineStep("Accepted", "${deliverables.size} tasks", completed = true),
                        TimelineStep("In Progress", "$done/${deliverables.size} done", completed = anyStarted || allDone),
                        TimelineStep("Delivered", if (earliestDue > 0L) MoneyFormatter.formatDate(earliestDue) else "Target", completed = allDone)
                    )
                )

                // Full-width Jet-Black Primary CTA Pill
                if (canQuote) {
                    Button(
                        onClick = onAddOnQuote,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Icon(Icons.Outlined.PostAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scope Add-On Quote", fontWeight = FontWeight.SemiBold)
                    }
                }

                SegmentedPillTabs(
                    options = listOf("Deliverables (${deliverables.size})", "Project Details"),
                    selectedIndex = detailTab,
                    onSelect = { detailTab = it }
                )

                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (detailTab == 0) {
                        items(deliverables, key = { it.id }) { d ->
                            var assignMenu by remember { mutableStateOf(false) }
                            val st = d.statusEnum()
                            val (pillBg, pillFg) = statusColorsForDeliverable(st)

                            Surface(
                                onClick = { onOpenDeliverable(d) },
                                shape = RoundedCornerShape(20.dp),
                                color = MutedSurface
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Box(
                                            modifier = Modifier.size(34.dp).clip(CircleShape).background(PureWhite),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                if (st == DeliverableStatus.APPROVED) Icons.Filled.Check else Icons.Outlined.TaskAlt,
                                                contentDescription = null,
                                                tint = ObsidianCharcoal,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(d.title, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                                            Text("${d.departmentName} • ${d.assignedToName.ifBlank { d.assignmentStatusEnum().label }}", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                        }
                                        StatusPill(st.label, pillBg, pillFg)
                                    }
                                    if (canAssign) {
                                        Box {
                                            TextButton(onClick = { assignMenu = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                                Text("Reassign / Set Assignee", color = InfoPillInk, fontWeight = FontWeight.SemiBold)
                                            }
                                            DropdownMenu(expanded = assignMenu, onDismissRequest = { assignMenu = false }) {
                                                state.members.filter { it.statusEnum() == MembershipStatus.APPROVED && it.active }.forEach { m ->
                                                    DropdownMenuItem(
                                                        text = { Text("${m.fullName} (${m.departmentName} / ${m.jobRoleName})") },
                                                        onClick = {
                                                            onAssign(d, m, d.dueDateMillis.takeIf { it > 0L } ?: (System.currentTimeMillis() + 7L * 86_400_000L))
                                                            assignMenu = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            val rows = buildList {
                                add(DetailKeyValueRow("Client", project.clientCompanyName))
                                add(DetailKeyValueRow("Quote Ref", "${project.approvedQuoteNumber} v${project.approvedQuoteRevision}"))
                                if (earliestDue > 0L) {
                                    add(DetailKeyValueRow("Next Deadline", MoneyFormatter.formatDate(earliestDue)))
                                }
                                if (state.hasPerm(AppPermission.VIEW_PAYMENTS) || state.hasPerm(AppPermission.RECORD_PAYMENTS)) {
                                    add(DetailKeyValueRow("Agreed Value", MoneyFormatter.formatInr(project.agreedValuePaise)))
                                    add(DetailKeyValueRow("Balance Due", MoneyFormatter.formatInr(project.outstandingPaise)))
                                }
                                if (anyBlocked) {
                                    add(DetailKeyValueRow("Alert", "Contains blocked deliverables", isWarning = true))
                                }
                            }
                            DetailKeyValueCard(
                                title = "Project Details",
                                icon = Icons.Outlined.LocalShipping,
                                rows = rows
                            )
                        }

                        // Contact / Manager Row Card matching Screen 2 Contact Rider card
                        item {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MutedSurface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    val mgrPhoto = state.members.firstOrNull { it.uid == project.responsibleManagerUid }?.photoBase64.orEmpty()
                                    StudioAvatar(
                                        name = project.responsibleManagerName.ifBlank { "X.MAX Lead" },
                                        size = 44.dp,
                                        photoBase64 = mgrPhoto
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = formatTitleCase(project.responsibleManagerName.ifBlank { "Studio Lead" }),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = ObsidianCharcoal
                                        )
                                        Text(
                                            text = WorkDistributionEngine.summarizeProjectProgress(deliverables),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = StudioTextSecondary
                                        )
                                    }
                                }
                            }
                        }

                        if (canDelete) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { onToggleDelete(!project.deleted) },
                                        shape = RoundedCornerShape(999.dp),
                                        border = BorderStroke(1.dp, SubtleOutline),
                                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("delete_restore_project_button"),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ObsidianCharcoal)
                                    ) { Text(if (project.deleted) "RESTORE PROJECT" else "ARCHIVE PROJECT", fontWeight = FontWeight.Bold) }

                                    Button(
                                        onClick = { showPermanentDeleteConfirm = true },
                                        shape = RoundedCornerShape(999.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite),
                                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("permanent_delete_project_button")
                                    ) { Text("DELETE PROJECT PERMANENTLY", fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPermanentDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showPermanentDeleteConfirm = false },
            containerColor = PureWhite,
            title = { Text("DELETE PROJECT FOREVER?", fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal) },
            text = { Text("Permanently remove ${project.title.uppercase()} and all ${deliverables.size} deliverable(s)?") },
            confirmButton = {
                Button(
                    onClick = {
                        showPermanentDeleteConfirm = false
                        onPermanentDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) { Text("DELETE PERMANENTLY", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showPermanentDeleteConfirm = false }) { Text("CANCEL") }
            }
        )
    }
}
