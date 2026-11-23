package com.example.domain

import com.example.model.AccessLevel
import com.example.model.AppPermission
import com.example.model.DelegatedGrant
import com.example.model.Department
import com.example.model.JobRole
import com.example.model.Member
import com.example.model.MembershipStatus
import com.example.model.RoutingRule
import com.example.model.ScopeType

data class RetirementImpact(
    val affectedEmployees: List<Member>,
    val affectedJobRoles: List<JobRole>,
    val affectedRoutingRules: List<RoutingRule>,
    val activeDeliverableCount: Int
)

object PermissionEngine {

    fun isActiveApproved(member: Member?): Boolean {
        return member != null &&
            member.statusEnum() == MembershipStatus.APPROVED &&
            member.active
    }

    fun hasPermission(
        member: Member?,
        permission: AppPermission,
        allMembersByUid: Map<String, Member>
    ): Boolean {
        val m = member ?: return false
        if (!isActiveApproved(m)) return false
        return when (m.accessLevelEnum()) {
            AccessLevel.SUPER_ADMIN -> true
            AccessLevel.ADMIN -> m.permissions[permission.name]?.allowed == true
            AccessLevel.STAFF -> {
                if (permission.reserved || !permission.staffEligible) return false
                val grant = m.delegatedGrants[permission.name] ?: return false
                if (!grant.allowed || grant.grantedByUid.isBlank()) return false
                val grantor = allMembersByUid[grant.grantedByUid] ?: return false
                isActiveApproved(grantor) &&
                    grantor.accessLevelEnum() == AccessLevel.ADMIN &&
                    grantor.permissions[permission.name]?.allowed == true &&
                    grantor.permissions[permission.name]?.canDelegate == true &&
                    canAccessDepartment(grantor, m.departmentId)
            }
        }
    }

    fun canDelegate(member: Member?, permission: AppPermission): Boolean {
        val m = member ?: return false
        if (!isActiveApproved(m)) return false
        if (permission.reserved || !permission.staffEligible) return false
        return when (m.accessLevelEnum()) {
            AccessLevel.SUPER_ADMIN -> true
            AccessLevel.ADMIN -> {
                val p = m.permissions[permission.name]
                p?.allowed == true && p.canDelegate
            }
            AccessLevel.STAFF -> false
        }
    }

    fun canAccessDepartment(member: Member?, departmentId: String): Boolean {
        val m = member ?: return false
        if (!isActiveApproved(m)) return false
        if (m.accessLevelEnum() == AccessLevel.SUPER_ADMIN) return true
        if (m.scopeTypeEnum() == ScopeType.COMPANY) return true
        if (departmentId.isBlank()) return true
        return departmentId in m.scopeDepartmentIds || m.departmentId == departmentId
    }

    fun canAdminDelegateToStaff(
        actor: Member?,
        targetStaff: Member,
        permission: AppPermission
    ): Boolean {
        if (!canDelegate(actor, permission)) return false
        if (!isActiveApproved(targetStaff)) return false
        if (targetStaff.accessLevelEnum() != AccessLevel.STAFF) return false
        return canAccessDepartment(actor, targetStaff.departmentId)
    }

    fun effectivePermissions(
        member: Member?,
        allMembersByUid: Map<String, Member>
    ): Set<AppPermission> {
        if (!isActiveApproved(member)) return emptySet()
        return AppPermission.entries.filter { hasPermission(member, it, allMembersByUid) }.toSet()
    }

    fun computeDependentStaffRevocations(
        adminUid: String,
        newPermissions: Map<String, com.example.model.PermissionGrant>,
        staffMembers: List<Member>
    ): Map<String, Map<String, DelegatedGrant>> {
        val updates = mutableMapOf<String, Map<String, DelegatedGrant>>()
        for (staff in staffMembers) {
            if (staff.accessLevelEnum() != AccessLevel.STAFF) continue
            val pruned = staff.delegatedGrants.filter { (permKey, grant) ->
                if (grant.grantedByUid != adminUid) return@filter true
                val adminPerm = newPermissions[permKey]
                adminPerm?.allowed == true && adminPerm.canDelegate
            }
            if (pruned.size != staff.delegatedGrants.size) {
                updates[staff.uid] = pruned
            }
        }
        return updates
    }

    fun analyzeDepartmentRetirement(
        departmentId: String,
        members: List<Member>,
        jobRoles: List<JobRole>,
        routingRules: List<RoutingRule>,
        activeDeliverablesCount: Int
    ): RetirementImpact {
        return RetirementImpact(
            affectedEmployees = members.filter { it.departmentId == departmentId && it.active },
            affectedJobRoles = jobRoles.filter { it.departmentId == departmentId && it.active },
            affectedRoutingRules = routingRules.filter { it.departmentId == departmentId && it.active },
            activeDeliverableCount = activeDeliverablesCount
        )
    }

    fun analyzeRoleRetirement(
        jobRoleId: String,
        members: List<Member>,
        routingRules: List<RoutingRule>,
        activeDeliverablesCount: Int
    ): RetirementImpact {
        return RetirementImpact(
            affectedEmployees = members.filter { it.jobRoleId == jobRoleId && it.active },
            affectedJobRoles = emptyList(),
            affectedRoutingRules = routingRules.filter { it.jobRoleId == jobRoleId && it.active },
            activeDeliverableCount = activeDeliverablesCount
        )
    }

    fun validReplacementDepartments(departments: List<Department>, retiringId: String): List<Department> {
        return departments.filter { it.active && it.id != retiringId }
    }
}
