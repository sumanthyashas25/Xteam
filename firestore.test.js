const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const COMPANY_ID = "xmax";

const SUPER_ADMIN_UID = "super_admin_1";
const ADMIN_UID = "admin_manager_1";
const STAFF_UID = "designer_staff_1";
const OTHER_STAFF_UID = "video_staff_2";
const PENDING_UID = "new_hire_pending";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(offsetMs = 5000) {
  return new Date(Date.now() - offsetMs);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

async function seedCompanyAndMembers(adminPerms = {}, staffDelegated = {}) {
  const now = pastDate(10000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("companies").doc(COMPANY_ID).set({
      name: "X.MAX",
      tagline: "Creative & Digital Marketing Agency",
      address: "Bengaluru, India",
      phone: "+91 98000 00000",
      email: "hello@xmax.agency",
      gstin: "29ABCDE1234F1Z5",
      gstEnabled: true,
      gstRatePercent: 18,
      defaultAdvancePercent: 50,
      defaultValidityDays: 15,
      defaultRevisionRounds: 2,
      defaultPaymentTerms: "50% advance to begin and 50% before final delivery.",
      defaultApprovalInstructions: "Reply in writing referencing quote number and version.",
      nextQuoteSeq: 1,
      seededCatalogue: true,
      createdByUid: SUPER_ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    });

    await db.collection("companies").doc(COMPANY_ID).collection("members").doc(SUPER_ADMIN_UID).set({
      uid: SUPER_ADMIN_UID,
      fullName: "X.MAX Founder",
      accessLevel: "SUPER_ADMIN",
      status: "APPROVED",
      active: true,
      departmentId: "dept_ops",
      departmentName: "Operations",
      jobRoleId: "role_director",
      jobRoleName: "Agency Director",
      scopeType: "COMPANY",
      scopeDepartmentIds: [],
      permissions: {},
      delegatedGrants: {},
      createdAt: now,
      updatedAt: now,
    });

    await db.collection("companies").doc(COMPANY_ID).collection("members").doc(ADMIN_UID).set({
      uid: ADMIN_UID,
      fullName: "Priya Manager",
      accessLevel: "ADMIN",
      status: "APPROVED",
      active: true,
      departmentId: "dept_creative",
      departmentName: "Creative",
      jobRoleId: "role_pm",
      jobRoleName: "Project Manager",
      scopeType: "COMPANY",
      scopeDepartmentIds: ["dept_creative", "dept_web"],
      permissions: adminPerms,
      delegatedGrants: {},
      createdAt: now,
      updatedAt: now,
    });

    await db.collection("companies").doc(COMPANY_ID).collection("members").doc(STAFF_UID).set({
      uid: STAFF_UID,
      fullName: "Aarav Designer",
      accessLevel: "STAFF",
      status: "APPROVED",
      active: true,
      departmentId: "dept_creative",
      departmentName: "Creative",
      jobRoleId: "role_graphic_designer",
      jobRoleName: "Graphic Designer",
      scopeType: "TEAM",
      scopeDepartmentIds: [],
      permissions: {},
      delegatedGrants: staffDelegated,
      createdAt: now,
      updatedAt: now,
    });

    await db.collection("companies").doc(COMPANY_ID).collection("members").doc(OTHER_STAFF_UID).set({
      uid: OTHER_STAFF_UID,
      fullName: "Rohan Video",
      accessLevel: "STAFF",
      status: "APPROVED",
      active: true,
      departmentId: "dept_video",
      departmentName: "Video",
      jobRoleId: "role_video_editor",
      jobRoleName: "Video Editor",
      scopeType: "TEAM",
      scopeDepartmentIds: [],
      permissions: {},
      delegatedGrants: {},
      createdAt: now,
      updatedAt: now,
    });
  });
}

test("Scenario 1: Unauthenticated & new pending employee cannot read company data or self-elevate", async () => {
  await seedCompanyAndMembers();
  const now = pastDate();

  // Unauthenticated cannot read anything
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("companies").doc(COMPANY_ID).get());
  await assertFails(unauthDb.collection("companies").doc(COMPANY_ID).collection("projects").get());

  // New employee signs in and submits onboarding (attempts to escalate to ADMIN/APPROVED -> MUST FAIL)
  const pendingDb = testEnv.authenticatedContext(PENDING_UID, {
    email: "newhire@gmail.com",
    email_verified: true,
  }).firestore();

  await assertFails(
    pendingDb.collection("companies").doc(COMPANY_ID).collection("members").doc(PENDING_UID).set({
      uid: PENDING_UID,
      companyNameInput: "X.MAX",
      fullName: "New Hire",
      accessLevel: "ADMIN",
      status: "APPROVED",
      active: true,
      scopeType: "COMPANY",
      scopeDepartmentIds: [],
      permissions: {},
      delegatedGrants: {},
      createdAt: now,
      updatedAt: now,
    })
  );

  // Valid PENDING + STAFF self-onboarding succeeds
  await assertSucceeds(
    pendingDb.collection("companies").doc(COMPANY_ID).collection("members").doc(PENDING_UID).set({
      uid: PENDING_UID,
      companyNameInput: "X.MAX",
      fullName: "New Hire",
      accessLevel: "STAFF",
      status: "PENDING",
      active: true,
      requestedDepartment: "Creative",
      requestedJobRole: "Graphic Designer",
      scopeType: "TEAM",
      scopeDepartmentIds: [],
      permissions: {},
      delegatedGrants: {},
      createdAt: now,
      updatedAt: now,
    })
  );

  // Private age/email stored safely in member_private
  await assertSucceeds(
    pendingDb.collection("companies").doc(COMPANY_ID).collection("member_private").doc(PENDING_UID).set({
      uid: PENDING_UID,
      age: 24,
      email: "newhire@gmail.com",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Pending employee CANNOT read company projects, clients, quotations, catalogue, or others' private info
  await assertFails(pendingDb.collection("companies").doc(COMPANY_ID).collection("projects").get());
  await assertFails(pendingDb.collection("companies").doc(COMPANY_ID).collection("clients").get());
  await assertFails(pendingDb.collection("companies").doc(COMPANY_ID).collection("quotations").get());
  await assertFails(pendingDb.collection("companies").doc(COMPANY_ID).collection("catalogue").get());
  await assertFails(pendingDb.collection("companies").doc(COMPANY_ID).collection("member_private").doc(STAFF_UID).get());

  // Default Super Admin xdotmax@gmail.com CAN create AND upgrade an existing PENDING doc to SUPER_ADMIN
  const xmaxOwnerDb = testEnv.authenticatedContext("xmax_owner", {
    email: "XDotMax@gmail.com",
    email_verified: true,
  }).firestore();
  await assertSucceeds(
    xmaxOwnerDb.collection("companies").doc(COMPANY_ID).collection("members").doc("xmax_owner").set({
      uid: "xmax_owner",
      companyNameInput: "X.MAX",
      fullName: "X.MAX Pending",
      accessLevel: "STAFF",
      status: "PENDING",
      active: true,
      requestedDepartment: "Creative",
      requestedJobRole: "Graphic Designer",
      scopeType: "TEAM",
      scopeDepartmentIds: [],
      permissions: {},
      delegatedGrants: {},
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    xmaxOwnerDb.collection("companies").doc(COMPANY_ID).collection("members").doc("xmax_owner").update({
      accessLevel: "SUPER_ADMIN",
      status: "APPROVED",
      scopeType: "COMPANY",
      fullName: "X.MAX Super Admin",
      updatedAt: pastDate(1000),
    })
  );
});

test("Scenario 2: Admin granted Accept & Decline (not Delete) can perform only those; Staff never can", async () => {
  await seedCompanyAndMembers({
    ACCEPT_PROJECT: { allowed: true, canDelegate: false },
    DECLINE_PROJECT: { allowed: true, canDelegate: false },
  });
  const now = pastDate();

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("companies").doc(COMPANY_ID).collection("requests").doc("req_1").set({
      clientId: "client_1",
      clientCompanyName: "Acme India",
      projectTitle: "Brand Refresh",
      brief: "Rebrand + website",
      desiredServices: ["Logo design"],
      responsibleManagerUid: ADMIN_UID,
      decisionStatus: "PENDING",
      createdByUid: SUPER_ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("companies").doc(COMPANY_ID).collection("requests").doc("req_2").set({
      clientId: "client_1",
      clientCompanyName: "Acme India",
      projectTitle: "Ad Sprint",
      brief: "Meta ads",
      desiredServices: ["Meta Ads campaign setup"],
      responsibleManagerUid: ADMIN_UID,
      decisionStatus: "PENDING",
      createdByUid: SUPER_ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("companies").doc(COMPANY_ID).collection("projects").doc("proj_existing").set({
      requestId: "req_old",
      clientId: "client_1",
      clientCompanyName: "Acme India",
      title: "Old Project",
      brief: "Test",
      responsibleManagerUid: ADMIN_UID,
      approvedQuoteId: "qt_old",
      agreedValuePaise: 5700000,
      advanceDuePaise: 2850000,
      paidPaise: 0,
      status: "AWAITING_ADVANCE",
      deleted: false,
      createdAt: now,
      updatedAt: now,
    });
  });

  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const staffDb = testEnv.authenticatedContext(STAFF_UID).firestore();

  // Admin can Accept req_1 and create proj_req_1
  await assertSucceeds(
    adminDb.collection("companies").doc(COMPANY_ID).collection("requests").doc("req_1").update({
      decisionStatus: "ACCEPTED",
      decisionByUid: ADMIN_UID,
      decisionAtMillis: Date.now(),
      acceptedProjectId: "proj_req_1",
      updatedAt: pastDate(1000),
    })
  );
  await assertSucceeds(
    adminDb.collection("companies").doc(COMPANY_ID).collection("projects").doc("proj_req_1").set({
      requestId: "req_1",
      clientId: "client_1",
      clientCompanyName: "Acme India",
      title: "Brand Refresh",
      brief: "Rebrand + website",
      responsibleManagerUid: ADMIN_UID,
      approvedQuoteId: "qt_1",
      agreedValuePaise: 5700000,
      advanceDuePaise: 2850000,
      paidPaise: 0,
      status: "AWAITING_ADVANCE",
      deleted: false,
      createdAt: pastDate(1000),
      updatedAt: pastDate(1000),
    })
  );

  // Admin can Decline req_2
  await assertSucceeds(
    adminDb.collection("companies").doc(COMPANY_ID).collection("requests").doc("req_2").update({
      decisionStatus: "DECLINED",
      decisionByUid: ADMIN_UID,
      decisionAtMillis: Date.now(),
      updatedAt: pastDate(1000),
    })
  );

  // Admin CANNOT soft-delete proj_existing (lacks DELETE_PROJECT)
  await assertFails(
    adminDb.collection("companies").doc(COMPANY_ID).collection("projects").doc("proj_existing").update({
      deleted: true,
      deletedByUid: ADMIN_UID,
      deletedAtMillis: Date.now(),
      updatedAt: pastDate(1000),
    })
  );

  // Staff CANNOT read projects or soft-delete or accept
  await assertFails(staffDb.collection("companies").doc(COMPANY_ID).collection("projects").get());
  await assertFails(
    staffDb.collection("companies").doc(COMPANY_ID).collection("requests").doc("req_1").update({
      decisionStatus: "DECLINED",
      updatedAt: pastDate(1000),
    })
  );
});

test("Scenario 3: Revoking Admin's delegated capability immediately removes inherited Staff access", async () => {
  await seedCompanyAndMembers(
    { ISSUE_QUOTATIONS: { allowed: true, canDelegate: true } },
    { ISSUE_QUOTATIONS: { allowed: true, grantedByUid: ADMIN_UID } }
  );
  const now = pastDate();

  const staffDb = testEnv.authenticatedContext(STAFF_UID).firestore();
  const superDb = testEnv.authenticatedContext(SUPER_ADMIN_UID).firestore();

  // While Admin holds ISSUE_QUOTATIONS with canDelegate=true, Staff can issue a quote
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("quotations").doc("qt_staff_1").set({
      quoteNumber: "QT-2026-0001",
      revision: 1,
      requestId: "req_1",
      clientId: "client_1",
      projectTitle: "Launch",
      status: "ISSUED",
      companySnapshot: { name: "X.MAX" },
      clientSnapshot: { companyName: "Acme" },
      lines: [],
      subtotalPaise: 5700000,
      discountPaise: 0,
      surchargePaise: 0,
      gstEnabled: false,
      gstRatePercent: 0,
      gstAmountPaise: 0,
      totalPaise: 5700000,
      advancePercent: 50,
      advancePaise: 2850000,
      balancePaise: 2850000,
      preparedByUid: STAFF_UID,
      preparedByName: "Aarav Designer",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Super Admin revokes ISSUE_QUOTATIONS from Admin
  await assertSucceeds(
    superDb.collection("companies").doc(COMPANY_ID).collection("members").doc(ADMIN_UID).update({
      permissions: {},
      updatedAt: pastDate(1000),
    })
  );

  // Immediately after Admin revocation, Staff CANNOT issue another quote even if delegatedGrants wasn't yet cleared!
  await assertFails(
    staffDb.collection("companies").doc(COMPANY_ID).collection("quotations").doc("qt_staff_2").set({
      quoteNumber: "QT-2026-0002",
      revision: 1,
      requestId: "req_1",
      clientId: "client_1",
      projectTitle: "Launch 2",
      status: "ISSUED",
      companySnapshot: { name: "X.MAX" },
      clientSnapshot: { companyName: "Acme" },
      lines: [],
      subtotalPaise: 100000,
      discountPaise: 0,
      surchargePaise: 0,
      gstEnabled: false,
      gstRatePercent: 0,
      gstAmountPaise: 0,
      totalPaise: 100000,
      advancePercent: 50,
      advancePaise: 50000,
      balancePaise: 50000,
      preparedByUid: STAFF_UID,
      preparedByName: "Aarav Designer",
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Scenario 7 & PII Isolation: Designer can update only own deliverable (cannot approve), cannot read others' age/payments", async () => {
  await seedCompanyAndMembers({ APPROVE_DELIVERABLES: { allowed: true, canDelegate: false } });
  const now = pastDate();

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("companies").doc(COMPANY_ID).collection("deliverables").doc("deliv_1").set({
      projectId: "proj_1",
      projectTitle: "Launch Campaign",
      clientCompanyName: "Acme India",
      projectBrief: "Creative assets",
      departmentId: "dept_creative",
      departmentName: "Creative",
      jobRoleId: "role_graphic_designer",
      jobRoleName: "Graphic Designer",
      title: "12 Social media creatives",
      quantity: 12,
      responsibleManagerUid: ADMIN_UID,
      assignedToUid: STAFF_UID,
      assignedToName: "Aarav Designer",
      assignmentStatus: "ASSIGNED",
      status: "NOT_STARTED",
      checklist: [{ id: "c1", title: "Draft grid", done: false }],
      completedChecklistCount: 0,
      totalChecklistCount: 1,
      clientRevisionsUsed: 0,
      clientRevisionLimit: 2,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("companies").doc(COMPANY_ID).collection("deliverables").doc("deliv_2").set({
      projectId: "proj_1",
      projectTitle: "Launch Campaign",
      clientCompanyName: "Acme India",
      projectBrief: "Video edits",
      departmentId: "dept_video",
      departmentName: "Video",
      jobRoleId: "role_video_editor",
      jobRoleName: "Video Editor",
      title: "6 Edited reels",
      quantity: 6,
      responsibleManagerUid: ADMIN_UID,
      assignedToUid: OTHER_STAFF_UID,
      assignedToName: "Rohan Video",
      assignmentStatus: "ASSIGNED",
      status: "NOT_STARTED",
      checklist: [{ id: "c1", title: "Rough cut", done: false }],
      completedChecklistCount: 0,
      totalChecklistCount: 1,
      clientRevisionsUsed: 0,
      clientRevisionLimit: 2,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("companies").doc(COMPANY_ID).collection("member_private").doc(OTHER_STAFF_UID).set({
      uid: OTHER_STAFF_UID,
      age: 27,
      email: "rohan@xmax.agency",
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("companies").doc(COMPANY_ID).collection("payments").doc("pay_1").set({
      projectId: "proj_1",
      projectTitle: "Launch Campaign",
      clientCompanyName: "Acme India",
      amountPaise: 2850000,
      paymentDateMillis: Date.now(),
      method: "UPI",
      recordedByUid: SUPER_ADMIN_UID,
      recordedByName: "X.MAX Founder",
      createdAt: now,
    });
  });

  const staffDb = testEnv.authenticatedContext(STAFF_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();

  // Designer updates own deliverable to READY_FOR_REVIEW
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("deliverables").doc("deliv_1").update({
      status: "READY_FOR_REVIEW",
      completedChecklistCount: 1,
      checklist: [{ id: "c1", title: "Draft grid", done: true }],
      latestNote: "Uploaded social pack preview",
      lastUpdatedByUid: STAFF_UID,
      lastUpdatedByName: "Aarav Designer",
      updatedAt: pastDate(1000),
    })
  );

  // Designer CANNOT self-approve own deliverable
  await assertFails(
    staffDb.collection("companies").doc(COMPANY_ID).collection("deliverables").doc("deliv_1").update({
      status: "APPROVED",
      updatedAt: pastDate(500),
    })
  );

  // Designer CANNOT update another employee's deliverable
  await assertFails(
    staffDb.collection("companies").doc(COMPANY_ID).collection("deliverables").doc("deliv_2").update({
      status: "IN_PROGRESS",
      updatedAt: pastDate(500),
    })
  );

  // Designer CANNOT read other employee's private age or company payments
  await assertFails(staffDb.collection("companies").doc(COMPANY_ID).collection("member_private").doc(OTHER_STAFF_UID).get());
  await assertFails(staffDb.collection("companies").doc(COMPANY_ID).collection("payments").get());

  // Designer's filtered query on assigned deliverables succeeds; unfiltered deliverables query fails for Staff
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("deliverables").where("assignedToUid", "==", STAFF_UID).get()
  );
  await assertFails(staffDb.collection("companies").doc(COMPANY_ID).collection("deliverables").get());

  // Manager with APPROVE_DELIVERABLES requests changes, then approves
  await assertSucceeds(
    adminDb.collection("companies").doc(COMPANY_ID).collection("deliverables").doc("deliv_1").update({
      status: "CHANGES_REQUESTED",
      latestFeedback: "Adjust brand typography contrast",
      lastUpdatedByUid: ADMIN_UID,
      lastUpdatedByName: "Priya Manager",
      updatedAt: pastDate(400),
    })
  );
  await assertSucceeds(
    adminDb.collection("companies").doc(COMPANY_ID).collection("deliverables").doc("deliv_1").update({
      status: "APPROVED",
      latestFeedback: "Approved for delivery",
      lastUpdatedByUid: ADMIN_UID,
      lastUpdatedByName: "Priya Manager",
      updatedAt: pastDate(200),
    })
  );
});

test("Scenario 8: Authorized manager records part payment and client-approved add-on quote updates project value", async () => {
  await seedCompanyAndMembers({
    RECORD_PAYMENTS: { allowed: true, canDelegate: false },
    ISSUE_QUOTATIONS: { allowed: true, canDelegate: false },
  });
  const now = pastDate();

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("companies").doc(COMPANY_ID).collection("projects").doc("proj_1").set({
      requestId: "req_1",
      clientId: "client_1",
      clientCompanyName: "Acme India",
      title: "Business Launch",
      brief: "Full package",
      responsibleManagerUid: ADMIN_UID,
      approvedQuoteId: "qt_1",
      agreedValuePaise: 5700000,
      advanceDuePaise: 2850000,
      paidPaise: 0,
      status: "AWAITING_ADVANCE",
      deleted: false,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("companies").doc(COMPANY_ID).collection("quotations").doc("qt_addon").set({
      quoteNumber: "QT-2026-0002",
      revision: 1,
      requestId: "req_1",
      linkedProjectId: "proj_1",
      clientId: "client_1",
      projectTitle: "Business Launch Add-On",
      status: "SENT",
      companySnapshot: { name: "X.MAX" },
      clientSnapshot: { companyName: "Acme India" },
      lines: [],
      subtotalPaise: 1000000,
      discountPaise: 0,
      surchargePaise: 0,
      gstEnabled: false,
      gstRatePercent: 0,
      gstAmountPaise: 0,
      totalPaise: 1000000,
      advancePercent: 50,
      advancePaise: 500000,
      balancePaise: 500000,
      preparedByUid: ADMIN_UID,
      preparedByName: "Priya Manager",
      createdAt: now,
      updatedAt: now,
    });
  });

  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const staffDb = testEnv.authenticatedContext(STAFF_UID).firestore();

  // Record 50% advance payment
  await assertSucceeds(
    adminDb.collection("companies").doc(COMPANY_ID).collection("payments").doc("pay_adv").set({
      projectId: "proj_1",
      projectTitle: "Business Launch",
      clientCompanyName: "Acme India",
      amountPaise: 2850000,
      paymentDateMillis: Date.now(),
      method: "UPI",
      reference: "UTR123456",
      recordedByUid: ADMIN_UID,
      recordedByName: "Priya Manager",
      createdAt: pastDate(500),
    })
  );
  await assertSucceeds(
    adminDb.collection("companies").doc(COMPANY_ID).collection("projects").doc("proj_1").update({
      paidPaise: 2850000,
      status: "IN_PROGRESS",
      updatedAt: pastDate(400),
    })
  );

  // Client approves scope add-on quote and updates agreedValuePaise
  await assertSucceeds(
    adminDb.collection("companies").doc(COMPANY_ID).collection("quotations").doc("qt_addon").update({
      status: "CLIENT_APPROVED",
      clientApprovedAtMillis: Date.now(),
      clientApprovalNote: "Approved over email",
      updatedAt: pastDate(300),
    })
  );
  await assertSucceeds(
    adminDb.collection("companies").doc(COMPANY_ID).collection("projects").doc("proj_1").update({
      agreedValuePaise: 6700000,
      advanceDuePaise: 3350000,
      updatedAt: pastDate(200),
    })
  );

  // Staff without payment permission cannot record payments
  await assertFails(
    staffDb.collection("companies").doc(COMPANY_ID).collection("payments").doc("pay_fake").set({
      projectId: "proj_1",
      projectTitle: "Business Launch",
      clientCompanyName: "Acme India",
      amountPaise: 100000,
      paymentDateMillis: Date.now(),
      method: "CASH",
      recordedByUid: STAFF_UID,
      recordedByName: "Aarav Designer",
      createdAt: pastDate(100),
    })
  );
});

test("Scenario 9: Approved team members can create chat channels, send threaded messages & upload files; pending/spoofed writes fail", async () => {
  await seedCompanyAndMembers();
  const now = pastDate();

  const staffDb = testEnv.authenticatedContext(STAFF_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const pendingDb = testEnv.authenticatedContext(PENDING_UID).firestore();

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("companies").doc(COMPANY_ID).collection("members").doc(PENDING_UID).set({
      uid: PENDING_UID,
      fullName: "Pending User",
      accessLevel: "STAFF",
      status: "PENDING",
      active: true,
      scopeType: "TEAM",
      scopeDepartmentIds: [],
      permissions: {},
      delegatedGrants: {},
      createdAt: now,
      updatedAt: now,
    });
  });

  // Pending member CANNOT read or create channels
  await assertFails(pendingDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").get());
  await assertFails(
    pendingDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_general").set({
      title: "General",
      topic: "Team",
      channelType: "GENERAL",
      projectId: "",
      projectTitle: "",
      createdByUid: PENDING_UID,
      createdByName: "Pending User",
      lastMessagePreview: "Hi",
      lastSenderName: "Pending User",
      lastMessageAtMillis: Date.now(),
      messageCount: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Approved staff creates # General channel
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_general").set({
      title: "General",
      topic: "Agency-wide updates, briefs & file sharing",
      channelType: "GENERAL",
      projectId: "",
      projectTitle: "",
      createdByUid: STAFF_UID,
      createdByName: "Aarav Designer",
      lastMessagePreview: "Channel ready",
      lastSenderName: "Aarav Designer",
      lastMessageAtMillis: Date.now(),
      messageCount: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Approved staff sends message with uploaded PDF file and updates channel summary
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_general").collection("messages").doc("msg_1").set({
      channelId: "ch_general",
      parentMessageId: "",
      parentPreview: "",
      senderUid: STAFF_UID,
      senderName: "Aarav Designer",
      senderRole: "Graphic Designer",
      body: "Updated brand deck PDF",
      attachmentType: "FILE",
      attachmentName: "Brand_Deck_v2.pdf",
      attachmentMimeType: "application/pdf",
      attachmentSizeBytes: 12450,
      attachmentLocalPath: "/data/user/0/com.example/files/chat_uploads/Brand_Deck_v2.pdf",
      attachmentDataBase64: "JVBERi0xLjQK",
      createdAt: pastDate(500),
    })
  );
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_general").update({
      lastMessagePreview: "Updated brand deck PDF • Brand_Deck_v2.pdf",
      lastSenderName: "Aarav Designer",
      lastMessageAtMillis: Date.now(),
      messageCount: 1,
      updatedAt: pastDate(500),
    })
  );

  // Admin reads channel messages and replies in thread
  await assertSucceeds(
    adminDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_general").collection("messages").get()
  );
  await assertSucceeds(
    adminDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_general").collection("messages").doc("msg_2").set({
      channelId: "ch_general",
      parentMessageId: "msg_1",
      parentPreview: "Aarav: Updated brand deck PDF",
      senderUid: ADMIN_UID,
      senderName: "Priya Manager",
      senderRole: "Project Manager",
      body: "Looks clean, forwarding to client.",
      attachmentType: "NONE",
      attachmentName: "",
      attachmentMimeType: "",
      attachmentSizeBytes: 0,
      attachmentLocalPath: "",
      attachmentDataBase64: "",
      createdAt: pastDate(200),
    })
  );

  // Empty message with NONE attachment MUST fail
  await assertFails(
    staffDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_general").collection("messages").doc("msg_empty").set({
      channelId: "ch_general",
      parentMessageId: "",
      parentPreview: "",
      senderUid: STAFF_UID,
      senderName: "Aarav Designer",
      senderRole: "Graphic Designer",
      body: "",
      attachmentType: "NONE",
      attachmentName: "",
      attachmentMimeType: "",
      attachmentSizeBytes: 0,
      attachmentLocalPath: "",
      attachmentDataBase64: "",
      createdAt: pastDate(100),
    })
  );

  // Spoofed senderUid MUST fail
  await assertFails(
    staffDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_general").collection("messages").doc("msg_spoof").set({
      channelId: "ch_general",
      parentMessageId: "",
      parentPreview: "",
      senderUid: ADMIN_UID,
      senderName: "Priya Manager",
      senderRole: "Project Manager",
      body: "Fake admin message",
      attachmentType: "NONE",
      attachmentName: "",
      attachmentMimeType: "",
      attachmentSizeBytes: 0,
      attachmentLocalPath: "",
      attachmentDataBase64: "",
      createdAt: pastDate(100),
    })
  );
});

test("Scenario 10: Profile photo update, 1:1 & Department chat privacy, DEPT_QUEUE staff claim, and notifications", async () => {
  await seedCompanyAndMembers();
  const now = pastDate();

  const staffDb = testEnv.authenticatedContext(STAFF_UID).firestore();
  const otherStaffDb = testEnv.authenticatedContext(OTHER_STAFF_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const superDb = testEnv.authenticatedContext(SUPER_ADMIN_UID).firestore();

  // 1. Any member can update their own photoBase64, cannot edit someone else's
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("members").doc(STAFF_UID).update({
      photoBase64: "/9j/4AAQSkZJRgABAQAAAQABAAD",
      updatedAt: pastDate(500),
    })
  );
  await assertFails(
    staffDb.collection("companies").doc(COMPANY_ID).collection("members").doc(OTHER_STAFF_UID).update({
      photoBase64: "/9j/4AAQSkZJRgABAQAAAQABAAD",
      updatedAt: pastDate(500),
    })
  );

  // 2. Staff creates 1:1 Direct chat with Admin and Department chat for Creative
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_dm_pair").set({
      title: "Priya Manager",
      topic: "1:1 Direct Conversation",
      channelType: "DIRECT",
      projectId: "",
      projectTitle: "",
      departmentId: "",
      departmentName: "",
      participantUids: [ADMIN_UID, STAFF_UID],
      createdByUid: STAFF_UID,
      createdByName: "Aarav Designer",
      lastMessagePreview: "Hi Priya",
      lastSenderName: "Aarav Designer",
      lastMessageAtMillis: Date.now(),
      messageCount: 0,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_dept_creative").set({
      title: "Creative Dept",
      topic: "Creative team collaboration",
      channelType: "DEPARTMENT",
      projectId: "",
      projectTitle: "",
      departmentId: "dept_creative",
      departmentName: "Creative",
      participantUids: [],
      createdByUid: STAFF_UID,
      createdByName: "Aarav Designer",
      lastMessagePreview: "Department channel ready",
      lastSenderName: "Aarav Designer",
      lastMessageAtMillis: Date.now(),
      messageCount: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Pre-creation existence checks on uncreated channel IDs succeed; DM privacy blocks non-participants on existing DMs
  await assertSucceeds(otherStaffDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_dm_uncreated_check").get());
  await assertFails(otherStaffDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_dm_pair").get());
  await assertSucceeds(
    otherStaffDb.collection("companies").doc(COMPANY_ID).collection("chat_channels")
      .where("channelType", "in", ["GENERAL", "PROJECT", "DEPARTMENT"]).get()
  );
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("chat_channels")
      .where("participantUids", "array-contains", STAFF_UID).get()
  );
  // Super Admin CANNOT read someone else's private 1:1 DM; can list shared team channels
  await assertFails(superDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_dm_pair").get());
  await assertSucceeds(
    superDb.collection("companies").doc(COMPANY_ID).collection("chat_channels")
      .where("channelType", "in", ["GENERAL", "PROJECT", "DEPARTMENT"]).get()
  );

  // 3. Department Queue deliverable claim for matching department staff only
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("companies").doc(COMPANY_ID).collection("deliverables").doc("deliv_queue_1").set({
      projectId: "proj_1",
      projectTitle: "Brand Campaign",
      clientCompanyName: "Acme India",
      projectBrief: "Creative queue task",
      departmentId: "dept_creative",
      departmentName: "Creative",
      jobRoleId: "role_graphic_designer",
      jobRoleName: "Graphic Designer",
      title: "Poster Pack",
      quantity: 4,
      responsibleManagerUid: ADMIN_UID,
      assignedToUid: "",
      assignedToName: "",
      assignmentStatus: "DEPT_QUEUE",
      status: "NOT_STARTED",
      checklist: [],
      completedChecklistCount: 0,
      totalChecklistCount: 0,
      clientRevisionsUsed: 0,
      clientRevisionLimit: 2,
      createdAt: now,
      updatedAt: now,
    });
  });

  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("deliverables")
      .where("departmentId", "==", "dept_creative")
      .where("assignmentStatus", "==", "DEPT_QUEUE")
      .get()
  );
  // Other department staff cannot read or claim Creative queue task
  await assertFails(
    otherStaffDb.collection("companies").doc(COMPANY_ID).collection("deliverables").doc("deliv_queue_1").get()
  );
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("deliverables").doc("deliv_queue_1").update({
      assignedToUid: STAFF_UID,
      assignedToName: "Aarav Designer",
      assignmentStatus: "ASSIGNED",
      lastUpdatedByUid: STAFF_UID,
      lastUpdatedByName: "Aarav Designer",
      updatedAt: pastDate(300),
    })
  );

  // 4. Notifications: approved user creates, lists, marks read
  await assertSucceeds(
    adminDb.collection("companies").doc(COMPANY_ID).collection("notifications").doc("notif_1").set({
      category: "DELIVERABLE",
      title: "NEW TASK IN QUEUE",
      body: "Poster Pack ready",
      targetUid: "ALL",
      targetDepartmentId: "dept_creative",
      actorUid: ADMIN_UID,
      actorName: "Priya Manager",
      entityId: "deliv_queue_1",
      channelId: "",
      readByUids: [ADMIN_UID],
      createdAt: pastDate(200),
    })
  );
  await assertSucceeds(staffDb.collection("companies").doc(COMPANY_ID).collection("notifications").get());
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("notifications").doc("notif_1").update({
      readByUids: [ADMIN_UID, STAFF_UID],
    })
  );
});

test("Scenario 11: Logs, projects, clients, deliverables, and chats can ONLY be deleted by Super Admin", async () => {
  await seedCompanyAndMembers({
    DELETE_PROJECT: { allowed: true, canDelegate: false },
    MANAGE_CLIENTS: { allowed: true, canDelegate: false },
    ASSIGN_WORK: { allowed: true, canDelegate: false },
  });
  const now = pastDate();

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("companies").doc(COMPANY_ID).collection("activity").doc("act_1").set({
      category: "PROJECT",
      title: "Project Accepted",
      detail: "Accepted Acme project",
      actorUid: SUPER_ADMIN_UID,
      actorName: "X.MAX Founder",
      entityId: "proj_del",
      createdAt: now,
    });
    await db.collection("companies").doc(COMPANY_ID).collection("clients").doc("cli_1").set({
      companyName: "Acme Corp",
      contactPerson: "Rahul",
      createdByUid: SUPER_ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("companies").doc(COMPANY_ID).collection("projects").doc("proj_del").set({
      requestId: "req_del",
      clientId: "cli_1",
      clientCompanyName: "Acme Corp",
      title: "Completed Site",
      brief: "Done",
      responsibleManagerUid: ADMIN_UID,
      approvedQuoteId: "qt_del",
      agreedValuePaise: 100000,
      advanceDuePaise: 50000,
      paidPaise: 100000,
      status: "COMPLETED",
      deleted: false,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_del").set({
      title: "General",
      topic: "Team",
      channelType: "GENERAL",
      projectId: "",
      projectTitle: "",
      createdByUid: SUPER_ADMIN_UID,
      createdByName: "X.MAX Founder",
      lastMessagePreview: "Hi",
      lastSenderName: "X.MAX Founder",
      lastMessageAtMillis: Date.now(),
      messageCount: 1,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("companies").doc(COMPANY_ID).collection("quotations").doc("qt_del").set({
      quoteNumber: "QT-2026-0099",
      revision: 1,
      parentQuoteId: "",
      requestId: "req_del",
      linkedProjectId: "",
      clientId: "cli_1",
      projectTitle: "Completed Site",
      scopeSummary: "Website launch",
      status: "DRAFT",
      companySnapshot: { name: "X.MAX", gstEnabled: false, gstRatePercent: 18 },
      clientSnapshot: { clientId: "cli_1", companyName: "Acme Corp", contactPerson: "Rahul" },
      lines: [],
      subtotalPaise: 500000,
      discountPaise: 0,
      surchargePaise: 0,
      surchargeBasePaise: 0,
      gstEnabled: false,
      gstRatePercent: 18,
      gstAmountPaise: 0,
      totalPaise: 500000,
      advancePercent: 50,
      advancePaise: 250000,
      balancePaise: 250000,
      preparedByUid: SUPER_ADMIN_UID,
      preparedByName: "X.MAX Founder",
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_del").collection("messages").doc("m_del").set({
      channelId: "ch_del",
      parentMessageId: "",
      parentPreview: "",
      senderUid: STAFF_UID,
      senderName: "Aarav Designer",
      senderRole: "Designer",
      body: "Old message",
      attachmentType: "NONE",
      attachmentName: "",
      attachmentMimeType: "",
      attachmentSizeBytes: 0,
      attachmentLocalPath: "",
      attachmentDataBase64: "",
      createdAt: now,
    });
  });

  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const staffDb = testEnv.authenticatedContext(STAFF_UID).firestore();
  const superDb = testEnv.authenticatedContext(SUPER_ADMIN_UID).firestore();

  // Admin & Staff CANNOT soft-delete or hard-delete projects, logs, clients, quotations, or chats
  await assertFails(
    adminDb.collection("companies").doc(COMPANY_ID).collection("projects").doc("proj_del").update({
      deleted: true,
      deletedByUid: ADMIN_UID,
      deletedAtMillis: Date.now(),
      updatedAt: pastDate(100),
    })
  );
  await assertFails(adminDb.collection("companies").doc(COMPANY_ID).collection("projects").doc("proj_del").delete());
  await assertFails(adminDb.collection("companies").doc(COMPANY_ID).collection("activity").doc("act_1").delete());
  await assertFails(adminDb.collection("companies").doc(COMPANY_ID).collection("clients").doc("cli_1").delete());
  await assertFails(adminDb.collection("companies").doc(COMPANY_ID).collection("quotations").doc("qt_del").delete());
  await assertFails(adminDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_del").collection("messages").doc("m_del").delete());
  await assertFails(adminDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_del").delete());

  await assertFails(staffDb.collection("companies").doc(COMPANY_ID).collection("activity").doc("act_1").delete());
  await assertFails(staffDb.collection("companies").doc(COMPANY_ID).collection("quotations").doc("qt_del").delete());
  await assertFails(staffDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_del").collection("messages").doc("m_del").delete());

  // Super Admin CAN delete logs, clients, quotations, completed/old projects, chat messages, and chat channels
  await assertSucceeds(superDb.collection("companies").doc(COMPANY_ID).collection("activity").doc("act_1").delete());
  await assertSucceeds(superDb.collection("companies").doc(COMPANY_ID).collection("clients").doc("cli_1").delete());
  await assertSucceeds(superDb.collection("companies").doc(COMPANY_ID).collection("quotations").doc("qt_del").delete());
  await assertSucceeds(superDb.collection("companies").doc(COMPANY_ID).collection("projects").doc("proj_del").delete());
  await assertSucceeds(superDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_del").collection("messages").doc("m_del").delete());
  await assertSucceeds(superDb.collection("companies").doc(COMPANY_ID).collection("chat_channels").doc("ch_del").delete());
});

test("Scenario 12: Staff Plan items are strictly owner-isolated and enforce valid schema", async () => {
  await seedCompanyAndMembers();
  const now = pastDate(5000);
  const staffDb = testEnv.authenticatedContext(STAFF_UID).firestore();
  const otherStaffDb = testEnv.authenticatedContext(OTHER_STAFF_UID).firestore();
  const pendingDb = testEnv.authenticatedContext(PENDING_UID).firestore();

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("companies").doc(COMPANY_ID).collection("members").doc(PENDING_UID).set({
      uid: PENDING_UID,
      fullName: "Pending User",
      accessLevel: "STAFF",
      status: "PENDING",
      active: true,
      scopeType: "TEAM",
      scopeDepartmentIds: [],
      permissions: {},
      delegatedGrants: {},
      createdAt: now,
      updatedAt: now,
    });
  });

  // Owner creates a valid plan item
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("staff_plans").doc("plan_1").set({
      ownerUid: STAFF_UID,
      title: "Website audit & moodboard",
      category: "TO_DO",
      creativeTag: "Design",
      dateMillis: Date.now(),
      timeRange: "11:20AM - 12:20 PM",
      notes: "Check typography contrast",
      linkedDeliverableId: "",
      completed: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Owner updates completion & reschedules
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("staff_plans").doc("plan_1").update({
      completed: true,
      updatedAt: pastDate(1000),
    })
  );

  // Scoped owner query succeeds; unfiltered query fails
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("staff_plans")
      .where("ownerUid", "==", STAFF_UID).get()
  );
  await assertFails(
    staffDb.collection("companies").doc(COMPANY_ID).collection("staff_plans").get()
  );

  // Other staff CANNOT read, update, or delete owner's plan
  await assertFails(
    otherStaffDb.collection("companies").doc(COMPANY_ID).collection("staff_plans").doc("plan_1").get()
  );
  await assertFails(
    otherStaffDb.collection("companies").doc(COMPANY_ID).collection("staff_plans").doc("plan_1").update({
      completed: false,
      updatedAt: pastDate(500),
    })
  );
  await assertFails(
    otherStaffDb.collection("companies").doc(COMPANY_ID).collection("staff_plans").doc("plan_1").delete()
  );

  // Spoofed ownerUid & pending member creation fail
  await assertFails(
    otherStaffDb.collection("companies").doc(COMPANY_ID).collection("staff_plans").doc("plan_spoof").set({
      ownerUid: STAFF_UID,
      title: "Spoof",
      category: "TO_DO",
      creativeTag: "General",
      dateMillis: Date.now(),
      timeRange: "",
      notes: "",
      linkedDeliverableId: "",
      completed: false,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(
    pendingDb.collection("companies").doc(COMPANY_ID).collection("staff_plans").doc("plan_pend").set({
      ownerUid: PENDING_UID,
      title: "Pending attempt",
      category: "TO_DO",
      creativeTag: "General",
      dateMillis: Date.now(),
      timeRange: "",
      notes: "",
      linkedDeliverableId: "",
      completed: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Owner deletes own plan item cleanly
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("staff_plans").doc("plan_1").delete()
  );
});

test("Scenario 13: Advanced Chat strict DM/Saved privacy, snapshot reports, kill-switch/suspension, and Invoice permissions", async () => {
  await seedCompanyAndMembers({
    ISSUE_QUOTATIONS: { allowed: true, canDelegate: false },
    VIEW_PAYMENTS: { allowed: true, canDelegate: false },
  });
  const now = pastDate(5000);
  const staffDb = testEnv.authenticatedContext(STAFF_UID).firestore();
  const otherStaffDb = testEnv.authenticatedContext(OTHER_STAFF_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const superDb = testEnv.authenticatedContext(SUPER_ADMIN_UID).firestore();

  // 1. Personal 1:1 chat between STAFF_UID and OTHER_STAFF_UID (Super Admin is NOT a participant)
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("chats").doc("dm_pair_1").set({
      type: "PERSONAL",
      title: "Private DM",
      participantIds: [STAFF_UID, OTHER_STAFF_UID],
      createdByUid: STAFF_UID,
      ownerUid: "",
      lastMessageAtMillis: Date.now(),
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("chats").doc("dm_pair_1").collection("messages").doc("cmsg_1").set({
      clientMsgId: "cmsg_1",
      chatId: "dm_pair_1",
      senderId: STAFF_UID,
      senderName: "Aarav Designer",
      kind: "TEXT",
      body: "Private direct message",
      createdAt: now,
    })
  );

  // Super Admin CANNOT read private PERSONAL chat or its messages
  await assertFails(superDb.collection("companies").doc(COMPANY_ID).collection("chats").doc("dm_pair_1").get());
  await assertFails(superDb.collection("companies").doc(COMPANY_ID).collection("chats").doc("dm_pair_1").collection("messages").get());

  // 2. Saved Messages strictly owner-only
  await assertSucceeds(
    staffDb.collection("companies").doc(COMPANY_ID).collection("chats").doc(`saved_${STAFF_UID}`).set({
      type: "SAVED",
      title: "Saved Messages",
      participantIds: [STAFF_UID],
      ownerUid: STAFF_UID,
      createdByUid: STAFF_UID,
      lastMessageAtMillis: Date.now(),
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(superDb.collection("companies").doc(COMPANY_ID).collection("chats").doc(`saved_${STAFF_UID}`).get());
  await assertFails(otherStaffDb.collection("companies").doc(COMPANY_ID).collection("chats").doc(`saved_${STAFF_UID}`).get());

  // 3. Reported snapshot: participant reports message; ONLY Super Admin can read chatReports
  await assertSucceeds(
    otherStaffDb.collection("companies").doc(COMPANY_ID).collection("chatReports").doc("rep_1").set({
      chatId: "dm_pair_1",
      chatType: "PERSONAL",
      messageId: "cmsg_1",
      reporterUid: OTHER_STAFF_UID,
      reason: "Inappropriate language",
      snapshotMessages: [{ messageId: "cmsg_1", senderId: STAFF_UID, body: "Private direct message" }],
      status: "OPEN",
      createdAt: now,
    })
  );
  await assertFails(staffDb.collection("companies").doc(COMPANY_ID).collection("chatReports").get());
  await assertFails(adminDb.collection("companies").doc(COMPANY_ID).collection("chatReports").get());
  await assertSucceeds(superDb.collection("companies").doc(COMPANY_ID).collection("chatReports").get());

  // 4. Global Read-Only kill-switch blocks staff messaging
  await assertSucceeds(
    superDb.collection("companies").doc(COMPANY_ID).collection("chatSettings").doc("global").set({
      chatEnabled: true,
      globalReadOnly: true,
      maxImageMb: 5,
      updatedByUid: SUPER_ADMIN_UID,
      updatedAt: pastDate(1000),
    })
  );
  await assertFails(
    staffDb.collection("companies").doc(COMPANY_ID).collection("chats").doc("dm_pair_1").collection("messages").doc("cmsg_blocked").set({
      clientMsgId: "cmsg_blocked",
      chatId: "dm_pair_1",
      senderId: STAFF_UID,
      kind: "TEXT",
      body: "Should be blocked",
      createdAt: pastDate(500),
    })
  );

  // 5. Invoices: authorized manager creates/reads; regular staff cannot
  await assertSucceeds(
    adminDb.collection("companies").doc(COMPANY_ID).collection("invoices").doc("inv_1").set({
      invoiceNumber: "INV-2026-0001",
      totalPaise: 5700000,
      balanceDuePaise: 2850000,
      status: "ISSUED",
      createdByUid: ADMIN_UID,
      createdAt: pastDate(500),
      updatedAt: pastDate(500),
    })
  );
  await assertFails(staffDb.collection("companies").doc(COMPANY_ID).collection("invoices").get());

  // 6. 24h Chat Statuses: Privacy modes, mutual blocking, permitted viewer updates, and deletion
  const statusDocRef = staffDb.collection("companies").doc(COMPANY_ID).collection("chat_statuses").doc("status_pub_1");
  // Staff creates ALL_APPROVED status
  await assertSucceeds(
    statusDocRef.set({
      id: "status_pub_1",
      companyId: COMPANY_ID,
      ownerUid: STAFF_UID,
      ownerName: "Creative Staff",
      kind: "TEXT",
      text: "Launching new campaign today!",
      privacyMode: "ALL_APPROVED",
      allowedUids: [],
      excludedUids: [],
      ownerBlockedUids: [],
      viewerUids: [],
      viewerTimestamps: {},
      reactions: {},
      replyCount: 0,
      createdAtMillis: Date.now(),
      expiresAtMillis: Date.now() + 86400000,
    })
  );

  // Other staff can read ALL_APPROVED status
  await assertSucceeds(otherStaffDb.collection("companies").doc(COMPANY_ID).collection("chat_statuses").doc("status_pub_1").get());

  // Other staff marks as viewed and reacts (permitted update fields)
  await assertSucceeds(
    otherStaffDb.collection("companies").doc(COMPANY_ID).collection("chat_statuses").doc("status_pub_1").update({
      viewerUids: [OTHER_STAFF_UID],
      viewerTimestamps: { [OTHER_STAFF_UID]: Date.now() },
      reactions: { [OTHER_STAFF_UID]: "🔥" },
      replyCount: 1,
    })
  );

  // Other staff attempts to change status text or owner (MUST FAIL)
  await assertFails(
    otherStaffDb.collection("companies").doc(COMPANY_ID).collection("chat_statuses").doc("status_pub_1").update({
      text: "Tampered status text",
    })
  );

  // Staff updates privacy mode to SELECTED_ONLY (only admin allowed)
  await assertSucceeds(
    statusDocRef.update({
      privacyMode: "SELECTED_ONLY",
      allowedUids: [ADMIN_UID],
      excludedUids: [],
      ownerBlockedUids: [],
    })
  );

  // Admin can read, but OTHER_STAFF cannot read SELECTED_ONLY status
  await assertSucceeds(adminDb.collection("companies").doc(COMPANY_ID).collection("chat_statuses").doc("status_pub_1").get());
  await assertFails(otherStaffDb.collection("companies").doc(COMPANY_ID).collection("chat_statuses").doc("status_pub_1").get());

  // Staff blocks OTHER_STAFF in ALL_APPROVED status
  await assertSucceeds(
    statusDocRef.update({
      privacyMode: "ALL_APPROVED",
      allowedUids: [],
      excludedUids: [],
      ownerBlockedUids: [OTHER_STAFF_UID],
    })
  );
  // Blocked user cannot read status
  await assertFails(otherStaffDb.collection("companies").doc(COMPANY_ID).collection("chat_statuses").doc("status_pub_1").get());

  // Other staff cannot delete staff's status
  await assertFails(otherStaffDb.collection("companies").doc(COMPANY_ID).collection("chat_statuses").doc("status_pub_1").delete());

  // Staff can delete their own status
  await assertSucceeds(statusDocRef.delete());
});



