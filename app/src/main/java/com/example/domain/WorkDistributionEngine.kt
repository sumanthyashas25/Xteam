package com.example.domain

import com.example.model.AssignmentStatus
import com.example.model.ChecklistItem
import com.example.model.Deliverable
import com.example.model.DeliverableStatus
import com.example.model.Department
import com.example.model.JobRole
import com.example.model.Member
import com.example.model.MembershipStatus
import com.example.model.PricingMode
import com.example.model.QuoteLineItem
import com.example.model.RoutingRule

data class DeliverableDraft(
    val draftId: String,
    val title: String,
    val description: String,
    val quantity: Int,
    val sourceCategory: String,
    val departmentId: String,
    val departmentName: String,
    val jobRoleId: String,
    val jobRoleName: String,
    val assignedToUid: String,
    val assignedToName: String,
    val assignmentStatus: AssignmentStatus,
    val matchingCandidates: List<Member>,
    val checklist: List<ChecklistItem>
)

object WorkDistributionEngine {

    fun buildDeliverableDrafts(
        quoteLines: List<QuoteLineItem>,
        routingRules: List<RoutingRule>,
        members: List<Member>,
        departments: List<Department>,
        jobRoles: List<JobRole>
    ): List<DeliverableDraft> {
        val activeMembers = members.filter { it.statusEnum() == MembershipStatus.APPROVED && it.active }
        val deptMap = departments.associateBy { it.id }
        val roleMap = jobRoles.associateBy { it.id }
        val sortedRules = routingRules.filter { it.active }.sortedByDescending { it.priority }

        val drafts = mutableListOf<DeliverableDraft>()
        var counter = 1

        for (line in quoteLines) {
            if (line.pricingModeEnum() == PricingMode.PERCENTAGE_SURCHARGE || line.separatelyBilled) {
                continue
            }

            if (line.inclusions.isNotEmpty()) {
                for (inc in line.inclusions) {
                    val qty = (inc.quantity.coerceAtLeast(1)) * line.quantity.coerceAtLeast(1)
                    drafts += resolveSingleDraft(
                        draftId = "draft_${counter++}",
                        title = inc.title,
                        description = inc.description.ifBlank { "Included from ${line.title}" },
                        quantity = qty,
                        category = inc.category.ifBlank { line.category },
                        sortedRules = sortedRules,
                        activeMembers = activeMembers,
                        deptMap = deptMap,
                        roleMap = roleMap
                    )
                }
            } else {
                drafts += resolveSingleDraft(
                    draftId = "draft_${counter++}",
                    title = line.title,
                    description = line.description,
                    quantity = line.quantity.coerceAtLeast(1),
                    category = line.category,
                    sortedRules = sortedRules,
                    activeMembers = activeMembers,
                    deptMap = deptMap,
                    roleMap = roleMap
                )
            }
        }
        return drafts
    }

    private fun resolveSingleDraft(
        draftId: String,
        title: String,
        description: String,
        quantity: Int,
        category: String,
        sortedRules: List<RoutingRule>,
        activeMembers: List<Member>,
        deptMap: Map<String, Department>,
        roleMap: Map<String, JobRole>
    ): DeliverableDraft {
        val text = "$title $description $category".lowercase()
        val matchedRule = sortedRules.firstOrNull { rule ->
            val kwMatch = rule.keywords.any { kw -> kw.isNotBlank() && text.contains(kw.lowercase()) }
            val catMatch = rule.serviceCategories.any { c -> c.equals(category, ignoreCase = true) }
            kwMatch || catMatch
        }

        val deptId = matchedRule?.departmentId ?: "dept_ops"
        val deptName = deptMap[deptId]?.name ?: matchedRule?.departmentName ?: "Operations"
        val roleId = matchedRule?.jobRoleId ?: "role_pm"
        val roleName = roleMap[roleId]?.name ?: matchedRule?.jobRoleName ?: "Project Manager"

        val candidates = activeMembers.filter { m ->
            m.departmentId == deptId && m.jobRoleId == roleId
        }

        val (assignedUid, assignedName, assignmentStatus) = when (candidates.size) {
            1 -> Triple(candidates.first().uid, candidates.first().fullName, AssignmentStatus.ASSIGNED)
            in 2..Int.MAX_VALUE -> Triple("", "", AssignmentStatus.DEPT_QUEUE)
            else -> Triple("", "", AssignmentStatus.NEEDS_ASSIGNMENT)
        }

        val checklist = buildDefaultChecklist(title, category)

        return DeliverableDraft(
            draftId = draftId,
            title = title,
            description = description,
            quantity = quantity,
            sourceCategory = category,
            departmentId = deptId,
            departmentName = deptName,
            jobRoleId = roleId,
            jobRoleName = roleName,
            assignedToUid = assignedUid,
            assignedToName = assignedName,
            assignmentStatus = assignmentStatus,
            matchingCandidates = candidates,
            checklist = checklist
        )
    }

    fun buildDefaultChecklist(title: String, category: String): List<ChecklistItem> {
        val t = "$title $category".lowercase()
        return when {
            t.contains("reel") || t.contains("video") || t.contains("motion") -> listOf(
                ChecklistItem("c1", "Review brief & footage / script"),
                ChecklistItem("c2", "Assemble rough cut & pacing"),
                ChecklistItem("c3", "Add captions, sound design & color grade"),
                ChecklistItem("c4", "Export final master & preview link")
            )
            t.contains("web") || t.contains("landing") || t.contains("page") || t.contains("store") || t.contains("seo") -> listOf(
                ChecklistItem("c1", "Confirm wireframe / content brief"),
                ChecklistItem("c2", "Build responsive layout & core sections"),
                ChecklistItem("c3", "QA mobile breakpoints, forms & speed"),
                ChecklistItem("c4", "Publish staging preview for manager review")
            )
            t.contains("ad") || t.contains("meta") || t.contains("google") || t.contains("lead") -> listOf(
                ChecklistItem("c1", "Verify pixel / conversion tracking"),
                ChecklistItem("c2", "Configure audience targeting & campaign structure"),
                ChecklistItem("c3", "Upload ad creatives & primary copy"),
                ChecklistItem("c4", "Final launch readiness review")
            )
            else -> listOf(
                ChecklistItem("c1", "Review client brief & brand references"),
                ChecklistItem("c2", "Create initial concept / draft"),
                ChecklistItem("c3", "Refine typography, layout & brand consistency"),
                ChecklistItem("c4", "Prepare export & preview link for review")
            )
        }
    }

    fun applyOverride(
        draft: DeliverableDraft,
        assignee: Member?
    ): DeliverableDraft {
        return if (assignee == null) {
            draft.copy(
                assignedToUid = "",
                assignedToName = "",
                assignmentStatus = AssignmentStatus.NEEDS_ASSIGNMENT
            )
        } else {
            draft.copy(
                assignedToUid = assignee.uid,
                assignedToName = assignee.fullName,
                assignmentStatus = AssignmentStatus.ASSIGNED,
                departmentId = assignee.departmentId.ifBlank { draft.departmentId },
                departmentName = assignee.departmentName.ifBlank { draft.departmentName },
                jobRoleId = assignee.jobRoleId.ifBlank { draft.jobRoleId },
                jobRoleName = assignee.jobRoleName.ifBlank { draft.jobRoleName }
            )
        }
    }

    fun summarizeProjectProgress(deliverables: List<Deliverable>): String {
        if (deliverables.isEmpty()) return "No deliverables created yet"
        val total = deliverables.size
        val approved = deliverables.count { it.statusEnum() == DeliverableStatus.APPROVED }
        val inReview = deliverables.count { it.statusEnum() == DeliverableStatus.READY_FOR_REVIEW }
        val blocked = deliverables.count { it.statusEnum() == DeliverableStatus.BLOCKED }
        val parts = mutableListOf("$approved of $total deliverables approved")
        if (inReview > 0) parts += "$inReview in review"
        if (blocked > 0) parts += "$blocked blocked"
        return parts.joinToString(", ")
    }
}
