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
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.MoneyFormatter
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.components.*
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class OverviewWindow(val label: String) {
    TODAY("Today"),
    THIS_WEEK("This Week"),
    THIS_MONTH("This Month"),
    ALL_TIME("All Time")
}

private enum class DashboardProjectSegment(val label: String) {
    ACTIVE("Active"),
    REQUESTS("Requests"),
    COMPLETED("Completed")
}

@Composable
fun HomeScreen(
    state: AgencyUiState,
    onOpenNewQuote: () -> Unit,
    onNavigateRequests: () -> Unit,
    onNavigateProjects: () -> Unit,
    onNavigateMyWork: () -> Unit,
    onNavigateTeam: () -> Unit,
    onNavigatePayments: () -> Unit,
    onNavigateCompanySettings: () -> Unit = {},
    onNavigateOrgRouting: () -> Unit = {},
    onOpenMenuPanel: () -> Unit = {},
    onOpenDeliverable: (Deliverable) -> Unit,
    onOpenProjectQuote: (Project) -> Unit = { onOpenNewQuote() },
    onToggleDeleteProject: (Project, Boolean) -> Unit = { _, _ -> },
    onCleanupOldDoneProjects: () -> Unit = {}
) {
    val member = state.myMember ?: return
    val access = member.accessLevelEnum()

    var selectedWindow by remember { mutableStateOf(OverviewWindow.THIS_WEEK) }
    var showWindowMenu by remember { mutableStateOf(false) }

    var selectedSegment by remember { mutableStateOf(DashboardProjectSegment.ACTIVE) }
    var projectFilterWindow by remember { mutableStateOf("All time") }
    var showProjectFilterMenu by remember { mutableStateOf(false) }
    var showCleanupConfirm by remember { mutableStateOf(false) }

    val canCreateQuote = state.hasPerm(AppPermission.CREATE_QUOTATIONS) || state.hasPerm(AppPermission.ISSUE_QUOTATIONS)
    val canDeleteProject = state.isSuperAdmin
    val memberPhotoByUid = remember(state.members) { state.members.associate { it.uid to it.photoBase64 } }

    val activeProjects = remember(state.projects) { state.projects.filter { !it.deleted } }
    val pendingMembers = remember(state.members) { state.members.filter { it.statusEnum() == MembershipStatus.PENDING } }
    val pendingRequests = remember(state.requests) { state.requests.filter { it.decisionStatusEnum() == DecisionStatus.PENDING } }
    val blockedDeliverables = remember(state.deliverables) { state.deliverables.filter { it.statusEnum() == DeliverableStatus.BLOCKED } }
    val reviewQueue = remember(state.deliverables) { state.deliverables.filter { it.statusEnum() == DeliverableStatus.READY_FOR_REVIEW } }
    val myAssigned = remember(state.deliverables, member.uid) {
        state.deliverables
            .filter { it.assignedToUid == member.uid && it.statusEnum() != DeliverableStatus.APPROVED }
            .sortedBy { if (it.dueDateMillis > 0L) it.dueDateMillis else Long.MAX_VALUE }
    }

    val openProjects = remember(activeProjects, state.deliverables) {
        activeProjects.filter { p ->
            val pDelivs = state.deliverables.filter { it.projectId == p.id }
            val allDone = pDelivs.isNotEmpty() && pDelivs.all { it.statusEnum() == DeliverableStatus.APPROVED }
            !allDone && p.statusEnum() != ProjectStatus.COMPLETED
        }
    }
    val completedProjects = remember(activeProjects, state.deliverables) {
        activeProjects.filter { p ->
            val pDelivs = state.deliverables.filter { it.projectId == p.id }
            val allDone = pDelivs.isNotEmpty() && pDelivs.all { it.statusEnum() == DeliverableStatus.APPROVED }
            allDone || p.statusEnum() == ProjectStatus.COMPLETED
        }
    }

    val nowMillis = remember { System.currentTimeMillis() }
    val cutoffMillis = remember(selectedWindow, nowMillis) {
        when (selectedWindow) {
            OverviewWindow.TODAY -> nowMillis - 24L * 3600_000L
            OverviewWindow.THIS_WEEK -> nowMillis - 7L * 24L * 3600_000L
            OverviewWindow.THIS_MONTH -> nowMillis - 30L * 24L * 3600_000L
            OverviewWindow.ALL_TIME -> 0L
        }
    }

    val scopedRequestsCount = if (cutoffMillis == 0L) pendingRequests.size else {
        pendingRequests.count { (it.createdAt?.seconds?.times(1000L) ?: nowMillis) >= cutoffMillis }
    }
    val scopedQuotesCount = if (cutoffMillis == 0L) state.quotations.size else {
        state.quotations.count { it.issuedDateMillis == 0L || it.issuedDateMillis >= cutoffMillis }
    }
    val scopedProjectsCount = activeProjects.size
    val scopedTasksCount = if (access == AccessLevel.STAFF) myAssigned.size else state.deliverables.size

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .pastelAmbientBackdrop(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Overview Section Header + Timeframe Dropdown ("This Week v")
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "OVERVIEW",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = ObsidianCharcoal
                )
                Box {
                    DropdownPillSelector(
                        label = selectedWindow.label,
                        onClick = { showWindowMenu = true }
                    )
                    DropdownMenu(
                        expanded = showWindowMenu,
                        onDismissRequest = { showWindowMenu = false },
                        containerColor = PureWhite
                    ) {
                        OverviewWindow.entries.forEach { win ->
                            DropdownMenuItem(
                                text = { Text(win.label, fontWeight = FontWeight.Medium) },
                                onClick = {
                                    selectedWindow = win
                                    showWindowMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // 2. Signature 2x2 Pastel Sparkline KPI Grid (matching dashboard_UI_referance_a887d8be.jpg)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PastelKpiSparklineCard(
                        valueText = if (access == AccessLevel.STAFF) "${myAssigned.size}" else "$scopedRequestsCount",
                        label = if (access == AccessLevel.STAFF) "Today" else "Requests",
                        containerColor = KpiLavenderBg,
                        strokeColor = KpiLavenderStroke,
                        sparklinePoints = listOf(0.28f, 0.72f, 0.35f, 0.88f, 0.48f, 0.92f),
                        onClick = { if (access == AccessLevel.STAFF) onNavigateMyWork() else onNavigateRequests() },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("kpi_overview_card_1")
                    )
                    PastelKpiSparklineCard(
                        valueText = if (access == AccessLevel.STAFF) "${reviewQueue.size}" else "$scopedQuotesCount",
                        label = if (access == AccessLevel.STAFF) "In Review" else "Quotations",
                        containerColor = KpiPeachBg,
                        strokeColor = KpiPeachStroke,
                        sparklinePoints = listOf(0.35f, 0.58f, 0.42f, 0.76f, 0.65f, 0.90f),
                        onClick = { if (canCreateQuote) onOpenNewQuote() else onNavigateMyWork() },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("new_quote_button")
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PastelKpiSparklineCard(
                        valueText = "$scopedProjectsCount",
                        label = "Projects",
                        containerColor = KpiSkyBg,
                        strokeColor = KpiSkyStroke,
                        sparklinePoints = listOf(0.45f, 0.30f, 0.68f, 0.52f, 0.84f, 0.78f),
                        onClick = onNavigateProjects,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("track_projects_pill_button")
                    )
                    PastelKpiSparklineCard(
                        valueText = "$scopedTasksCount",
                        label = "All Task",
                        containerColor = KpiMintBg,
                        strokeColor = KpiMintStroke,
                        sparklinePoints = listOf(0.38f, 0.70f, 0.50f, 0.86f, 0.62f, 0.88f),
                        onClick = onNavigateMyWork,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("kpi_overview_card_4")
                    )
                }
            }
        }

        // 3. Projects Section + Dropdown ("All time v") + Segmented Pills ("Active (X)" / "Requests (Y)" / "Completed (Z)")
        item {
            Column(
                modifier = Modifier.padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PROJECTS",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = ObsidianCharcoal
                    )
                    Box {
                        DropdownPillSelector(
                            label = projectFilterWindow,
                            onClick = { showProjectFilterMenu = true }
                        )
                        DropdownMenu(
                            expanded = showProjectFilterMenu,
                            onDismissRequest = { showProjectFilterMenu = false },
                            containerColor = PureWhite
                        ) {
                            listOf("All time", "This month", "Active only").forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt) },
                                    onClick = {
                                        projectFilterWindow = opt
                                        showProjectFilterMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DashboardProjectSegment.entries.forEach { seg ->
                        val count = when (seg) {
                            DashboardProjectSegment.ACTIVE -> openProjects.size
                            DashboardProjectSegment.REQUESTS -> pendingRequests.size
                            DashboardProjectSegment.COMPLETED -> completedProjects.size
                        }
                        val selected = selectedSegment == seg
                        Surface(
                            onClick = { selectedSegment = seg },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) ObsidianCharcoal else PureWhite,
                            border = if (selected) null else BorderStroke(1.dp, SubtleOutline),
                            modifier = Modifier.defaultMinSize(minHeight = 40.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                            ) {
                                Text(
                                    text = "${seg.label} ($count)",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) PureWhite else ObsidianCharcoal
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Cards List matching the active Segment ("Active" / "Requests" / "Completed")
        when (selectedSegment) {
            DashboardProjectSegment.ACTIVE, DashboardProjectSegment.COMPLETED -> {
                val list = if (selectedSegment == DashboardProjectSegment.ACTIVE) openProjects else completedProjects
                if (selectedSegment == DashboardProjectSegment.COMPLETED && canDeleteProject && completedProjects.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(
                                onClick = { showCleanupConfirm = true },
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.4f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonBlocked),
                                modifier = Modifier.testTag("home_clean_old_done_button")
                            ) {
                                Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("CLEAN OLD DONE (${completedProjects.size})", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (list.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.Outlined.Inventory2,
                            title = if (selectedSegment == DashboardProjectSegment.ACTIVE) "No active projects yet" else "No completed projects yet",
                            subtitle = if (canCreateQuote) "Create a client quotation or accept a request to launch deliverables." else "Assigned project work will appear here once active.",
                            actionLabel = if (canCreateQuote) "New Quote" else null,
                            onAction = if (canCreateQuote) onOpenNewQuote else null
                        )
                    }
                } else {
                    items(list.take(4), key = { "dash_proj_${it.id}" }) { proj ->
                        val projDelivs = state.deliverables.filter { it.projectId == proj.id }
                        val doneCount = projDelivs.count { it.statusEnum() == DeliverableStatus.APPROVED }
                        val dateMillis = proj.createdAt?.seconds?.times(1000L) ?: System.currentTimeMillis()
                        DashboardReferenceProjectCard(
                            dateText = formatShortDate(dateMillis),
                            title = proj.title.uppercase(),
                            subtitle = "${proj.clientCompanyName.uppercase()} • ${proj.approvedQuoteNumber.ifBlank { "ACTIVE" }}",
                            primaryDotColor = VioletPrimary,
                            primaryStatText = "Tasks: ${projDelivs.size}",
                            secondaryDotColor = EmeraldApproved,
                            secondaryStatText = "Done: $doneCount",
                            trailingBadge = if (state.hasPerm(AppPermission.VIEW_PAYMENTS) && proj.outstandingPaise > 0L) {
                                MoneyFormatter.formatInr(proj.outstandingPaise)
                            } else null,
                            canEdit = canCreateQuote,
                            canDelete = canDeleteProject,
                            onClick = onNavigateProjects,
                            onEditQuote = { onOpenProjectQuote(proj) },
                            onDelete = { onToggleDeleteProject(proj, true) }
                        )
                    }
                }
            }

            DashboardProjectSegment.REQUESTS -> {
                if (pendingRequests.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.Outlined.Inbox,
                            title = "No pending requests",
                            subtitle = "All incoming client briefs are reviewed.",
                            actionLabel = if (canCreateQuote) "View Requests" else null,
                            onAction = if (canCreateQuote) onNavigateRequests else null
                        )
                    }
                } else {
                    items(pendingRequests.take(4), key = { "dash_req_${it.id}" }) { req ->
                        val dateMillis = if (req.targetDateMillis > 0L) req.targetDateMillis else System.currentTimeMillis()
                        DashboardReferenceProjectCard(
                            dateText = formatShortDate(dateMillis),
                            title = req.projectTitle.uppercase(),
                            subtitle = "${req.clientCompanyName.uppercase()} • ${req.latestQuoteNumber.ifBlank { "AWAITING QUOTE" }}",
                            primaryDotColor = VioletPrimary,
                            primaryStatText = "Scope: ${req.desiredServices.size.coerceAtLeast(1)}",
                            secondaryDotColor = KpiPeachStroke,
                            secondaryStatText = req.decisionStatusEnum().label.uppercase(),
                            trailingBadge = null,
                            canEdit = canCreateQuote,
                            canDelete = false,
                            onClick = onNavigateRequests,
                            onEditQuote = onNavigateRequests,
                            onDelete = {}
                        )
                    }
                }
            }
        }

        // 5. Compact Super Admin & Admin Controls Strip (1-tap governance without cluttering dashboard)
        if (access == AccessLevel.SUPER_ADMIN || access == AccessLevel.ADMIN) {
            item {
                AdminQuickMenuStrip(
                    isSuperAdmin = access == AccessLevel.SUPER_ADMIN,
                    pendingMembersCount = pendingMembers.size,
                    pendingRequestsCount = pendingRequests.size,
                    blockedCount = blockedDeliverables.size,
                    reviewCount = reviewQueue.size,
                    onOpenPermissionsAndTeam = onNavigateTeam,
                    onOpenRequests = onNavigateRequests,
                    onOpenPayments = onNavigatePayments,
                    onOpenCompanySettings = onNavigateCompanySettings,
                    onOpenOrgRouting = onNavigateOrgRouting,
                    onOpenMenuPanel = onOpenMenuPanel
                )
            }
        }

        // 6. Current Tracking / Recent Deliverable Activities
        item {
            SectionHeader(
                title = "Current Tracking",
                actionLabel = "See all",
                onAction = {
                    if (access == AccessLevel.STAFF) onNavigateMyWork()
                    else onNavigateProjects()
                }
            )
        }

        val recentTasks = if (access == AccessLevel.STAFF) myAssigned else state.deliverables
        if (recentTasks.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Outlined.TaskAlt,
                    title = "No deliverables yet",
                    subtitle = "Accepted project deliverables and staff updates will stream here in real time."
                )
            }
        } else {
            items(recentTasks.take(5), key = { "home_deliv_${it.id}" }) { d ->
                DeliverableCompactCard(
                    d = d,
                    cardColor = PureWhite,
                    showUpdateButton = access == AccessLevel.STAFF,
                    assigneePhotoBase64 = memberPhotoByUid[d.assignedToUid].orEmpty(),
                    onClick = { onOpenDeliverable(d) }
                )
            }
        }
    }

    if (showCleanupConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanupConfirm = false },
            containerColor = PureWhite,
            title = { Text("CLEAN OLD DONE PROJECTS?", fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal) },
            text = { Text("This permanently deletes completed and archived projects along with their deliverables.") },
            confirmButton = {
                Button(
                    onClick = {
                        showCleanupConfirm = false
                        onCleanupOldDoneProjects()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) { Text("DELETE ALL", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirm = false }) { Text("CANCEL") }
            }
        )
    }
}

@Composable
private fun DropdownPillSelector(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, SubtleOutline),
        modifier = Modifier.defaultMinSize(minHeight = 34.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = StudioTextSecondary,
                fontWeight = FontWeight.Medium
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = StudioTextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Clean White Project Card with Lavender Date Pill, 3-dot Edit/Delete Floating Popup Menu,
 * and Violet/Emerald indicator dots matching dashboard_UI_referance_a887d8be.jpg.
 */
@Composable
private fun DashboardReferenceProjectCard(
    dateText: String,
    title: String,
    subtitle: String,
    primaryDotColor: Color,
    primaryStatText: String,
    secondaryDotColor: Color,
    secondaryStatText: String,
    trailingBadge: String?,
    canEdit: Boolean,
    canDelete: Boolean,
    onClick: () -> Unit,
    onEditQuote: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, SubtleOutline),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = KpiLavenderBg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DateRange,
                            contentDescription = null,
                            tint = VioletPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = dateText,
                            style = MaterialTheme.typography.labelMedium,
                            color = ObsidianCharcoal,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Project Actions",
                            tint = StudioTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = PureWhite,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Visibility,
                                    contentDescription = null,
                                    tint = ObsidianCharcoal,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            text = { Text("Open Details", fontWeight = FontWeight.Medium) },
                            onClick = {
                                menuExpanded = false
                                onClick()
                            }
                        )
                        if (canEdit) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = null,
                                        tint = VioletPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                text = { Text("Edit / Quote", fontWeight = FontWeight.Medium) },
                                onClick = {
                                    menuExpanded = false
                                    onEditQuote()
                                }
                            )
                        }
                        if (canDelete) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = null,
                                        tint = CrimsonBlocked,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                text = { Text("Delete", color = CrimsonBlocked, fontWeight = FontWeight.Medium) },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    DotStatItem(dotColor = primaryDotColor, label = primaryStatText)
                    DotStatItem(dotColor = secondaryDotColor, label = secondaryStatText)
                }
                if (!trailingBadge.isNullOrBlank()) {
                    StatusPill(
                        label = trailingBadge,
                        containerColor = KpiMintBg,
                        contentColor = DeepMintInk
                    )
                }
            }
        }
    }
}

@Composable
private fun DotStatItem(dotColor: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = StudioTextSecondary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AdminQuickMenuStrip(
    isSuperAdmin: Boolean,
    pendingMembersCount: Int,
    pendingRequestsCount: Int,
    blockedCount: Int,
    reviewCount: Int,
    onOpenPermissionsAndTeam: () -> Unit,
    onOpenRequests: () -> Unit,
    onOpenPayments: () -> Unit,
    onOpenCompanySettings: () -> Unit,
    onOpenOrgRouting: () -> Unit,
    onOpenMenuPanel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, SubtleOutline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (isSuperAdmin) "SUPER ADMIN CONTROLS" else "ADMIN CONTROLS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = StudioTextSecondary
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickControlChip(
                    label = if (pendingMembersCount > 0) "Team ($pendingMembersCount)" else "Team & Perms",
                    icon = Icons.Outlined.AdminPanelSettings,
                    highlighted = pendingMembersCount > 0,
                    modifier = Modifier.testTag("quick_control_team"),
                    onClick = onOpenPermissionsAndTeam
                )
                QuickControlChip(
                    label = "Requests ($pendingRequestsCount)",
                    icon = Icons.Outlined.Inbox,
                    modifier = Modifier.testTag("quick_control_requests"),
                    onClick = onOpenRequests
                )
                QuickControlChip(
                    label = "Payments",
                    icon = Icons.Outlined.Payments,
                    modifier = Modifier.testTag("quick_control_payments"),
                    onClick = onOpenPayments
                )
                if (reviewCount + blockedCount > 0) {
                    QuickControlChip(
                        label = "Review ($reviewCount) • Blocked ($blockedCount)",
                        icon = Icons.AutoMirrored.Filled.FactCheck,
                        highlighted = blockedCount > 0,
                        onClick = onOpenRequests
                    )
                }
                if (isSuperAdmin) {
                    QuickControlChip(
                        label = "Depts & Routing",
                        icon = Icons.Outlined.AccountTree,
                        modifier = Modifier.testTag("quick_control_org"),
                        onClick = onOpenOrgRouting
                    )
                    QuickControlChip(
                        label = "Settings",
                        icon = Icons.Outlined.Business,
                        modifier = Modifier.testTag("quick_control_settings"),
                        onClick = onOpenCompanySettings
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickControlChip(
    label: String,
    icon: ImageVector,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (highlighted) ObsidianCharcoal else PureWhite,
        border = if (!highlighted) BorderStroke(1.dp, SubtleOutline) else null,
        modifier = modifier.defaultMinSize(minHeight = 44.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (highlighted) PureWhite else ObsidianCharcoal,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (highlighted) PureWhite else ObsidianCharcoal,
                maxLines = 1
            )
        }
    }
}

private fun formatShortDate(millis: Long): String {
    if (millis <= 0L) return "Active"
    return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))
}

fun statusColorsForDeliverable(st: DeliverableStatus): Pair<Color, Color> = when (st) {
    DeliverableStatus.APPROVED -> SoftMint to DeepMintInk
    DeliverableStatus.BLOCKED, DeliverableStatus.CHANGES_REQUESTED -> SoftBlush to DeepBlushInk
    DeliverableStatus.READY_FOR_REVIEW -> ButterYellow to DeepAmberInk
    DeliverableStatus.IN_PROGRESS -> KpiSkyBg to InfoPillInk
    DeliverableStatus.NOT_STARTED -> KpiLavenderBg to VioletPrimary
}

@Composable
fun DeliverableCompactCard(
    d: Deliverable,
    cardColor: Color = PureWhite,
    showUpdateButton: Boolean = false,
    assigneePhotoBase64: String = "",
    onClick: () -> Unit
) {
    val st = d.statusEnum()
    val (pillBg, pillFg) = statusColorsForDeliverable(st)
    val totalChecklist = d.totalChecklistCount.coerceAtLeast(1)
    val progress = if (st == DeliverableStatus.APPROVED) 1f else d.completedChecklistCount.toFloat() / totalChecklist.toFloat()

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = cardColor,
        border = BorderStroke(1.dp, SubtleOutline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(KpiLavenderBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = VioletPrimary, modifier = Modifier.size(18.dp))
                    }
                    Column {
                        Text(
                            d.title.uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = ObsidianCharcoal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${d.projectTitle.uppercase()} • ${d.clientCompanyName.uppercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = StudioTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                StatusPill(st.label.uppercase(), pillBg, pillFg)
            }

            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = VioletPrimary,
                trackColor = MutedSurface
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (d.assignedToName.isNotBlank()) {
                        OverlappingAvatarMembers(
                            members = listOf(d.assignedToName to assigneePhotoBase64),
                            avatarSize = 26.dp,
                            borderColor = cardColor
                        )
                    }
                    StatusPill(
                        label = MoneyFormatter.formatDate(d.dueDateMillis),
                        containerColor = MutedSurface,
                        contentColor = ObsidianCharcoal
                    )
                }
                if (showUpdateButton) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = ObsidianCharcoal,
                        contentColor = PureWhite
                    ) {
                        Text(
                            "Update",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                } else {
                    StatusPill(
                        label = "${d.completedChecklistCount}/${d.totalChecklistCount}",
                        containerColor = MutedSurface,
                        contentColor = ObsidianCharcoal
                    )
                }
            }
        }
    }
}
