package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.MoneyFormatter
import com.example.domain.WorkDistributionEngine
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

@Suppress("UNUSED_PARAMETER")
@Composable
fun HomeScreen(
    state: AgencyUiState,
    onOpenNewQuote: () -> Unit,
    onNavigateRequests: () -> Unit,
    onNavigateProjects: () -> Unit,
    onNavigateMyWork: () -> Unit,
    onNavigateTeam: () -> Unit,
    onNavigatePayments: () -> Unit,
    onNavigateQuotations: () -> Unit = onOpenNewQuote,
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
    val pendingRequests = remember(state.requests) { state.requests.filter { it.decisionStatusEnum() == DecisionStatus.PENDING } }
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

    val requestTimestamps = remember(pendingRequests, myAssigned, access, nowMillis) {
        if (access == AccessLevel.STAFF) {
            myAssigned.map { it.updatedAt?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: nowMillis }
        } else {
            pendingRequests.map { it.createdAt?.toDate()?.time ?: it.updatedAt?.toDate()?.time ?: nowMillis }
        }
    }
    val quoteTimestamps = remember(state.quotations, reviewQueue, access, nowMillis) {
        if (access == AccessLevel.STAFF) {
            reviewQueue.map { it.updatedAt?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: nowMillis }
        } else {
            state.quotations.map {
                when {
                    it.issuedDateMillis > 0L -> it.issuedDateMillis
                    it.createdAt != null -> it.createdAt.toDate().time
                    it.updatedAt != null -> it.updatedAt.toDate().time
                    else -> nowMillis
                }
            }
        }
    }
    val projectTimestamps = remember(activeProjects, nowMillis) {
        activeProjects.map { it.createdAt?.toDate()?.time ?: it.updatedAt?.toDate()?.time ?: nowMillis }
    }
    val taskListForMetric = if (access == AccessLevel.STAFF) myAssigned else state.deliverables
    val taskTimestamps = remember(taskListForMetric, nowMillis) {
        taskListForMetric.map { it.updatedAt?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: nowMillis }
    }

    val scopedRequestsCount = remember(requestTimestamps, cutoffMillis) {
        if (cutoffMillis == 0L) requestTimestamps.size else requestTimestamps.count { it >= cutoffMillis }
    }
    val scopedQuotesCount = remember(quoteTimestamps, cutoffMillis) {
        if (cutoffMillis == 0L) quoteTimestamps.size else quoteTimestamps.count { it >= cutoffMillis }
    }
    val scopedProjectsCount = remember(projectTimestamps, cutoffMillis, selectedWindow, activeProjects) {
        if (cutoffMillis == 0L) activeProjects.size else projectTimestamps.count { it >= cutoffMillis }
    }
    val scopedTasksCount = remember(taskTimestamps, cutoffMillis, taskListForMetric) {
        if (cutoffMillis == 0L) taskListForMetric.size else taskTimestamps.count { it >= cutoffMillis }
    }

    val requestsSeries = remember(requestTimestamps, selectedWindow, nowMillis) {
        buildSeriesBuckets(requestTimestamps, selectedWindow, nowMillis)
    }
    val quotesSeries = remember(quoteTimestamps, selectedWindow, nowMillis) {
        buildSeriesBuckets(quoteTimestamps, selectedWindow, nowMillis)
    }
    val projectsSeries = remember(projectTimestamps, selectedWindow, nowMillis) {
        buildSeriesBuckets(projectTimestamps, selectedWindow, nowMillis)
    }
    val tasksSeries = remember(taskTimestamps, selectedWindow, nowMillis) {
        buildSeriesBuckets(taskTimestamps, selectedWindow, nowMillis)
    }

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
                    text = "Overview",
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
                        sparklinePoints = requestsSeries,
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
                        sparklinePoints = quotesSeries,
                        onClick = { if (access == AccessLevel.STAFF) onNavigateMyWork() else onNavigateQuotations() },
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
                        sparklinePoints = projectsSeries,
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
                        sparklinePoints = tasksSeries,
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
                        text = "Projects",
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
                        val pillContainer by animateColorAsState(
                            targetValue = if (selected) ObsidianCharcoal else PureWhite,
                            animationSpec = tween(180),
                            label = "seg_bg_${seg.name}"
                        )
                        val pillContent by animateColorAsState(
                            targetValue = if (selected) PureWhite else ObsidianCharcoal,
                            animationSpec = tween(180),
                            label = "seg_fg_${seg.name}"
                        )
                        Surface(
                            onClick = { selectedSegment = seg },
                            shape = RoundedCornerShape(12.dp),
                            color = pillContainer,
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
                                    color = pillContent
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
                                Text("Clean Old Done (${completedProjects.size})", fontWeight = FontWeight.Bold)
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
                            title = formatTitleCase(proj.title),
                            subtitle = "${formatTitleCase(proj.clientCompanyName)} • ${proj.approvedQuoteNumber.ifBlank { "Active" }}",
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
                            onDelete = { onToggleDeleteProject(proj, true) },
                            modifier = Modifier.animateItem()
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
                            title = formatTitleCase(req.projectTitle),
                            subtitle = "${formatTitleCase(req.clientCompanyName)} • ${req.latestQuoteNumber.ifBlank { "Awaiting Quote" }}",
                            primaryDotColor = VioletPrimary,
                            primaryStatText = "Scope: ${req.desiredServices.size.coerceAtLeast(1)}",
                            secondaryDotColor = KpiPeachStroke,
                            secondaryStatText = req.decisionStatusEnum().label,
                            trailingBadge = null,
                            canEdit = canCreateQuote,
                            canDelete = false,
                            onClick = onNavigateRequests,
                            onEditQuote = onNavigateRequests,
                            onDelete = {},
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }

        // Compact Payment Tracker Widget for roles with Payment permissions
        if (state.hasPerm(AppPermission.VIEW_PAYMENTS) || state.hasPerm(AppPermission.RECORD_PAYMENTS)) {
            if (activeProjects.isNotEmpty()) {
                item {
                    val homeReminders = remember(state.projects, state.deliverables, state.quotations) {
                        WorkDistributionEngine.computePaymentReminders(
                            projects = state.projects,
                            deliverables = state.deliverables,
                            quotations = state.quotations
                        )
                    }
                    val totalAgreed = activeProjects.sumOf { it.agreedValuePaise }
                    val totalPaid = activeProjects.sumOf { it.paidPaise }
                    val totalDue = activeProjects.sumOf { it.outstandingPaise }
                    val pct = if (totalAgreed <= 0L) 0 else ((totalPaid.toDouble() / totalAgreed.toDouble()) * 100.0).toInt().coerceIn(0, 100)
                    val animatedPct by animateFloatAsState(
                        targetValue = (pct / 100f).coerceIn(0f, 1f),
                        animationSpec = tween(420, easing = FastOutSlowInEasing),
                        label = "home_payment_progress"
                    )
                    Surface(
                        onClick = onNavigatePayments,
                        shape = RoundedCornerShape(22.dp),
                        color = PureWhite,
                        border = BorderStroke(1.dp, SubtleOutline),
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home_payment_tracker_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(SoftMint),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, tint = DeepMintInk, modifier = Modifier.size(18.dp))
                                    }
                                    Text(
                                        text = "Payment Tracker",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = ObsidianCharcoal
                                    )
                                }
                                StatusPill("$pct% Collected", SoftMint, DeepMintInk)
                            }
                            LinearProgressIndicator(
                                progress = { animatedPct },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = EmeraldApproved,
                                trackColor = MutedSurface
                            )
                            if (homeReminders.totalCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (homeReminders.finalCount > 0) SoftBlush else ButterYellow.copy(alpha = 0.72f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Filled.NotificationsActive,
                                            contentDescription = null,
                                            tint = if (homeReminders.finalCount > 0) CrimsonBlocked else DeepAmberInk,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = buildString {
                                                append("Reminder: ")
                                                val parts = mutableListOf<String>()
                                                if (homeReminders.finalCount > 0) parts += "${homeReminders.finalCount} Final Due"
                                                if (homeReminders.advanceCount > 0) parts += "${homeReminders.advanceCount} Advance Due"
                                                append(parts.joinToString(" • "))
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (homeReminders.finalCount > 0) CrimsonBlocked else DeepAmberInk
                                        )
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Paid ${MoneyFormatter.formatInr(totalPaid)} • Due ${MoneyFormatter.formatInr(totalDue)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioTextSecondary
                                )
                                Text(
                                    text = "Open →",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = VioletPrimary
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Current Tracking / Recent Deliverable Activities
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
                    onClick = { onOpenDeliverable(d) },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }

    if (showCleanupConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanupConfirm = false },
            containerColor = PureWhite,
            title = { Text("Clean Old Done Projects?", fontWeight = FontWeight.Bold, color = ObsidianCharcoal) },
            text = { Text("This permanently deletes completed and archived projects along with their deliverables.") },
            confirmButton = {
                Button(
                    onClick = {
                        showCleanupConfirm = false
                        onCleanupOldDoneProjects()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite)
                ) { Text("Delete All", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirm = false }) { Text("Cancel") }
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
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, SubtleOutline),
        shadowElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
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
                    text = formatTitleCase(title),
                    style = MaterialTheme.typography.titleMedium,
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

private fun buildSeriesBuckets(
    timestamps: List<Long>,
    window: OverviewWindow,
    nowMillis: Long
): List<Float> {
    val bucketCount = when (window) {
        OverviewWindow.TODAY -> 6
        OverviewWindow.THIS_WEEK -> 7
        OverviewWindow.THIS_MONTH -> 6
        OverviewWindow.ALL_TIME -> 6
    }
    if (timestamps.isEmpty()) {
        return List(bucketCount) { 0.12f }
    }
    val spanStart = when (window) {
        OverviewWindow.TODAY -> nowMillis - 24L * 3600_000L
        OverviewWindow.THIS_WEEK -> nowMillis - 7L * 24L * 3600_000L
        OverviewWindow.THIS_MONTH -> nowMillis - 30L * 24L * 3600_000L
        OverviewWindow.ALL_TIME -> {
            val minTs = timestamps.minOrNull() ?: (nowMillis - 30L * 24L * 3600_000L)
            minOf(minTs, nowMillis - 6L * 3600_000L)
        }
    }
    val spanDuration = (nowMillis - spanStart).coerceAtLeast(bucketCount.toLong())
    val counts = IntArray(bucketCount)
    timestamps.forEach { ts ->
        if (ts >= spanStart) {
            val ratio = ((ts - spanStart).toDouble() / spanDuration.toDouble()).coerceIn(0.0, 0.9999)
            val idx = (ratio * bucketCount).toInt().coerceIn(0, bucketCount - 1)
            counts[idx]++
        }
    }
    val maxCount = counts.maxOrNull() ?: 0
    if (maxCount == 0) {
        return List(bucketCount) { 0.12f }
    }
    return counts.map { c ->
        0.12f + (c.toFloat() / maxCount.toFloat()) * 0.84f
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier
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
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
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
                            formatTitleCase(d.title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ObsidianCharcoal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${formatTitleCase(d.projectTitle)} • ${formatTitleCase(d.clientCompanyName)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = StudioTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                StatusPill(st.label, pillBg, pillFg)
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
