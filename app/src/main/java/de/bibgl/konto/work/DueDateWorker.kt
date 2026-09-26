package de.bibgl.konto.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.bibgl.konto.data.AccountRepository
import de.bibgl.konto.data.Store
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Prueft einmal taeglich im Hintergrund die Rueckgabefristen und erinnert
 * rechtzeitig. Laeuft nur, wenn Zugangsdaten hinterlegt sind und der Nutzer
 * Benachrichtigungen nicht abgeschaltet hat.
 */
class DueDateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        if (!store.notificationsEnabled) return Result.success()

        val profiles = store.profiles
        if (profiles.isEmpty()) return Result.success()

        val repo = AccountRepository(store)
        val today = LocalDate.now().toEpochDay()
        // Bei mehreren Ausweisen gehoert der Kontoname in die Meldung.
        val showProfile = profiles.size > 1
        var anyFailed = false

        profiles.forEach { profile ->
            val account = try {
                repo.load(profile.id)
            } catch (e: Exception) {
                // Netz weg oder Seite gerade kaputt: spaeter erneut versuchen.
                // Die anderen Ausweise werden trotzdem geprueft.
                anyFailed = true
                return@forEach
            }

            if (store.lastNotifiedDay(profile.id) != today) {
                val notified = Notifications.notifyDueSoon(
                    applicationContext, profile, account, store.reminderDays, showProfile,
                )
                if (notified) store.setLastNotifiedDay(profile.id, today)
            }

            // Ausweisablauf nur einmal pro Woche melden - taeglich waere zu aufdringlich.
            if (today - store.lastCardNotifiedDay(profile.id) >= CARD_REMINDER_INTERVAL_DAYS) {
                val notified = Notifications.notifyCardExpiry(
                    applicationContext, profile, account, showProfile,
                )
                if (notified) store.setLastCardNotifiedDay(profile.id, today)
            }
        }

        return if (anyFailed) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "due_date_check"
        private const val CARD_REMINDER_INTERVAL_DAYS = 7

        /** Plant den taeglichen Check auf ca. 9 Uhr morgens. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DueDateWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayToNextMorning(), TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun delayToNextMorning(): Long {
            val now = LocalDateTime.now()
            var target = now.toLocalDate().atTime(LocalTime.of(9, 0))
            if (!target.isAfter(now)) target = target.plusDays(1)
            return Duration.between(now, target).toMinutes().coerceAtLeast(1)
        }
    }
}
