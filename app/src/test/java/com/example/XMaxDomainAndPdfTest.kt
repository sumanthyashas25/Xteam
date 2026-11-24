package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AuthSessionStatus
import com.example.domain.CatalogueSeedData
import com.example.domain.MoneyFormatter
import com.example.domain.PermissionEngine
import com.example.domain.QuoteCalculator
import com.example.domain.WorkDistributionEngine
import com.example.model.*
import com.example.pdf.QuotationPdfGenerator
import com.example.ui.AgencyUiState
import com.example.ui.navigation.XMaxRoute
import com.example.ui.navigation.resolveAuthRoute
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XMaxDomainAndPdfTest {

    @Test
    fun `seeded catalogue contains 40 services, 5 packages, and 11 addons`() {
        val all = CatalogueSeedData.defaultCatalogue()
        assertEquals(40, all.count { it.kindEnum() == CatalogueKind.SERVICE })
        assertEquals(5, all.count { it.kindEnum() == CatalogueKind.PACKAGE })
        assertEquals(11, all.count { it.kindEnum() == CatalogueKind.ADDON })
        assertEquals(56, all.size)
        assertEquals("2026_02", CatalogueSeedData.OFFICIAL_CATALOGUE_VERSION)
        val byId = all.associateBy { it.id }
        assertEquals(50_000L, byId["srv_01"]?.pricePaise)
        assertEquals(2_000_000L, byId["srv_28"]?.pricePaise)
        assertEquals("DIGITAL PRESENCE", byId["pkg_03"]?.name)
        assertEquals(4_000_000L, byId["pkg_03"]?.pricePaise)
        assertEquals("BUSINESS LAUNCH", byId["pkg_04"]?.name)
        assertEquals(5_500_000L, byId["pkg_04"]?.pricePaise)
        assertEquals(200_000L, byId["add_08"]?.pricePaise)
        assertTrue(CatalogueSeedData.isBootstrapEmail("xdotmax@gmail.com"))
        assertFalse(CatalogueSeedData.isBootstrapEmail("random@example.com"))
        assertEquals("xdotmax@gmail.com", CatalogueSeedData.defaultCompanyProfile("u1").email)
    }

    @Test
    fun `scenario 4 business launch plus extra page totals 57000 INR with 28500 INR advance`() {
        val catalogue = CatalogueSeedData.defaultCatalogue().associateBy { it.id }
        val businessLaunch = QuoteCalculator.fromCatalogueItem(catalogue["pkg_04"]!!, "l1")
        val extraPage = QuoteCalculator.fromCatalogueItem(catalogue["add_08"]!!, "l2")

        val calc = QuoteCalculator.calculate(
            rawLines = listOf(businessLaunch, extraPage),
            urgentDeliveryEnabled = false,
            gstEnabled = false,
            advancePercent = 50
        )

        assertEquals(5_700_000L, calc.totalPaise)
        assertEquals(2_850_000L, calc.advancePaise)
        assertEquals(2_850_000L, calc.balancePaise)
        assertEquals("₹57,000", MoneyFormatter.formatInr(calc.totalPaise))
        assertEquals("₹28,500", MoneyFormatter.formatInr(calc.advancePaise))
        assertTrue(calc.validationErrors.isEmpty())
    }

    @Test
    fun `custom quote and actual cost require price or separately billed flag before issuing`() {
        val catalogue = CatalogueSeedData.defaultCatalogue().associateBy { it.id }
        val customLine = QuoteCalculator.fromCatalogueItem(catalogue["add_06"]!!, "l_custom")

        val blocked = QuoteCalculator.calculate(listOf(customLine), urgentDeliveryEnabled = false)
        assertEquals(1, blocked.validationErrors.size)

        val fixedWithSep = QuoteCalculator.calculate(listOf(customLine.copy(separatelyBilled = true)), urgentDeliveryEnabled = false)
        assertTrue(fixedWithSep.validationErrors.isEmpty())

        val fixedWithAmount = QuoteCalculator.calculate(listOf(customLine.copy(overridePricePaise = 1_500_000L)), urgentDeliveryEnabled = false)
        assertTrue(fixedWithAmount.validationErrors.isEmpty())
        assertEquals(1_500_000L, fixedWithAmount.totalPaise)
    }

    @Test
    fun `urgent delivery 25 percent surcharge applies only to eligible lines and warns on overlap`() {
        val catalogue = CatalogueSeedData.defaultCatalogue().associateBy { it.id }
        val businessLaunch = QuoteCalculator.fromCatalogueItem(catalogue["pkg_04"]!!, "l1")
        val standaloneBrand = QuoteCalculator.fromCatalogueItem(catalogue["srv_13"]!!, "l2")

        val calc = QuoteCalculator.calculate(
            rawLines = listOf(businessLaunch, standaloneBrand),
            urgentDeliveryEnabled = true,
            urgentSurchargePercent = 25,
            urgentExcludedLineIds = setOf("l2")
        )

        assertEquals(5_500_000L, calc.surchargeBasePaise)
        assertEquals(1_375_000L, calc.surchargePaise)
        assertEquals(1, calc.overlapWarnings.size)
    }

    @Test
    fun `scenario 2 and 3 permission engine checks reserved actions and delegation revocation`() {
        val admin = Member(
            uid = "admin_1",
            fullName = "Manager",
            accessLevel = AccessLevel.ADMIN.name,
            status = MembershipStatus.APPROVED.name,
            active = true,
            scopeType = ScopeType.COMPANY.name,
            permissions = mapOf(
                AppPermission.ACCEPT_PROJECT.name to PermissionGrant(allowed = true, canDelegate = false),
                AppPermission.DECLINE_PROJECT.name to PermissionGrant(allowed = true, canDelegate = false),
                AppPermission.ISSUE_QUOTATIONS.name to PermissionGrant(allowed = true, canDelegate = true)
            )
        )
        val staff = Member(
            uid = "staff_1",
            fullName = "Designer",
            accessLevel = AccessLevel.STAFF.name,
            status = MembershipStatus.APPROVED.name,
            active = true,
            departmentId = "dept_creative",
            delegatedGrants = mapOf(
                AppPermission.ISSUE_QUOTATIONS.name to DelegatedGrant(allowed = true, grantedByUid = "admin_1"),
                AppPermission.ACCEPT_PROJECT.name to DelegatedGrant(allowed = true, grantedByUid = "admin_1")
            )
        )
        val map = mapOf(admin.uid to admin, staff.uid to staff)

        assertTrue(PermissionEngine.hasPermission(admin, AppPermission.ACCEPT_PROJECT, map))
        assertTrue(PermissionEngine.hasPermission(admin, AppPermission.DECLINE_PROJECT, map))
        assertFalse(PermissionEngine.hasPermission(admin, AppPermission.DELETE_PROJECT, map))
        assertTrue(PermissionEngine.hasPermission(staff, AppPermission.ISSUE_QUOTATIONS, map))
        // Reserved permissions can never be held by Staff even if injected
        assertFalse(PermissionEngine.hasPermission(staff, AppPermission.ACCEPT_PROJECT, map))

        // Revoking Admin's ISSUE_QUOTATIONS immediately disables Staff capability and computes cascade cleanup
        val updatedAdmin = admin.copy(permissions = emptyMap())
        val mapAfter = mapOf(updatedAdmin.uid to updatedAdmin, staff.uid to staff)
        assertFalse(PermissionEngine.hasPermission(staff, AppPermission.ISSUE_QUOTATIONS, mapAfter))

        val revocations = PermissionEngine.computeDependentStaffRevocations("admin_1", emptyMap(), listOf(staff))
        assertTrue(revocations.containsKey("staff_1"))
    }

    @Test
    fun `scenario 6 work distribution resolves 1 match, queue on multiple, and needs assignment on zero`() {
        val catalogue = CatalogueSeedData.defaultCatalogue().associateBy { it.id }
        val businessLaunch = QuoteCalculator.fromCatalogueItem(catalogue["pkg_04"]!!, "l1")
        val extraPage = QuoteCalculator.fromCatalogueItem(catalogue["add_08"]!!, "l2")

        val designer = Member(
            uid = "u_des", fullName = "Aarav", accessLevel = AccessLevel.STAFF.name,
            status = MembershipStatus.APPROVED.name, active = true,
            departmentId = "dept_creative", departmentName = "Creative",
            jobRoleId = "role_graphic_designer", jobRoleName = "Graphic Designer"
        )
        val web1 = Member(
            uid = "u_web1", fullName = "Dev One", accessLevel = AccessLevel.STAFF.name,
            status = MembershipStatus.APPROVED.name, active = true,
            departmentId = "dept_web", departmentName = "Web",
            jobRoleId = "role_web_developer", jobRoleName = "Web Developer"
        )
        val web2 = Member(
            uid = "u_web2", fullName = "Dev Two", accessLevel = AccessLevel.STAFF.name,
            status = MembershipStatus.APPROVED.name, active = true,
            departmentId = "dept_web", departmentName = "Web",
            jobRoleId = "role_web_developer", jobRoleName = "Web Developer"
        )

        val drafts = WorkDistributionEngine.buildDeliverableDrafts(
            quoteLines = listOf(businessLaunch, extraPage),
            routingRules = CatalogueSeedData.defaultRoutingRules(),
            members = listOf(designer, web1, web2),
            departments = CatalogueSeedData.defaultDepartments(),
            jobRoles = CatalogueSeedData.defaultJobRoles()
        )

        val brandDraft = drafts.first { it.title.contains("brand identity", ignoreCase = true) }
        assertEquals(AssignmentStatus.ASSIGNED, brandDraft.assignmentStatus)
        assertEquals("u_des", brandDraft.assignedToUid)

        val webDraft = drafts.first { it.title.contains("website", ignoreCase = true) }
        assertEquals(AssignmentStatus.DEPT_QUEUE, webDraft.assignmentStatus)

        val adsDraft = drafts.first { it.title.contains("Meta Ads", ignoreCase = true) }
        assertEquals(AssignmentStatus.NEEDS_ASSIGNMENT, adsDraft.assignmentStatus)

        val summary = WorkDistributionEngine.summarizeProjectProgress(
            listOf(
                Deliverable(id = "1", status = DeliverableStatus.APPROVED.name),
                Deliverable(id = "2", status = DeliverableStatus.APPROVED.name),
                Deliverable(id = "3", status = DeliverableStatus.READY_FOR_REVIEW.name),
                Deliverable(id = "4", status = DeliverableStatus.BLOCKED.name)
            )
        )
        assertEquals("2 of 4 deliverables approved, 1 in review, 1 blocked", summary)
    }

    @Test
    fun `scenario 5 pdf target path and FileProvider share intent use persistent quotations directory`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalogue = CatalogueSeedData.defaultCatalogue().associateBy { it.id }
        val lines = listOf(
            QuoteCalculator.fromCatalogueItem(catalogue["pkg_04"]!!, "l1"),
            QuoteCalculator.fromCatalogueItem(catalogue["add_08"]!!, "l2")
        )
        val calc = QuoteCalculator.calculate(lines, urgentDeliveryEnabled = false, advancePercent = 50)
        val quote = Quotation(
            id = "qt_test",
            quoteNumber = "QT-2026-0001",
            revision = 1,
            requestId = "req_1",
            clientId = "cli_1",
            projectTitle = "Acme Launch",
            status = QuotationStatus.ISSUED.name,
            companySnapshot = CompanySnapshot(name = "X.MAX", tagline = "Creative Agency", address = "Bengaluru"),
            clientSnapshot = ClientSnapshot("cli_1", "Acme India", "Riya Shah", "+91 90000 11111", "riya@acme.in", "Mumbai"),
            lines = calc.computedLines,
            subtotalPaise = calc.subtotalPaise,
            totalPaise = calc.totalPaise,
            advancePercent = 50,
            advancePaise = calc.advancePaise,
            balancePaise = calc.balancePaise,
            paymentTerms = "50% advance, 50% on delivery",
            revisionPolicy = "2 revision rounds per deliverable",
            preparedByUid = "u1",
            preparedByName = "Manager"
        )

        val target = QuotationPdfGenerator.buildPdfTargetFile(context, quote)
        target.writeText("%PDF-1.4 mock")
        assertTrue(target.exists())
        assertTrue(target.absolutePath.contains("quotations/QT-2026-0001_v1.pdf"))

        val shareIntent = QuotationPdfGenerator.buildShareIntent(context, target, quote)
        assertEquals(android.content.Intent.ACTION_SEND, shareIntent.action)
        assertEquals("application/pdf", shareIntent.type)
        val streamUri = shareIntent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
        assertNotNull(streamUri)
        assertTrue(streamUri.toString().contains("fileprovider"))
        assertEquals("X.MAX Quotation QT-2026-0001 (v1) - Acme Launch", shareIntent.getStringExtra(android.content.Intent.EXTRA_SUBJECT))
        assertTrue(shareIntent.getStringExtra(android.content.Intent.EXTRA_TEXT)!!.contains("₹57,000"))
    }

    @Test
    fun `auth navigation graph resolves Initializing and SignedOut destinations cleanly`() {
        assertEquals(
            XMaxRoute.Loading,
            resolveAuthRoute(AgencyUiState(authSessionStatus = AuthSessionStatus.Initializing))
        )
        assertEquals(
            XMaxRoute.Login,
            resolveAuthRoute(AgencyUiState(authSessionStatus = AuthSessionStatus.SignedOut))
        )
        assertEquals("login", XMaxRoute.Login.route)
        assertEquals("onboarding", XMaxRoute.Onboarding.route)
        assertEquals("pending_approval", XMaxRoute.PendingApproval.route)
        assertEquals("dashboard", XMaxRoute.Dashboard.route)
    }

    @Test
    fun `user data model preserves profile details, access level, and membership status`() {
        val member = Member(
            id = "u_123",
            uid = "u_123",
            companyNameInput = "X.MAX",
            fullName = "Sumanth Director",
            accessLevel = AccessLevel.SUPER_ADMIN.name,
            status = MembershipStatus.APPROVED.name,
            active = true,
            departmentName = "Creative",
            jobRoleName = "Creative Director"
        )
        val pii = MemberPrivateInfo(uid = "u_123", email = "xdotmax@gmail.com", age = 28, phone = "+91 98000 00000")
        val user = User.fromMember(member, pii)

        assertEquals("u_123", user.uid)
        assertEquals("X.MAX", user.companyName)
        assertEquals("Sumanth Director", user.fullName)
        assertEquals("xdotmax@gmail.com", user.email)
        assertEquals(28, user.age)
        assertEquals("Creative", user.department)
        assertEquals("Creative Director", user.jobRole)
        assertEquals(AccessLevel.SUPER_ADMIN, user.accessLevelEnum())
        assertEquals(MembershipStatus.APPROVED, user.membershipStatusEnum())
        assertTrue(user.isApprovedAndActive())
    }
}
