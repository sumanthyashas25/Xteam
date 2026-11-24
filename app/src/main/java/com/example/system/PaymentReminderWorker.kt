package com.example.system

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.AgencyRepository
import com.example.domain.CatalogueSeedData
import com.example.model.Deliverable
import com.example.model.Member
import com.example.model.Project
import com.example.model.Quotation
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

class PaymentReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val user = FirebaseAuth.getInstance().currentUser ?: return Result.success()
            val repo = AgencyRepository(applicationContext)
            val memberSnap = repo.sub("members").document(user.uid).get().await()
            val member = memberSnap.toObject(Member::class.java) ?: return Result.success()
            if (!PaymentReminderAutomator.canReceivePaymentReminders(member)) {
                return Result.success()
            }

            val quotesSnap = repo.sub("quotations").get().await()
            val projectsSnap = repo.sub("projects").get().await()
            val delivsSnap = repo.sub("deliverables").get().await()

            val quotations = quotesSnap.documents.mapNotNull { it.toObject(Quotation::class.java) }
            val projects = projectsSnap.documents.mapNotNull { it.toObject(Project::class.java) }
            val deliverables = delivsSnap.documents.mapNotNull { it.toObject(Deliverable::class.java) }

            PaymentReminderAutomator.evaluateAndDispatch(
                context = applicationContext,
                coreRepo = repo,
                actor = member,
                quotations = quotations,
                projects = projects,
                deliverables = deliverables,
                soundEnabled = AppHapticsAndNotifier.isSoundEnabled(applicationContext)
            )
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "xmax_payment_reminders_periodic"

        fun schedulePeriodic(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<PaymentReminderWorker>(6, TimeUnit.HOURS).build()
                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }
        }
    }
}
