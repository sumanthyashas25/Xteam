package com.example.system

import android.content.Context
import com.example.data.AgencyRepository
import com.example.domain.MoneyFormatter
import com.example.model.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PaymentReminderKind {
    ADVANCE_DUE,
    DEADLINE_FINAL,
    POST_SUBMISSION_FINAL
}

data class PaymentReminderAlert(
    val kind: PaymentReminderKind,
    val uniqueKey: String,
    val title: String,
    val body: String,
    val entityId: String,
    val amountPaise: Long
)

object PaymentReminderAutomator {

    private const val PREFS_NAME = "xmax_payment_reminders_prefs"
    private const val THREE_DAYS_MILLIS = 3L * 86_400_000L

    fun dayStamp(nowMillis: Long = System.currentTimeMillis()): String {
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(nowMillis))
    }

    fun canReceivePaymentReminders(member: Member?): Boolean {
        val m = member ?: return false
        if (m.statusEnum() != MembershipStatus.APPROVED || !m.active) return false
        val level = m.accessLevelEnum()
        if (level == AccessLevel.SUPER_ADMIN || level == AccessLevel.ADMIN) return true
        return m.permissions[AppPermission.VIEW_PAYMENTS.name]?.allowed == true ||
            m.permissions[AppPermission.RECORD_PAYMENTS.name]?.allowed == true ||
            m.delegatedGrants[AppPermission.VIEW_PAYMENTS.name]?.allowed == true ||
            m.delegatedGrants[AppPermission.RECORD_PAYMENTS.name]?.allowed == true
    }

    fun evaluatePaymentReminders(
        quotations: List<Quotation>,
        projects: List<Project>,
        deliverables: List<Deliverable>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<PaymentReminderAlert> {
        val alerts = mutableListOf<PaymentReminderAlert>()

        // 1. Advance payment reminders for client-approved quotations
        quotations
            .filter { it.statusEnum() == QuotationStatus.CLIENT_APPROVED && it.advancePaise > 0L }
            .forEach { quote ->
                val linkedProj = projects.firstOrNull { p ->
                    !p.deleted && (
                        p.id == quote.linkedProjectId ||
                            p.approvedQuoteId == quote.id ||
                            (quote.requestId.isNotBlank() && p.requestId == quote.requestId)
                        )
                }
                val requiredAdvance = linkedProj?.advanceDuePaise?.coerceAtLeast(quote.advancePaise) ?: quote.advancePaise
                val paid = linkedProj?.paidPaise ?: 0L
                val remainingAdvance = (requiredAdvance - paid).coerceAtLeast(0L)
                if (remainingAdvance > 0L) {
                    val label = quote.clientSnapshot.companyName.ifBlank { quote.projectTitle.ifBlank { quote.quoteNumber } }
                    alerts += PaymentReminderAlert(
                        kind = PaymentReminderKind.ADVANCE_DUE,
                        uniqueKey = "adv_${quote.id}",
                        title = "ADVANCE PAYMENT REMINDER: ${quote.quoteNumber}".take(150),
                        body = "$label approved quotation ${quote.quoteNumber}. Track pending advance of ${MoneyFormatter.formatInr(remainingAdvance)} in Payments.".take(560),
                        entityId = linkedProj?.id ?: quote.id,
                        amountPaise = remainingAdvance
                    )
                }
            }

        // 2 & 3. Near deadline or post-submission final payment reminders for active/completed projects
        projects
            .filter { !it.deleted }
            .forEach { project ->
                val outstanding = (project.agreedValuePaise - project.paidPaise).coerceAtLeast(0L)
                if (outstanding <= 0L) return@forEach

                val projDelivs = deliverables.filter { it.projectId == project.id }
                val allSubmittedOrApproved = (projDelivs.isNotEmpty() && projDelivs.all { d ->
                    val st = d.statusEnum()
                    st == DeliverableStatus.READY_FOR_REVIEW || st == DeliverableStatus.APPROVED
                }) || project.statusEnum() == ProjectStatus.COMPLETED

                if (allSubmittedOrApproved) {
                    alerts += PaymentReminderAlert(
                        kind = PaymentReminderKind.POST_SUBMISSION_FINAL,
                        uniqueKey = "fin_${project.id}",
                        title = "FINAL PAYMENT TRACKING: ${project.title}".take(150),
                        body = "Deliverables submitted/completed for ${project.title} (${project.clientCompanyName}). Collect remaining balance of ${MoneyFormatter.formatInr(outstanding)} in Payment Tracker.".take(560),
                        entityId = project.id,
                        amountPaise = outstanding
                    )
                } else {
                    val activeDueDates = projDelivs
                        .filter { it.statusEnum() != DeliverableStatus.APPROVED && it.dueDateMillis > 0L }
                        .map { it.dueDateMillis }
                    val earliestDue = activeDueDates.minOrNull() ?: 0L
                    if (earliestDue > 0L && earliestDue <= nowMillis + THREE_DAYS_MILLIS) {
                        val isOverdue = earliestDue < nowMillis
                        val daysLeft = ((earliestDue - nowMillis) / 86_400_000L).coerceAtLeast(0L)
                        val timingText = when {
                            isOverdue -> "deadline passed"
                            daysLeft == 0L -> "due today"
                            else -> "due in ${daysLeft}d"
                        }
                        alerts += PaymentReminderAlert(
                            kind = PaymentReminderKind.DEADLINE_FINAL,
                            uniqueKey = "dl_${project.id}",
                            title = "DEADLINE PAYMENT REMINDER: ${project.title}".take(150),
                            body = "${project.title} ($timingText) still has ${MoneyFormatter.formatInr(outstanding)} pending. Track final payment in Payment Tracker.".take(560),
                            entityId = project.id,
                            amountPaise = outstanding
                        )
                    }
                }
            }

        return alerts.distinctBy { it.uniqueKey }
    }

    fun wasDispatchedToday(context: Context, uid: String, uniqueKey: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (uid.isBlank()) return true
        val prefKey = "sent_${uid}_${uniqueKey}_${dayStamp(nowMillis)}"
        return runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(prefKey, false)
        }.getOrDefault(false)
    }

    fun markDispatchedToday(context: Context, uid: String, uniqueKey: String, nowMillis: Long = System.currentTimeMillis()) {
        if (uid.isBlank()) return
        val prefKey = "sent_${uid}_${uniqueKey}_${dayStamp(nowMillis)}"
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(prefKey, true)
                .apply()
        }
    }

    suspend fun evaluateAndDispatch(
        context: Context,
        coreRepo: AgencyRepository,
        actor: Member,
        quotations: List<Quotation>,
        projects: List<Project>,
        deliverables: List<Deliverable>,
        soundEnabled: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): Int {
        if (!canReceivePaymentReminders(actor)) return 0
        val alerts = evaluatePaymentReminders(quotations, projects, deliverables, nowMillis)
        if (alerts.isEmpty()) return 0

        val day = dayStamp(nowMillis)
        var dispatched = 0
        alerts.forEach { alert ->
            if (!wasDispatchedToday(context, actor.uid, alert.uniqueKey, nowMillis)) {
                markDispatchedToday(context, actor.uid, alert.uniqueKey, nowMillis)
                val docId = "notif_pay_${alert.uniqueKey}_$day"
                coreRepo.createNotificationIfAbsent(
                    notificationId = docId,
                    category = NotificationCategory.PAYMENT,
                    title = alert.title,
                    body = alert.body,
                    actor = actor,
                    targetUid = "MANAGERS",
                    entityId = alert.entityId
                )
                AppHapticsAndNotifier.postSystemNotification(
                    context = context,
                    title = alert.title,
                    body = alert.body,
                    notifId = (docId.hashCode() and 0x7FFFFFFF),
                    soundEnabled = soundEnabled
                )
                dispatched++
            }
        }
        return dispatched
    }
}
