package com.example.model

import androidx.annotation.Keep
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties

/**
 * Unified User domain & Firestore profile model holding onboarding details,
 * access level, and membership status.
 */
@Keep
@IgnoreExtraProperties
data class User(
    @DocumentId val uid: String = "",
    val companyName: String = "",
    val fullName: String = "",
    val email: String = "",
    val age: Int? = null,
    val phone: String = "",
    val jobRole: String = "",
    val department: String = "",
    val accessLevel: String = AccessLevel.STAFF.name,
    val membershipStatus: String = MembershipStatus.PENDING.name,
    val active: Boolean = true
) {
    fun accessLevelEnum(): AccessLevel = safeEnum(accessLevel, AccessLevel.STAFF)
    fun membershipStatusEnum(): MembershipStatus = safeEnum(membershipStatus, MembershipStatus.PENDING)
    fun isApprovedAndActive(): Boolean = active && membershipStatusEnum() == MembershipStatus.APPROVED

    companion object {
        fun fromMember(
            member: Member,
            privateInfo: MemberPrivateInfo? = null,
            email: String = ""
        ): User {
            val resolvedCompany = member.companyNameInput.ifBlank { "X.MAX" }
            val resolvedRole = member.jobRoleName.ifBlank { member.requestedJobRole }
            val resolvedDept = member.departmentName.ifBlank { member.requestedDepartment }
            return User(
                uid = member.uid.ifBlank { member.id },
                companyName = resolvedCompany,
                fullName = member.fullName,
                email = privateInfo?.email?.ifBlank { email } ?: email,
                age = privateInfo?.age,
                phone = privateInfo?.phone.orEmpty(),
                jobRole = resolvedRole,
                department = resolvedDept,
                accessLevel = member.accessLevel,
                membershipStatus = member.status,
                active = member.active
            )
        }
    }
}
