package com.example

import com.example.chat.StatusPrivacyEngine
import com.example.model.*
import org.junit.Assert.*
import org.junit.Test

class StatusSystemTest {

    private val myMember = Member(
        uid = "user_me",
        fullName = "Myself",
        status = MembershipStatus.APPROVED.name,
        accessLevel = AccessLevel.STAFF.name,
        active = true
    )

    private val colleague1 = Member(
        uid = "user_colleague_1",
        fullName = "Alice Smith",
        status = MembershipStatus.APPROVED.name,
        accessLevel = AccessLevel.STAFF.name,
        active = true
    )

    private val colleague2 = Member(
        uid = "user_colleague_2",
        fullName = "Bob Jones",
        status = MembershipStatus.APPROVED.name,
        accessLevel = AccessLevel.STAFF.name,
        active = true
    )

    private val pendingMember = Member(
        uid = "user_pending",
        fullName = "Pending User",
        status = MembershipStatus.PENDING.name,
        accessLevel = AccessLevel.STAFF.name,
        active = true
    )

    @Test
    fun testStatus24hExpirationLogic() {
        val now = System.currentTimeMillis()

        val recentStatus = ChatStatus(
            id = "s_recent",
            ownerUid = "user_colleague_1",
            createdAtMillis = now - (2 * 3600 * 1000L), // 2 hours ago
            expiresAtMillis = now + (22 * 3600 * 1000L)
        )
        assertTrue("Status created 2 hours ago must be active", recentStatus.isActive)

        val expiredStatus = ChatStatus(
            id = "s_expired",
            ownerUid = "user_colleague_1",
            createdAtMillis = now - (25 * 3600 * 1000L), // 25 hours ago
            expiresAtMillis = now - (1 * 3600 * 1000L)
        )
        assertFalse("Status created 25 hours ago must not be active", expiredStatus.isActive)
    }

    @Test
    fun testPrivacyEngineMembershipAndBlockCheck() {
        val now = System.currentTimeMillis()

        val publicStatus = ChatStatus(
            id = "s_public",
            ownerUid = colleague1.uid,
            privacyMode = StatusPrivacyMode.ALL_APPROVED.name,
            createdAtMillis = now
        )

        // Pending user cannot see statuses
        assertFalse(
            "Pending member must not be authorized to view status",
            StatusPrivacyEngine.canViewerSeeStatus(publicStatus, pendingMember)
        )

        // Approved user can see public status
        assertTrue(
            "Approved colleague must see ALL_APPROVED status",
            StatusPrivacyEngine.canViewerSeeStatus(publicStatus, myMember)
        )

        // Mutual blocking: if viewer blocked owner
        assertFalse(
            "Viewer cannot see status if viewer blocked the status owner",
            StatusPrivacyEngine.canViewerSeeStatus(publicStatus, myMember, blockedUids = setOf(colleague1.uid))
        )

        // Mutual blocking: if owner blocked viewer
        val blockedStatus = publicStatus.copy(ownerBlockedUids = listOf(myMember.uid))
        assertFalse(
            "Viewer cannot see status if owner blocked viewer in status doc",
            StatusPrivacyEngine.canViewerSeeStatus(blockedStatus, myMember)
        )
    }

    @Test
    fun testPrivacyModesSelectedOnlyAndAllExcept() {
        val now = System.currentTimeMillis()

        // SELECTED_ONLY
        val selectedStatus = ChatStatus(
            id = "s_selected",
            ownerUid = colleague1.uid,
            privacyMode = StatusPrivacyMode.SELECTED_ONLY.name,
            allowedUids = listOf(myMember.uid),
            createdAtMillis = now
        )
        assertTrue("Whitelisted member can see SELECTED_ONLY status", StatusPrivacyEngine.canViewerSeeStatus(selectedStatus, myMember))
        assertFalse("Unlisted colleague cannot see SELECTED_ONLY status", StatusPrivacyEngine.canViewerSeeStatus(selectedStatus, colleague2))

        // ALL_EXCEPT
        val exceptStatus = ChatStatus(
            id = "s_except",
            ownerUid = colleague1.uid,
            privacyMode = StatusPrivacyMode.ALL_EXCEPT.name,
            excludedUids = listOf(colleague2.uid),
            createdAtMillis = now
        )
        assertTrue("Non-excluded member can see ALL_EXCEPT status", StatusPrivacyEngine.canViewerSeeStatus(exceptStatus, myMember))
        assertFalse("Excluded member cannot see ALL_EXCEPT status", StatusPrivacyEngine.canViewerSeeStatus(exceptStatus, colleague2))
    }

    @Test
    fun testCategorizeStatusesAndUnviewedRepromotion() {
        val now = System.currentTimeMillis()

        val myStatus = ChatStatus(
            id = "s_my",
            ownerUid = myMember.uid,
            ownerName = myMember.fullName,
            text = "My first status",
            createdAtMillis = now
        )

        val colleague1Status1 = ChatStatus(
            id = "s_c1_1",
            ownerUid = colleague1.uid,
            ownerName = colleague1.fullName,
            text = "Morning coffee",
            viewerUids = listOf(myMember.uid), // Already viewed by me
            createdAtMillis = now - 5000L
        )

        val allMembers = listOf(myMember, colleague1, colleague2)

        // Case A: colleague1 status is already viewed
        var summary = StatusPrivacyEngine.categorizeStatuses(
            allStatuses = listOf(myStatus, colleague1Status1),
            allMembers = allMembers,
            viewer = myMember,
            mutedOwnerUids = emptySet(),
            blockedUids = emptySet()
        )

        assertNotNull("My status summary must exist", summary.mySummary)
        assertEquals(1, summary.mySummary!!.activeStatuses.size)
        assertEquals(0, summary.recentUpdates.size)
        assertEquals(1, summary.viewedUpdates.size)
        assertEquals(colleague1.uid, summary.viewedUpdates.first().member.uid)

        // Case B: colleague1 uploads a NEW status that I haven't viewed yet
        val colleague1Status2 = ChatStatus(
            id = "s_c1_2",
            ownerUid = colleague1.uid,
            ownerName = colleague1.fullName,
            text = "Afternoon update",
            viewerUids = emptyList(), // NOT viewed
            createdAtMillis = now
        )

        summary = StatusPrivacyEngine.categorizeStatuses(
            allStatuses = listOf(myStatus, colleague1Status1, colleague1Status2),
            allMembers = allMembers,
            viewer = myMember,
            mutedOwnerUids = emptySet(),
            blockedUids = emptySet()
        )

        // Because colleague1 has at least 1 unviewed status, they must be automatically re-promoted to recentUpdates!
        assertEquals("Colleague with unviewed status must be in recentUpdates", 1, summary.recentUpdates.size)
        assertEquals(colleague1.uid, summary.recentUpdates.first().member.uid)
        assertEquals("viewedUpdates must now be empty for colleague1", 0, summary.viewedUpdates.size)

        // Case C: Muting colleague1 moves them to mutedUpdates
        summary = StatusPrivacyEngine.categorizeStatuses(
            allStatuses = listOf(myStatus, colleague1Status1, colleague1Status2),
            allMembers = allMembers,
            viewer = myMember,
            mutedOwnerUids = setOf(colleague1.uid),
            blockedUids = emptySet()
        )
        assertEquals("Muted contact must not be in recentUpdates", 0, summary.recentUpdates.size)
        assertEquals("Muted contact must be in mutedUpdates", 1, summary.mutedUpdates.size)
        assertEquals(colleague1.uid, summary.mutedUpdates.first().member.uid)
    }

    @Test
    fun testPrimaryTabStatusExists() {
        val tabs = PrimaryTab.values()
        assertTrue("PrimaryTab must include STATUS", tabs.any { it.name == "STATUS" })
        val statusTab = PrimaryTab.valueOf("STATUS")
        assertEquals("Status", statusTab.label)
    }
}
