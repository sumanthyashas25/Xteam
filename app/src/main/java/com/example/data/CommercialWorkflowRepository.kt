package com.example.data

import com.example.domain.DeliverableDraft
import com.example.domain.MoneyFormatter
import com.example.domain.WorkDistributionEngine
import com.example.model.*
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.UUID

class CommercialWorkflowRepository(private val core: AgencyRepository) {

    private val db get() = core.db
    private val companyRef get() = core.companyRef
    private fun sub(name: String) = core.sub(name)
    private val est = DocumentSnapshot.ServerTimestampBehavior.ESTIMATE

    // --- Real-time Commercial & Delivery Streams ---

    fun observeClients(): Flow<List<Client>> =
        core.observeCollection("clients") { it.toObject(Client::class.java) }

    fun observeRequests(): Flow<List<ProjectRequest>> =
        core.observeCollection("requests") { it.toObject(ProjectRequest::class.java) }

    fun observeQuotations(): Flow<List<Quotation>> =
        core.observeCollection("quotations") { it.toObject(Quotation::class.java) }

    fun observeProjects(): Flow<List<Project>> =
        core.observeCollection("projects") { it.toObject(Project::class.java) }

    fun observePayments(): Flow<List<PaymentRecord>> =
        core.observeCollection("payments") { it.toObject(PaymentRecord::class.java) }

    fun observeManagerDeliverables(): Flow<SyncedData<List<Deliverable>>> = callbackFlow {
        val reg = sub("deliverables").addSnapshotListener { snap, err ->
            if (err != null) {
                handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/deliverables")
                close(err)
                return@addSnapshotListener
            }
            val list = snap?.documents?.mapNotNull { it.toObject(Deliverable::class.java) }.orEmpty()
            val pending = snap?.metadata?.hasPendingWrites() == true
            trySend(SyncedData(list, pending))
        }
        awaitClose { reg.remove() }
    }

    fun observeStaffDeliverables(uid: String): Flow<SyncedData<List<Deliverable>>> = callbackFlow {
        val reg = sub("deliverables")
            .whereEqualTo("assignedToUid", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/deliverables?assignedToUid=$uid")
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { it.toObject(Deliverable::class.java) }.orEmpty()
                val pending = snap?.metadata?.hasPendingWrites() == true
                trySend(SyncedData(list, pending))
            }
        awaitClose { reg.remove() }
    }

    fun observeDeptQueueDeliverables(departmentId: String): Flow<List<Deliverable>> = callbackFlow {
        if (departmentId.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val reg = sub("deliverables")
            .whereEqualTo("departmentId", departmentId)
            .whereEqualTo("assignmentStatus", AssignmentStatus.DEPT_QUEUE.name)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/deliverables?deptQueue=$departmentId")
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { it.toObject(Deliverable::class.java) }.orEmpty()
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    fun observeDeliverableUpdates(deliverableId: String): Flow<SyncedData<List<DeliverableUpdate>>> = callbackFlow {
        val reg = sub("deliverables").document(deliverableId).collection("updates")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    handleFirestoreError(err, OperationType.LIST, "companies/${core.companyId}/deliverables/$deliverableId/updates")
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull {
                    it.get("createdAt", est)
                    it.toObject(DeliverableUpdate::class.java)
                }.orEmpty()
                val pending = snap?.metadata?.hasPendingWrites() == true
                trySend(SyncedData(list, pending))
            }
        awaitClose { reg.remove() }
    }

    // --- Clients & Requests ---

    suspend fun saveClient(client: Client, actor: Member): Client {
        val isCreate = client.id.isBlank()
        val id = client.id.ifBlank { "cli_" + UUID.randomUUID().toString().take(8) }
        val payload = mutableMapOf<String, Any>(
            "companyName" to client.companyName.trim(),
            "contactPerson" to client.contactPerson.trim(),
            "phone" to client.phone.trim(),
            "email" to client.email.trim(),
            "address" to client.address.trim(),
            "meetingNotes" to client.meetingNotes.trim(),
            "followUpDateMillis" to client.followUpDateMillis,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (isCreate) {
            payload["createdByUid"] = actor.uid
            payload["createdAt"] = FieldValue.serverTimestamp()
            sub("clients").document(id).set(payload).await()
        } else {
            sub("clients").document(id).update(payload).await()
        }
        core.logActivity(ActivityCategory.QUOTATION, "Client Saved", client.companyName, actor.uid, actor.fullName, id)
        return client.copy(id = id, createdByUid = actor.uid)
    }

    suspend fun createRequest(
        client: Client,
        projectTitle: String,
        brief: String,
        desiredServices: List<String>,
        targetDateMillis: Long,
        responsibleManager: Member,
        actor: Member
    ): ProjectRequest {
        val id = "req_" + UUID.randomUUID().toString().take(8)
        sub("requests").document(id).set(
            mapOf(
                "clientId" to client.id,
                "clientCompanyName" to client.companyName,
                "projectTitle" to projectTitle.trim(),
                "brief" to brief.trim(),
                "desiredServices" to desiredServices,
                "targetDateMillis" to targetDateMillis,
                "responsibleManagerUid" to responsibleManager.uid,
                "responsibleManagerName" to responsibleManager.fullName,
                "decisionStatus" to DecisionStatus.PENDING.name,
                "decisionByUid" to "",
                "decisionByName" to "",
                "decisionAtMillis" to 0L,
                "decisionNote" to "",
                "acceptedProjectId" to "",
                "latestQuoteId" to "",
                "latestQuoteNumber" to "",
                "latestQuoteRevision" to 0,
                "clientApprovedQuoteId" to "",
                "createdByUid" to actor.uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        core.logActivity(ActivityCategory.PROJECT, "New Request Captured", "${client.companyName} — $projectTitle", actor.uid, actor.fullName, id)
        return ProjectRequest(
            id = id,
            clientId = client.id,
            clientCompanyName = client.companyName,
            projectTitle = projectTitle.trim(),
            brief = brief.trim(),
            desiredServices = desiredServices,
            targetDateMillis = targetDateMillis,
            responsibleManagerUid = responsibleManager.uid,
            responsibleManagerName = responsibleManager.fullName,
            createdByUid = actor.uid
        )
    }

    // --- Quotations, Revisions, PDF Metadata, Sent & Client Approval ---

    suspend fun saveOrIssueQuotation(
        draft: Quotation,
        company: CompanyProfile,
        issueNow: Boolean,
        actor: Member
    ): Quotation {
        val now = System.currentTimeMillis()
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val expiryMillis = if (draft.expiryDateMillis > 0L) draft.expiryDateMillis else now + company.defaultValidityDays * 86_400_000L
        val companySnap = CompanySnapshot(
            name = company.name,
            tagline = company.tagline,
            address = company.address,
            phone = company.phone,
            email = company.email,
            gstin = company.gstin,
            gstEnabled = company.gstEnabled,
            gstRatePercent = company.gstRatePercent
        )

        val savedId = db.runTransaction { tx ->
            val qId = draft.id.ifBlank { "qt_" + UUID.randomUUID().toString().take(8) }
            val qRef = sub("quotations").document(qId)
            val existingQuote = tx.get(qRef)

            val compSnapDoc = tx.get(companyRef)
            val currentSeq = compSnapDoc.getLong("nextQuoteSeq") ?: 1L
            val needsNumber = draft.quoteNumber.isBlank() || draft.quoteNumber.startsWith("DRAFT")
            val finalNumber = if (issueNow && needsNumber) {
                tx.update(companyRef, mapOf("nextQuoteSeq" to (currentSeq + 1L), "updatedAt" to FieldValue.serverTimestamp()))
                "QT-$year-${currentSeq.toString().padStart(4, '0')}"
            } else {
                draft.quoteNumber.ifBlank { "DRAFT-$qId" }
            }

            val finalStatus = if (issueNow) QuotationStatus.ISSUED.name else QuotationStatus.DRAFT.name
            val qMap = quotationToMap(
                draft.copy(
                    id = qId,
                    quoteNumber = finalNumber,
                    status = finalStatus,
                    companySnapshot = companySnap,
                    issuedDateMillis = now,
                    expiryDateMillis = expiryMillis,
                    preparedByUid = actor.uid,
                    preparedByName = actor.fullName
                ),
                isCreate = !existingQuote.exists()
            )
            if (existingQuote.exists()) {
                tx.update(qRef, qMap)
            } else {
                tx.set(qRef, qMap)
            }

            if (draft.requestId.isNotBlank()) {
                tx.update(
                    sub("requests").document(draft.requestId),
                    mapOf(
                        "latestQuoteId" to qId,
                        "latestQuoteNumber" to finalNumber,
                        "latestQuoteRevision" to draft.revision,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            Triple(qId, finalNumber, finalStatus)
        }.await()

        core.logActivity(
            ActivityCategory.QUOTATION,
            if (issueNow) "Quotation Issued (${savedId.second})" else "Quotation Draft Saved",
            "${draft.clientSnapshot.companyName} • ${MoneyFormatter.formatInr(draft.totalPaise)}",
            actor.uid,
            actor.fullName,
            savedId.first
        )
        return draft.copy(
            id = savedId.first,
            quoteNumber = savedId.second,
            status = savedId.third,
            companySnapshot = companySnap,
            issuedDateMillis = now,
            expiryDateMillis = expiryMillis,
            preparedByUid = actor.uid,
            preparedByName = actor.fullName
        )
    }

    suspend fun createQuotationRevision(previous: Quotation, actor: Member): Quotation {
        val newRevId = "qt_" + UUID.randomUUID().toString().take(8)
        val nextRev = previous.revision + 1
        db.runTransaction { tx ->
            if (previous.statusEnum() != QuotationStatus.DRAFT) {
                tx.update(
                    sub("quotations").document(previous.id),
                    mapOf("status" to QuotationStatus.SUPERSEDED.name, "updatedAt" to FieldValue.serverTimestamp())
                )
            }
            val revQuote = previous.copy(
                id = newRevId,
                revision = nextRev,
                parentQuoteId = previous.id,
                status = QuotationStatus.DRAFT.name,
                pdfLocalPath = "",
                pdfSha256 = "",
                sentAtMillis = 0L,
                clientApprovedAtMillis = 0L,
                clientApprovalNote = "",
                preparedByUid = actor.uid,
                preparedByName = actor.fullName
            )
            tx.set(sub("quotations").document(newRevId), quotationToMap(revQuote, isCreate = true))
            if (previous.requestId.isNotBlank()) {
                tx.update(
                    sub("requests").document(previous.requestId),
                    mapOf(
                        "latestQuoteId" to newRevId,
                        "latestQuoteNumber" to previous.quoteNumber,
                        "latestQuoteRevision" to nextRev,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
        }.await()
        core.logActivity(ActivityCategory.QUOTATION, "Created Revision v$nextRev", "${previous.quoteNumber} for ${previous.clientSnapshot.companyName}", actor.uid, actor.fullName, newRevId)
        return previous.copy(
            id = newRevId,
            revision = nextRev,
            parentQuoteId = previous.id,
            status = QuotationStatus.DRAFT.name,
            pdfLocalPath = "",
            pdfSha256 = "",
            sentAtMillis = 0L,
            clientApprovedAtMillis = 0L,
            clientApprovalNote = ""
        )
    }

    suspend fun savePdfMetadata(quoteId: String, localPath: String, sha256: String) {
        sub("quotations").document(quoteId).update(
            mapOf(
                "pdfLocalPath" to localPath,
                "pdfSha256" to sha256,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun markQuotationSent(quote: Quotation, actor: Member) {
        val now = System.currentTimeMillis()
        sub("quotations").document(quote.id).update(
            mapOf(
                "status" to QuotationStatus.SENT.name,
                "sentAtMillis" to now,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        core.logActivity(ActivityCategory.QUOTATION, "Quotation Marked Sent", "${quote.quoteNumber} v${quote.revision}", actor.uid, actor.fullName, quote.id)
    }

    suspend fun markQuotationClientApproved(quote: Quotation, approvalNote: String, actor: Member) {
        val now = System.currentTimeMillis()
        db.runTransaction { tx ->
            val projectRef = if (quote.linkedProjectId.isNotBlank()) sub("projects").document(quote.linkedProjectId) else null
            val projectSnap = projectRef?.let { tx.get(it) }

            tx.update(
                sub("quotations").document(quote.id),
                mapOf(
                    "status" to QuotationStatus.CLIENT_APPROVED.name,
                    "clientApprovedAtMillis" to now,
                    "clientApprovalNote" to approvalNote.trim(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            if (quote.requestId.isNotBlank()) {
                tx.update(
                    sub("requests").document(quote.requestId),
                    mapOf("clientApprovedQuoteId" to quote.id, "updatedAt" to FieldValue.serverTimestamp())
                )
            }
            // Post-acceptance scope change add-on quote updates agreed project value ONLY upon client approval
            if (projectSnap != null && projectSnap.exists()) {
                val oldAgreed = projectSnap.getLong("agreedValuePaise") ?: 0L
                val oldAdv = projectSnap.getLong("advanceDuePaise") ?: 0L
                tx.update(
                    projectRef,
                    mapOf(
                        "agreedValuePaise" to (oldAgreed + quote.totalPaise),
                        "advanceDuePaise" to (oldAdv + quote.advancePaise),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
        }.await()
        core.logActivity(ActivityCategory.QUOTATION, "Client Approved Quote", "${quote.quoteNumber} v${quote.revision} (${MoneyFormatter.formatInr(quote.totalPaise)})", actor.uid, actor.fullName, quote.id)
    }

    // --- Project Acceptance (Concurrency-Safe Transaction) & Decline / Delete ---

    suspend fun acceptRequestAndCreateProject(
        request: ProjectRequest,
        approvedQuote: Quotation,
        deliverableDrafts: List<DeliverableDraft>,
        company: CompanyProfile,
        actor: Member
    ): String {
        val projectId = "proj_${request.id}"
        val now = System.currentTimeMillis()
        val dueDefaultMillis = if (request.targetDateMillis > now) request.targetDateMillis else now + 14L * 86_400_000L

        db.runTransaction { tx ->
            val reqRef = sub("requests").document(request.id)
            val projRef = sub("projects").document(projectId)
            val reqSnap = tx.get(reqRef)
            val projSnap = tx.get(projRef)

            if (reqSnap.getString("decisionStatus") == DecisionStatus.ACCEPTED.name || projSnap.exists()) {
                throw FirebaseFirestoreException("This request has already been accepted into a project.", FirebaseFirestoreException.Code.ABORTED)
            }

            tx.update(
                reqRef,
                mapOf(
                    "decisionStatus" to DecisionStatus.ACCEPTED.name,
                    "decisionByUid" to actor.uid,
                    "decisionByName" to actor.fullName,
                    "decisionAtMillis" to now,
                    "acceptedProjectId" to projectId,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            tx.set(
                projRef,
                mapOf(
                    "requestId" to request.id,
                    "clientId" to request.clientId,
                    "clientCompanyName" to request.clientCompanyName,
                    "title" to request.projectTitle,
                    "brief" to request.brief,
                    "responsibleManagerUid" to request.responsibleManagerUid.ifBlank { actor.uid },
                    "responsibleManagerName" to request.responsibleManagerName.ifBlank { actor.fullName },
                    "approvedQuoteId" to approvedQuote.id,
                    "approvedQuoteNumber" to approvedQuote.quoteNumber,
                    "approvedQuoteRevision" to approvedQuote.revision,
                    "agreedValuePaise" to approvedQuote.totalPaise,
                    "advanceDuePaise" to approvedQuote.advancePaise,
                    "paidPaise" to 0L,
                    "paymentTerms" to approvedQuote.paymentTerms,
                    "revisionLimitPerDeliverable" to company.defaultRevisionRounds,
                    "status" to ProjectStatus.AWAITING_ADVANCE.name,
                    "deleted" to false,
                    "deletedByUid" to "",
                    "deletedAtMillis" to 0L,
                    "totalDeliverables" to deliverableDrafts.size,
                    "approvedDeliverables" to 0,
                    "inReviewDeliverables" to 0,
                    "blockedDeliverables" to 0,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            deliverableDrafts.forEachIndexed { idx, draft ->
                val dId = "deliv_${request.id}_${idx + 1}"
                tx.set(
                    sub("deliverables").document(dId),
                    mapOf(
                        "projectId" to projectId,
                        "projectTitle" to request.projectTitle,
                        "clientCompanyName" to request.clientCompanyName,
                        "projectBrief" to request.brief,
                        "departmentId" to draft.departmentId,
                        "departmentName" to draft.departmentName,
                        "jobRoleId" to draft.jobRoleId,
                        "jobRoleName" to draft.jobRoleName,
                        "title" to draft.title,
                        "description" to draft.description,
                        "quantity" to draft.quantity.coerceAtLeast(1),
                        "responsibleManagerUid" to request.responsibleManagerUid.ifBlank { actor.uid },
                        "assignedToUid" to draft.assignedToUid,
                        "assignedToName" to draft.assignedToName,
                        "assignmentStatus" to draft.assignmentStatus.name,
                        "dueDateMillis" to dueDefaultMillis,
                        "status" to DeliverableStatus.NOT_STARTED.name,
                        "checklist" to draft.checklist.map { mapOf("id" to it.id, "title" to it.title, "done" to it.done) },
                        "completedChecklistCount" to 0,
                        "totalChecklistCount" to draft.checklist.size,
                        "previewUrl" to "",
                        "latestNote" to "",
                        "blockedReason" to "",
                        "helpNeeded" to "",
                        "latestFeedback" to "",
                        "clientRevisionsUsed" to 0,
                        "clientRevisionLimit" to company.defaultRevisionRounds,
                        "lastUpdatedByUid" to actor.uid,
                        "lastUpdatedByName" to actor.fullName,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
        }.await()

        core.logActivity(
            ActivityCategory.PROJECT,
            "Project Accepted & Distributed",
            "${request.projectTitle} • ${deliverableDrafts.size} deliverables generated",
            actor.uid,
            actor.fullName,
            projectId
        )
        core.createNotification(
            category = NotificationCategory.PROJECT,
            title = "PROJECT ACCEPTED: ${request.projectTitle.uppercase()}",
            body = "${request.clientCompanyName} approved with ${deliverableDrafts.size} deliverables ready.",
            actor = actor,
            targetUid = "ALL",
            entityId = projectId
        )
        return projectId
    }

    suspend fun declineRequest(request: ProjectRequest, note: String, actor: Member) {
        sub("requests").document(request.id).update(
            mapOf(
                "decisionStatus" to DecisionStatus.DECLINED.name,
                "decisionByUid" to actor.uid,
                "decisionByName" to actor.fullName,
                "decisionAtMillis" to System.currentTimeMillis(),
                "decisionNote" to note.trim(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        core.logActivity(ActivityCategory.PROJECT, "Request Declined", "${request.projectTitle} — ${note.trim()}", actor.uid, actor.fullName, request.id)
    }

    suspend fun setProjectDeleted(project: Project, deleted: Boolean, actor: Member) {
        sub("projects").document(project.id).update(
            mapOf(
                "deleted" to deleted,
                "deletedByUid" to if (deleted) actor.uid else "",
                "deletedAtMillis" to if (deleted) System.currentTimeMillis() else 0L,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        core.logActivity(ActivityCategory.PROJECT, if (deleted) "Project Soft-Deleted" else "Project Restored", project.title, actor.uid, actor.fullName, project.id)
    }

    // --- Deliverable Assignment, Staff Progress, Manager Review & Payments ---

    suspend fun assignDeliverable(deliverable: Deliverable, assignee: Member, dueDateMillis: Long, actor: Member) {
        sub("deliverables").document(deliverable.id).update(
            mapOf(
                "assignedToUid" to assignee.uid,
                "assignedToName" to assignee.fullName,
                "assignmentStatus" to AssignmentStatus.ASSIGNED.name,
                "departmentId" to assignee.departmentId.ifBlank { deliverable.departmentId },
                "departmentName" to assignee.departmentName.ifBlank { deliverable.departmentName },
                "jobRoleId" to assignee.jobRoleId.ifBlank { deliverable.jobRoleId },
                "jobRoleName" to assignee.jobRoleName.ifBlank { deliverable.jobRoleName },
                "dueDateMillis" to dueDateMillis,
                "lastUpdatedByUid" to actor.uid,
                "lastUpdatedByName" to actor.fullName,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        core.logActivity(ActivityCategory.ASSIGNMENT, "Assigned Deliverable", "${deliverable.title} -> ${assignee.fullName}", actor.uid, actor.fullName, deliverable.id)
        core.createNotification(
            category = NotificationCategory.DELIVERABLE,
            title = "NEW TASK ASSIGNED",
            body = "${deliverable.title} (${deliverable.projectTitle}) assigned to ${assignee.fullName}.",
            actor = actor,
            targetUid = assignee.uid,
            entityId = deliverable.id
        )
    }

    suspend fun claimDepartmentDeliverable(deliverable: Deliverable, staff: Member) {
        sub("deliverables").document(deliverable.id).update(
            mapOf(
                "assignedToUid" to staff.uid,
                "assignedToName" to staff.fullName,
                "assignmentStatus" to AssignmentStatus.ASSIGNED.name,
                "lastUpdatedByUid" to staff.uid,
                "lastUpdatedByName" to staff.fullName,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        core.logActivity(
            ActivityCategory.ASSIGNMENT,
            "Queue Task Claimed",
            "${staff.fullName} claimed ${deliverable.title}",
            staff.uid,
            staff.fullName,
            deliverable.id
        )
        core.createNotification(
            category = NotificationCategory.DELIVERABLE,
            title = "TASK CLAIMED FROM QUEUE",
            body = "${staff.fullName} picked up ${deliverable.title} (${deliverable.projectTitle}).",
            actor = staff,
            targetUid = deliverable.responsibleManagerUid.ifBlank { "MANAGERS" },
            entityId = deliverable.id
        )
    }

    suspend fun startDeliverableWork(deliverable: Deliverable, staff: Member) {
        val completed = deliverable.checklist.count { it.done }
        val batch = db.batch()
        batch.update(
            sub("deliverables").document(deliverable.id),
            mapOf(
                "status" to DeliverableStatus.IN_PROGRESS.name,
                "latestNote" to "Work started by ${staff.fullName}",
                "lastUpdatedByUid" to staff.uid,
                "lastUpdatedByName" to staff.fullName,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        val updId = "upd_" + UUID.randomUUID().toString().take(10)
        batch.set(
            sub("deliverables").document(deliverable.id).collection("updates").document(updId),
            mapOf(
                "deliverableId" to deliverable.id,
                "projectId" to deliverable.projectId,
                "assignedToUid" to deliverable.assignedToUid.ifBlank { staff.uid },
                "departmentId" to deliverable.departmentId,
                "authorUid" to staff.uid,
                "authorName" to staff.fullName,
                "actionType" to UpdateActionType.PROGRESS.name,
                "statusAfter" to DeliverableStatus.IN_PROGRESS.name,
                "note" to "Started work on ${deliverable.title}",
                "previewUrl" to deliverable.previewUrl,
                "blockedReason" to "",
                "helpNeeded" to "",
                "completedCount" to completed,
                "totalCount" to deliverable.checklist.size,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        core.createNotification(
            category = NotificationCategory.DELIVERABLE,
            title = "PROJECT STARTED: ${deliverable.title.uppercase()}",
            body = "${staff.fullName} started working on ${deliverable.projectTitle}.",
            actor = staff,
            targetUid = deliverable.responsibleManagerUid.ifBlank { "MANAGERS" },
            entityId = deliverable.id
        )
    }

    suspend fun submitStaffProgress(
        deliverable: Deliverable,
        newStatus: DeliverableStatus,
        checklist: List<ChecklistItem>,
        note: String,
        previewUrl: String,
        blockedReason: String,
        helpNeeded: String,
        actor: Member
    ) {
        val completed = checklist.count { it.done }
        val actionType = when (newStatus) {
            DeliverableStatus.BLOCKED -> UpdateActionType.BLOCKED
            DeliverableStatus.READY_FOR_REVIEW -> UpdateActionType.SUBMIT_REVIEW
            else -> UpdateActionType.PROGRESS
        }
        val batch = db.batch()
        batch.update(
            sub("deliverables").document(deliverable.id),
            mapOf(
                "status" to newStatus.name,
                "checklist" to checklist.map { mapOf("id" to it.id, "title" to it.title, "done" to it.done) },
                "completedChecklistCount" to completed,
                "previewUrl" to previewUrl.trim(),
                "latestNote" to note.trim(),
                "blockedReason" to if (newStatus == DeliverableStatus.BLOCKED) blockedReason.trim() else "",
                "helpNeeded" to if (newStatus == DeliverableStatus.BLOCKED) helpNeeded.trim() else "",
                "lastUpdatedByUid" to actor.uid,
                "lastUpdatedByName" to actor.fullName,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        val updId = "upd_" + UUID.randomUUID().toString().take(10)
        batch.set(
            sub("deliverables").document(deliverable.id).collection("updates").document(updId),
            mapOf(
                "deliverableId" to deliverable.id,
                "projectId" to deliverable.projectId,
                "assignedToUid" to deliverable.assignedToUid,
                "departmentId" to deliverable.departmentId,
                "authorUid" to actor.uid,
                "authorName" to actor.fullName,
                "actionType" to actionType.name,
                "statusAfter" to newStatus.name,
                "note" to note.trim(),
                "previewUrl" to previewUrl.trim(),
                "blockedReason" to blockedReason.trim(),
                "helpNeeded" to helpNeeded.trim(),
                "completedCount" to completed,
                "totalCount" to checklist.size,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        if (newStatus == DeliverableStatus.BLOCKED || newStatus == DeliverableStatus.READY_FOR_REVIEW) {
            core.createNotification(
                category = NotificationCategory.DELIVERABLE,
                title = if (newStatus == DeliverableStatus.BLOCKED) "BLOCKER FLAGGED" else "READY FOR REVIEW",
                body = "${actor.fullName}: ${deliverable.title} (${deliverable.projectTitle})",
                actor = actor,
                targetUid = deliverable.responsibleManagerUid.ifBlank { "MANAGERS" },
                entityId = deliverable.id
            )
        }
    }

    suspend fun reviewDeliverable(
        deliverable: Deliverable,
        action: UpdateActionType,
        feedback: String,
        projectDeliverables: List<Deliverable>,
        actor: Member
    ) {
        val nextStatus = when (action) {
            UpdateActionType.APPROVED -> DeliverableStatus.APPROVED
            UpdateActionType.CHANGES_REQUESTED -> DeliverableStatus.CHANGES_REQUESTED
            UpdateActionType.CLIENT_REVISION -> DeliverableStatus.CHANGES_REQUESTED
            else -> deliverable.statusEnum()
        }
        val nextClientRev = if (action == UpdateActionType.CLIENT_REVISION) deliverable.clientRevisionsUsed + 1 else deliverable.clientRevisionsUsed

        val batch = db.batch()
        batch.update(
            sub("deliverables").document(deliverable.id),
            mapOf(
                "status" to nextStatus.name,
                "latestFeedback" to feedback.trim(),
                "clientRevisionsUsed" to nextClientRev,
                "lastUpdatedByUid" to actor.uid,
                "lastUpdatedByName" to actor.fullName,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        val updId = "upd_" + UUID.randomUUID().toString().take(10)
        batch.set(
            sub("deliverables").document(deliverable.id).collection("updates").document(updId),
            mapOf(
                "deliverableId" to deliverable.id,
                "projectId" to deliverable.projectId,
                "assignedToUid" to deliverable.assignedToUid,
                "departmentId" to deliverable.departmentId,
                "authorUid" to actor.uid,
                "authorName" to actor.fullName,
                "actionType" to action.name,
                "statusAfter" to nextStatus.name,
                "note" to feedback.trim(),
                "previewUrl" to deliverable.previewUrl,
                "blockedReason" to "",
                "helpNeeded" to "",
                "completedCount" to deliverable.completedChecklistCount,
                "totalCount" to deliverable.totalChecklistCount,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )

        // Update rolled-up counters on Project for quick overview
        val updatedList = projectDeliverables.map { if (it.id == deliverable.id) it.copy(status = nextStatus.name) else it }
        val approved = updatedList.count { it.statusEnum() == DeliverableStatus.APPROVED }
        val inRev = updatedList.count { it.statusEnum() == DeliverableStatus.READY_FOR_REVIEW }
        val blocked = updatedList.count { it.statusEnum() == DeliverableStatus.BLOCKED }
        batch.update(
            sub("projects").document(deliverable.projectId),
            mapOf(
                "totalDeliverables" to updatedList.size,
                "approvedDeliverables" to approved,
                "inReviewDeliverables" to inRev,
                "blockedDeliverables" to blocked,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().await()
        core.logActivity(ActivityCategory.DELIVERABLE, "${action.label}: ${deliverable.title}", feedback.trim(), actor.uid, actor.fullName, deliverable.id)
        core.createNotification(
            category = NotificationCategory.DELIVERABLE,
            title = "${action.label.uppercase()}: ${deliverable.title.uppercase()}",
            body = feedback.trim().ifBlank { "Reviewed by ${actor.fullName}." },
            actor = actor,
            targetUid = deliverable.assignedToUid.ifBlank { "ALL" },
            entityId = deliverable.id
        )
    }

    suspend fun recordPayment(
        project: Project,
        amountPaise: Long,
        paymentDateMillis: Long,
        method: PaymentMethod,
        reference: String,
        note: String,
        actor: Member
    ) {
        val payId = "pay_" + UUID.randomUUID().toString().take(10)
        db.runTransaction { tx ->
            val projRef = sub("projects").document(project.id)
            val snap = tx.get(projRef)
            val currentPaid = snap.getLong("paidPaise") ?: 0L
            val newPaid = currentPaid + amountPaise
            val advanceDue = snap.getLong("advanceDuePaise") ?: 0L
            val agreed = snap.getLong("agreedValuePaise") ?: 0L
            val currentStatus = snap.getString("status") ?: ProjectStatus.AWAITING_ADVANCE.name

            val nextStatus = when {
                agreed > 0L && newPaid >= agreed && (snap.getLong("totalDeliverables") ?: 0L) == (snap.getLong("approvedDeliverables") ?: -1L) ->
                    ProjectStatus.COMPLETED.name
                currentStatus == ProjectStatus.AWAITING_ADVANCE.name && newPaid >= advanceDue && advanceDue > 0L ->
                    ProjectStatus.IN_PROGRESS.name
                else -> currentStatus
            }

            tx.set(
                sub("payments").document(payId),
                mapOf(
                    "projectId" to project.id,
                    "projectTitle" to project.title,
                    "clientCompanyName" to project.clientCompanyName,
                    "amountPaise" to amountPaise,
                    "paymentDateMillis" to paymentDateMillis,
                    "method" to method.name,
                    "reference" to reference.trim(),
                    "note" to note.trim(),
                    "recordedByUid" to actor.uid,
                    "recordedByName" to actor.fullName,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            tx.update(
                projRef,
                mapOf("paidPaise" to newPaid, "status" to nextStatus, "updatedAt" to FieldValue.serverTimestamp())
            )
        }.await()

        core.logActivity(
            ActivityCategory.PAYMENT,
            "Payment Recorded (${MoneyFormatter.formatInr(amountPaise)})",
            "${project.title} via ${method.label} ${reference.trim()}",
            actor.uid,
            actor.fullName,
            payId
        )
        core.createNotification(
            category = NotificationCategory.PAYMENT,
            title = "PAYMENT RECORDED: ${MoneyFormatter.formatInr(amountPaise)}",
            body = "${project.title} • ${method.label}",
            actor = actor,
            targetUid = "MANAGERS",
            entityId = project.id
        )
    }

    private fun quotationToMap(q: Quotation, isCreate: Boolean): Map<String, Any> {
        val linesList = q.lines.map { line ->
            mutableMapOf<String, Any>(
                "lineId" to line.lineId,
                "catalogueItemId" to line.catalogueItemId,
                "kind" to line.kind,
                "category" to line.category,
                "title" to line.title,
                "description" to line.description,
                "pricingMode" to line.pricingMode,
                "quantity" to line.quantity,
                "unitPricePaise" to line.unitPricePaise,
                "lineTotalPaise" to line.lineTotalPaise,
                "billingFrequency" to line.billingFrequency,
                "separatelyBilled" to line.separatelyBilled,
                "urgentEligible" to line.urgentEligible,
                "inclusions" to line.inclusions.map { inc ->
                    mapOf(
                        "serviceId" to inc.serviceId,
                        "title" to inc.title,
                        "category" to inc.category,
                        "quantity" to inc.quantity,
                        "description" to inc.description
                    )
                },
                "sortOrder" to line.sortOrder
            ).apply {
                line.overridePricePaise?.let { put("overridePricePaise", it) }
            }
        }
        val map = mutableMapOf<String, Any>(
            "quoteNumber" to q.quoteNumber,
            "revision" to q.revision,
            "parentQuoteId" to q.parentQuoteId,
            "requestId" to q.requestId,
            "linkedProjectId" to q.linkedProjectId,
            "clientId" to q.clientId,
            "projectTitle" to q.projectTitle,
            "scopeSummary" to q.scopeSummary,
            "status" to q.status,
            "companySnapshot" to mapOf(
                "name" to q.companySnapshot.name,
                "tagline" to q.companySnapshot.tagline,
                "address" to q.companySnapshot.address,
                "phone" to q.companySnapshot.phone,
                "email" to q.companySnapshot.email,
                "gstin" to q.companySnapshot.gstin,
                "gstEnabled" to q.companySnapshot.gstEnabled,
                "gstRatePercent" to q.companySnapshot.gstRatePercent
            ),
            "clientSnapshot" to mapOf(
                "clientId" to q.clientSnapshot.clientId,
                "companyName" to q.clientSnapshot.companyName,
                "contactPerson" to q.clientSnapshot.contactPerson,
                "phone" to q.clientSnapshot.phone,
                "email" to q.clientSnapshot.email,
                "address" to q.clientSnapshot.address
            ),
            "lines" to linesList,
            "subtotalPaise" to q.subtotalPaise,
            "discountPaise" to q.discountPaise,
            "surchargePaise" to q.surchargePaise,
            "surchargeBasePaise" to q.surchargeBasePaise,
            "gstEnabled" to q.gstEnabled,
            "gstRatePercent" to q.gstRatePercent,
            "gstAmountPaise" to q.gstAmountPaise,
            "totalPaise" to q.totalPaise,
            "advancePercent" to q.advancePercent,
            "advancePaise" to q.advancePaise,
            "balancePaise" to q.balancePaise,
            "paymentTerms" to q.paymentTerms,
            "revisionPolicy" to q.revisionPolicy,
            "timelineNote" to q.timelineNote,
            "separatelyBilledNote" to q.separatelyBilledNote,
            "approvalInstructions" to q.approvalInstructions,
            "issuedDateMillis" to q.issuedDateMillis,
            "expiryDateMillis" to q.expiryDateMillis,
            "preparedByUid" to q.preparedByUid,
            "preparedByName" to q.preparedByName,
            "pdfLocalPath" to q.pdfLocalPath,
            "pdfSha256" to q.pdfSha256,
            "sentAtMillis" to q.sentAtMillis,
            "clientApprovedAtMillis" to q.clientApprovedAtMillis,
            "clientApprovalNote" to q.clientApprovalNote,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (isCreate) map["createdAt"] = FieldValue.serverTimestamp()
        return map
    }

    suspend fun deleteQuotation(quoteId: String, quoteNum: String, actor: Member) {
        sub("quotations").document(quoteId).delete().await()
        core.logActivity(ActivityCategory.QUOTATION, "Quotation Deleted", quoteNum, actor.uid, actor.fullName, quoteId)
    }
}
