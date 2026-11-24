package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.PrimaryTab
import com.example.SecondaryDestination
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.theme.*

@Composable
fun AgencyMenuDrawerContent(
    state: AgencyUiState,
    visibleTabs: List<PrimaryTab>,
    activeTab: PrimaryTab,
    secondaryDest: SecondaryDestination?,
    permittedSecondary: List<SecondaryDestination>,
    canCreateQuote: Boolean,
    onSelectTab: (PrimaryTab) -> Unit,
    onSelectSecondary: (SecondaryDestination) -> Unit,
    onOpenNewQuote: () -> Unit,
    onOpenProfilePhoto: () -> Unit,
    onSignOut: () -> Unit,
    onCloseDrawer: () -> Unit
) {
    val member = state.myMember ?: return
    val access = member.accessLevelEnum()
    val pendingMembersCount = state.members.count { it.statusEnum() == MembershipStatus.PENDING }
    val activeProjectsCount = state.projects.count { !it.deleted }
    val pendingRequestsCount = state.requests.count { it.decisionStatusEnum() == DecisionStatus.PENDING }
    val myPendingCount = state.deliverables.count {
        it.assignedToUid == member.uid && it.statusEnum() != DeliverableStatus.APPROVED
    }

    val adminSecondary = permittedSecondary.filter {
        it in setOf(
            SecondaryDestination.TEAM,
            SecondaryDestination.ORG_ROUTING,
            SecondaryDestination.COMPANY_SETTINGS,
            SecondaryDestination.ACTIVITY
        )
    }
    val commercialSecondary = permittedSecondary.filter {
        it in setOf(
            SecondaryDestination.CLIENTS,
            SecondaryDestination.QUOTATIONS,
            SecondaryDestination.CATALOGUE,
            SecondaryDestination.PAYMENTS
        )
    }

    ModalDrawerSheet(
        drawerContainerColor = PureWhite,
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        modifier = Modifier
            .widthIn(min = 288.dp, max = 334.dp)
            .testTag("agency_menu_drawer")
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                XMaxLogoImage(tint = ObsidianCharcoal, height = 16.dp)
                Surface(
                    onClick = onCloseDrawer,
                    shape = CircleShape,
                    color = MutedSurface,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("close_menu_drawer_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close Menu",
                            tint = ObsidianCharcoal,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Surface(
                onClick = onOpenProfilePhoto,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("drawer_profile_card"),
                shape = RoundedCornerShape(20.dp),
                color = SoftCardSurface,
                border = BorderStroke(1.dp, SubtleOutline)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StudioAvatar(
                            name = member.fullName,
                            size = 46.dp,
                            borderColor = PureWhite,
                            photoBase64 = member.photoBase64,
                            showEditBadge = true,
                            onClick = onOpenProfilePhoto,
                            modifier = Modifier.testTag("drawer_profile_avatar")
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = formatTitleCase(member.fullName.ifBlank { "Member" }),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = ObsidianCharcoal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = state.firebaseUser?.email ?: state.firebaseUser?.phoneNumber ?: member.jobRoleName,
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = "Update Profile Photo",
                            tint = VioletPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(
                            label = formatTitleCase(access.label),
                            containerColor = ObsidianCharcoal,
                            contentColor = PureWhite
                        )
                        if (member.jobRoleName.isNotBlank()) {
                            StatusPill(
                                label = formatTitleCase(member.jobRoleName),
                                containerColor = PureWhite,
                                contentColor = ObsidianCharcoal
                            )
                        }
                    }

                    if (state.isSuperAdmin || state.isAdmin) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            DrawerMiniCounter(
                                label = "Projects",
                                count = "$activeProjectsCount",
                                containerColor = KpiSkyBg,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectSecondary(SecondaryDestination.PROJECTS) }
                            )
                            DrawerMiniCounter(
                                label = "Requests",
                                count = "$pendingRequestsCount",
                                containerColor = KpiLavenderBg,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectTab(PrimaryTab.REQUESTS) }
                            )
                            DrawerMiniCounter(
                                label = "Approvals",
                                count = "$pendingMembersCount",
                                containerColor = if (pendingMembersCount > 0) ButterYellow else KpiMintBg,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectSecondary(SecondaryDestination.TEAM) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MenuSectionLabel("Workspace")
                val workspaceTabs = visibleTabs.filter { it != PrimaryTab.CHAT }
                workspaceTabs.forEach { tab ->
                    val badge = when (tab) {
                        PrimaryTab.CHAT -> null
                        PrimaryTab.REQUESTS -> pendingRequestsCount.takeIf { it > 0 }?.toString()
                        PrimaryTab.MY_WORK -> myPendingCount.takeIf { it > 0 }?.toString()
                        PrimaryTab.HOME -> null
                    }
                    MinimalSidebarRow(
                        title = formatTitleCase(tab.label),
                        icon = tab.inactiveIcon,
                        activeIcon = tab.icon,
                        selected = secondaryDest == null && activeTab == tab,
                        badgeText = badge,
                        modifier = Modifier.testTag("menu_tab_${tab.name.lowercase()}"),
                        onClick = { onSelectTab(tab) }
                    )
                }
                if (permittedSecondary.contains(SecondaryDestination.PROJECTS)) {
                    MinimalSidebarRow(
                        title = formatTitleCase(SecondaryDestination.PROJECTS.label),
                        icon = SecondaryDestination.PROJECTS.icon,
                        activeIcon = SecondaryDestination.PROJECTS.icon,
                        selected = secondaryDest == SecondaryDestination.PROJECTS,
                        badgeText = activeProjectsCount.takeIf { it > 0 }?.toString(),
                        modifier = Modifier.testTag("menu_dest_projects"),
                        onClick = { onSelectSecondary(SecondaryDestination.PROJECTS) }
                    )
                }

                if (permittedSecondary.contains(SecondaryDestination.NOTIFICATIONS)) {
                    MinimalSidebarRow(
                        title = formatTitleCase(SecondaryDestination.NOTIFICATIONS.label),
                        icon = SecondaryDestination.NOTIFICATIONS.icon,
                        activeIcon = SecondaryDestination.NOTIFICATIONS.icon,
                        selected = secondaryDest == SecondaryDestination.NOTIFICATIONS,
                        badgeText = state.unreadNotificationCount.takeIf { it > 0 }?.toString(),
                        badgeAlert = state.unreadNotificationCount > 0,
                        modifier = Modifier.testTag("menu_dest_notifications"),
                        onClick = { onSelectSecondary(SecondaryDestination.NOTIFICATIONS) }
                    )
                }

                if (adminSecondary.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    MenuSectionLabel(
                        if (state.isSuperAdmin) "Super Admin Controls" else "Admin Controls"
                    )
                    adminSecondary.forEach { dest ->
                        val badge = if (dest == SecondaryDestination.TEAM && pendingMembersCount > 0) {
                            "$pendingMembersCount"
                        } else null
                        MinimalSidebarRow(
                            title = formatTitleCase(dest.label),
                            icon = dest.icon,
                            activeIcon = dest.icon,
                            selected = secondaryDest == dest,
                            badgeText = badge,
                            badgeAlert = dest == SecondaryDestination.TEAM && pendingMembersCount > 0,
                            modifier = Modifier.testTag("menu_dest_${dest.name.lowercase()}"),
                            onClick = { onSelectSecondary(dest) }
                        )
                    }
                }

                if (canCreateQuote || commercialSecondary.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    MenuSectionLabel("Commercial & Finance")
                    if (canCreateQuote) {
                        MinimalSidebarRow(
                            title = "New Quote",
                            icon = Icons.Filled.AddCircleOutline,
                            activeIcon = Icons.Filled.AddCircle,
                            selected = false,
                            modifier = Modifier.testTag("menu_new_quote_button"),
                            onClick = onOpenNewQuote
                        )
                    }
                    commercialSecondary.forEach { dest ->
                        val badge = when (dest) {
                            SecondaryDestination.CLIENTS -> state.clients.size.takeIf { it > 0 }?.toString()
                            SecondaryDestination.QUOTATIONS -> state.quotations.size.takeIf { it > 0 }?.toString()
                            else -> null
                        }
                        MinimalSidebarRow(
                            title = dest.label,
                            icon = dest.icon,
                            activeIcon = dest.icon,
                            selected = secondaryDest == dest,
                            badgeText = badge,
                            modifier = Modifier.testTag("menu_dest_${dest.name.lowercase()}"),
                            onClick = { onSelectSecondary(dest) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = SubtleOutline)
            Spacer(Modifier.height(8.dp))

            // Clean Log Out row at bottom matching sidebar_menu_2f70c97b.jpg
            Surface(
                onClick = onSignOut,
                shape = RoundedCornerShape(16.dp),
                color = CrispCanvas,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("menu_sign_out_button")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(SoftBlush),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Log out",
                            tint = DeepBlushInk,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Text(
                        text = "Log Out (${member.accessLevelEnum().label})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = DeepBlushInk
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerMiniCounter(
    label: String,
    count: String,
    containerColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = ObsidianCharcoal
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MenuSectionLabel(title: String) {
    Text(
        text = formatTitleCase(title),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.ExtraBold,
        color = StudioTextSecondary,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/**
 * Clean White Minimal Sidebar Row matching sidebar_menu_2f70c97b.jpg:
 * Black circular active icon badge + right-edge vertical indicator bar.
 */
@Composable
private fun MinimalSidebarRow(
    title: String,
    icon: ImageVector,
    activeIcon: ImageVector,
    selected: Boolean,
    badgeText: String? = null,
    badgeAlert: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) SoftCardSurface else Color.Transparent,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (selected) ObsidianCharcoal else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (selected) activeIcon else icon,
                    contentDescription = null,
                    tint = if (selected) PureWhite else StudioTextSecondary,
                    modifier = Modifier.size(19.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) ObsidianCharcoal else DockIconInactive,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!badgeText.isNullOrBlank()) {
                StatusPill(
                    label = badgeText,
                    containerColor = if (badgeAlert) SoftBlush else MutedSurface,
                    contentColor = if (badgeAlert) DeepBlushInk else ObsidianCharcoal
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianCharcoal)
                )
            }
        }
    }
}
