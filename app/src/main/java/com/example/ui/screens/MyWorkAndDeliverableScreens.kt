package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.SyncedData
import com.example.domain.MoneyFormatter
import com.example.model.*
import com.example.system.AppHapticsAndNotifier
import com.example.ui.AgencyUiState
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class DayCell(
    val dayOfMonth: Int,
    val dayKey: Long,
    val isCurrentMonth: Boolean
)

private fun startOfDayMillis(millis: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

@Composable
fun MyWorkScreen(
    state: AgencyUiState,
    onOpenDeliverable: (Deliverable) -> Unit,
    onStartDeliverable: (Deliverable) -> Unit = {},
    onClaimDepartmentTask: (Deliverable) -> Unit = {},
    onToggleTimer: (String) -> Unit = {},
    onQuickSubmitReview: (Deliverable) -> Unit = {},
    planModeRequestKey: Int = 0,
    onSavePlanItem: (StaffPlanItem, () -> Unit) -> Unit = { _, onDone -> onDone() },
    onTogglePlanCompleted: (StaffPlanItem) -> Unit = {},
    onReschedulePlanTomorrow: (StaffPlanItem) -> Unit = {},
    onCarryOverIncompletePlans: (List<StaffPlanItem>, Long) -> Unit = { _, _ -> },
    onDeletePlanItem: (String) -> Unit = {},
    onToggleFocusTimer: () -> Unit = {},
    onResetFocusTimer: () -> Unit = {},
    onSetFocusPreset: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val member = state.myMember
    val uid = member?.uid.orEmpty()
    val myDeptId = member?.departmentId.orEmpty()
    var filter by remember { mutableStateOf<DeliverableStatus?>(null) }
    var quickDueFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showQuickWeekStrip by remember { mutableStateOf(false) }

    val todayStart = remember { startOfDayMillis(System.currentTimeMillis()) }
    var selectedDayMillis by remember { mutableStateOf<Long?>(null) }
    var calendarMonthOffset by remember { mutableIntStateOf(0) }
    val photoByUid = remember(state.members) { state.members.associate { it.uid to it.photoBase64 } }

    val allMyDeliverables = remember(state.deliverables, uid) {
        state.deliverables
            .filter { it.assignedToUid == uid }
            .sortedBy { if (it.dueDateMillis > 0L) it.dueDateMillis else Long.MAX_VALUE }
    }

    val deptQueueTasks = remember(state.deliverables, myDeptId) {
        if (myDeptId.isBlank()) emptyList()
        else state.deliverables.filter {
            it.assignmentStatusEnum() == AssignmentStatus.DEPT_QUEUE &&
                it.assignedToUid.isBlank() &&
                it.departmentId == myDeptId &&
                it.statusEnum() != DeliverableStatus.APPROVED
        }.sortedBy { if (it.dueDateMillis > 0L) it.dueDateMillis else Long.MAX_VALUE }
    }

    val nowMillis = remember { System.currentTimeMillis() }
    val endOfTodayMillis = todayStart + 86_400_000L - 1L
    val overdueCount = remember(allMyDeliverables, todayStart) {
        allMyDeliverables.count {
            it.statusEnum() != DeliverableStatus.APPROVED && it.dueDateMillis in 1 until todayStart
        }
    }
    val dueTodayCount = remember(allMyDeliverables, todayStart, endOfTodayMillis) {
        allMyDeliverables.count {
            it.statusEnum() != DeliverableStatus.APPROVED && it.dueDateMillis in todayStart..endOfTodayMillis
        }
    }
    val urgentIssueCount = remember(allMyDeliverables) {
        allMyDeliverables.count {
            it.statusEnum() == DeliverableStatus.BLOCKED || it.statusEnum() == DeliverableStatus.CHANGES_REQUESTED
        }
    }

    val dueDotColorByDay = remember(allMyDeliverables) {
        val map = mutableMapOf<Long, Color>()
        allMyDeliverables.forEach { d ->
            if (d.dueDateMillis > 0L) {
                val key = startOfDayMillis(d.dueDateMillis)
                val dotColor = when (d.statusEnum()) {
                    DeliverableStatus.BLOCKED, DeliverableStatus.CHANGES_REQUESTED -> CrimsonBlocked
                    DeliverableStatus.APPROVED -> KpiMintStroke
                    DeliverableStatus.READY_FOR_REVIEW -> KpiPeachStroke
                    else -> VioletPrimary
                }
                if (!map.containsKey(key) || dotColor == CrimsonBlocked) {
                    map[key] = dotColor
                }
            }
        }
        map
    }

    val myDeliverables = remember(allMyDeliverables, filter, quickDueFilter, searchQuery, selectedDayMillis, todayStart, endOfTodayMillis) {
        allMyDeliverables
            .filter { filter == null || it.statusEnum() == filter }
            .filter { d ->
                when (quickDueFilter) {
                    "TODAY" -> d.statusEnum() != DeliverableStatus.APPROVED && d.dueDateMillis in todayStart..endOfTodayMillis
                    "OVERDUE" -> d.statusEnum() != DeliverableStatus.APPROVED && d.dueDateMillis in 1 until todayStart
                    else -> true
                }
            }
            .filter { d ->
                selectedDayMillis == null || (d.dueDateMillis > 0L && startOfDayMillis(d.dueDateMillis) == selectedDayMillis)
            }
            .filter { d ->
                searchQuery.isBlank() ||
                    d.title.contains(searchQuery, ignoreCase = true) ||
                    d.projectTitle.contains(searchQuery, ignoreCase = true) ||
                    d.clientCompanyName.contains(searchQuery, ignoreCase = true)
            }
    }

    val approvedCount = remember(allMyDeliverables) {
        allMyDeliverables.count { it.statusEnum() == DeliverableStatus.APPROVED }
    }
    val inProgressCount = remember(allMyDeliverables) {
        allMyDeliverables.count {
            it.statusEnum() in listOf(
                DeliverableStatus.IN_PROGRESS,
                DeliverableStatus.READY_FOR_REVIEW,
                DeliverableStatus.CHANGES_REQUESTED
            )
        }
    }
    val completionPct = remember(allMyDeliverables, approvedCount) {
        if (allMyDeliverables.isEmpty()) 0 else ((approvedCount * 100f) / allMyDeliverables.size).toInt().coerceIn(0, 100)
    }

    val displayCal = remember(calendarMonthOffset, selectedDayMillis) {
        Calendar.getInstance().apply {
            if (selectedDayMillis != null && calendarMonthOffset == 0) {
                timeInMillis = selectedDayMillis!!
            } else {
                add(Calendar.MONTH, calendarMonthOffset)
            }
        }
    }
    val headerDateText = remember(displayCal.timeInMillis) {
        SimpleDateFormat("d MMM, yy", Locale.getDefault()).format(Date(displayCal.timeInMillis))
    }
    val headerWeekdayText = remember(displayCal.timeInMillis) {
        SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(displayCal.timeInMillis))
    }

    val weekDays = remember(displayCal.timeInMillis) {
        val c = Calendar.getInstance().apply {
            timeInMillis = displayCal.timeInMillis
            firstDayOfWeek = Calendar.SUNDAY
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        }
        List(7) {
            val dayStart = startOfDayMillis(c.timeInMillis)
            val cell = DayCell(c.get(Calendar.DAY_OF_MONTH), dayStart, true)
            c.add(Calendar.DAY_OF_MONTH, 1)
            cell
        }
    }

    val monthCells = remember(displayCal.get(Calendar.YEAR), displayCal.get(Calendar.MONTH)) {
        val c = Calendar.getInstance().apply {
            timeInMillis = displayCal.timeInMillis
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val firstDayOffset = (c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).coerceIn(0, 6)
        val maxDays = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        val cells = mutableListOf<DayCell?>()
        repeat(firstDayOffset) { cells.add(null) }
        for (day in 1..maxDays) {
            c.set(Calendar.DAY_OF_MONTH, day)
            cells.add(DayCell(day, startOfDayMillis(c.timeInMillis), true))
        }
        while (cells.size % 7 != 0) cells.add(null)
        cells.chunked(7)
    }

    var showPlanMode by rememberSaveable { mutableStateOf(planModeRequestKey > 0) }
    LaunchedEffect(planModeRequestKey) {
        if (planModeRequestKey > 0) {
            showPlanMode = true
        }
    }

    if (showPlanMode) {
        StaffPlanCalendarSection(
            state = state,
            onSavePlanItem = onSavePlanItem,
            onTogglePlanCompleted = onTogglePlanCompleted,
            onRescheduleTomorrow = onReschedulePlanTomorrow,
            onCarryOverIncomplete = onCarryOverIncompletePlans,
            onDeletePlanItem = onDeletePlanItem,
            onOpenDeliverable = onOpenDeliverable,
            onToggleFocusTimer = onToggleFocusTimer,
            onResetFocusTimer = onResetFocusTimer,
            onSetFocusPreset = onSetFocusPreset,
            topModeSwitcher = {
                MyWorkPlanSwitcher(
                    showPlanMode = true,
                    taskCount = allMyDeliverables.size,
                    planCount = state.staffPlanItems.size,
                    onSelectPlan = { showPlanMode = it }
                )
            }
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .pastelAmbientBackdrop()
            .testTag("my_work_lazy_list"),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            MyWorkPlanSwitcher(
                showPlanMode = false,
                taskCount = allMyDeliverables.size,
                planCount = state.staffPlanItems.size,
                onSelectPlan = { showPlanMode = it }
            )
        }
        // 1. Primary Page Title + Compact Inline Productivity Summary Pill
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (selectedDayMillis != null) "Day Schedule (${myDeliverables.size})" else "My Work (${myDeliverables.size})",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = ObsidianCharcoal
                    )
                    Text(
                        text = "$inProgressCount active • $approvedCount done",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioTextSecondary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (filter != null || quickDueFilter != null || selectedDayMillis != null || searchQuery.isNotBlank()) {
                        TextButton(
                            onClick = {
                                filter = null
                                quickDueFilter = null
                                selectedDayMillis = null
                                searchQuery = ""
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Reset", color = VioletPrimary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = PureWhite,
                        border = BorderStroke(1.dp, SubtleOutline)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val strokeWidth = 3.2.dp.toPx()
                                    drawArc(
                                        color = VioletSoftBg,
                                        startAngle = 140f,
                                        sweepAngle = 260f,
                                        useCenter = false,
                                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                    )
                                    drawArc(
                                        color = VioletPrimary,
                                        startAngle = 140f,
                                        sweepAngle = 260f * (completionPct / 100f),
                                        useCenter = false,
                                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "Productivity",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = StudioTextSecondary
                                )
                                Text(
                                    text = "$completionPct%",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = ObsidianCharcoal
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Staff Priority Summary Strip + Plan Calendar Shortcut + Collapsible Week Strip
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("staff_priority_strip"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = quickDueFilter == "OVERDUE",
                    onClick = {
                        AppHapticsAndNotifier.triggerTickHaptic(context)
                        quickDueFilter = if (quickDueFilter == "OVERDUE") null else "OVERDUE"
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CrimsonBlocked,
                        selectedLabelColor = PureWhite,
                        containerColor = if (overdueCount > 0) SoftBlush else SoftMint,
                        labelColor = if (overdueCount > 0) DeepBlushInk else DeepMintInk
                    ),
                    border = null,
                    label = {
                        Text(
                            if (overdueCount > 0) "Overdue ($overdueCount)" else "No Overdue",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                )
                FilterChip(
                    selected = quickDueFilter == "TODAY",
                    onClick = {
                        AppHapticsAndNotifier.triggerTickHaptic(context)
                        quickDueFilter = if (quickDueFilter == "TODAY") null else "TODAY"
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ObsidianCharcoal,
                        selectedLabelColor = PureWhite,
                        containerColor = if (dueTodayCount > 0) ButterYellow else MutedSurface,
                        labelColor = if (dueTodayCount > 0) DeepAmberInk else ObsidianCharcoal
                    ),
                    border = null,
                    label = { Text("Due Today ($dueTodayCount)", fontWeight = FontWeight.SemiBold) }
                )
                if (urgentIssueCount > 0) {
                    StatusPill(
                        label = "Attention ($urgentIssueCount)",
                        containerColor = SoftBlush,
                        contentColor = DeepBlushInk
                    )
                }
                if (deptQueueTasks.isNotEmpty()) {
                    StatusPill(
                        label = "Dept Queue (${deptQueueTasks.size})",
                        containerColor = KpiLavenderBg,
                        contentColor = VioletPrimary
                    )
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = PureWhite,
                border = BorderStroke(1.dp, SubtleOutline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "$headerDateText • $headerWeekdayText",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = ObsidianCharcoal
                            )
                            Text(
                                text = "Tap Plan tab above for monthly task blocks",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextSecondary
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = {
                                    AppHapticsAndNotifier.triggerTickHaptic(context)
                                    showPlanMode = true
                                },
                                shape = RoundedCornerShape(999.dp),
                                color = KpiLavenderBg
                            ) {
                                Text(
                                    text = "Plan →",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = VioletPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                )
                            }
                            Surface(
                                onClick = {
                                    AppHapticsAndNotifier.triggerTickHaptic(context)
                                    showQuickWeekStrip = !showQuickWeekStrip
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (showQuickWeekStrip) ObsidianCharcoal else MutedSurface,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("toggle_calendar_view_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (showQuickWeekStrip) Icons.Filled.ExpandLess else Icons.Filled.DateRange,
                                        contentDescription = if (showQuickWeekStrip) "Hide due dates" else "Filter by due date",
                                        tint = if (showQuickWeekStrip) PureWhite else ObsidianCharcoal,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = showQuickWeekStrip,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Filter tasks by due day",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioTextSecondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    IconButton(
                                        onClick = { calendarMonthOffset -= 1 },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous", tint = ObsidianCharcoal)
                                    }
                                    IconButton(
                                        onClick = { calendarMonthOffset += 1 },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next", tint = ObsidianCharcoal)
                                    }
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth()) {
                                listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { dLabel ->
                                    Text(
                                        text = dLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioTextSecondary,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth()) {
                                weekDays.forEach { cell ->
                                    CalendarDayItem(
                                        cell = cell,
                                        isSelected = selectedDayMillis == cell.dayKey || (selectedDayMillis == null && cell.dayKey == todayStart),
                                        dotColor = dueDotColorByDay[cell.dayKey],
                                        onClick = {
                                            AppHapticsAndNotifier.triggerTickHaptic(context)
                                            selectedDayMillis = if (selectedDayMillis == cell.dayKey) null else cell.dayKey
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            if (selectedDayMillis != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Due on ${MoneyFormatter.formatDate(selectedDayMillis!!)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VioletPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    TextButton(
                                        onClick = { selectedDayMillis = null },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Show All Dates", style = MaterialTheme.typography.labelSmall, color = ObsidianCharcoal)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Search + Status Filter Chips
        item {
            MonochromeSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search assigned tasks or clients…"
            )
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = filter == null,
                        borderColor = SubtleOutline
                    ),
                    label = { Text("All", fontWeight = FontWeight.SemiBold) }
                )
                listOf(
                    DeliverableStatus.NOT_STARTED,
                    DeliverableStatus.IN_PROGRESS,
                    DeliverableStatus.BLOCKED,
                    DeliverableStatus.CHANGES_REQUESTED,
                    DeliverableStatus.READY_FOR_REVIEW,
                    DeliverableStatus.APPROVED
                ).forEach { st ->
                    FilterChip(
                        selected = filter == st,
                        onClick = { filter = st },
                        shape = RoundedCornerShape(999.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ObsidianCharcoal,
                            selectedLabelColor = PureWhite,
                            containerColor = PureWhite,
                            labelColor = ObsidianCharcoal
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = filter == st,
                            borderColor = SubtleOutline
                        ),
                        label = { Text(st.label, fontWeight = FontWeight.SemiBold) }
                    )
                }
            }
        }

        // 4. Department Queue Pickup Section (for unclaimed tasks in staff's own department)
        if (deptQueueTasks.isNotEmpty()) {
            item {
                SectionHeader(title = "Department Queue (${deptQueueTasks.size})")
            }
            items(deptQueueTasks.size, key = { idx -> "queue_${deptQueueTasks[idx].id}" }) { idx ->
                val qTask = deptQueueTasks[idx]
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = KpiLavenderBg.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, VioletPrimary.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = formatTitleCase(qTask.title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = ObsidianCharcoal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${formatTitleCase(qTask.projectTitle)} • ${MoneyFormatter.formatDate(qTask.dueDateMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Button(
                            onClick = {
                                AppHapticsAndNotifier.triggerCompletionVibration(context)
                                onClaimDepartmentTask(qTask)
                            },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("claim_task_${qTask.id}")
                        ) {
                            Text("Claim Task", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 5. Pastel Color-Coded Deliverable Schedule Cards
        if (myDeliverables.isEmpty()) {
            item {
                EmptyStateCard(
                    Icons.Outlined.TaskAlt,
                    "No deliverables match this filter",
                    "Your assigned deliverables appear here ordered by due date with checklists and update actions."
                )
            }
        } else {
            items(myDeliverables.size, key = { idx -> myDeliverables[idx].id }) { idx ->
                val d = myDeliverables[idx]
                val tintBg = when (d.statusEnum()) {
                    DeliverableStatus.BLOCKED, DeliverableStatus.CHANGES_REQUESTED -> SoftBlush.copy(alpha = 0.72f)
                    DeliverableStatus.APPROVED -> KpiMintBg
                    DeliverableStatus.READY_FOR_REVIEW -> KpiPeachBg
                    DeliverableStatus.IN_PROGRESS -> KpiSkyBg
                    DeliverableStatus.NOT_STARTED -> KpiLavenderBg
                }
                val elapsedSecs = state.timerSecondsByDeliverable[d.id] ?: 0L
                val isTimerRunning = state.activeTimerDeliverableId == d.id
                PastelDeliverableScheduleCard(
                    deliverable = d,
                    cardColor = tintBg,
                    assigneePhotoBase64 = photoByUid[d.assignedToUid].orEmpty(),
                    todayStartMillis = todayStart,
                    nowMillis = nowMillis,
                    elapsedTimerSeconds = elapsedSecs,
                    isTimerRunning = isTimerRunning,
                    onToggleTimer = { onToggleTimer(d.id) },
                    onSlideStart = { onStartDeliverable(d) },
                    onQuickSubmitReview = { onQuickSubmitReview(d) },
                    onClick = { onOpenDeliverable(d) }
                )
            }
        }
    }
}

@Composable
private fun MyWorkPlanSwitcher(
    showPlanMode: Boolean,
    taskCount: Int,
    planCount: Int,
    onSelectPlan: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, SubtleOutline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("my_work_plan_switcher")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                onClick = { onSelectPlan(false) },
                shape = RoundedCornerShape(999.dp),
                color = if (!showPlanMode) ObsidianCharcoal else Color.Transparent,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("my_work_mode_tasks")
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.TaskAlt,
                        contentDescription = null,
                        tint = if (!showPlanMode) PureWhite else ObsidianCharcoal,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (taskCount > 0) "Tasks ($taskCount)" else "Tasks",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (!showPlanMode) PureWhite else ObsidianCharcoal
                    )
                }
            }

            Surface(
                onClick = { onSelectPlan(true) },
                shape = RoundedCornerShape(999.dp),
                color = if (showPlanMode) Color(0xFF6C3BF5) else Color.Transparent,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("my_work_mode_plan")
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.CalendarMonth,
                        contentDescription = null,
                        tint = if (showPlanMode) PureWhite else ObsidianCharcoal,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (planCount > 0) "Plan ($planCount)" else "Plan",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (showPlanMode) PureWhite else ObsidianCharcoal
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarDayItem(
    cell: DayCell,
    isSelected: Boolean,
    dotColor: Color?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (isSelected) ObsidianCharcoal else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = cell.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) PureWhite else ObsidianCharcoal
            )
        }
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(dotColor ?: Color.Transparent)
        )
    }
}

private fun formatCountdownLabel(dueMillis: Long, todayStartMillis: Long, nowMillis: Long): Pair<String, Boolean> {
    if (dueMillis <= 0L) return "Scheduled" to false
    val diffDays = ((startOfDayMillis(dueMillis) - todayStartMillis) / 86_400_000L).toInt()
    return when {
        diffDays < 0 -> "Overdue ${-diffDays}d" to true
        diffDays == 0 -> "Due Today" to false
        diffDays == 1 -> "Due Tomorrow" to false
        else -> "Due in ${diffDays}d" to false
    }
}

private fun formatElapsedTimer(seconds: Long): String {
    val hrs = seconds / 3600L
    val mins = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return if (hrs > 0L) "%d:%02d:%02d".format(hrs, mins, secs) else "%02d:%02d".format(mins, secs)
}

@Composable
private fun PastelDeliverableScheduleCard(
    deliverable: Deliverable,
    cardColor: Color,
    assigneePhotoBase64: String = "",
    todayStartMillis: Long = startOfDayMillis(System.currentTimeMillis()),
    nowMillis: Long = System.currentTimeMillis(),
    elapsedTimerSeconds: Long = 0L,
    isTimerRunning: Boolean = false,
    onToggleTimer: () -> Unit = {},
    onSlideStart: () -> Unit = {},
    onQuickSubmitReview: () -> Unit = {},
    onClick: () -> Unit
) {
    val st = deliverable.statusEnum()
    val (pillBg, pillFg) = statusColorsForDeliverable(st)
    val doneCount = deliverable.checklist.count { it.done }
    val totalCount = deliverable.checklist.size
    val avatarMembers = buildList {
        if (deliverable.assignedToName.isNotBlank()) add(deliverable.assignedToName to assigneePhotoBase64)
        if (deliverable.clientCompanyName.isNotBlank()) add(deliverable.clientCompanyName to "")
    }
    val (countdownText, isOverdue) = formatCountdownLabel(deliverable.dueDateMillis, todayStartMillis, nowMillis)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onClick),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = formatTitleCase(deliverable.title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ObsidianCharcoal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${formatTitleCase(deliverable.projectTitle)} • ${formatTitleCase(deliverable.departmentName)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = StudioTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusPill(st.label, pillBg, pillFg)
                        if (st != DeliverableStatus.APPROVED) {
                            StatusPill(
                                label = countdownText,
                                containerColor = if (isOverdue) CrimsonBlocked else PureWhite,
                                contentColor = if (isOverdue) PureWhite else ObsidianCharcoal
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = ObsidianCharcoal,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = MoneyFormatter.formatDate(deliverable.dueDateMillis) +
                                if (totalCount > 0) " • $doneCount/$totalCount steps" else "",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = ObsidianCharcoal
                        )
                    }
                    if (avatarMembers.isNotEmpty()) {
                        OverlappingAvatarMembers(
                            members = avatarMembers,
                            avatarSize = 26.dp,
                            borderColor = cardColor
                        )
                    }
                }
            }

            if (st == DeliverableStatus.NOT_STARTED) {
                SlideToActionButton(
                    label = "SLIDE TO START WORK",
                    onSlideComplete = onSlideStart,
                    testTag = "slide_start_${deliverable.id}"
                )
            } else if (st == DeliverableStatus.IN_PROGRESS || st == DeliverableStatus.CHANGES_REQUESTED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onToggleTimer,
                        shape = RoundedCornerShape(999.dp),
                        color = if (isTimerRunning) ObsidianCharcoal else PureWhite,
                        border = if (isTimerRunning) null else BorderStroke(1.dp, SubtleOutline),
                        modifier = Modifier
                            .height(38.dp)
                            .testTag("timer_toggle_${deliverable.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (isTimerRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isTimerRunning) "Pause timer" else "Start timer",
                                tint = if (isTimerRunning) PureWhite else ObsidianCharcoal,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (isTimerRunning || elapsedTimerSeconds > 0L) formatElapsedTimer(elapsedTimerSeconds) else "Start Timer",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isTimerRunning) PureWhite else ObsidianCharcoal
                            )
                        }
                    }

                    if (totalCount == 0 || doneCount == totalCount) {
                        Button(
                            onClick = onQuickSubmitReview,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(38.dp)
                                .testTag("quick_submit_review_${deliverable.id}")
                        ) {
                            Text("Submit Review", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeliverableDetailDialog(
    state: AgencyUiState,
    deliverable: Deliverable,
    updatesFlow: Flow<SyncedData<List<DeliverableUpdate>>>,
    onSubmitProgress: (Deliverable, DeliverableStatus, List<ChecklistItem>, String, String, String, String) -> Unit,
    onReviewDeliverable: (Deliverable, UpdateActionType, String) -> Unit,
    onStartWork: (Deliverable) -> Unit = {},
    onToggleTimer: (String) -> Unit = {},
    onResetTimer: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val updatesSynced by updatesFlow.collectAsStateWithLifecycle(initialValue = SyncedData(emptyList(), false))
    val uid = state.myMember?.uid.orEmpty()
    val isAssignee = deliverable.assignedToUid == uid
    val canApprove = state.hasPerm(AppPermission.APPROVE_DELIVERABLES)

    var detailTab by remember(deliverable.id) { mutableStateOf(0) } // 0 = Details & Timeline, 1 = Checklist & Review
    var checklist by remember(deliverable.id) { mutableStateOf(deliverable.checklist) }
    var selectedStatus by remember(deliverable.id) {
        mutableStateOf(
            if (deliverable.statusEnum() in listOf(DeliverableStatus.NOT_STARTED, DeliverableStatus.IN_PROGRESS, DeliverableStatus.BLOCKED, DeliverableStatus.READY_FOR_REVIEW)) {
                deliverable.statusEnum()
            } else {
                DeliverableStatus.IN_PROGRESS
            }
        )
    }
    var note by remember(deliverable.id) { mutableStateOf("") }
    var previewUrl by remember(deliverable.id) { mutableStateOf(deliverable.previewUrl) }
    var blockedReason by remember(deliverable.id) { mutableStateOf(deliverable.blockedReason) }
    var helpNeeded by remember(deliverable.id) { mutableStateOf(deliverable.helpNeeded) }
    var reviewFeedback by remember { mutableStateOf("") }
    var savedFeedbackBanner by remember(deliverable.id) { mutableStateOf<String?>(null) }
    var justSavedButton by remember(deliverable.id) { mutableStateOf(false) }

    LaunchedEffect(savedFeedbackBanner) {
        if (!savedFeedbackBanner.isNullOrBlank()) {
            delay(3800L)
            savedFeedbackBanner = null
            justSavedButton = false
        }
    }

    val st = deliverable.statusEnum()
    val (stBg, stFg) = statusColorsForDeliverable(st)
    val started = st != DeliverableStatus.NOT_STARTED
    val approved = st == DeliverableStatus.APPROVED
    val ownerPhoto = state.members.firstOrNull { it.uid == deliverable.assignedToUid }?.photoBase64.orEmpty()
    val elapsedSecs = state.timerSecondsByDeliverable[deliverable.id] ?: 0L
    val isTimerRunning = state.activeTimerDeliverableId == deliverable.id

    val timeFmt = remember { SimpleDateFormat("h:mma", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = CrispCanvas
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ObsidianCharcoal)
                    }
                    Text("Deliverable Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                    Spacer(Modifier.size(48.dp))
                }

                AnimatedVisibility(
                    visible = !savedFeedbackBanner.isNullOrBlank(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SoftMint,
                        border = BorderStroke(1.dp, EmeraldApproved.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("deliverable_saved_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = EmeraldApproved, modifier = Modifier.size(20.dp))
                            Text(
                                text = savedFeedbackBanner.orEmpty(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = DeepMintInk,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
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
                        Text("Deliverable", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = StudioTextSecondary)
                        Text(
                            text = formatTitleCase(deliverable.title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ObsidianCharcoal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    StatusPill(st.label, stBg, stFg)
                }

                if (isAssignee && st == DeliverableStatus.NOT_STARTED) {
                    SlideToActionButton(
                        label = "SLIDE TO START WORK",
                        onSlideComplete = {
                            AppHapticsAndNotifier.triggerCompletionVibration(context)
                            onStartWork(deliverable)
                            selectedStatus = DeliverableStatus.IN_PROGRESS
                            savedFeedbackBanner = "Progress saved — started work (In Progress)"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "dialog_slide_start_button"
                    )
                }

                StepProgressTimeline(
                    steps = listOf(
                        TimelineStep("Assigned", deliverable.departmentName, completed = true),
                        TimelineStep("In Progress", "${checklist.count { it.done }}/${checklist.size}", completed = started),
                        TimelineStep("Approved", MoneyFormatter.formatDate(deliverable.dueDateMillis), completed = approved)
                    )
                )

                // Segmented Pill Switcher
                SegmentedPillTabs(
                    options = listOf("Details & Timeline", if (isAssignee) "Checklist & Update" else "Review (${updatesSynced.data.size})"),
                    selectedIndex = detailTab,
                    onSelect = { detailTab = it }
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("deliverable_detail_list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (detailTab == 0) {
                        item {
                            val rows = buildList {
                                add(DetailKeyValueRow("Client", deliverable.clientCompanyName))
                                add(DetailKeyValueRow("Project", deliverable.projectTitle))
                                add(DetailKeyValueRow("Department", "${deliverable.departmentName} • ${deliverable.jobRoleName}"))
                                add(DetailKeyValueRow("Due Date", MoneyFormatter.formatDate(deliverable.dueDateMillis)))
                                add(DetailKeyValueRow("Revisions", "${deliverable.clientRevisionsUsed}/${deliverable.clientRevisionLimit} rounds used"))
                                if (deliverable.blockedReason.isNotBlank()) {
                                    add(DetailKeyValueRow("Blocked Note", deliverable.blockedReason, isWarning = true))
                                } else if (deliverable.latestFeedback.isNotBlank()) {
                                    add(DetailKeyValueRow("Note", deliverable.latestFeedback, isWarning = st == DeliverableStatus.CHANGES_REQUESTED))
                                }
                            }
                            DetailKeyValueCard(
                                title = "Delivery Details",
                                icon = Icons.Outlined.LocalShipping,
                                rows = rows
                            )
                        }

                        // Assignee Card
                        item {
                            val ownerName = deliverable.assignedToName.ifBlank { deliverable.assignmentStatusEnum().label }
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
                                    StudioAvatar(name = ownerName, size = 44.dp, photoBase64 = ownerPhoto)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(formatTitleCase(ownerName), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                                        Text("${formatTitleCase(deliverable.jobRoleName)} • Assignee", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                    }
                                    Surface(
                                        onClick = { detailTab = 1 },
                                        shape = CircleShape,
                                        color = PureWhite,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Update or Review", tint = ObsidianCharcoal, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // Vertical Dotted Update History matching Screen 2 Bottom Timeline
                        item {
                            val entries = if (updatesSynced.data.isEmpty()) {
                                listOf(
                                    VerticalTimelineEntry(
                                        id = "created",
                                        timeLabel = "Initial",
                                        dateLabel = MoneyFormatter.formatDate(deliverable.dueDateMillis),
                                        description = "Deliverable scheduled under ${deliverable.projectTitle} (${deliverable.checklist.size} checklist steps)."
                                    )
                                )
                            } else {
                                updatesSynced.data.map { upd ->
                                    val tsMillis = upd.createdAt?.toDate()?.time ?: System.currentTimeMillis()
                                    val tStr = timeFmt.format(Date(tsMillis)).lowercase()
                                    val dStr = dateFmt.format(Date(tsMillis))
                                    val summary = buildString {
                                        append("${upd.authorName} marked ${upd.actionTypeEnum().label}")
                                        if (upd.note.isNotBlank()) append(" — ${upd.note}")
                                        if (upd.blockedReason.isNotBlank()) append(" (Blocked: ${upd.blockedReason})")
                                    }
                                    VerticalTimelineEntry(
                                        id = upd.id,
                                        timeLabel = tStr,
                                        dateLabel = dStr,
                                        description = summary,
                                        highlightRed = upd.actionTypeEnum() == UpdateActionType.BLOCKED || upd.actionTypeEnum() == UpdateActionType.CHANGES_REQUESTED
                                    )
                                }
                            }
                            VerticalTimelineLog(entries = entries)
                        }
                    } else {
                        if (deliverable.projectBrief.isNotBlank() || deliverable.latestFeedback.isNotBlank()) {
                            item {
                                Surface(shape = RoundedCornerShape(20.dp), color = MutedSurface) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (deliverable.projectBrief.isNotBlank()) {
                                            Text("Brief: ${deliverable.projectBrief}", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                        }
                                        if (deliverable.latestFeedback.isNotBlank()) {
                                            Text("Latest Feedback: ${deliverable.latestFeedback}", style = MaterialTheme.typography.bodySmall, color = CrimsonBlocked, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            val doneCount = checklist.count { it.done }
                            Text("Checklist ($doneCount of ${checklist.size} complete)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                            Spacer(Modifier.height(6.dp))
                            checklist.forEachIndexed { idx, item ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = PureWhite,
                                    border = BorderStroke(1.dp, SubtleOutline),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = item.done,
                                            enabled = isAssignee,
                                            colors = CheckboxDefaults.colors(checkedColor = ObsidianCharcoal),
                                            onCheckedChange = { checked ->
                                                AppHapticsAndNotifier.triggerTickHaptic(context)
                                                checklist = checklist.mapIndexed { i, c -> if (i == idx) c.copy(done = checked) else c }
                                            }
                                        )
                                        Text(item.title, style = MaterialTheme.typography.bodyMedium, color = ObsidianCharcoal, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        if (isAssignee) {
                            if (!approved) {
                                item {
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = KpiLavenderBg,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    text = "Focus Work Timer",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = VioletPrimary
                                                )
                                                Text(
                                                    text = formatElapsedTimer(elapsedSecs),
                                                    style = MaterialTheme.typography.headlineSmall,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = ObsidianCharcoal
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Button(
                                                    onClick = {
                                                        AppHapticsAndNotifier.triggerTickHaptic(context)
                                                        onToggleTimer(deliverable.id)
                                                    },
                                                    shape = RoundedCornerShape(999.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(40.dp).testTag("dialog_timer_toggle_button")
                                                ) {
                                                    Icon(
                                                        if (isTimerRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(if (isTimerRunning) "Pause" else "Start", fontWeight = FontWeight.Bold)
                                                }
                                                if (elapsedSecs > 0L) {
                                                    OutlinedButton(
                                                        onClick = {
                                                            val stamped = "Logged ${formatElapsedTimer(elapsedSecs)}"
                                                            note = if (note.isBlank()) stamped else "$note\n$stamped"
                                                            onResetTimer(deliverable.id)
                                                        },
                                                        shape = RoundedCornerShape(999.dp),
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                        modifier = Modifier.height(40.dp)
                                                    ) {
                                                        Text("Log Time", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                Surface(shape = RoundedCornerShape(22.dp), color = MutedSurface) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Update Progress", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listOf(
                                                DeliverableStatus.NOT_STARTED,
                                                DeliverableStatus.IN_PROGRESS,
                                                DeliverableStatus.BLOCKED,
                                                DeliverableStatus.READY_FOR_REVIEW
                                            ).forEach { opt ->
                                                FilterChip(
                                                    selected = selectedStatus == opt,
                                                    onClick = {
                                                        AppHapticsAndNotifier.triggerTickHaptic(context)
                                                        selectedStatus = opt
                                                    },
                                                    shape = RoundedCornerShape(999.dp),
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = ObsidianCharcoal,
                                                        selectedLabelColor = PureWhite,
                                                        containerColor = PureWhite,
                                                        labelColor = ObsidianCharcoal
                                                    ),
                                                    border = null,
                                                    label = { Text(opt.label, fontWeight = FontWeight.SemiBold) }
                                                )
                                            }
                                        }
                                        if (selectedStatus == DeliverableStatus.BLOCKED) {
                                            OutlinedTextField(
                                                value = blockedReason,
                                                onValueChange = { blockedReason = it },
                                                label = { Text("Blocked Reason *") },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            OutlinedTextField(
                                                value = helpNeeded,
                                                onValueChange = { helpNeeded = it },
                                                label = { Text("Help / Input Needed") },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        OutlinedTextField(
                                            value = previewUrl,
                                            onValueChange = { previewUrl = it },
                                            label = { Text("Preview / Drive / Staging URL") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        OutlinedTextField(
                                            value = note,
                                            onValueChange = { note = it },
                                            label = { Text("Progress Note") },
                                            minLines = 2,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            item {
                                Button(
                                    onClick = {
                                        AppHapticsAndNotifier.triggerCompletionVibration(context)
                                        onSubmitProgress(deliverable, selectedStatus, checklist, note, previewUrl, blockedReason, helpNeeded)
                                        savedFeedbackBanner = "Progress saved (${selectedStatus.label}) • ${checklist.count { it.done }}/${checklist.size} steps"
                                        justSavedButton = true
                                        note = ""
                                    },
                                    enabled = selectedStatus != DeliverableStatus.BLOCKED || blockedReason.isNotBlank(),
                                    shape = RoundedCornerShape(999.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (justSavedButton) EmeraldApproved else ObsidianCharcoal,
                                        contentColor = PureWhite
                                    ),
                                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("submit_progress_button")
                                ) {
                                    Text(
                                        if (justSavedButton) "Saved ✓" else "Save Progress Update",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        if (canApprove) {
                            item {
                                Surface(shape = RoundedCornerShape(22.dp), color = MutedSurface) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Manager Review & Client Revisions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                                        OutlinedTextField(
                                            value = reviewFeedback,
                                            onValueChange = { reviewFeedback = it },
                                            label = { Text("Review Feedback / Revision Notes") },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            OutlinedButton(
                                                onClick = {
                                                    AppHapticsAndNotifier.triggerCompletionVibration(context)
                                                    onReviewDeliverable(deliverable, UpdateActionType.CHANGES_REQUESTED, reviewFeedback.ifBlank { "Please refine deliverable per brief." })
                                                    savedFeedbackBanner = "Review saved: Changes Requested"
                                                    reviewFeedback = ""
                                                },
                                                shape = RoundedCornerShape(999.dp),
                                                border = BorderStroke(1.dp, SubtleOutline),
                                                modifier = Modifier.weight(1f).height(48.dp).testTag("request_changes_button")
                                            ) { Text("Request Changes", color = ObsidianCharcoal) }
                                            Button(
                                                onClick = {
                                                    AppHapticsAndNotifier.triggerCompletionVibration(context)
                                                    onReviewDeliverable(deliverable, UpdateActionType.APPROVED, reviewFeedback.ifBlank { "Approved for client delivery." })
                                                    savedFeedbackBanner = "Deliverable Approved ✓"
                                                    reviewFeedback = ""
                                                },
                                                shape = RoundedCornerShape(999.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                                                modifier = Modifier.weight(1f).height(48.dp).testTag("approve_deliverable_button")
                                            ) { Text("Approve", fontWeight = FontWeight.SemiBold) }
                                        }
                                        TextButton(
                                            onClick = {
                                                AppHapticsAndNotifier.triggerCompletionVibration(context)
                                                onReviewDeliverable(deliverable, UpdateActionType.CLIENT_REVISION, reviewFeedback.ifBlank { "Client requested revision round." })
                                                savedFeedbackBanner = "Logged client revision round"
                                                reviewFeedback = ""
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("Log Client Revision (+1 of ${deliverable.clientRevisionLimit} rounds)", color = InfoPillInk, fontWeight = FontWeight.SemiBold) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
