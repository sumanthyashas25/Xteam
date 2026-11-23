package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.MoneyFormatter
import com.example.model.*
import com.example.system.AppHapticsAndNotifier
import com.example.ui.AgencyUiState
import com.example.ui.components.StatusPill
import com.example.ui.components.pastelAmbientBackdrop
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val PlanVioletAccent = Color(0xFF6C3BF5)
private val PlanOutlineBlue = Color(0xFF4A80C4)

private enum class PlanFilterMode(val label: String) {
    TODAY("Today's Task"),
    ALL_MONTH("All Month"),
    TO_DO("To Do"),
    EVENT("Event"),
    REMINDER("Reminder"),
    MILESTONE("Milestone")
}

private data class MonthDaySlot(
    val dayOfMonth: Int,
    val dayKey: Long
)

private data class CreativePlanTemplate(
    val label: String,
    val category: PlanCategory,
    val creativeTag: String,
    val defaultTime: String
)

private val creativeTemplates = listOf(
    CreativePlanTemplate("Creative Sprint", PlanCategory.TO_DO, "Design", "10:00AM - 11:30 AM"),
    CreativePlanTemplate("Client Review", PlanCategory.EVENT, "Client", "12:30 - 1:00 PM"),
    CreativePlanTemplate("Design Handoff", PlanCategory.MILESTONE, "Assets", "4:20 - 5:00 PM"),
    CreativePlanTemplate("Daily Standup", PlanCategory.REMINDER, "Team", "9:30AM - 9:45 AM")
)

private fun normalizeDayStart(millis: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

private fun dotColorForCategory(category: PlanCategory): Color = when (category) {
    PlanCategory.TO_DO -> PlanOutlineBlue
    PlanCategory.EVENT -> Color(0xFFEA580C)
    PlanCategory.REMINDER -> Color(0xFF10B981)
    PlanCategory.MILESTONE -> PlanVioletAccent
}

private fun iconForCategory(category: PlanCategory, completed: Boolean): ImageVector = when {
    completed && category == PlanCategory.TO_DO -> Icons.Filled.CheckCircle
    category == PlanCategory.TO_DO -> Icons.Outlined.RadioButtonUnchecked
    category == PlanCategory.EVENT -> Icons.Outlined.CalendarToday
    category == PlanCategory.REMINDER -> Icons.Outlined.NotificationsNone
    category == PlanCategory.MILESTONE -> Icons.Outlined.DonutLarge
    else -> Icons.Outlined.RadioButtonUnchecked
}

private fun formatCountdownMmSs(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val mins = safe / 60L
    val secs = safe % 60L
    return "%02d:%02d".format(mins, secs)
}

@Composable
fun StaffPlanCalendarSection(
    state: AgencyUiState,
    onSavePlanItem: (StaffPlanItem, () -> Unit) -> Unit,
    onTogglePlanCompleted: (StaffPlanItem) -> Unit,
    onRescheduleTomorrow: (StaffPlanItem) -> Unit,
    onCarryOverIncomplete: (List<StaffPlanItem>, Long) -> Unit,
    onDeletePlanItem: (String) -> Unit,
    onOpenDeliverable: (Deliverable) -> Unit,
    onToggleFocusTimer: () -> Unit,
    onResetFocusTimer: () -> Unit,
    onSetFocusPreset: (Int) -> Unit,
    topModeSwitcher: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val uid = state.myMember?.uid.orEmpty()
    val todayStart = remember { normalizeDayStart(System.currentTimeMillis()) }

    var selectedDayMillis by rememberSaveable { mutableStateOf(todayStart) }
    var calendarMonthOffset by rememberSaveable { mutableIntStateOf(0) }
    var filterMode by rememberSaveable { mutableStateOf(PlanFilterMode.TODAY) }
    var filterMenuExpanded by remember { mutableStateOf(false) }

    var editingPlan by remember { mutableStateOf<StaffPlanItem?>(null) }
    var showPlanEditorDialog by remember { mutableStateOf(false) }
    var quickAddText by remember { mutableStateOf("") }
    var showScratchpad by rememberSaveable { mutableStateOf(false) }
    var dailyScratchpad by remember(uid) { mutableStateOf(AppHapticsAndNotifier.loadDailyNote(context, uid)) }

    val displayMonthCal = remember(calendarMonthOffset, todayStart) {
        Calendar.getInstance().apply {
            timeInMillis = todayStart
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, calendarMonthOffset)
        }
    }

    val selectedHeaderCal = remember(selectedDayMillis) {
        Calendar.getInstance().apply { timeInMillis = selectedDayMillis }
    }

    // Header matches reference: "10 Mar, 23  Wednesday" when viewing selected day's month,
    // or "01 Apr, 23  Saturday" if navigated to another month.
    val headerReferenceMillis = remember(selectedDayMillis, displayMonthCal.timeInMillis) {
        val selYr = selectedHeaderCal.get(Calendar.YEAR)
        val selMo = selectedHeaderCal.get(Calendar.MONTH)
        val dispYr = displayMonthCal.get(Calendar.YEAR)
        val dispMo = displayMonthCal.get(Calendar.MONTH)
        if (selYr == dispYr && selMo == dispMo) selectedDayMillis else displayMonthCal.timeInMillis
    }

    val headerDateText = remember(headerReferenceMillis) {
        SimpleDateFormat("d MMM, yy", Locale.getDefault()).format(Date(headerReferenceMillis))
    }
    val headerWeekdayText = remember(headerReferenceMillis) {
        SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(headerReferenceMillis))
    }

    val monthGridRows = remember(displayMonthCal.get(Calendar.YEAR), displayMonthCal.get(Calendar.MONTH)) {
        val cal = Calendar.getInstance().apply {
            timeInMillis = displayMonthCal.timeInMillis
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val firstOffset = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).coerceIn(0, 6)
        val maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val list = mutableListOf<MonthDaySlot?>()
        repeat(firstOffset) { list.add(null) }
        for (d in 1..maxDays) {
            cal.set(Calendar.DAY_OF_MONTH, d)
            list.add(MonthDaySlot(d, normalizeDayStart(cal.timeInMillis)))
        }
        while (list.size % 7 != 0) list.add(null)
        list.chunked(7)
    }

    val myDeliverables = remember(state.deliverables, uid) {
        state.deliverables.filter { it.assignedToUid == uid }
    }

    // Category dots mapped per normalized calendar day
    val dotsByDay = remember(state.staffPlanItems, myDeliverables) {
        val map = mutableMapOf<Long, List<Color>>()
        val grouped = mutableMapOf<Long, MutableSet<Color>>()
        state.staffPlanItems.forEach { item ->
            val key = normalizeDayStart(item.dateMillis)
            val set = grouped.getOrPut(key) { linkedSetOf() }
            set.add(dotColorForCategory(item.categoryEnum()))
        }
        myDeliverables.forEach { d ->
            if (d.dueDateMillis > 0L) {
                val key = normalizeDayStart(d.dueDateMillis)
                val set = grouped.getOrPut(key) { linkedSetOf() }
                set.add(PlanVioletAccent)
            }
        }
        grouped.forEach { (k, v) -> map[k] = v.take(3) }
        map
    }

    val overdueIncompletePlans = remember(state.staffPlanItems, todayStart) {
        state.staffPlanItems.filter {
            !it.completed && normalizeDayStart(it.dateMillis) < todayStart
        }
    }

    val currentYearMonth = remember(displayMonthCal.timeInMillis) {
        displayMonthCal.get(Calendar.YEAR) to displayMonthCal.get(Calendar.MONTH)
    }

    val visiblePlanItems = remember(state.staffPlanItems, selectedDayMillis, filterMode, currentYearMonth) {
        when (filterMode) {
            PlanFilterMode.TODAY -> state.staffPlanItems.filter {
                normalizeDayStart(it.dateMillis) == selectedDayMillis
            }
            PlanFilterMode.ALL_MONTH -> state.staffPlanItems.filter { item ->
                val c = Calendar.getInstance().apply { timeInMillis = item.dateMillis }
                c.get(Calendar.YEAR) == currentYearMonth.first && c.get(Calendar.MONTH) == currentYearMonth.second
            }
            PlanFilterMode.TO_DO -> state.staffPlanItems.filter {
                normalizeDayStart(it.dateMillis) == selectedDayMillis && it.categoryEnum() == PlanCategory.TO_DO
            }
            PlanFilterMode.EVENT -> state.staffPlanItems.filter {
                normalizeDayStart(it.dateMillis) == selectedDayMillis && it.categoryEnum() == PlanCategory.EVENT
            }
            PlanFilterMode.REMINDER -> state.staffPlanItems.filter {
                normalizeDayStart(it.dateMillis) == selectedDayMillis && it.categoryEnum() == PlanCategory.REMINDER
            }
            PlanFilterMode.MILESTONE -> state.staffPlanItems.filter {
                normalizeDayStart(it.dateMillis) == selectedDayMillis && it.categoryEnum() == PlanCategory.MILESTONE
            }
        }.sortedWith(compareBy<StaffPlanItem> { it.completed }.thenBy { it.dateMillis })
    }

    val dayDeliverables = remember(myDeliverables, selectedDayMillis, filterMode) {
        if (filterMode == PlanFilterMode.ALL_MONTH) {
            myDeliverables.filter { d ->
                if (d.dueDateMillis <= 0L) false
                else {
                    val c = Calendar.getInstance().apply { timeInMillis = d.dueDateMillis }
                    c.get(Calendar.YEAR) == currentYearMonth.first && c.get(Calendar.MONTH) == currentYearMonth.second
                }
            }
        } else {
            myDeliverables.filter { d ->
                d.dueDateMillis > 0L && normalizeDayStart(d.dueDateMillis) == selectedDayMillis
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .pastelAmbientBackdrop()
            .testTag("staff_plan_lazy_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (topModeSwitcher != null) {
            item { topModeSwitcher() }
        }

        // Carry-over helper banner for yesterday's unfinished items
        if (overdueIncompletePlans.isNotEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = ButterYellow,
                    border = BorderStroke(1.dp, WarmAlarmAmber.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.EventRepeat, contentDescription = null, tint = DeepAmberInk, modifier = Modifier.size(18.dp))
                            Text(
                                text = "${overdueIncompletePlans.size} unfinished plan item(s) from earlier",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = ObsidianCharcoal
                            )
                        }
                        TextButton(
                            onClick = {
                                onCarryOverIncomplete(overdueIncompletePlans, todayStart)
                                selectedDayMillis = todayStart
                                calendarMonthOffset = 0
                            },
                            modifier = Modifier.testTag("carry_over_plans_button")
                        ) {
                            Text("Carry to Today", fontWeight = FontWeight.ExtraBold, color = DeepAmberInk)
                        }
                    }
                }
            }
        }

        // Primary Monthly Calendar + Today's Task Card (matching calander_design_ddea79f7.jpg)
        item {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = PureWhite,
                border = BorderStroke(1.dp, SubtleOutline),
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("staff_plan_calendar_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header: 10 Mar, 23  Wednesday   [Today]  <  >
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    AppHapticsAndNotifier.triggerTickHaptic(context)
                                    selectedDayMillis = todayStart
                                    calendarMonthOffset = 0
                                }
                        ) {
                            Text(
                                text = headerDateText,
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                                fontWeight = FontWeight.ExtraBold,
                                color = ObsidianCharcoal
                            )
                            Text(
                                text = headerWeekdayText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = StudioTextSecondary
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (calendarMonthOffset != 0 || selectedDayMillis != todayStart) {
                                Surface(
                                    onClick = {
                                        AppHapticsAndNotifier.triggerTickHaptic(context)
                                        selectedDayMillis = todayStart
                                        calendarMonthOffset = 0
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    color = KpiLavenderBg,
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Text(
                                        text = "Today",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = PlanVioletAccent,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    AppHapticsAndNotifier.triggerTickHaptic(context)
                                    calendarMonthOffset -= 1
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("plan_prev_month_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = "Previous month",
                                    tint = ObsidianCharcoal
                                )
                            }
                            IconButton(
                                onClick = {
                                    AppHapticsAndNotifier.triggerTickHaptic(context)
                                    calendarMonthOffset += 1
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("plan_next_month_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "Next month",
                                    tint = ObsidianCharcoal
                                )
                            }
                        }
                    }

                    // Weekday Columns: Sun Mon Tue Wed Thu Fri Sat
                    Row(modifier = Modifier.fillMaxWidth()) {
                        listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { dayName ->
                            Text(
                                text = dayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioTextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Full Month Grid
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        monthGridRows.forEach { row ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                row.forEach { slot ->
                                    if (slot == null) {
                                        Spacer(modifier = Modifier.weight(1f).height(42.dp))
                                    } else {
                                        val isSelected = slot.dayKey == selectedDayMillis
                                        val isToday = slot.dayKey == todayStart
                                        val dots = dotsByDay[slot.dayKey].orEmpty()
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable {
                                                    AppHapticsAndNotifier.triggerTickHaptic(context)
                                                    selectedDayMillis = slot.dayKey
                                                }
                                                .padding(vertical = 2.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isSelected) ObsidianCharcoal else Color.Transparent)
                                                    .then(
                                                        if (!isSelected && isToday) {
                                                            Modifier.border(1.dp, ObsidianCharcoal.copy(alpha = 0.28f), CircleShape)
                                                        } else Modifier
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = slot.dayOfMonth.toString(),
                                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                                    fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isSelected) PureWhite else ObsidianCharcoal
                                                )
                                            }
                                            Row(
                                                modifier = Modifier.height(6.dp),
                                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                dots.forEach { c ->
                                                    Box(
                                                        modifier = Modifier
                                                            .size(4.5.dp)
                                                            .clip(CircleShape)
                                                            .background(c)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Category color legend
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlanCategory.entries.forEach { cat ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(dotColorForCategory(cat))
                                )
                                Text(
                                    text = cat.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioTextSecondary
                                )
                            }
                        }
                    }

                    // Action Row: "Today's Task v"  +  Violet '+' square button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { filterMenuExpanded = true }
                                    .padding(vertical = 6.dp, horizontal = 4.dp)
                                    .testTag("today_task_filter"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = filterMode.label,
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                                    fontWeight = FontWeight.ExtraBold,
                                    color = ObsidianCharcoal
                                )
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Filter plan items",
                                    tint = ObsidianCharcoal,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = filterMenuExpanded,
                                onDismissRequest = { filterMenuExpanded = false },
                                containerColor = PureWhite
                            ) {
                                PlanFilterMode.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = option.label,
                                                fontWeight = if (option == filterMode) FontWeight.Bold else FontWeight.Medium,
                                                color = ObsidianCharcoal
                                            )
                                        },
                                        onClick = {
                                            AppHapticsAndNotifier.triggerTickHaptic(context)
                                            filterMode = option
                                            filterMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val dayCount = visiblePlanItems.size + dayDeliverables.size
                            if (dayCount > 0) {
                                StatusPill(
                                    label = "$dayCount items",
                                    containerColor = MutedSurface,
                                    contentColor = StudioTextSecondary
                                )
                            }
                            Surface(
                                onClick = {
                                    AppHapticsAndNotifier.triggerTickHaptic(context)
                                    editingPlan = StaffPlanItem(dateMillis = selectedDayMillis)
                                    showPlanEditorDialog = true
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = PlanVioletAccent,
                                shadowElevation = 2.dp,
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("plan_add_task_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = "Add plan task",
                                        tint = PureWhite,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Hairline-divided schedule items (matching calander_design_ddea79f7.jpg)
                    if (visiblePlanItems.isEmpty() && dayDeliverables.isEmpty()) {
                        HorizontalDivider(color = SubtleOutline)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.EventNote,
                                contentDescription = null,
                                tint = StudioTextSecondary.copy(alpha = 0.7f),
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = "No tasks scheduled for ${MoneyFormatter.formatDate(selectedDayMillis)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = ObsidianCharcoal,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Add a plan block or pick a creative template below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = {
                                    AppHapticsAndNotifier.triggerTickHaptic(context)
                                    editingPlan = StaffPlanItem(dateMillis = selectedDayMillis)
                                    showPlanEditorDialog = true
                                },
                                shape = RoundedCornerShape(999.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PlanVioletAccent, contentColor = PureWhite),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add Task for This Day", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            visiblePlanItems.forEach { item ->
                                HorizontalDivider(color = SubtleOutline)
                                PlanTimelineRow(
                                    item = item,
                                    onToggleCompleted = {
                                        AppHapticsAndNotifier.triggerCompletionVibration(context)
                                        onTogglePlanCompleted(item)
                                    },
                                    onEdit = {
                                        AppHapticsAndNotifier.triggerTickHaptic(context)
                                        editingPlan = item
                                        showPlanEditorDialog = true
                                    },
                                    onRescheduleTomorrow = {
                                        AppHapticsAndNotifier.triggerTickHaptic(context)
                                        onRescheduleTomorrow(item)
                                    },
                                    onDelete = { onDeletePlanItem(item.id) }
                                )
                            }

                            dayDeliverables.forEach { deliv ->
                                HorizontalDivider(color = SubtleOutline)
                                AssignedDeliverablePlanRow(
                                    deliverable = deliv,
                                    onClick = { onOpenDeliverable(deliv) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Day-to-Day Staff Helpers: Quick Inline Add Bar
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = PureWhite,
                border = BorderStroke(1.dp, SubtleOutline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, tint = PlanVioletAccent, modifier = Modifier.size(20.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (quickAddText.isEmpty()) {
                            Text(
                                text = "Quick add To Do for ${SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(selectedDayMillis))}…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = StudioTextSecondary
                            )
                        }
                        BasicTextField(
                            value = quickAddText,
                            onValueChange = { quickAddText = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = ObsidianCharcoal),
                            cursorBrush = SolidColor(ObsidianCharcoal),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("plan_quick_add_input")
                        )
                    }
                    Surface(
                        onClick = {
                            val title = quickAddText.trim()
                            if (title.isNotEmpty()) {
                                AppHapticsAndNotifier.triggerCompletionVibration(context)
                                onSavePlanItem(
                                    StaffPlanItem(
                                        title = title,
                                        category = PlanCategory.TO_DO.name,
                                        creativeTag = "Daily",
                                        dateMillis = selectedDayMillis,
                                        timeRange = "Today"
                                    )
                                ) {
                                    quickAddText = ""
                                }
                            }
                        },
                        enabled = quickAddText.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        color = if (quickAddText.isNotBlank()) ObsidianCharcoal else MutedSurface,
                        modifier = Modifier
                            .height(38.dp)
                            .testTag("plan_quick_add_submit")
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Add",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (quickAddText.isNotBlank()) PureWhite else StudioTextSecondary
                            )
                        }
                    }
                }
            }
        }

        // Day-to-Day Staff Helpers: 25m Focus Sprint Timer + Daily Scratchpad
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = KpiLavenderBg.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, VioletPrimary.copy(alpha = 0.20f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("staff_focus_timer_card")
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
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f).padding(end = 6.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (state.isFocusTimerRunning) PlanVioletAccent else PureWhite,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.Timer,
                                        contentDescription = null,
                                        tint = if (state.isFocusTimerRunning) PureWhite else PlanVioletAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Deep Work Focus Sprint",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = ObsidianCharcoal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatCountdownMmSs(state.focusSecondsRemaining),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = PlanVioletAccent
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = onResetFocusTimer,
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, SubtleOutline),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Text("Reset", color = ObsidianCharcoal, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                            }
                            Button(
                                onClick = onToggleFocusTimer,
                                shape = RoundedCornerShape(999.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (state.isFocusTimerRunning) DeepBlushInk else ObsidianCharcoal,
                                    contentColor = PureWhite
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .height(38.dp)
                                    .defaultMinSize(minWidth = 84.dp)
                                    .testTag("focus_timer_toggle_button")
                            ) {
                                Icon(
                                    imageVector = if (state.isFocusTimerRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (state.isFocusTimerRunning) "Pause" else "Start",
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(15, 25, 45).forEach { mins ->
                            val active = state.focusPresetMinutes == mins
                            FilterChip(
                                selected = active,
                                onClick = { onSetFocusPreset(mins) },
                                shape = RoundedCornerShape(999.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ObsidianCharcoal,
                                    selectedLabelColor = PureWhite,
                                    containerColor = PureWhite,
                                    labelColor = ObsidianCharcoal
                                ),
                                label = { Text("${mins}m", fontWeight = FontWeight.SemiBold) }
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { showScratchpad = !showScratchpad },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.EditNote,
                                contentDescription = null,
                                tint = PlanVioletAccent,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (showScratchpad) "Hide Notes" else "Scratchpad", fontWeight = FontWeight.Bold, color = PlanVioletAccent)
                        }
                    }

                    AnimatedVisibility(visible = showScratchpad) {
                        OutlinedTextField(
                            value = dailyScratchpad,
                            onValueChange = {
                                dailyScratchpad = it
                                AppHapticsAndNotifier.saveDailyNote(context, uid, it)
                            },
                            label = { Text("Quick daily scratchpad (saved locally)") },
                            placeholder = { Text("Hex codes, copy snippets, quick client notes…") },
                            minLines = 2,
                            maxLines = 5,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = PureWhite,
                                unfocusedContainerColor = PureWhite
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("staff_daily_scratchpad_input")
                        )
                    }
                }
            }
        }

        // Day-to-Day Creative Routine Templates
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "One-Tap Creative Blocks",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = StudioTextSecondary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    creativeTemplates.forEach { tpl ->
                        Surface(
                            onClick = {
                                AppHapticsAndNotifier.triggerTickHaptic(context)
                                editingPlan = StaffPlanItem(
                                    title = tpl.label,
                                    category = tpl.category.name,
                                    creativeTag = tpl.creativeTag,
                                    dateMillis = selectedDayMillis,
                                    timeRange = tpl.defaultTime
                                )
                                showPlanEditorDialog = true
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = PureWhite,
                            border = BorderStroke(1.dp, SubtleOutline),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(dotColorForCategory(tpl.category))
                                )
                                Text(
                                    text = tpl.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ObsidianCharcoal
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPlanEditorDialog) {
        val initial = editingPlan ?: StaffPlanItem(dateMillis = selectedDayMillis)
        StaffPlanEditorDialog(
            initial = initial,
            assignedDeliverables = myDeliverables,
            onSave = { item ->
                AppHapticsAndNotifier.triggerCompletionVibration(context)
                onSavePlanItem(item) {
                    showPlanEditorDialog = false
                    editingPlan = null
                }
            },
            onDismiss = {
                showPlanEditorDialog = false
                editingPlan = null
            }
        )
    }
}

@Composable
private fun PlanTimelineRow(
    item: StaffPlanItem,
    onToggleCompleted: () -> Unit,
    onEdit: () -> Unit,
    onRescheduleTomorrow: () -> Unit,
    onDelete: () -> Unit
) {
    val category = item.categoryEnum()
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 13.dp)
            .testTag("plan_item_row_${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconButton(
            onClick = onToggleCompleted,
            modifier = Modifier
                .size(40.dp)
                .testTag("plan_toggle_${item.id}")
        ) {
            Icon(
                imageVector = iconForCategory(category, item.completed),
                contentDescription = if (item.completed) "Mark incomplete" else "Mark completed",
                tint = if (item.completed) EmeraldApproved else PlanOutlineBlue,
                modifier = Modifier.size(22.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = category.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioTextSecondary
                )
                if (item.creativeTag.isNotBlank() && item.creativeTag != "General") {
                    StatusPill(
                        label = item.creativeTag,
                        containerColor = MutedSurface,
                        contentColor = ObsidianCharcoal
                    )
                }
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                fontWeight = FontWeight.Bold,
                color = if (item.completed) StudioTextSecondary else ObsidianCharcoal,
                textDecoration = if (item.completed) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (item.timeRange.isNotBlank() || item.notes.isNotBlank()) {
                Text(
                    text = listOf(item.timeRange, item.notes).filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Plan options",
                    tint = StudioTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = PureWhite
            ) {
                DropdownMenuItem(
                    text = { Text(if (item.completed) "Reopen" else "Mark Done") },
                    onClick = {
                        menuExpanded = false
                        onToggleCompleted()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = {
                        menuExpanded = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Move to Next Day") },
                    onClick = {
                        menuExpanded = false
                        onRescheduleTomorrow()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = DeepBlushInk) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun AssignedDeliverablePlanRow(
    deliverable: Deliverable,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.WorkOutline,
                contentDescription = "Assigned deliverable",
                tint = PlanVioletAccent,
                modifier = Modifier.size(21.dp)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Deliverable • ${deliverable.statusEnum().label}",
                style = MaterialTheme.typography.labelMedium,
                color = PlanVioletAccent
            )
            Text(
                text = deliverable.title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                fontWeight = FontWeight.Bold,
                color = ObsidianCharcoal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${deliverable.projectTitle} • Due ${MoneyFormatter.formatDate(deliverable.dueDateMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Open deliverable",
            tint = StudioTextSecondary
        )
    }
}

@Composable
private fun StaffPlanEditorDialog(
    initial: StaffPlanItem,
    assignedDeliverables: List<Deliverable>,
    onSave: (StaffPlanItem) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(initial.title) }
    var category by remember { mutableStateOf(initial.categoryEnum()) }
    var creativeTag by remember { mutableStateOf(initial.creativeTag.ifBlank { "Design" }) }
    var timeRange by remember { mutableStateOf(initial.timeRange.ifBlank { "11:20AM - 12:20 PM" }) }
    var notes by remember { mutableStateOf(initial.notes) }
    var linkedDeliverableId by remember { mutableStateOf(initial.linkedDeliverableId) }

    val presetTimes = listOf(
        "09:30AM - 10:30 AM",
        "11:20AM - 12:20 PM",
        "12:30 - 1:00 PM",
        "2:00 - 3:00 PM",
        "4:20 - 5:00 PM"
    )
    val creativeTags = listOf("Design", "Video", "Copy", "Audit", "Client", "General")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        shape = RoundedCornerShape(26.dp),
        title = {
            Text(
                text = if (initial.id.isBlank()) "Plan Task or Event" else "Edit Plan Item",
                fontWeight = FontWeight.ExtraBold,
                color = ObsidianCharcoal
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Date: ${MoneyFormatter.formatDate(initial.dateMillis)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = PlanVioletAccent,
                    fontWeight = FontWeight.Bold
                )

                Text("Category", style = MaterialTheme.typography.labelMedium, color = StudioTextSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PlanCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.label, fontWeight = FontWeight.SemiBold) }
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task / Event Title") },
                    placeholder = { Text("e.g. Website audit or Client critique") },
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("plan_editor_title_input")
                )

                OutlinedTextField(
                    value = timeRange,
                    onValueChange = { timeRange = it },
                    label = { Text("Time Slot") },
                    placeholder = { Text("11:20AM - 12:20 PM") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presetTimes.forEach { slot ->
                        AssistChip(
                            onClick = { timeRange = slot },
                            label = { Text(slot, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Text("Creative Focus", style = MaterialTheme.typography.labelMedium, color = StudioTextSecondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    creativeTags.forEach { tag ->
                        FilterChip(
                            selected = creativeTag == tag,
                            onClick = { creativeTag = tag },
                            label = { Text(tag) }
                        )
                    }
                }

                if (assignedDeliverables.isNotEmpty()) {
                    Text("Link Project Deliverable (Optional)", style = MaterialTheme.typography.labelMedium, color = StudioTextSecondary)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        assignedDeliverables.take(6).forEach { deliv ->
                            val selected = linkedDeliverableId == deliv.id
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    linkedDeliverableId = if (selected) "" else deliv.id
                                    if (!selected && title.isBlank()) title = deliv.title
                                },
                                label = { Text(deliv.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes / Checklist Cue") },
                    placeholder = { Text("Key deliverables or meeting agenda…") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            title = title.trim(),
                            category = category.name,
                            creativeTag = creativeTag,
                            timeRange = timeRange.trim(),
                            notes = notes.trim(),
                            linkedDeliverableId = linkedDeliverableId
                        )
                    )
                },
                enabled = title.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = PlanVioletAccent, contentColor = PureWhite),
                modifier = Modifier.testTag("plan_editor_save_button")
            ) {
                Text(if (initial.id.isBlank()) "Add to Plan" else "Save Changes", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = ObsidianCharcoal)
            }
        }
    )
}
