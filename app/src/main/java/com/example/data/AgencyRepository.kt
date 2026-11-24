package com.example.data

import android.content.Context
import com.example.R
import com.example.domain.CatalogueSeedData
import com.example.domain.PermissionEngine
import com.example.model.*
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

data class SyncedData<T>(
    val data: T,
    val hasPendingWrites: Boolean = false
)

class AgencyRepository(context: Context) {

    val companyId: String = CatalogueSeedData.DEFAULT_COMPANY_ID

    val db: FirebaseFirestore = getOrConfigureFirestore(context)

    companion object {
        @Volatile
        private var settingsConfigured = false

        private fun getOrConfigureFirestore(context: Context): FirebaseFirestore {
            val appCtx = context.applicationContext
            FirebaseStartup.ensureInitialized(appCtx)
            val instance = FirebaseFirestore.getInstance(
                appCtx.getString(R.string.firestore_database_id)
            )
            if (!settingsConfigured) {
                synchronized(this) {
                    if (!settingsConfigured) {
                        try {
                            instance.firestoreSettings = FirebaseFirestoreSettings.Builder()
                                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                                .build()
                        } catch (_: Exception) {
                            // Settings already locked after first Firestore use
                        }
                        settingsConfigured = true
                    }
                }
            }
            return instance
        }
    }

    internal val companyRef get() = db.collection("companies").document(companyId)
    internal fun sub(name: String) = companyRef.collection(name)

    private val estimate = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE

    // --- Real-Time Snapshot Streams ---

    fun observeCompany(): Flow<CompanyProfile?> = callbackFlow {
        val reg = companyRef.addSnapshotListener { snap, err ->
            if (err != null) {
                handleFirestoreError(err, OperationType.GET, "companies/$companyId")
                close(err)
                return@addSnapshotListener
            }
            trySend(snap?.toObject(CompanyProfile::class.java))
        }
        awaitClose { reg.remove() }
    }

    fun observeMyMember(uid: String): Flow<Member?> = callbackFlow {
        val reg = sub("members").document(uid).addSnapshotListener { snap, err ->
            if (err != null) {
                handleFirestoreError(err, OperationType.GET, "companies/$companyId/members/$uid")
                close(err)
                return@addSnapshotListener
            }
            trySend(snap?.toObject(Member::class.java))
        }
        awaitClose { reg.remove() }
    }

    fun observeMyPrivateInfo(uid: String): Flow<MemberPrivateInfo?> = callbackFlow {
        val reg = sub("member_private").document(uid).addSnapshotListener { snap, err ->
            if (err != null) {
                handleFirestoreError(err, OperationType.GET, "companies/$companyId/member_private/$uid")
                close(err)
                return@addSnapshotListener
            }
            trySend(snap?.toObject(MemberPrivateInfo::class.java))
        }
        awaitClose { reg.remove() }
    }

    fun observeAllPrivateInfo(): Flow<List<MemberPrivateInfo>> =
        observeCollection("member_private") { it.toObject(MemberPrivateInfo::class.java) }

    fun observeMembers(): Flow<List<Member>> =
        observeCollection("members") { it.toObject(Member::class.java) }

    fun observeInvitations(): Flow<List<Invitation>> =
        observeCollection("invitations") { it.toObject(Invitation::class.java) }

    fun observeDepartments(): Flow<List<Department>> =
        observeCollection("departments") { it.toObject(Department::class.java) }

    fun observeJobRoles(): Flow<List<JobRole>> =
        observeCollection("job_roles") { it.toObject(JobRole::class.java) }

    fun observeCatalogue(): Flow<List<CatalogueItem>> =
        observeCollection("catalogue") { it.toObject(CatalogueItem::class.java) }

    fun observeRoutingRules(): Flow<List<RoutingRule>> =
        observeCollection("routing_rules") { it.toObject(RoutingRule::class.java) }

    fun observeActivityLog(): Flow<List<ActivityLog>> = callbackFlow {
        val reg = sub("activity")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(80)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "companies/$companyId/activity")
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { it.toObject(ActivityLog::class.java) }.orEmpty()
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    fun observeNotifications(): Flow<List<WorkspaceNotification>> = callbackFlow {
        val reg = sub("notifications")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(80)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "companies/$companyId/notifications")
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.get("createdAt", estimate)
                    doc.toObject(WorkspaceNotification::class.java)
                }.orEmpty()
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    internal fun <T> observeCollection(
        collectionName: String,
        mapper: (DocumentSnapshot) -> T?
    ): Flow<List<T>> = callbackFlow {
        val reg = sub(collectionName).addSnapshotListener { snap, err ->
            if (err != null) {
                handleFirestoreError(err, OperationType.LIST, "companies/$companyId/$collectionName")
                close(err)
                return@addSnapshotListener
            }
            val list = snap?.documents?.mapNotNull(mapper).orEmpty()
            trySend(list)
        }
        awaitClose { reg.remove() }
    }

    // --- Bootstrapped Setup, Onboarding & Invitations ---

    suspend fun checkPendingInvitation(emailLower: String): Invitation? {
        if (emailLower.isBlank()) return null
        return try {
            val snap = sub("invitations").document(emailLower.trim().lowercase()).get().await()
            snap.toObject(Invitation::class.java)?.takeIf { it.status == "PENDING" }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun bootstrapXMaxWorkspace(user: FirebaseUser, fullName: String, age: Int? = null) {
        val uid = user.uid
        val emailLower = (user.email ?: "").trim().lowercase()
        val companySnap = companyRef.get().await()
        if (!companySnap.exists()) {
            val profile = CatalogueSeedData.defaultCompanyProfile(uid)
            val cMap = mapOf(
                "name" to profile.name,
                "tagline" to profile.tagline,
                "address" to profile.address,
                "phone" to profile.phone,
                "email" to profile.email,
                "gstin" to profile.gstin,
                "gstEnabled" to profile.gstEnabled,
                "gstRatePercent" to profile.gstRatePercent,
                "defaultAdvancePercent" to profile.defaultAdvancePercent,
                "defaultValidityDays" to profile.defaultValidityDays,
                "defaultRevisionRounds" to profile.defaultRevisionRounds,
                "defaultPaymentTerms" to profile.defaultPaymentTerms,
                "defaultApprovalInstructions" to profile.defaultApprovalInstructions,
                "nextQuoteSeq" to profile.nextQuoteSeq,
                "seededCatalogue" to true,
                "createdByUid" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            companyRef.set(cMap).await()
        }

        // Member doc for Super Admin (preserve createdAt and existing name if updating an existing doc)
        val memberRef = sub("members").document(uid)
        val existingMember = memberRef.get().await()
        val existingName = existingMember.getString("fullName")?.trim().orEmpty()
        val resolvedName = fullName.trim()
            .ifBlank { existingName }
            .ifBlank { user.displayName?.trim().orEmpty() }
            .ifBlank { "X.MAX Director" }
        val memberMap = mutableMapOf<String, Any>(
            "uid" to uid,
            "companyNameInput" to "X.MAX",
            "fullName" to resolvedName,
            "accessLevel" to AccessLevel.SUPER_ADMIN.name,
            "status" to MembershipStatus.APPROVED.name,
            "active" to true,
            "departmentId" to "dept_ops",
            "departmentName" to "Operations",
            "jobRoleId" to "role_pm",
            "jobRoleName" to "Project Manager",
            "requestedDepartment" to "Operations",
            "requestedJobRole" to "Agency Director",
            "scopeType" to ScopeType.COMPANY.name,
            "scopeDepartmentIds" to emptyList<String>(),
            "permissions" to emptyMap<String, Any>(),
            "delegatedGrants" to emptyMap<String, Any>(),
            "invitedByUid" to "",
            "approvedByUid" to uid,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (existingMember.exists()) {
            memberRef.update(memberMap).await()
        } else {
            memberMap["createdAt"] = FieldValue.serverTimestamp()
            memberRef.set(memberMap).await()
        }
        saveMemberPrivateInfo(uid, age, emailLower, null)
        seedCatalogueAndOrgIfNeeded()
        runCatching {
            syncOfficialCatalogueAndRouting(syncRouting = true, syncCompanyTerms = true, actor = null)
        }
        logActivity(
            ActivityCategory.COMPANY,
            "X.MAX Workspace Provisioned",
            "Trusted Super Admin setup completed by $resolvedName",
            uid,
            resolvedName,
            companyId
        )
    }

    suspend fun seedCatalogueAndOrgIfNeeded() {
        val deptsEmpty = sub("departments").limit(1).get().await().isEmpty
        val rolesEmpty = sub("job_roles").limit(1).get().await().isEmpty
        val rulesEmpty = sub("routing_rules").limit(1).get().await().isEmpty
        val catEmpty = sub("catalogue").limit(1).get().await().isEmpty
        if (!deptsEmpty && !rolesEmpty && !rulesEmpty && !catEmpty) return

        // Idempotent batched writes with deterministic IDs for any unseeded collection
        val batch = db.batch()
        if (deptsEmpty) {
            CatalogueSeedData.defaultDepartments().forEach { dept ->
                batch.set(
                    sub("departments").document(dept.id),
                    mapOf(
                        "name" to dept.name,
                        "description" to dept.description,
                        "active" to dept.active,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }
        }
        if (rolesEmpty) {
            CatalogueSeedData.defaultJobRoles().forEach { role ->
                batch.set(
                    sub("job_roles").document(role.id),
                    mapOf(
                        "name" to role.name,
                        "departmentId" to role.departmentId,
                        "active" to role.active,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }
        }
        if (rulesEmpty) {
            CatalogueSeedData.defaultRoutingRules().forEach { rule ->
                batch.set(
                    sub("routing_rules").document(rule.id),
                    mapOf(
                        "name" to rule.name,
                        "keywords" to rule.keywords,
                        "serviceCategories" to rule.serviceCategories,
                        "departmentId" to rule.departmentId,
                        "departmentName" to rule.departmentName,
                        "jobRoleId" to rule.jobRoleId,
                        "jobRoleName" to rule.jobRoleName,
                        "priority" to rule.priority,
                        "active" to rule.active,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }
        }
        if (catEmpty) {
            CatalogueSeedData.defaultCatalogue().forEach { item ->
                batch.set(
                    sub("catalogue").document(item.id),
                    catalogueToMap(item, isCreate = true),
                    SetOptions.merge()
                )
            }
        }
        batch.commit().await()
    }

    suspend fun submitOnboardingOrAcceptInvite(
        user: FirebaseUser,
        companyNameInput: String,
        fullName: String,
        age: Int,
        requestedDepartment: String,
        requestedJobRole: String,
        phone: String
    ) {
        val uid = user.uid
        val emailLower = (user.email ?: "").trim().lowercase()
        if (CatalogueSeedData.isBootstrapEmail(emailLower)) {
            bootstrapXMaxWorkspace(user, fullName, age)
            saveMemberPrivateInfo(uid, age, emailLower, phone.trim())
            return
        }
        val invite = checkPendingInvitation(emailLower)

        val memberMap = if (invite != null) {
            val permsMap = invite.permissions.mapValues { (_, v) ->
                mapOf("allowed" to v.allowed, "canDelegate" to v.canDelegate)
            }
            mapOf(
                "uid" to uid,
                "companyNameInput" to companyNameInput.trim(),
                "fullName" to fullName.trim(),
                "accessLevel" to invite.accessLevel,
                "status" to MembershipStatus.APPROVED.name,
                "active" to true,
                "departmentId" to invite.departmentId,
                "departmentName" to invite.departmentName,
                "jobRoleId" to invite.jobRoleId,
                "jobRoleName" to invite.jobRoleName,
                "requestedDepartment" to requestedDepartment.trim(),
                "requestedJobRole" to requestedJobRole.trim(),
                "scopeType" to invite.scopeType,
                "scopeDepartmentIds" to invite.scopeDepartmentIds,
                "permissions" to permsMap,
                "delegatedGrants" to emptyMap<String, Any>(),
                "invitedByUid" to invite.invitedByUid,
                "approvedByUid" to invite.invitedByUid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        } else {
            // Security invariant: self-registration ALWAYS creates STAFF + PENDING with 0 permissions
            mapOf(
                "uid" to uid,
                "companyNameInput" to companyNameInput.trim(),
                "fullName" to fullName.trim(),
                "accessLevel" to AccessLevel.STAFF.name,
                "status" to MembershipStatus.PENDING.name,
                "active" to true,
                "departmentId" to "",
                "departmentName" to "",
                "jobRoleId" to "",
                "jobRoleName" to "",
                "requestedDepartment" to requestedDepartment.trim(),
                "requestedJobRole" to requestedJobRole.trim(),
                "scopeType" to ScopeType.TEAM.name,
                "scopeDepartmentIds" to emptyList<String>(),
                "permissions" to emptyMap<String, Any>(),
                "delegatedGrants" to emptyMap<String, Any>(),
                "invitedByUid" to "",
                "approvedByUid" to "",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        }

        sub("members").document(uid).set(memberMap).await()
        val resolvedPrivateEmail = emailLower.ifBlank {
            phone.trim().takeIf { it.length >= 3 } ?: "${uid.take(12)}@phone.xmax"
        }
        saveMemberPrivateInfo(uid, age, resolvedPrivateEmail, phone.trim())

        if (invite != null) {
            sub("invitations").document(emailLower).update(
                mapOf("status" to "ACCEPTED", "updatedAt" to FieldValue.serverTimestamp())
            ).await()
        }
    }

    private suspend fun saveMemberPrivateInfo(uid: String, age: Int?, email: String, phone: String?) {
        val ref = sub("member_private").document(uid)
        val existing = ref.get().await()
        val existingAge = existing.getLong("age")?.toInt()
        val safeAge = (age ?: existingAge ?: 28).coerceIn(16, 100)
        val resolvedPhone = phone ?: (existing.getString("phone") ?: "")
        val data = mutableMapOf<String, Any>(
            "uid" to uid,
            "age" to safeAge,
            "email" to email,
            "phone" to resolvedPhone,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (existing.exists()) {
            ref.update(data).await()
        } else {
            data["createdAt"] = FieldValue.serverTimestamp()
            ref.set(data).await()
        }
    }

    // --- Company Settings, Members, Permissions, Departments, Job Roles, Catalogue, Routing ---

    suspend fun updateCompanyProfile(profile: CompanyProfile, actor: Member) {
        companyRef.update(
            mapOf(
                "name" to profile.name.trim(),
                "tagline" to profile.tagline.trim(),
                "address" to profile.address.trim(),
                "phone" to profile.phone.trim(),
                "email" to profile.email.trim(),
                "gstin" to profile.gstin.trim(),
                "gstEnabled" to profile.gstEnabled,
                "gstRatePercent" to profile.gstRatePercent.coerceIn(0, 28),
                "defaultAdvancePercent" to profile.defaultAdvancePercent.coerceIn(0, 100),
                "defaultValidityDays" to profile.defaultValidityDays.coerceIn(1, 365),
                "defaultRevisionRounds" to profile.defaultRevisionRounds.coerceIn(0, 20),
                "defaultPaymentTerms" to profile.defaultPaymentTerms.trim(),
                "defaultApprovalInstructions" to profile.defaultApprovalInstructions.trim(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        logActivity(ActivityCategory.COMPANY, "Company Profile Updated", "Updated X.MAX details & quote defaults", actor.uid, actor.fullName, companyId)
    }

    suspend fun createInvitation(
        email: String,
        accessLevel: AccessLevel,
        department: Department,
        jobRole: JobRole,
        scopeType: ScopeType,
        scopeDeptIds: List<String>,
        permissions: Map<String, PermissionGrant>,
        actor: Member
    ) {
        val emailLower = email.trim().lowercase()
        val permMap = permissions.filterValues { it.allowed }.mapValues { (_, g) ->
            mapOf("allowed" to g.allowed, "canDelegate" to g.canDelegate)
        }
        sub("invitations").document(emailLower).set(
            mapOf(
                "email" to emailLower,
                "accessLevel" to accessLevel.name,
                "departmentId" to department.id,
                "departmentName" to department.name,
                "jobRoleId" to jobRole.id,
                "jobRoleName" to jobRole.name,
                "scopeType" to scopeType.name,
                "scopeDepartmentIds" to scopeDeptIds,
                "permissions" to permMap,
                "invitedByUid" to actor.uid,
                "status" to "PENDING",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        logActivity(ActivityCategory.MEMBERSHIP, "Invited $emailLower", "Role: ${jobRole.name} (${accessLevel.label})", actor.uid, actor.fullName, emailLower)
    }

    suspend fun approveAndConfigureMember(
        targetMember: Member,
        status: MembershipStatus,
        accessLevel: AccessLevel,
        departmentId: String,
        departmentName: String,
        jobRoleId: String,
        jobRoleName: String,
        scopeType: ScopeType,
        scopeDepartmentIds: List<String>,
        permissions: Map<String, PermissionGrant>,
        allStaff: List<Member>,
        actor: Member
    ) {
        val batch = db.batch()
        val sanitizedPerms = if (accessLevel == AccessLevel.ADMIN) {
            permissions.filterValues { it.allowed }.mapValues { (key, g) ->
                val enumPerm = runCatching { AppPermission.valueOf(key) }.getOrNull()
                val allowDel = if (enumPerm?.staffEligible == true) g.canDelegate else false
                mapOf("allowed" to true, "canDelegate" to allowDel)
            }
        } else emptyMap()

        batch.update(
            sub("members").document(targetMember.uid),
            mapOf(
                "status" to status.name,
                "active" to (status == MembershipStatus.APPROVED),
                "accessLevel" to accessLevel.name,
                "departmentId" to departmentId,
                "departmentName" to departmentName,
                "jobRoleId" to jobRoleId,
                "jobRoleName" to jobRoleName,
                "scopeType" to scopeType.name,
                "scopeDepartmentIds" to scopeDepartmentIds,
                "permissions" to sanitizedPerms,
                "approvedByUid" to actor.uid,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        // Cascade revocation to Staff whose delegatedGrants depended on this Admin
        val revocations = PermissionEngine.computeDependentStaffRevocations(
            adminUid = targetMember.uid,
            newPermissions = if (accessLevel == AccessLevel.ADMIN) permissions else emptyMap(),
            staffMembers = allStaff
        )
        revocations.forEach { (staffUid, pruned) ->
            val prunedMap = pruned.mapValues { (_, dg) ->
                mapOf("allowed" to dg.allowed, "grantedByUid" to dg.grantedByUid, "grantedAtMillis" to dg.grantedAtMillis)
            }
            batch.update(
                sub("members").document(staffUid),
                mapOf("delegatedGrants" to prunedMap, "updatedAt" to FieldValue.serverTimestamp())
            )
        }
        batch.commit().await()
        logActivity(
            ActivityCategory.PERMISSIONS,
            "Updated ${targetMember.fullName}",
            "Status: ${status.label}, Access: ${accessLevel.label}, Dept: $departmentName / $jobRoleName",
            actor.uid,
            actor.fullName,
            targetMember.uid
        )
    }

    suspend fun updateStaffDelegations(
        staff: Member,
        delegatedGrants: Map<String, DelegatedGrant>,
        actor: Member
    ) {
        val map = delegatedGrants
            .filter { (key, g) ->
                val p = runCatching { AppPermission.valueOf(key) }.getOrNull()
                g.allowed && p != null && p.staffEligible && !p.reserved
            }
            .mapValues { (_, g) ->
                mapOf(
                    "allowed" to true,
                    "grantedByUid" to (g.grantedByUid.ifBlank { actor.uid }),
                    "grantedAtMillis" to (if (g.grantedAtMillis > 0L) g.grantedAtMillis else System.currentTimeMillis())
                )
            }
        sub("members").document(staff.uid).update(
            mapOf("delegatedGrants" to map, "updatedAt" to FieldValue.serverTimestamp())
        ).await()
        logActivity(ActivityCategory.PERMISSIONS, "Updated Staff Capabilities", "Updated delegated permissions for ${staff.fullName}", actor.uid, actor.fullName, staff.uid)
    }

    suspend fun saveDepartment(deptId: String?, name: String, description: String, actor: Member) {
        val cleanName = name.trim()
        if (deptId.isNullOrBlank()) {
            val id = "dept_" + UUID.randomUUID().toString().take(8)
            sub("departments").document(id).set(
                mapOf(
                    "name" to cleanName,
                    "description" to description.trim(),
                    "active" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } else {
            sub("departments").document(deptId).update(
                mapOf("name" to cleanName, "description" to description.trim(), "updatedAt" to FieldValue.serverTimestamp())
            ).await()
        }
        logActivity(ActivityCategory.COMPANY, "Department Saved", cleanName, actor.uid, actor.fullName, deptId ?: "new")
    }

    suspend fun saveJobRole(roleId: String?, name: String, departmentId: String, actor: Member) {
        val cleanName = name.trim()
        if (roleId.isNullOrBlank()) {
            val id = "role_" + UUID.randomUUID().toString().take(8)
            sub("job_roles").document(id).set(
                mapOf(
                    "name" to cleanName,
                    "departmentId" to departmentId,
                    "active" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } else {
            sub("job_roles").document(roleId).update(
                mapOf("name" to cleanName, "departmentId" to departmentId, "updatedAt" to FieldValue.serverTimestamp())
            ).await()
        }
        logActivity(ActivityCategory.COMPANY, "Job Role Saved", cleanName, actor.uid, actor.fullName, roleId ?: "new")
    }

    suspend fun retireDepartmentAndReassign(
        retiringDept: Department,
        replacementDept: Department,
        replacementRole: JobRole,
        members: List<Member>,
        jobRoles: List<JobRole>,
        routingRules: List<RoutingRule>,
        activeDeliverables: List<Deliverable>,
        actor: Member
    ) {
        val batch = db.batch()
        batch.update(sub("departments").document(retiringDept.id), mapOf("active" to false, "updatedAt" to FieldValue.serverTimestamp()))
        members.filter { it.departmentId == retiringDept.id }.forEach { m ->
            batch.update(
                sub("members").document(m.uid),
                mapOf(
                    "departmentId" to replacementDept.id,
                    "departmentName" to replacementDept.name,
                    "jobRoleId" to replacementRole.id,
                    "jobRoleName" to replacementRole.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }
        jobRoles.filter { it.departmentId == retiringDept.id && it.active }.forEach { r ->
            batch.update(sub("job_roles").document(r.id), mapOf("departmentId" to replacementDept.id, "updatedAt" to FieldValue.serverTimestamp()))
        }
        routingRules.filter { it.departmentId == retiringDept.id }.forEach { rule ->
            batch.update(
                sub("routing_rules").document(rule.id),
                mapOf(
                    "departmentId" to replacementDept.id,
                    "departmentName" to replacementDept.name,
                    "jobRoleId" to replacementRole.id,
                    "jobRoleName" to replacementRole.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }
        activeDeliverables.filter { it.departmentId == retiringDept.id && it.statusEnum() != DeliverableStatus.APPROVED }.forEach { d ->
            batch.update(
                sub("deliverables").document(d.id),
                mapOf(
                    "departmentId" to replacementDept.id,
                    "departmentName" to replacementDept.name,
                    "jobRoleId" to replacementRole.id,
                    "jobRoleName" to replacementRole.name,
                    "lastUpdatedByUid" to actor.uid,
                    "lastUpdatedByName" to actor.fullName,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }
        batch.commit().await()
        logActivity(ActivityCategory.COMPANY, "Department Retired", "${retiringDept.name} -> reassigned to ${replacementDept.name}", actor.uid, actor.fullName, retiringDept.id)
    }

    suspend fun retireJobRoleAndReassign(
        retiringRole: JobRole,
        replacementDept: Department,
        replacementRole: JobRole,
        members: List<Member>,
        routingRules: List<RoutingRule>,
        activeDeliverables: List<Deliverable>,
        actor: Member
    ) {
        val batch = db.batch()
        batch.update(sub("job_roles").document(retiringRole.id), mapOf("active" to false, "updatedAt" to FieldValue.serverTimestamp()))
        members.filter { it.jobRoleId == retiringRole.id }.forEach { m ->
            batch.update(
                sub("members").document(m.uid),
                mapOf("jobRoleId" to replacementRole.id, "jobRoleName" to replacementRole.name, "updatedAt" to FieldValue.serverTimestamp())
            )
        }
        routingRules.filter { it.jobRoleId == retiringRole.id }.forEach { rule ->
            batch.update(
                sub("routing_rules").document(rule.id),
                mapOf(
                    "departmentId" to replacementDept.id,
                    "departmentName" to replacementDept.name,
                    "jobRoleId" to replacementRole.id,
                    "jobRoleName" to replacementRole.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }
        activeDeliverables.filter { it.jobRoleId == retiringRole.id && it.statusEnum() != DeliverableStatus.APPROVED }.forEach { d ->
            batch.update(
                sub("deliverables").document(d.id),
                mapOf(
                    "departmentId" to replacementDept.id,
                    "departmentName" to replacementDept.name,
                    "jobRoleId" to replacementRole.id,
                    "jobRoleName" to replacementRole.name,
                    "lastUpdatedByUid" to actor.uid,
                    "lastUpdatedByName" to actor.fullName,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }
        batch.commit().await()
        logActivity(ActivityCategory.COMPANY, "Job Role Retired", "${retiringRole.name} -> reassigned to ${replacementRole.name}", actor.uid, actor.fullName, retiringRole.id)
    }

    suspend fun saveCatalogueItem(item: CatalogueItem, actor: Member) {
        val isCreate = item.id.isBlank()
        val id = item.id.ifBlank { "cat_" + UUID.randomUUID().toString().take(8) }
        if (isCreate) {
            sub("catalogue").document(id).set(catalogueToMap(item, isCreate = true)).await()
        } else {
            sub("catalogue").document(id).update(catalogueToMap(item, isCreate = false)).await()
        }
        logActivity(ActivityCategory.COMPANY, "Catalogue Updated", "${item.name} (${item.pricePaise / 100} INR)", actor.uid, actor.fullName, id)
    }

    suspend fun setCatalogueArchived(item: CatalogueItem, archived: Boolean, actor: Member) {
        sub("catalogue").document(item.id).update(
            mapOf("archived" to archived, "updatedAt" to FieldValue.serverTimestamp())
        ).await()
        logActivity(ActivityCategory.COMPANY, if (archived) "Archived Service" else "Restored Service", item.name, actor.uid, actor.fullName, item.id)
    }

    suspend fun syncOfficialCatalogueAndRouting(
        syncRouting: Boolean,
        syncCompanyTerms: Boolean,
        actor: Member? = null
    ): Int {
        val officialItems = CatalogueSeedData.defaultCatalogue()
        val existingCatIds = sub("catalogue").get().await().documents.map { it.id }.toSet()

        val ops = mutableListOf<(com.google.firebase.firestore.WriteBatch) -> Unit>()
        officialItems.forEach { item ->
            val docRef = sub("catalogue").document(item.id)
            if (item.id in existingCatIds) {
                val payload = catalogueToMap(item, isCreate = false)
                ops += { batch -> batch.update(docRef, payload) }
            } else {
                val payload = catalogueToMap(item, isCreate = true)
                ops += { batch -> batch.set(docRef, payload) }
            }
        }

        if (syncRouting) {
            val existingDeptIds = sub("departments").get().await().documents.map { it.id }.toSet()
            CatalogueSeedData.defaultDepartments().forEach { dept ->
                if (dept.id !in existingDeptIds) {
                    val docRef = sub("departments").document(dept.id)
                    val payload = mapOf(
                        "name" to dept.name,
                        "description" to dept.description,
                        "active" to dept.active,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    ops += { batch -> batch.set(docRef, payload) }
                }
            }
            val existingRoleIds = sub("job_roles").get().await().documents.map { it.id }.toSet()
            CatalogueSeedData.defaultJobRoles().forEach { role ->
                if (role.id !in existingRoleIds) {
                    val docRef = sub("job_roles").document(role.id)
                    val payload = mapOf(
                        "name" to role.name,
                        "departmentId" to role.departmentId,
                        "active" to role.active,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    ops += { batch -> batch.set(docRef, payload) }
                }
            }
            val existingRuleIds = sub("routing_rules").get().await().documents.map { it.id }.toSet()
            CatalogueSeedData.defaultRoutingRules().forEach { rule ->
                val docRef = sub("routing_rules").document(rule.id)
                val map = mutableMapOf<String, Any>(
                    "name" to rule.name,
                    "keywords" to rule.keywords,
                    "serviceCategories" to rule.serviceCategories,
                    "departmentId" to rule.departmentId,
                    "departmentName" to rule.departmentName,
                    "jobRoleId" to rule.jobRoleId,
                    "jobRoleName" to rule.jobRoleName,
                    "priority" to rule.priority,
                    "active" to rule.active,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (rule.id in existingRuleIds) {
                    ops += { batch -> batch.update(docRef, map) }
                } else {
                    map["createdAt"] = FieldValue.serverTimestamp()
                    ops += { batch -> batch.set(docRef, map) }
                }
            }
        }

        ops.chunked(180).forEach { slice ->
            val batch = db.batch()
            slice.forEach { applyOp -> applyOp(batch) }
            batch.commit().await()
        }

        if (syncCompanyTerms) {
            val compSnap = companyRef.get().await()
            if (compSnap.exists()) {
                val existingTerms = compSnap.getString("defaultPaymentTerms").orEmpty()
                if (existingTerms.isBlank() || existingTerms.contains("due before production begins", ignoreCase = true)) {
                    val defaults = CatalogueSeedData.defaultCompanyProfile(actor?.uid ?: "")
                    companyRef.update(
                        mapOf(
                            "defaultAdvancePercent" to defaults.defaultAdvancePercent,
                            "defaultValidityDays" to defaults.defaultValidityDays,
                            "defaultRevisionRounds" to defaults.defaultRevisionRounds,
                            "defaultPaymentTerms" to defaults.defaultPaymentTerms,
                            "defaultApprovalInstructions" to defaults.defaultApprovalInstructions,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }
        }

        if (actor != null) {
            logActivity(
                ActivityCategory.COMPANY,
                "Catalogue Synced (${CatalogueSeedData.OFFICIAL_CATALOGUE_VERSION})",
                "Updated ${officialItems.size} official PDF services, packages & add-ons",
                actor.uid,
                actor.fullName,
                CatalogueSeedData.OFFICIAL_CATALOGUE_VERSION
            )
        }
        return officialItems.size
    }

    suspend fun saveRoutingRule(rule: RoutingRule, actor: Member) {
        val isCreate = rule.id.isBlank()
        val id = rule.id.ifBlank { "rule_" + UUID.randomUUID().toString().take(8) }
        val map = mutableMapOf<String, Any>(
            "name" to rule.name.trim(),
            "keywords" to rule.keywords,
            "serviceCategories" to rule.serviceCategories,
            "departmentId" to rule.departmentId,
            "departmentName" to rule.departmentName,
            "jobRoleId" to rule.jobRoleId,
            "jobRoleName" to rule.jobRoleName,
            "priority" to rule.priority,
            "active" to rule.active,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (isCreate) {
            map["createdAt"] = FieldValue.serverTimestamp()
            sub("routing_rules").document(id).set(map).await()
        } else {
            sub("routing_rules").document(id).update(map).await()
        }
        logActivity(ActivityCategory.ASSIGNMENT, "Routing Rule Saved", rule.name, actor.uid, actor.fullName, id)
    }

    suspend fun updateMyProfilePhoto(uid: String, photoBase64: String) {
        sub("members").document(uid).update(
            mapOf(
                "photoBase64" to photoBase64,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun createNotification(
        category: NotificationCategory,
        title: String,
        body: String,
        actor: Member,
        targetUid: String = "ALL",
        targetDepartmentId: String = "",
        entityId: String = "",
        channelId: String = ""
    ) {
        try {
            val id = "notif_" + UUID.randomUUID().toString().take(12)
            sub("notifications").document(id).set(
                mapOf(
                    "category" to category.name,
                    "title" to title.trim().take(150).ifBlank { "NOTIFICATION" },
                    "body" to body.trim().take(580),
                    "targetUid" to targetUid.take(120),
                    "targetDepartmentId" to targetDepartmentId.take(120),
                    "actorUid" to actor.uid,
                    "actorName" to actor.fullName.ifBlank { "Member" }.take(120),
                    "entityId" to entityId.take(120),
                    "channelId" to channelId.take(120),
                    "readByUids" to listOf(actor.uid),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "companies/$companyId/notifications")
        }
    }

    suspend fun markNotificationRead(notification: WorkspaceNotification, uid: String) {
        if (uid.isBlank() || uid in notification.readByUids) return
        val updated = (notification.readByUids + uid).distinct().takeLast(100)
        sub("notifications").document(notification.id).update(mapOf("readByUids" to updated)).await()
    }

    suspend fun markAllNotificationsRead(unread: List<WorkspaceNotification>, uid: String) {
        if (uid.isBlank() || unread.isEmpty()) return
        unread.chunked(250).forEach { slice ->
            val batch = db.batch()
            slice.forEach { n ->
                val updated = (n.readByUids + uid).distinct().takeLast(100)
                batch.update(sub("notifications").document(n.id), mapOf("readByUids" to updated))
            }
            batch.commit().await()
        }
    }

    suspend fun deleteNotification(notificationId: String) {
        sub("notifications").document(notificationId).delete().await()
    }

    suspend fun deleteActivityLog(activityId: String) {
        sub("activity").document(activityId).delete().await()
    }

    suspend fun clearAllActivityLogs(activityIds: List<String>) {
        if (activityIds.isEmpty()) return
        activityIds.chunked(250).forEach { slice ->
            val batch = db.batch()
            slice.forEach { id -> batch.delete(sub("activity").document(id)) }
            batch.commit().await()
        }
    }

    suspend fun deleteClient(clientId: String, companyName: String, actor: Member) {
        sub("clients").document(clientId).delete().await()
        logActivity(ActivityCategory.COMPANY, "Client Deleted", companyName, actor.uid, actor.fullName, clientId)
    }

    suspend fun deleteProjectCascade(project: Project, actor: Member) {
        val delivSnap = sub("deliverables").whereEqualTo("projectId", project.id).get().await()
        for (dDoc in delivSnap.documents) {
            val updSnap = dDoc.reference.collection("updates").get().await()
            updSnap.documents.chunked(200).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
        }
        delivSnap.documents.chunked(200).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
        sub("projects").document(project.id).delete().await()
        logActivity(
            ActivityCategory.PROJECT,
            "Project Permanently Deleted",
            project.title,
            actor.uid,
            actor.fullName,
            project.id
        )
    }

    suspend fun deleteOldCompletedProjects(projects: List<Project>, actor: Member): Int {
        var count = 0
        for (p in projects) {
            deleteProjectCascade(p, actor)
            count += 1
        }
        return count
    }

    suspend fun logActivity(
        category: ActivityCategory,
        title: String,
        detail: String,
        actorUid: String,
        actorName: String,
        entityId: String
    ) {
        try {
            val id = "act_" + UUID.randomUUID().toString().take(12)
            sub("activity").document(id).set(
                mapOf(
                    "category" to category.name,
                    "title" to title.take(150),
                    "detail" to detail.take(900),
                    "actorUid" to actorUid,
                    "actorName" to actorName.ifBlank { "System" },
                    "entityId" to entityId,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "companies/$companyId/activity")
        }
    }

    private fun catalogueToMap(item: CatalogueItem, isCreate: Boolean): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "kind" to item.kind,
            "category" to item.category,
            "name" to item.name,
            "description" to item.description,
            "pricePaise" to item.pricePaise,
            "pricingMode" to item.pricingMode,
            "surchargePercent" to item.surchargePercent,
            "requiresBillingFrequency" to item.requiresBillingFrequency,
            "unitLabel" to item.unitLabel,
            "inclusions" to item.inclusions.map {
                mapOf(
                    "serviceId" to it.serviceId,
                    "title" to it.title,
                    "category" to it.category,
                    "quantity" to it.quantity,
                    "description" to it.description
                )
            },
            "archived" to item.archived,
            "sortOrder" to item.sortOrder,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (isCreate) map["createdAt"] = FieldValue.serverTimestamp()
        return map
    }

    // --- Staff Monthly Planner & Day-to-Day Tasks ---

    fun observeMyPlanItems(uid: String): Flow<List<StaffPlanItem>> = callbackFlow {
        val reg = sub("staff_plans")
            .whereEqualTo("ownerUid", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "companies/$companyId/staff_plans")
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.get("createdAt", estimate)
                    doc.get("updatedAt", estimate)
                    doc.toObject(StaffPlanItem::class.java)
                }?.sortedWith(
                    compareBy<StaffPlanItem> { it.dateMillis }.thenBy { it.completed }
                ).orEmpty()
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    suspend fun savePlanItem(item: StaffPlanItem, uid: String): Result<StaffPlanItem> {
        val isCreate = item.id.isBlank()
        val id = item.id.ifBlank { "plan_" + UUID.randomUUID().toString().take(10) }
        val cleanTitle = item.title.trim().take(160)
        if (cleanTitle.isEmpty()) {
            return Result.failure(IllegalArgumentException("Plan item title cannot be empty."))
        }
        val safeCategory = runCatching { PlanCategory.valueOf(item.category) }
            .getOrDefault(PlanCategory.TO_DO).name
        val safeDateMillis = item.dateMillis.coerceAtLeast(1L)
        val safeTag = item.creativeTag.trim().ifBlank { "General" }.take(40)
        val safeTime = item.timeRange.trim().take(60)
        val safeNotes = item.notes.trim().take(600)
        val safeLinked = item.linkedDeliverableId.trim().take(128)
        val path = "companies/$companyId/staff_plans/$id"
        return try {
            val ref = sub("staff_plans").document(id)
            if (isCreate) {
                val map = mapOf(
                    "ownerUid" to uid,
                    "title" to cleanTitle,
                    "category" to safeCategory,
                    "creativeTag" to safeTag,
                    "dateMillis" to safeDateMillis,
                    "timeRange" to safeTime,
                    "notes" to safeNotes,
                    "linkedDeliverableId" to safeLinked,
                    "completed" to item.completed,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                ref.set(map).await()
            } else {
                val updateMap = mapOf(
                    "title" to cleanTitle,
                    "category" to safeCategory,
                    "creativeTag" to safeTag,
                    "dateMillis" to safeDateMillis,
                    "timeRange" to safeTime,
                    "notes" to safeNotes,
                    "linkedDeliverableId" to safeLinked,
                    "completed" to item.completed,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                ref.update(updateMap).await()
            }
            Result.success(
                item.copy(
                    id = id,
                    ownerUid = uid,
                    title = cleanTitle,
                    category = safeCategory,
                    creativeTag = safeTag,
                    dateMillis = safeDateMillis,
                    timeRange = safeTime,
                    notes = safeNotes,
                    linkedDeliverableId = safeLinked
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, if (isCreate) OperationType.CREATE else OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun togglePlanItemCompleted(item: StaffPlanItem): Result<Unit> {
        if (item.id.isBlank()) return Result.failure(IllegalArgumentException("Missing plan item ID."))
        val path = "companies/$companyId/staff_plans/${item.id}"
        return try {
            sub("staff_plans").document(item.id).update(
                mapOf(
                    "completed" to !item.completed,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun reschedulePlanItemDate(item: StaffPlanItem, newDateMillis: Long): Result<Unit> {
        if (item.id.isBlank()) return Result.failure(IllegalArgumentException("Missing plan item ID."))
        val safeDate = newDateMillis.coerceAtLeast(1L)
        val path = "companies/$companyId/staff_plans/${item.id}"
        return try {
            sub("staff_plans").document(item.id).update(
                mapOf(
                    "dateMillis" to safeDate,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun carryOverIncompletePlanItems(items: List<StaffPlanItem>, targetDateMillis: Long): Result<Int> {
        val pending = items.filter { !it.completed && it.id.isNotBlank() }
        if (pending.isEmpty()) return Result.success(0)
        val safeDate = targetDateMillis.coerceAtLeast(1L)
        val path = "companies/$companyId/staff_plans"
        return try {
            pending.chunked(250).forEach { slice ->
                val batch = db.batch()
                slice.forEach { item ->
                    batch.update(
                        sub("staff_plans").document(item.id),
                        mapOf(
                            "dateMillis" to safeDate,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.commit().await()
            }
            Result.success(pending.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deletePlanItem(planId: String): Result<Unit> {
        if (planId.isBlank()) return Result.failure(IllegalArgumentException("Missing plan item ID."))
        val path = "companies/$companyId/staff_plans/$planId"
        return try {
            sub("staff_plans").document(planId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }
}
