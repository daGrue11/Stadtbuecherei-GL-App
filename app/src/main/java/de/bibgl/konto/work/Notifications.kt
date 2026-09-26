package de.bibgl.konto.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.bibgl.konto.MainActivity
import de.bibgl.konto.R
import de.bibgl.konto.data.Account
import de.bibgl.konto.data.Loan
import de.bibgl.konto.data.Profile
import java.time.LocalDate
import kotlin.math.absoluteValue

object Notifications {

    private const val CHANNEL_DUE = "due_dates"

    // Jedes Profil bekommt eigene Meldungen, damit sie sich nicht gegenseitig
    // ueberschreiben. Die ID leitet sich stabil aus der Profil-ID ab.
    private const val BASE_DUE = 1_000_000
    private const val BASE_CARD = 2_000_000

    private fun notificationId(base: Int, profileId: String): Int =
        base + (profileId.hashCode().absoluteValue % 100_000)

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_DUE,
            "Rückgabe-Erinnerungen",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Erinnert, bevor Medien der Stadtbücherei fällig werden."
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openAppIntent(context: Context, profileId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            profileId.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                // Antippen oeffnet die App direkt beim betroffenen Ausweis.
                .putExtra(MainActivity.EXTRA_PROFILE_ID, profileId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Meldet faellige und ueberfaellige Medien eines Ausweises. Liefert true,
     * wenn etwas gemeldet wurde - der Worker merkt sich dann den Tag.
     *
     * @param showProfile blendet den Kontonamen ein; bei nur einem hinterlegten
     *   Ausweis waere er ueberfluessiges Rauschen.
     */
    fun notifyDueSoon(
        context: Context,
        profile: Profile,
        account: Account,
        reminderDays: Int,
        showProfile: Boolean,
    ): Boolean {
        val today = LocalDate.now()
        val relevant = account.loans
            .filter { (it.daysLeft(today) ?: Long.MAX_VALUE) <= reminderDays }
            .sortedBy { it.daysLeft(today) ?: Long.MAX_VALUE }
        if (relevant.isEmpty() || !canNotify(context)) return false

        val overdue = relevant.filter { it.isOverdue(today) }
        val core = when {
            overdue.isNotEmpty() -> "${overdue.size} ${mediaWord(overdue.size)} überfällig"
            relevant.size == 1 -> "Rückgabe ${dueLabel(relevant[0], today)}"
            else -> "${relevant.size} Medien bald fällig"
        }
        val title = if (showProfile) "${profile.label}: $core" else core

        val lines = relevant.map { loan ->
            val renew = when (loan.renewable) {
                true -> " · verlängerbar"
                false -> " · nicht verlängerbar"
                null -> ""
            }
            "${loan.title} — ${dueLabel(loan, today)}$renew"
        }

        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.take(6).forEach(style::addLine)
        if (lines.size > 6) style.setSummaryText("+ ${lines.size - 6} weitere")

        val notification = NotificationCompat.Builder(context, CHANNEL_DUE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(style)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentIntent(openAppIntent(context, profile.id))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context)
            .notify(notificationId(BASE_DUE, profile.id), notification)
        return true
    }

    /**
     * Warnt, wenn ein Bibliotheksausweis in den naechsten 30 Tagen ablaeuft.
     * Liefert true, wenn gewarnt wurde - der Worker merkt sich dann den Tag.
     */
    fun notifyCardExpiry(
        context: Context,
        profile: Profile,
        account: Account,
        showProfile: Boolean,
    ): Boolean {
        val days = account.cardDaysLeft() ?: return false
        if (days > 30 || !canNotify(context)) return false
        val who = if (showProfile) "Der Ausweis von ${profile.label}" else "Dein Bibliotheksausweis"
        val text = if (days < 0) {
            "$who ist seit ${-days} ${dayWord(-days)} abgelaufen."
        } else {
            "$who läuft in $days ${dayWord(days)} ab (${account.cardValidUntilRaw})."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_DUE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (showProfile) "${profile.label}: Ausweis läuft ab" else "Ausweis läuft ab")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentIntent(openAppIntent(context, profile.id))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context)
            .notify(notificationId(BASE_CARD, profile.id), notification)
        return true
    }

    private fun dueLabel(loan: Loan, today: LocalDate): String {
        val d = loan.daysLeft(today) ?: return "ohne Frist"
        return when {
            d == 0L -> "heute fällig"
            d == 1L -> "morgen fällig"
            d > 1L -> "in $d Tagen"
            d == -1L -> "seit gestern überfällig"
            else -> "seit ${-d} Tagen überfällig"
        }
    }

    private fun dayWord(n: Long) = if (n == 1L) "Tag" else "Tagen"

    private fun mediaWord(n: Int) = if (n == 1) "Medium" else "Medien"
}
