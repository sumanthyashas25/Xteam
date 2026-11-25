package com.example.chat

import com.example.model.ChatStatus
import com.example.model.Member
import com.example.model.MembershipStatus
import com.example.model.StatusPrivacyMode

data class StatusOwnerSummary(
    val owner: Member,
    val statuses: List<ChatStatus>,
    val unviewedCount: Int,
    val viewedCount: Int,
    val isMuted: Boolean,
    val latestCreatedAtMillis: Long
) {
    val hasUnviewed: Boolean get() = unviewedCount > 0
    val member: Member get() = owner
    val activeStatuses: List<ChatStatus> get() = statuses
}

data class CategorizedStatuses(
    val myStatuses: List<ChatStatus>,
    val recentUpdates: List<StatusOwnerSummary>,
    val viewedUpdates: List<StatusOwnerSummary>,
    val mutedUpdates: List<StatusOwnerSummary>,
    val visibleByOwner: Map<String, List<ChatStatus>>,
    val totalUnviewedContacts: Int
) {
    val mySummary: StatusOwnerSummary? get() {
        if (myStatuses.isEmpty()) return null
        return StatusOwnerSummary(
            owner = Member(uid = myStatuses.first().ownerUid, fullName = myStatuses.first().ownerName),
            statuses = myStatuses,
            unviewedCount = 0,
            viewedCount = myStatuses.size,
            isMuted = false,
            latestCreatedAtMillis = myStatuses.maxOfOrNull { it.createdAtMillis } ?: 0L
        )
    }
}

/**
 * Pure domain engine enforcing 24h status expiration, privacy rules
 * (All Approved, Selected Members Only, All Except Selected), blocked users,
 * unauthorized member guards, and Recent/Viewed/Muted categorization.
 */
object StatusPrivacyEngine {

    fun isAuthorizedMember(member: Member?): Boolean {
        if (member == null) return false
        return member.statusEnum() == MembershipStatus.APPROVED && member.active
    }

    fun canViewerSeeStatus(
        status: ChatStatus,
        viewerUid: String,
        viewerMember: Member?,
        blockedByViewerUids: Set<String> = emptySet(),
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (viewerUid.isBlank()) return false
        if (!status.isActive(nowMillis)) return false
        if (status.ownerUid == viewerUid) return true

        // Unauthorized or suspended/pending users never see statuses or status rings
        if (!isAuthorizedMember(viewerMember)) return false

        // Mutual block enforcement
        if (viewerUid in status.ownerBlockedUids) return false
        if (status.ownerUid in blockedByViewerUids) return false

        return when (status.privacyModeEnum()) {
            StatusPrivacyMode.ALL_APPROVED -> true
            StatusPrivacyMode.SELECTED_ONLY -> viewerUid in status.allowedUids
            StatusPrivacyMode.ALL_EXCEPT -> viewerUid !in status.excludedUids
        }
    }

    fun canViewerSeeStatus(
        status: ChatStatus,
        viewer: Member?,
        blockedUids: Set<String> = emptySet(),
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = canViewerSeeStatus(
        status = status,
        viewerUid = viewer?.uid.orEmpty(),
        viewerMember = viewer,
        blockedByViewerUids = blockedUids,
        nowMillis = nowMillis
    )

    fun visibleActiveStatusesForOwner(
        ownerUid: String,
        allStatuses: List<ChatStatus>,
        viewer: Member?,
        blockedUids: Set<String> = emptySet(),
        nowMillis: Long = System.currentTimeMillis()
    ): List<ChatStatus> = visibleActiveStatusesForOwner(
        statuses = allStatuses,
        ownerUid = ownerUid,
        viewerUid = viewer?.uid.orEmpty(),
        viewerMember = viewer,
        blockedByViewerUids = blockedUids,
        nowMillis = nowMillis
    )

    fun categorizeStatuses(
        allStatuses: List<ChatStatus>,
        allMembers: List<Member>,
        viewer: Member?,
        mutedOwnerUids: Set<String> = emptySet(),
        blockedUids: Set<String> = emptySet(),
        nowMillis: Long = System.currentTimeMillis()
    ): CategorizedStatuses = categorizeStatuses(
        statuses = allStatuses,
        myMember = viewer,
        allMembers = allMembers,
        mutedOwnerUids = mutedOwnerUids,
        blockedByViewerUids = blockedUids,
        nowMillis = nowMillis
    )

    fun visibleActiveStatusesForOwner(
        statuses: List<ChatStatus>,
        ownerUid: String,
        viewerUid: String,
        viewerMember: Member?,
        blockedByViewerUids: Set<String> = emptySet(),
        nowMillis: Long = System.currentTimeMillis()
    ): List<ChatStatus> {
        return statuses
            .filter {
                it.ownerUid == ownerUid &&
                    canViewerSeeStatus(it, viewerUid, viewerMember, blockedByViewerUids, nowMillis)
            }
            .sortedBy { it.createdAtMillis }
    }

    fun categorizeStatuses(
        statuses: List<ChatStatus>,
        myMember: Member?,
        allMembers: List<Member>,
        mutedOwnerUids: Set<String>,
        blockedByViewerUids: Set<String>,
        nowMillis: Long = System.currentTimeMillis()
    ): CategorizedStatuses {
        val myUid = myMember?.uid.orEmpty()
        if (myUid.isBlank()) {
            return CategorizedStatuses(
                myStatuses = emptyList(),
                recentUpdates = emptyList(),
                viewedUpdates = emptyList(),
                mutedUpdates = emptyList(),
                visibleByOwner = emptyMap(),
                totalUnviewedContacts = 0
            )
        }

        val memberByUid = allMembers.associateBy { it.uid }

        // Only consider statuses the viewer is permitted to see, and from active approved owners (or self)
        val visible = statuses.filter { st ->
            val ownerMember = memberByUid[st.ownerUid]
            val ownerAllowed = st.ownerUid == myUid || ownerMember == null || isAuthorizedMember(ownerMember)
            ownerAllowed && canViewerSeeStatus(st, myUid, myMember, blockedByViewerUids, nowMillis)
        }

        val grouped = visible.groupBy { it.ownerUid }
            .mapValues { (_, list) -> list.sortedBy { it.createdAtMillis } }

        val myStatuses = grouped[myUid].orEmpty()
        val recent = mutableListOf<StatusOwnerSummary>()
        val viewed = mutableListOf<StatusOwnerSummary>()
        val muted = mutableListOf<StatusOwnerSummary>()

        grouped.forEach { (ownerUid, list) ->
            if (ownerUid == myUid || list.isEmpty()) return@forEach
            val ownerMember = memberByUid[ownerUid] ?: Member(
                id = ownerUid,
                uid = ownerUid,
                fullName = list.lastOrNull()?.ownerName ?: "Team Member",
                jobRoleName = list.lastOrNull()?.ownerRole ?: "",
                photoBase64 = list.lastOrNull()?.ownerPhotoBase64 ?: "",
                status = MembershipStatus.APPROVED.name,
                active = true
            )
            val viewedCount = list.count { it.isViewedBy(myUid) }
            val unviewedCount = (list.size - viewedCount).coerceAtLeast(0)
            val isMuted = ownerUid in mutedOwnerUids
            val summary = StatusOwnerSummary(
                owner = ownerMember,
                statuses = list,
                unviewedCount = unviewedCount,
                viewedCount = viewedCount,
                isMuted = isMuted,
                latestCreatedAtMillis = list.maxOfOrNull { it.createdAtMillis } ?: 0L
            )
            when {
                isMuted -> muted += summary
                unviewedCount > 0 -> recent += summary // Viewed contacts auto-return here on new upload
                else -> viewed += summary
            }
        }

        val sortedRecent = recent.sortedByDescending { it.latestCreatedAtMillis }
        val sortedViewed = viewed.sortedByDescending { it.latestCreatedAtMillis }
        val sortedMuted = muted.sortedByDescending { it.latestCreatedAtMillis }

        return CategorizedStatuses(
            myStatuses = myStatuses,
            recentUpdates = sortedRecent,
            viewedUpdates = sortedViewed,
            mutedUpdates = sortedMuted,
            visibleByOwner = grouped,
            totalUnviewedContacts = sortedRecent.size
        )
    }
}
