package com.example

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.example.chat.ChatFileManager
import com.example.data.AuthSessionStatus
import com.example.data.SyncedData
import com.example.domain.CatalogueSeedData
import com.example.domain.PermissionEngine
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.AgencyViewModel
import com.example.ui.components.AgencyMenuDrawerContent
import com.example.ui.components.ProfilePhotoDialog
import com.example.ui.components.SlideToActionButton
import com.example.ui.components.ToastTone
import com.example.ui.components.XMaxToastBanner
import com.example.ui.navigation.LoadingDestinationScreen
import com.example.ui.navigation.XMaxRoute
import com.example.ui.navigation.resolveAuthRoute
import com.example.ui.screens.ActivityLogScreen
import com.example.ui.screens.ClientsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MyWorkScreen
import com.example.ui.screens.NotificationsScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.PendingApprovalScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.screens.TeamChatScreen
import com.example.ui.theme.XMaxTheme
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  private val composeTestRule = createAndroidComposeRule<MainActivity>()

  @get:Rule
  val ruleChain: RuleChain = RuleChain.outerRule(object : ExternalResource() {
    override fun before() {
      val ctx = ApplicationProvider.getApplicationContext<Context>()
      if (FirebaseApp.getApps(ctx).isEmpty()) {
        FirebaseApp.initializeApp(ctx)
      }
    }
  }).around(composeTestRule)

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Xteam", appName)
  }

  @Test
  fun `main activity launches cleanly into white login screen without crashing`() {
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("login_hero_emblem").assertExists()
    composeTestRule.onNodeWithText("Sign in to continue").assertExists()
    composeTestRule.onNodeWithTag("continue_with_google_button").assertExists()
    composeTestRule.onNodeWithTag("continue_with_phone_button").assertExists()
    composeTestRule.onNodeWithTag("login_terms_caption").assertExists()
  }

  @Test
  fun `resolveAuthRoute and PermissionEngine behave deterministically`() {
    assertEquals(
      XMaxRoute.Loading,
      resolveAuthRoute(AgencyUiState(authSessionStatus = AuthSessionStatus.Initializing))
    )
    assertEquals(
      XMaxRoute.Login,
      resolveAuthRoute(AgencyUiState(authSessionStatus = AuthSessionStatus.SignedOut))
    )
    val superAdmin = Member(
      id = "u_1",
      uid = "u_1",
      accessLevel = AccessLevel.SUPER_ADMIN.name,
      status = MembershipStatus.APPROVED.name,
      active = true
    )
    val effective = PermissionEngine.effectivePermissions(superAdmin, emptyMap())
    assertTrue(effective.contains(AppPermission.CREATE_QUOTATIONS))
    assertTrue(effective.contains(AppPermission.DELETE_PROJECT))
  }

  @Test
  fun `upgraded dashboard, my work calendar, onboarding, pending, toast, and sidebar screens render cleanly`() {
    val superAdmin = Member(
      id = "u_admin",
      uid = "u_admin",
      companyNameInput = "X.MAX",
      fullName = "X.MAX Director",
      accessLevel = AccessLevel.SUPER_ADMIN.name,
      status = MembershipStatus.APPROVED.name,
      active = true,
      departmentId = "dept_creative",
      departmentName = "Creative",
      jobRoleName = "Director"
    )
    val sampleDeliverable = Deliverable(
      id = "deliv_1",
      projectId = "proj_1",
      projectTitle = "Brand Launch 2026",
      clientCompanyName = "Acme Corp",
      title = "Brand Identity Guidelines",
      departmentId = "dept_creative",
      departmentName = "Creative",
      jobRoleName = "Director",
      assignedToUid = "u_admin",
      assignedToName = "X.MAX Director",
      status = DeliverableStatus.IN_PROGRESS.name,
      dueDateMillis = System.currentTimeMillis() + 86_400_000L,
      checklist = listOf(ChecklistItem("Draft moodboard", done = true), ChecklistItem("Export PDF", done = false))
    )
    val state = AgencyUiState(
      authSessionStatus = AuthSessionStatus.SignedOut,
      company = CatalogueSeedData.defaultCompanyProfile("u_admin"),
      companyLoaded = true,
      myMember = superAdmin,
      memberLoaded = true,
      members = listOf(superAdmin),
      departments = CatalogueSeedData.defaultDepartments(),
      deliverables = listOf(sampleDeliverable),
      catalogue = CatalogueSeedData.defaultCatalogue()
    )

    // 1. Upgraded 2x2 Pastel Sparkline Dashboard
    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          HomeScreen(
            state = state,
            onOpenNewQuote = {},
            onNavigateRequests = {},
            onNavigateProjects = {},
            onNavigateMyWork = {},
            onNavigateTeam = {},
            onNavigatePayments = {},
            onOpenDeliverable = {}
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("OVERVIEW").assertExists()
    composeTestRule.onNodeWithText("PROJECTS").assertExists()
    composeTestRule.onAllNodesWithText("Projects", ignoreCase = true).assertCountEquals(2)
    composeTestRule.onNodeWithTag("kpi_overview_card_1").assertExists()
    composeTestRule.onNodeWithTag("new_quote_button").assertExists()
    composeTestRule.onNodeWithTag("track_projects_pill_button").assertExists()
    composeTestRule.onNodeWithTag("kpi_overview_card_4").assertExists()

    // 2. Upgraded My Work Calendar & Pastel Deliverable Schedule
    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          MyWorkScreen(
            state = state,
            onOpenDeliverable = {}
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("PRODUCTIVITY").assertExists()
    composeTestRule.onNodeWithTag("staff_priority_strip").assertExists()
    composeTestRule.onNodeWithTag("toggle_calendar_view_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("my_work_lazy_list")
      .performScrollToNode(hasText("BRAND IDENTITY GUIDELINES"))
    composeTestRule.onNodeWithText("BRAND IDENTITY GUIDELINES").assertExists()

    // 3. Onboarding Screen
    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          OnboardingScreen(
            state = state.copy(myMember = null),
            onSubmitOnboarding = { _, _, _, _, _, _ -> },
            onBootstrapSuperAdmin = { _, _ -> },
            onSignOut = {}
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("submit_onboarding_button").assertExists()

    // 4. Pending Approval Screen
    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          PendingApprovalScreen(
            state = state.copy(myMember = superAdmin.copy(status = MembershipStatus.PENDING.name, active = false)),
            onBootstrapSuperAdmin = {},
            onSignOut = {}
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("pending_sign_out_button").assertExists()

    // 5. Approved Workspace with Welcome Header & 5-Slot Floating Dock + '+' FAB
    composeTestRule.activityRule.scenario.onActivity { activity ->
      val vm = ViewModelProvider(activity)[AgencyViewModel::class.java]
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          ApprovedWorkspace(state = state, viewModel = vm)
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("WELCOME").assertExists()
    composeTestRule.onAllNodesWithText("X.MAX DIRECTOR")[0].assertExists()
    composeTestRule.onNodeWithTag("header_profile_avatar").assertExists()
    composeTestRule.onNodeWithTag("open_chat_button").assertExists()
    composeTestRule.onNodeWithTag("notifications_button").assertExists()
    composeTestRule.onNodeWithTag("more_menu_button").assertExists()
    composeTestRule.onNodeWithTag("dock_quick_create_button").assertExists()
    composeTestRule.onNodeWithTag("nav_tab_home").assertExists()
    composeTestRule.onNodeWithTag("dock_quick_create_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("QUICK WORKSPACE ACTIONS").assertExists()

    // 6. Clean Minimal White Sidebar Menu
    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          AgencyMenuDrawerContent(
            state = state,
            visibleTabs = PrimaryTab.entries,
            activeTab = PrimaryTab.HOME,
            secondaryDest = null,
            permittedSecondary = SecondaryDestination.entries,
            canCreateQuote = true,
            onSelectTab = {},
            onSelectSecondary = {},
            onOpenNewQuote = {},
            onOpenProfilePhoto = {},
            onSignOut = {},
            onCloseDrawer = {}
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("menu_dest_chat").assertExists()
    composeTestRule.onNodeWithTag("menu_dest_notifications").assertExists()
    composeTestRule.onNodeWithTag("menu_sign_out_button").assertExists()

    // 7. Floating Pastel Toast Banner & Loading Recovery Controls
    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          XMaxToastBanner(
            title = "Completed",
            message = "Quotation saved to Firestore",
            tone = ToastTone.SUCCESS,
            onDismiss = {}
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("xmax_toast_banner").assertExists()

    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          LoadingDestinationScreen(
            label = "Loading your X.MAX profile…",
            errorMessage = "Network unreachable",
            canSignOut = true,
            onRetry = {},
            onSignOut = {}
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("loading_retry_button").assertExists()
    composeTestRule.onNodeWithTag("loading_sign_out_button").assertExists()

    // 8. Team Chat Hub (DM + Dept + Threads + Files) & Threaded Conversation
    val staffColleague = Member(
      id = "u_staff",
      uid = "u_staff",
      fullName = "Aarav Designer",
      accessLevel = AccessLevel.STAFF.name,
      status = MembershipStatus.APPROVED.name,
      active = true,
      departmentId = "dept_creative",
      departmentName = "Creative",
      jobRoleName = "Graphic Designer"
    )
    val generalChannel = ChatChannel(
      id = "ch_general",
      title = "General",
      topic = "Agency-wide updates & file sharing",
      channelType = ChatChannelType.GENERAL.name,
      lastMessagePreview = "Brand_Deck_v2.pdf",
      messageCount = 2L
    )
    val chatState = state.copy(
      members = listOf(superAdmin, staffColleague),
      chatChannels = listOf(generalChannel)
    )
    val sampleMessages = listOf(
      ChatMessage(
        id = "m1",
        channelId = "ch_general",
        senderUid = "u_admin",
        senderName = "X.MAX Director",
        senderRole = "Director",
        body = "Uploaded latest brand guidelines PDF",
        attachmentType = ChatAttachmentType.FILE.name,
        attachmentName = "Brand_Guidelines.pdf",
        attachmentMimeType = "application/pdf",
        attachmentSizeBytes = 248_000L
      ),
      ChatMessage(
        id = "m2",
        channelId = "ch_general",
        parentMessageId = "m1",
        parentPreview = "X.MAX: Uploaded latest brand guidelines PDF",
        senderUid = "u_staff",
        senderName = "Aarav Designer",
        senderRole = "Graphic Designer",
        body = "Reviewed — ready for client sign-off",
        attachmentType = ChatAttachmentType.NONE.name
      )
    )

    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          TeamChatScreen(
            state = chatState,
            initialChannelId = null,
            observeMessages = { flowOf(SyncedData(sampleMessages, false)) },
            onEnsureGeneralChannel = {},
            onCreateChannel = { _, _, _, _, _ -> },
            onSendMessage = { _, _, _, _, _ -> },
            onReportMessage = { _, _ -> },
            onCloseChat = {}
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("chat_hub_screen").assertExists()
    composeTestRule.onNodeWithText("NEW CHAT").assertExists()
    composeTestRule.onNodeWithTag("chat_tile_channels").assertExists()
    composeTestRule.onNodeWithTag("chat_tile_audio").assertExists()
    composeTestRule.onNodeWithTag("chat_tile_files").assertExists()
    composeTestRule.onNodeWithTag("chat_tile_images").assertExists()
    composeTestRule.onNodeWithTag("dm_person_u_staff").assertExists()
    composeTestRule.onNodeWithTag("dept_chat_dept_creative").assertExists()
    composeTestRule.onNodeWithTag("chat_hub_send_button").assertExists()

    composeTestRule.onNodeWithTag("chat_threads_section").performScrollTo()
    composeTestRule.onNodeWithTag("channel_chip_ch_general").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("chat_thread_screen").assertExists()
    composeTestRule.onNodeWithText("Brand_Guidelines.pdf").assertExists()
    composeTestRule.onNodeWithText("Reviewed — ready for client sign-off").assertExists()
    composeTestRule.onNodeWithTag("quick_attach_file_button").assertExists()
    composeTestRule.onNodeWithTag("quick_attach_photo_button").assertExists()
    composeTestRule.onNodeWithTag("quick_attach_audio_button").assertExists()
    composeTestRule.onNodeWithTag("chat_thread_send_button").assertExists()

    assertEquals("242 KB", ChatFileManager.formatFileSize(248_000L))
  }

  @Test
  fun `slide button, staff workflow features, profile photo, notifications sound, and super admin only deletions behave accurately`() {
    var slideTriggered = false
    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          SlideToActionButton(
            label = "SLIDE TO ACCEPT",
            onSlideComplete = { slideTriggered = true },
            testTag = "test_slide_pill"
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.runOnUiThread {
      composeTestRule.onNodeWithTag("test_slide_pill")
        .fetchSemanticsNode()
        .config[SemanticsActions.CustomActions]
        .first { it.label == "SLIDE TO ACCEPT" }
        .action()
    }
    assertTrue(slideTriggered)

    val staffMember = Member(
      id = "u_staff",
      uid = "u_staff",
      fullName = "Aarav Designer",
      accessLevel = AccessLevel.STAFF.name,
      status = MembershipStatus.APPROVED.name,
      active = true,
      departmentId = "dept_creative",
      departmentName = "Creative",
      jobRoleName = "Graphic Designer"
    )
    val adminMember = Member(
      id = "u_admin_mgr",
      uid = "u_admin_mgr",
      fullName = "Neha Manager",
      accessLevel = AccessLevel.ADMIN.name,
      status = MembershipStatus.APPROVED.name,
      active = true,
      departmentId = "dept_creative",
      departmentName = "Creative",
      jobRoleName = "Manager",
      permissions = mapOf(AppPermission.DELETE_PROJECT.name to PermissionGrant(allowed = true, canDelegate = false))
    )
    val superAdmin = staffMember.copy(
      id = "u_super",
      uid = "u_super",
      fullName = "Rohan Director",
      accessLevel = AccessLevel.SUPER_ADMIN.name
    )

    val notStartedTask = Deliverable(
      id = "d_not_started",
      projectId = "p_done",
      projectTitle = "Done Campaign",
      clientCompanyName = "Alpha Co",
      title = "Social Launch Reel",
      departmentId = "dept_creative",
      departmentName = "Creative",
      assignedToUid = "u_staff",
      assignedToName = "Aarav Designer",
      status = DeliverableStatus.NOT_STARTED.name,
      dueDateMillis = System.currentTimeMillis() + 3_600_000L
    )
    val queueTask = Deliverable(
      id = "d_queue",
      projectId = "p_done",
      projectTitle = "Done Campaign",
      clientCompanyName = "Alpha Co",
      title = "Motion Graphics Pack",
      departmentId = "dept_creative",
      departmentName = "Creative",
      assignmentStatus = AssignmentStatus.DEPT_QUEUE.name,
      assignedToUid = "",
      status = DeliverableStatus.NOT_STARTED.name
    )
    val doneProject = Project(
      id = "p_done",
      title = "Done Campaign",
      clientCompanyName = "Alpha Co",
      status = ProjectStatus.COMPLETED.name,
      agreedValuePaise = 50_000_00L
    )
    val client = Client(id = "c_1", companyName = "Alpha Co", contactPerson = "Vikram")
    val activityEntry = ActivityLog(
      id = "act_1",
      title = "Project Accepted",
      detail = "Created Done Campaign",
      actorName = "Rohan Director",
      category = ActivityCategory.PROJECT.name
    )
    val channel = ChatChannel(
      id = "ch_dm_u_staff_u_super",
      title = "Aarav Designer",
      channelType = ChatChannelType.DIRECT.name,
      participantUids = listOf("u_staff", "u_super")
    )
    val notif = WorkspaceNotification(
      id = "n_1",
      title = "New Deliverable Assigned",
      body = "Social Launch Reel is ready to start.",
      category = NotificationCategory.DELIVERABLE.name,
      targetUid = "ALL",
      actorUid = "u_super",
      actorName = "Rohan Director",
      entityId = "d_not_started"
    )

    // 1. Staff MyWork: Slide-to-start + Dept Queue Claim + Profile Photo Dialog
    var startedId = ""
    var claimedId = ""
    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          MyWorkScreen(
            state = AgencyUiState(
              myMember = staffMember,
              memberLoaded = true,
              members = listOf(staffMember, superAdmin),
              deliverables = listOf(notStartedTask, queueTask)
            ),
            onOpenDeliverable = {},
            onStartDeliverable = { startedId = it.id },
            onClaimDepartmentTask = { claimedId = it.id }
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("my_work_lazy_list")
      .performScrollToNode(hasTestTag("claim_task_d_queue"))
    composeTestRule.onNodeWithTag("claim_task_d_queue").performClick()
    assertEquals("d_queue", claimedId)
    composeTestRule.onNodeWithTag("my_work_lazy_list")
      .performScrollToNode(hasText("SOCIAL LAUNCH REEL"))
    composeTestRule.waitForIdle()
    composeTestRule.runOnUiThread {
      composeTestRule.onNodeWithTag("slide_start_d_not_started")
        .fetchSemanticsNode()
        .config[SemanticsActions.CustomActions]
        .first { it.label == "SLIDE TO START WORK" }
        .action()
    }
    assertEquals("d_not_started", startedId)

    // Staff can edit their own profile photo
    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          ProfilePhotoDialog(
            member = staffMember,
            onPickPhoto = {},
            onRemovePhoto = {},
            onDismiss = {}
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("pick_profile_photo_button").assertExists()

    // 2. Non-Super-Admin ADMIN must NOT see delete controls even with DELETE_PROJECT permission
    val adminState = AgencyUiState(
      myMember = adminMember,
      memberLoaded = true,
      members = listOf(staffMember, adminMember, superAdmin),
      clients = listOf(client),
      projects = listOf(doneProject),
      activityLog = listOf(activityEntry),
      chatChannels = listOf(channel),
      notifications = listOf(notif)
    )
    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          ActivityLogScreen(state = adminState)
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("clear_all_logs_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("delete_log_act_1").assertDoesNotExist()

    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          ClientsScreen(state = adminState, onSaveClient = {})
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("delete_client_c_1").assertDoesNotExist()

    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          ProjectsScreen(
            state = adminState,
            onOpenDeliverable = {},
            onAssignDeliverable = { _, _, _ -> },
            onOpenAddOnQuote = {},
            onToggleDeleteProject = { _, _ -> }
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("cleanup_old_projects_button").assertDoesNotExist()

    // 3. SUPER_ADMIN DOES see delete controls across Logs, Clients, Projects, Chats & Notifications
    val superState = adminState.copy(myMember = superAdmin)
    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          ActivityLogScreen(state = superState)
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("clear_all_logs_button").assertExists()
    composeTestRule.onNodeWithTag("delete_log_act_1").assertExists()

    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          ClientsScreen(state = superState, onSaveClient = {})
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("delete_client_c_1").assertExists()

    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          ProjectsScreen(
            state = superState,
            onOpenDeliverable = {},
            onAssignDeliverable = { _, _, _ -> },
            onOpenAddOnQuote = {},
            onToggleDeleteProject = { _, _ -> }
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("cleanup_old_projects_button").assertExists()

    var testedSound = false
    var toggledSoundTo: Boolean? = null
    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        XMaxTheme(darkTheme = false) {
          NotificationsScreen(
            state = superState,
            onToggleSound = { toggledSoundTo = it },
            onTestSound = { testedSound = true }
          )
        }
      }
    }
    composeTestRule.onNodeWithTag("notification_sound_switch").performClick()
    assertEquals(false, toggledSoundTo)
    composeTestRule.onNodeWithTag("notification_card_n_1").assertExists()
    composeTestRule.onNodeWithTag("delete_notif_n_1").assertExists()
  }
}
