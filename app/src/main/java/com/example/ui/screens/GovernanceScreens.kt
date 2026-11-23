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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.domain.MoneyFormatter
import com.example.domain.PermissionEngine
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioAvatar
import com.example.ui.components.XMaxWordmarkBadge
import com.example.ui.components.formatTitleCase
import com.example.ui.theme.*

@Composable
private fun monochromeChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = ObsidianCharcoal,
    selectedLabelColor = PureWhite,
    containerColor = MutedSurface,
    labelColor = ObsidianCharcoal
)

@Composable
fun TeamGovernanceScreen(
    state: AgencyUiState,
    onInviteMember: (String, AccessLevel, Department, JobRole, ScopeType, List<String>, Map<String, PermissionGrant>) -> Unit,
    onConfigureMember: (Member, MembershipStatus, AccessLevel, Department, JobRole, ScopeType, List<String>, Map<String, PermissionGrant>) -> Unit,
    onUpdateStaffDelegation: (Member, Map<String, DelegatedGrant>) -> Unit
) {
    var editingMember by remember { mutableStateOf<Member?>(null) }
    var delegatingStaff by remember { mutableStateOf<Member?>(null) }
    var showInviteDialog by remember { mutableStateOf(false) }

    val actor = state.myMember ?: return
    val isSuper = state.isSuperAdmin
    val privateByUid = remember(state.allPrivateInfo) { state.allPrivateInfo.associateBy { it.uid } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(CrispCanvas),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Team & Permissions (${state.members.size})", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                if (isSuper) {
                    Button(
                        onClick = { showInviteDialog = true },
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                        modifier = Modifier.height(42.dp).testTag("invite_member_button")
                    ) {
                        Icon(Icons.Filled.MailOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("INVITE", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        items(state.members, key = { it.uid }) { m ->
            val pInfo = privateByUid[m.uid]
            val activeCount = state.deliverables.count { it.assignedToUid == m.uid && it.statusEnum() != DeliverableStatus.APPROVED }
            val blockedCount = state.deliverables.count { it.assignedToUid == m.uid && it.statusEnum() == DeliverableStatus.BLOCKED }
            val (stBg, stFg) = when (m.statusEnum()) {
                MembershipStatus.APPROVED -> SoftMint to DeepMintInk
                MembershipStatus.PENDING -> ButterYellow to DeepAmberInk
                MembershipStatus.DECLINED, MembershipStatus.SUSPENDED -> SoftBlush to CrimsonBlocked
            }
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = PureWhite,
                border = BorderStroke(1.dp, SubtleOutline),
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StudioAvatar(
                            name = m.fullName.ifBlank { m.requestedJobRole },
                            photoBase64 = m.photoBase64,
                            size = 46.dp,
                            borderColor = KpiLavenderBg
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(m.fullName.ifBlank { m.requestedJobRole }.uppercase(), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium, color = ObsidianCharcoal)
                            Text(
                                "${m.accessLevelEnum().label} • ${m.jobRoleName.ifBlank { m.requestedJobRole }} (${m.departmentName.ifBlank { m.requestedDepartment }})",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary
                            )
                        }
                        StatusPill(m.statusEnum().label, stBg, stFg)
                    }
                    if (isSuper && pInfo != null) {
                        Text("Private PII: Age ${pInfo.age} • ${pInfo.email}", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Workload: $activeCount active • $blockedCount blocked",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = StudioTextSecondary
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isSuper) {
                            OutlinedButton(
                                onClick = { editingMember = m },
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, SubtleOutline),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = KpiLavenderBg, contentColor = VioletPrimary),
                                modifier = Modifier.testTag("configure_member_${m.uid}")
                            ) {
                                Text("Configure & Permissions", fontWeight = FontWeight.SemiBold)
                            }
                        } else if (actor.accessLevelEnum() == AccessLevel.ADMIN && m.accessLevelEnum() == AccessLevel.STAFF && PermissionEngine.canAccessDepartment(actor, m.departmentId)) {
                            OutlinedButton(
                                onClick = { delegatingStaff = m },
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, SubtleOutline),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = KpiLavenderBg, contentColor = VioletPrimary)
                            ) {
                                Text("Delegate Staff Capabilities", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }

    editingMember?.let { target ->
        MemberPermissionEditorDialog(
            state = state,
            target = target,
            onSave = { st, lvl, dept, role, scopeType, scopeIds, perms ->
                onConfigureMember(target, st, lvl, dept, role, scopeType, scopeIds, perms)
                editingMember = null
            },
            onDismiss = { editingMember = null }
        )
    }

    delegatingStaff?.let { staff ->
        StaffDelegationDialog(
            actor = actor,
            staff = staff,
            onSave = { grants ->
                onUpdateStaffDelegation(staff, grants)
                delegatingStaff = null
            },
            onDismiss = { delegatingStaff = null }
        )
    }

    if (showInviteDialog) {
        InviteMemberDialog(
            departments = state.departments.filter { it.active },
            jobRoles = state.jobRoles.filter { it.active },
            onInvite = { email, lvl, dept, role ->
                onInviteMember(email, lvl, dept, role, ScopeType.TEAM, listOf(dept.id), emptyMap())
                showInviteDialog = false
            },
            onDismiss = { showInviteDialog = false }
        )
    }
}

@Composable
private fun MemberPermissionEditorDialog(
    state: AgencyUiState,
    target: Member,
    onSave: (MembershipStatus, AccessLevel, Department, JobRole, ScopeType, List<String>, Map<String, PermissionGrant>) -> Unit,
    onDismiss: () -> Unit
) {
    val activeDepts = state.departments.filter { it.active }
    val activeRoles = state.jobRoles.filter { it.active }

    var status by remember { mutableStateOf(target.statusEnum()) }
    var accessLevel by remember { mutableStateOf(target.accessLevelEnum()) }
    var selectedDept by remember { mutableStateOf(activeDepts.firstOrNull { it.id == target.departmentId } ?: activeDepts.firstOrNull() ?: Department("dept_ops", "Operations")) }
    var selectedRole by remember { mutableStateOf(activeRoles.firstOrNull { it.id == target.jobRoleId } ?: activeRoles.firstOrNull() ?: JobRole("role_pm", "Project Manager", selectedDept.id)) }
    var scopeType by remember { mutableStateOf(target.scopeTypeEnum()) }
    var scopeDeptIds by remember { mutableStateOf(target.scopeDepartmentIds.toSet().ifEmpty { setOf(selectedDept.id) }) }
    var perms by remember { mutableStateOf(target.permissions) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            shape = RoundedCornerShape(28.dp),
            color = CrispCanvas
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = ObsidianCharcoal)
                    }
                    Text("CONFIGURE ${target.fullName.uppercase()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                    Spacer(Modifier.size(48.dp))
                }
                HorizontalDivider(color = SubtleOutline)

                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("Membership Status", fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MembershipStatus.entries.forEach { st ->
                                FilterChip(
                                    selected = status == st,
                                    onClick = { status = st },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = monochromeChipColors(),
                                    border = null,
                                    label = { Text(st.label, fontWeight = FontWeight.SemiBold) }
                                )
                            }
                        }
                    }
                    item {
                        Text("Access Level (Separate from Job Role)", fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AccessLevel.entries.forEach { lvl ->
                                FilterChip(
                                    selected = accessLevel == lvl,
                                    onClick = { accessLevel = lvl },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = monochromeChipColors(),
                                    border = null,
                                    label = { Text(lvl.label, fontWeight = FontWeight.SemiBold) }
                                )
                            }
                        }
                    }
                    item {
                        Text("Department & Job Role", fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            activeDepts.forEach { d ->
                                FilterChip(
                                    selected = selectedDept.id == d.id,
                                    onClick = { selectedDept = d },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = monochromeChipColors(),
                                    border = null,
                                    label = { Text(d.name, fontWeight = FontWeight.SemiBold) }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            activeRoles.forEach { r ->
                                FilterChip(
                                    selected = selectedRole.id == r.id,
                                    onClick = { selectedRole = r },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = monochromeChipColors(),
                                    border = null,
                                    label = { Text(r.name, fontWeight = FontWeight.SemiBold) }
                                )
                            }
                        }
                    }

                    if (accessLevel == AccessLevel.ADMIN) {
                        item {
                            Text("Manager Scope", fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ScopeType.entries.forEach { sc ->
                                    FilterChip(
                                        selected = scopeType == sc,
                                        onClick = { scopeType = sc },
                                        shape = RoundedCornerShape(999.dp),
                                        colors = monochromeChipColors(),
                                        border = null,
                                        label = { Text(sc.label, fontWeight = FontWeight.SemiBold) }
                                    )
                                }
                            }
                            if (scopeType == ScopeType.TEAM) {
                                Spacer(Modifier.height(6.dp))
                                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    activeDepts.forEach { d ->
                                        val checked = d.id in scopeDeptIds
                                        FilterChip(
                                            selected = checked,
                                            onClick = { scopeDeptIds = if (checked) scopeDeptIds - d.id else scopeDeptIds + d.id },
                                            shape = RoundedCornerShape(999.dp),
                                            colors = monochromeChipColors(),
                                            border = null,
                                            label = { Text(d.name, fontWeight = FontWeight.SemiBold) }
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Text("Admin Permission Matrix (Allowed / May Delegate to Staff)", fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                        }
                        items(AppPermission.entries, key = { it.name }) { p ->
                            val current = perms[p.name] ?: PermissionGrant()
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MutedSurface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = PureWhite,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                                    Text(p.label, fontWeight = FontWeight.SemiBold, color = ObsidianCharcoal)
                                                    Text(p.description, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                                                }
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text("Allowed", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                                                    Switch(
                                                        checked = current.allowed,
                                                        onCheckedChange = { allow ->
                                                            perms = perms + (p.name to current.copy(allowed = allow, canDelegate = if (allow) current.canDelegate else false))
                                                        }
                                                    )
                                                }
                                            }
                                            if (p.staffEligible) {
                                                HorizontalDivider(color = SubtleOutline)
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                    Text("May delegate to staff", style = MaterialTheme.typography.labelMedium, color = ObsidianCharcoal)
                                                    Switch(
                                                        checked = current.canDelegate,
                                                        enabled = current.allowed,
                                                        onCheckedChange = { del ->
                                                            perms = perms + (p.name to current.copy(canDelegate = del))
                                                        }
                                                    )
                                                }
                                            } else {
                                                Text("Reserved action — never delegable to Staff", style = MaterialTheme.typography.labelSmall, color = CrimsonBlocked)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = { onSave(status, accessLevel, selectedDept, selectedRole, scopeType, scopeDeptIds.toList(), perms) },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("save_member_permissions_button")
                ) { Text("Save Member & Permissions", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun StaffDelegationDialog(
    actor: Member,
    staff: Member,
    onSave: (Map<String, DelegatedGrant>) -> Unit,
    onDismiss: () -> Unit
) {
    var grants by remember { mutableStateOf(staff.delegatedGrants) }
    val delegablePerms = AppPermission.entries.filter { PermissionEngine.canAdminDelegateToStaff(actor, staff, it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        shape = RoundedCornerShape(26.dp),
        title = { Text("DELEGATE CAPABILITIES — ${staff.fullName.uppercase()}", fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (delegablePerms.isEmpty()) {
                    Text("You do not hold any delegable permissions within this team member's scope.", color = StudioTextSecondary)
                } else {
                    delegablePerms.forEach { p ->
                        val active = grants[p.name]?.allowed == true
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(p.label, modifier = Modifier.weight(1f), color = ObsidianCharcoal)
                            Switch(
                                checked = active,
                                onCheckedChange = { checked ->
                                    grants = if (checked) {
                                        grants + (p.name to DelegatedGrant(true, actor.uid, System.currentTimeMillis()))
                                    } else {
                                        grants - p.name
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(grants) },
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = StudioTextSecondary) } }
    )
}

@Composable
private fun InviteMemberDialog(
    departments: List<Department>,
    jobRoles: List<JobRole>,
    onInvite: (String, AccessLevel, Department, JobRole) -> Unit,
    onDismiss: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(AccessLevel.STAFF) }
    var dept by remember { mutableStateOf(departments.firstOrNull() ?: Department("dept_creative", "Creative")) }
    var role by remember { mutableStateOf(jobRoles.firstOrNull() ?: JobRole("role_graphic_designer", "Graphic Designer", dept.id)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        shape = RoundedCornerShape(26.dp),
        title = { Text("Invite Team Member", fontWeight = FontWeight.Bold, color = ObsidianCharcoal) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Google Email *") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccessLevel.entries.forEach { l ->
                        FilterChip(selected = level == l, onClick = { level = l }, shape = RoundedCornerShape(999.dp), colors = monochromeChipColors(), border = null, label = { Text(l.label) })
                    }
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    departments.forEach { d ->
                        FilterChip(selected = dept.id == d.id, onClick = { dept = d }, shape = RoundedCornerShape(999.dp), colors = monochromeChipColors(), border = null, label = { Text(d.name) })
                    }
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    jobRoles.forEach { r ->
                        FilterChip(selected = role.id == r.id, onClick = { role = r }, shape = RoundedCornerShape(999.dp), colors = monochromeChipColors(), border = null, label = { Text(r.name) })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onInvite(email, level, dept, role) },
                enabled = email.contains("@"),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
            ) { Text("Send Invite") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = StudioTextSecondary) } }
    )
}

@Composable
fun OrgAndRoutingScreen(
    state: AgencyUiState,
    onSaveDepartment: (String?, String, String) -> Unit,
    onSaveJobRole: (String?, String, String) -> Unit,
    onRetireDepartment: (Department, Department, JobRole) -> Unit,
    onRetireJobRole: (JobRole, Department, JobRole) -> Unit,
    onSaveRoutingRule: (RoutingRule) -> Unit
) {
    var retireDeptTarget by remember { mutableStateOf<Department?>(null) }
    var retireRoleTarget by remember { mutableStateOf<JobRole?>(null) }
    var showAddDept by remember { mutableStateOf(false) }
    var showAddRole by remember { mutableStateOf(false) }

    val activeDepts = state.departments.filter { it.active }
    val activeRoles = state.jobRoles.filter { it.active }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(CrispCanvas),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Departments (${activeDepts.size})", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                Button(
                    onClick = { showAddDept = true },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                    modifier = Modifier.height(40.dp)
                ) { Text("+ DEPT", fontWeight = FontWeight.Bold) }
            }
        }
        items(activeDepts, key = { it.id }) { d ->
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MutedSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name.uppercase(), fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                        if (d.description.isNotBlank()) Text(d.description, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                    }
                    if (activeDepts.size > 1) {
                        TextButton(onClick = { retireDeptTarget = d }) { Text("Retire", color = CrimsonBlocked) }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Job Roles (${activeRoles.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                Button(
                    onClick = { showAddRole = true },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                    modifier = Modifier.height(40.dp)
                ) { Text("+ ROLE", fontWeight = FontWeight.Bold) }
            }
        }
        items(activeRoles, key = { it.id }) { r ->
            val dName = activeDepts.firstOrNull { it.id == r.departmentId }?.name ?: r.departmentId
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MutedSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name.uppercase(), fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                        Text("Department: ${dName.uppercase()}", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                    }
                    if (activeRoles.size > 1) {
                        TextButton(onClick = { retireRoleTarget = r }) { Text("Retire", color = CrimsonBlocked) }
                    }
                }
            }
        }

        item { Text("Service-to-Role Routing Rules (${state.routingRules.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal) }
        items(state.routingRules, key = { it.id }) { rule ->
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MutedSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(formatTitleCase(rule.name), fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                        Text("Priority ${rule.priority} • Target: ${formatTitleCase(rule.departmentName)} / ${formatTitleCase(rule.jobRoleName)}", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                    }
                    Switch(checked = rule.active, onCheckedChange = { onSaveRoutingRule(rule.copy(active = it)) })
                }
            }
        }
    }

    retireDeptTarget?.let { dept ->
        val activeDeliverablesCount = state.deliverables.count { it.departmentId == dept.id && it.statusEnum() != DeliverableStatus.APPROVED }
        val impact = PermissionEngine.analyzeDepartmentRetirement(dept.id, state.members, state.jobRoles, state.routingRules, activeDeliverablesCount)
        val candidates = PermissionEngine.validReplacementDepartments(state.departments, dept.id)
        var repDept by remember(dept.id) { mutableStateOf(candidates.firstOrNull()) }
        var repRole by remember(dept.id, repDept?.id) {
            mutableStateOf(
                activeRoles.firstOrNull { it.departmentId == repDept?.id } ?: activeRoles.firstOrNull()
            )
        }

        AlertDialog(
            onDismissRequest = { retireDeptTarget = null },
            containerColor = PureWhite,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Retire Department: ${dept.name}", fontWeight = FontWeight.Bold, color = ObsidianCharcoal) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Impact Preview:\n• ${impact.affectedEmployees.size} employees\n• ${impact.affectedJobRoles.size} job roles\n• ${impact.affectedRoutingRules.size} routing rules\n• ${impact.activeDeliverableCount} active deliverables", color = StudioTextSecondary)
                    if (candidates.isEmpty() || repRole == null) {
                        Text(
                            "Create another active department and job role before retiring this department.",
                            color = CrimsonBlocked,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text("Reassign all to:", fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            candidates.forEach { c ->
                                FilterChip(
                                    selected = repDept?.id == c.id,
                                    onClick = {
                                        repDept = c
                                        repRole = activeRoles.firstOrNull { it.departmentId == c.id } ?: activeRoles.firstOrNull()
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = monochromeChipColors(),
                                    border = null,
                                    label = { Text(c.name) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val safeDept = repDept
                val safeRole = repRole
                Button(
                    onClick = {
                        if (safeDept != null && safeRole != null) {
                            onRetireDepartment(dept, safeDept, safeRole)
                            retireDeptTarget = null
                        }
                    },
                    enabled = safeDept != null && safeRole != null,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                ) { Text("Reassign & Retire") }
            },
            dismissButton = { TextButton(onClick = { retireDeptTarget = null }) { Text("Cancel", color = StudioTextSecondary) } }
        )
    }

    retireRoleTarget?.let { role ->
        val activeCount = state.deliverables.count { it.jobRoleId == role.id && it.statusEnum() != DeliverableStatus.APPROVED }
        val impact = PermissionEngine.analyzeRoleRetirement(role.id, state.members, state.routingRules, activeCount)
        val candidates = activeRoles.filter { it.id != role.id }
        var repRole by remember(role.id) { mutableStateOf(candidates.firstOrNull()) }
        val repDept = repRole?.let { rr -> activeDepts.firstOrNull { it.id == rr.departmentId } } ?: activeDepts.firstOrNull()

        AlertDialog(
            onDismissRequest = { retireRoleTarget = null },
            containerColor = PureWhite,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Retire Job Role: ${role.name}", fontWeight = FontWeight.Bold, color = ObsidianCharcoal) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Impact Preview:\n• ${impact.affectedEmployees.size} employees\n• ${impact.affectedRoutingRules.size} routing rules\n• ${impact.activeDeliverableCount} active deliverables", color = StudioTextSecondary)
                    if (candidates.isEmpty() || repDept == null) {
                        Text(
                            "Create another active job role before retiring this role.",
                            color = CrimsonBlocked,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text("Reassign to replacement role:", fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            candidates.forEach { c ->
                                FilterChip(selected = repRole?.id == c.id, onClick = { repRole = c }, shape = RoundedCornerShape(999.dp), colors = monochromeChipColors(), border = null, label = { Text(c.name) })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val safeRole = repRole
                val safeDept = repDept
                Button(
                    onClick = {
                        if (safeDept != null && safeRole != null) {
                            onRetireJobRole(role, safeDept, safeRole)
                            retireRoleTarget = null
                        }
                    },
                    enabled = safeDept != null && safeRole != null,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                ) { Text("Reassign & Retire") }
            },
            dismissButton = { TextButton(onClick = { retireRoleTarget = null }) { Text("Cancel", color = StudioTextSecondary) } }
        )
    }

    if (showAddDept) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDept = false },
            containerColor = PureWhite,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Add Department", fontWeight = FontWeight.Bold, color = ObsidianCharcoal) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Department Name *") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, shape = RoundedCornerShape(14.dp))
                }
            },
            confirmButton = {
                Button(
                    onClick = { onSaveDepartment(null, name, desc); showAddDept = false },
                    enabled = name.isNotBlank(),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddDept = false }) { Text("Cancel", color = StudioTextSecondary) } }
        )
    }

    if (showAddRole && activeDepts.isNotEmpty()) {
        var name by remember { mutableStateOf("") }
        var dept by remember { mutableStateOf(activeDepts.first()) }
        AlertDialog(
            onDismissRequest = { showAddRole = false },
            containerColor = PureWhite,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Add Job Role", fontWeight = FontWeight.Bold, color = ObsidianCharcoal) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Role Name *") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        activeDepts.forEach { d -> FilterChip(selected = dept.id == d.id, onClick = { dept = d }, shape = RoundedCornerShape(999.dp), colors = monochromeChipColors(), border = null, label = { Text(d.name) }) }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onSaveJobRole(null, name, dept.id); showAddRole = false },
                    enabled = name.isNotBlank(),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddRole = false }) { Text("Cancel", color = StudioTextSecondary) } }
        )
    }
}

@Composable
fun CompanySettingsScreen(
    state: AgencyUiState,
    onSaveCompany: (CompanyProfile) -> Unit
) {
    val company = state.company ?: return
    var name by remember(company) { mutableStateOf(company.name) }
    var tagline by remember(company) { mutableStateOf(company.tagline) }
    var address by remember(company) { mutableStateOf(company.address) }
    var phone by remember(company) { mutableStateOf(company.phone) }
    var email by remember(company) { mutableStateOf(company.email) }
    var gstin by remember(company) { mutableStateOf(company.gstin) }
    var gstEnabled by remember(company) { mutableStateOf(company.gstEnabled) }
    var gstRate by remember(company) { mutableStateOf(company.gstRatePercent.toString()) }
    var advPct by remember(company) { mutableStateOf(company.defaultAdvancePercent.toString()) }
    var valDays by remember(company) { mutableStateOf(company.defaultValidityDays.toString()) }
    var revRounds by remember(company) { mutableStateOf(company.defaultRevisionRounds.toString()) }
    var payTerms by remember(company) { mutableStateOf(company.defaultPaymentTerms) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(CrispCanvas),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { XMaxWordmarkBadge("X.MAX COMPANY & QUOTE DEFAULTS") }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MutedSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val tfColors = OutlinedTextFieldDefaults.colors(focusedContainerColor = PureWhite, unfocusedContainerColor = PureWhite)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Company Name") }, shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = tagline, onValueChange = { tagline = it }, label = { Text("Tagline") }, shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Business Address") }, shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = gstin, onValueChange = { gstin = it }, label = { Text("GSTIN (Optional)") }, shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.fillMaxWidth())
                    Surface(shape = RoundedCornerShape(14.dp), color = PureWhite, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Enable GST on new quotes", color = ObsidianCharcoal, fontWeight = FontWeight.Medium)
                            Switch(checked = gstEnabled, onCheckedChange = { gstEnabled = it })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(value = gstRate, onValueChange = { gstRate = it.filter(Char::isDigit) }, label = { Text("GST %") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = advPct, onValueChange = { advPct = it.filter(Char::isDigit) }, label = { Text("Advance %") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = valDays, onValueChange = { valDays = it.filter(Char::isDigit) }, label = { Text("Valid Days") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = revRounds, onValueChange = { revRounds = it.filter(Char::isDigit) }, label = { Text("Revisions") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = payTerms, onValueChange = { payTerms = it }, label = { Text("Default Payment Terms") }, shape = RoundedCornerShape(14.dp), colors = tfColors, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            onSaveCompany(
                                company.copy(
                                    name = name,
                                    tagline = tagline,
                                    address = address,
                                    phone = phone,
                                    email = email,
                                    gstin = gstin,
                                    gstEnabled = gstEnabled,
                                    gstRatePercent = gstRate.toIntOrNull()?.coerceIn(0, 28) ?: 18,
                                    defaultAdvancePercent = advPct.toIntOrNull()?.coerceIn(0, 100) ?: 50,
                                    defaultValidityDays = valDays.toIntOrNull()?.coerceIn(1, 365) ?: 15,
                                    defaultRevisionRounds = revRounds.toIntOrNull()?.coerceIn(0, 20) ?: 2,
                                    defaultPaymentTerms = payTerms
                                )
                            )
                        },
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) { Text("SAVE COMPANY SETTINGS", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
fun NotificationsScreen(
    state: AgencyUiState,
    onMarkRead: (WorkspaceNotification) -> Unit = {},
    onMarkAllRead: () -> Unit = {},
    onToggleSound: (Boolean) -> Unit = {},
    onTestSound: () -> Unit = {},
    onDeleteNotification: (String) -> Unit = {},
    onOpenChatChannel: (String) -> Unit = {},
    onOpenDeliverableById: (String) -> Unit = {}
) {
    val myUid = state.myMember?.uid.orEmpty()
    val relevant = state.myNotifications
    val unreadCount = relevant.count { it.isUnreadFor(myUid) }
    var unreadOnly by remember { mutableStateOf(false) }
    val visible = remember(relevant, unreadOnly, myUid) {
        if (unreadOnly) relevant.filter { it.isUnreadFor(myUid) } else relevant
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CrispCanvas)
            .testTag("notifications_screen_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Notifications (${relevant.size})",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = ObsidianCharcoal,
                    modifier = Modifier.weight(1f)
                )
                if (unreadCount > 0) {
                    TextButton(
                        onClick = onMarkAllRead,
                        modifier = Modifier.testTag("mark_all_notifications_read_button")
                    ) {
                        Text("MARK ALL READ", fontWeight = FontWeight.Bold, color = VioletPrimary)
                    }
                }
            }
        }

        // Sound Alert Settings Card
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = PureWhite,
                border = BorderStroke(1.dp, SubtleOutline),
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
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
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier.size(40.dp).clip(CircleShape).background(KpiLavenderBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (state.soundEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                    contentDescription = "Notification sound",
                                    tint = VioletPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text("NOTIFICATION SOUND", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                                Text("Play alert chime on new tasks, approvals & messages", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                            }
                        }
                        Switch(
                            checked = state.soundEnabled,
                            onCheckedChange = onToggleSound,
                            modifier = Modifier.testTag("notification_sound_switch")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !unreadOnly,
                            onClick = { unreadOnly = false },
                            shape = RoundedCornerShape(999.dp),
                            colors = monochromeChipColors(),
                            border = null,
                            modifier = Modifier.testTag("notif_filter_all"),
                            label = { Text("ALL (${relevant.size})", fontWeight = FontWeight.Bold) }
                        )
                        FilterChip(
                            selected = unreadOnly,
                            onClick = { unreadOnly = true },
                            shape = RoundedCornerShape(999.dp),
                            colors = monochromeChipColors(),
                            border = null,
                            modifier = Modifier.testTag("notif_filter_unread"),
                            label = { Text("UNREAD ($unreadCount)", fontWeight = FontWeight.Bold) }
                        )
                    }
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                EmptyStateCard(
                    Icons.Filled.NotificationsNone,
                    if (unreadOnly) "NO UNREAD NOTIFICATIONS" else "ALL CAUGHT UP",
                    "New tasks, approvals, projects & chat alerts will chime and appear here."
                )
            }
        } else {
            items(visible, key = { it.id }) { notif ->
                val isUnread = notif.isUnreadFor(myUid)
                val icon = when (notif.categoryEnum()) {
                    NotificationCategory.CHAT -> Icons.Filled.ChatBubbleOutline
                    NotificationCategory.DELIVERABLE -> Icons.Filled.TaskAlt
                    NotificationCategory.PROJECT -> Icons.Filled.WorkOutline
                    NotificationCategory.PAYMENT -> Icons.Filled.Payments
                    NotificationCategory.MEMBERSHIP -> Icons.Filled.Group
                    NotificationCategory.SYSTEM -> Icons.Filled.Notifications
                }
                val timeLabel = notif.createdAt?.toDate()?.time?.let { MoneyFormatter.formatDateTime(it) } ?: "JUST NOW"
                Surface(
                    onClick = {
                        onMarkRead(notif)
                        if (notif.channelId.isNotBlank()) {
                            onOpenChatChannel(notif.channelId)
                        } else if (notif.categoryEnum() == NotificationCategory.DELIVERABLE && notif.entityId.isNotBlank()) {
                            onOpenDeliverableById(notif.entityId)
                        }
                    },
                    shape = RoundedCornerShape(22.dp),
                    color = if (isUnread) KpiLavenderBg.copy(alpha = 0.72f) else PureWhite,
                    border = BorderStroke(1.dp, if (isUnread) VioletPrimary.copy(alpha = 0.45f) else SubtleOutline),
                    shadowElevation = if (isUnread) 3.dp else 1.dp,
                    modifier = Modifier.fillMaxWidth().testTag("notification_card_${notif.id}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isUnread) VioletPrimary else MutedSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isUnread) PureWhite else ObsidianCharcoal,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (isUnread) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(VioletPrimary)
                                    )
                                }
                                Text(
                                    formatTitleCase(notif.title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = ObsidianCharcoal,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (notif.body.isNotBlank()) {
                                Text(
                                    notif.body,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ObsidianCharcoal
                                )
                            }
                            Text(
                                "${formatTitleCase(notif.actorName.ifBlank { "Workspace" })} • $timeLabel",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = StudioTextSecondary
                            )
                        }
                        if (state.isSuperAdmin) {
                            IconButton(
                                onClick = { onDeleteNotification(notif.id) },
                                modifier = Modifier.size(48.dp).testTag("delete_notif_${notif.id}")
                            ) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete notification", tint = CrimsonBlocked)
                            }
                        }
                    }
                }
            }
        }
    }
}

