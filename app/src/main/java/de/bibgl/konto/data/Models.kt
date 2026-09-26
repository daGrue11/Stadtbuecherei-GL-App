package de.bibgl.konto.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val GERMAN_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Wandelt "08.10.2026" in ein LocalDate; gibt null bei leeren/kaputten Werten zurueck. */
fun parseGermanDate(raw: String?): LocalDate? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return null
    return runCatching { LocalDate.parse(s, GERMAN_DATE) }.getOrNull()
        // Der Server liefert 01.01.1800 als Platzhalter fuer "kein Datum".
        ?.takeIf { it.year > 1900 }
}

@Serializable
data class Loan(
    val copyId: String,
    val title: String,
    val author: String = "",
    val mediaType: String = "",
    val branch: String = "",
    val dueDateRaw: String = "",
    val coverUrl: String? = null,
    val detailUrl: String? = null,
    /** WebForms-Postback-Ziel des "Verlaengern"-Links dieser Zeile. */
    val extendTarget: String? = null,
    /** Kommt asynchron vom IsCatalogueCopyExtendable-Service; null = noch unbekannt. */
    val renewable: Boolean? = null,
    /** Begruendung des Servers, z.B. "Die maximale Anzahl der Verlaengerungen ist erreicht." */
    val renewNote: String? = null,
    /** Neues Frist-Datum bei Verlaengerung, roh, z.B. "01.11.2026". */
    val renewToRaw: String? = null,
) {
    val dueDate: LocalDate? get() = parseGermanDate(dueDateRaw)
    val renewToDate: LocalDate? get() = parseGermanDate(renewToRaw)

    /** Verbleibende Tage bis zur Frist. Negativ = ueberfaellig. Null = kein Datum. */
    fun daysLeft(today: LocalDate = LocalDate.now()): Long? =
        dueDate?.let { ChronoUnit.DAYS.between(today, it) }

    fun isOverdue(today: LocalDate = LocalDate.now()): Boolean =
        (daysLeft(today) ?: Long.MAX_VALUE) < 0
}

/** Generische Tabelle — fuer Vormerkungen, Abholbereit und Gebuehren, deren
 *  Spalten je nach Bestand variieren. */
@Serializable
data class Table(
    val headers: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
) {
    val isEmpty: Boolean get() = rows.isEmpty()
    fun asMaps(): List<Map<String, String>> = rows.map { row ->
        headers.zip(row).filter { (h, v) -> h.isNotBlank() && v.isNotBlank() }.toMap()
    }
}

/** Deutscher Geldbetrag als Zahl; "1.234,56 EUR" -> 1234.56, null/leer -> 0.0 */
fun parseEuroAmount(raw: String?): Double =
    Regex("""-?[\d.]*\d,\d{2}""").find(raw.orEmpty())
        ?.value?.replace(".", "")?.replace(",", ".")?.toDoubleOrNull() ?: 0.0

@Serializable
data class Fees(
    val open: String = "0,00 EUR",
    val paid: String = "0,00 EUR",
    val balance: String = "0,00 EUR",
    val table: Table = Table(),
) {
    val openAmount: Double get() = parseEuroAmount(open)
    val hasOpenFees: Boolean get() = openAmount > 0.0
}

@Serializable
data class WatchItem(
    val title: String,
    val url: String? = null,
    /** Mediennummer des Katalogs, z.B. "9222468" - eindeutig, anders als der Titel. */
    val mediaId: String = "",
    /** WebForms-Postback-Ziel des Links "von der Merkliste entfernen". */
    val removeTarget: String? = null,
)

/**
 * Ein hinterlegter Bibliotheksausweis (eigener, Kinder, Partner...).
 *
 * [id] ist intern und stabil; [label] ist frei umbenennbar und wird im
 * Konto-Umschalter angezeigt.
 */
@Serializable
data class Profile(
    val id: String,
    val label: String,
)

@Serializable
data class Account(
    val patronName: String = "",
    val cardNumber: String = "",
    val cardValidUntilRaw: String = "",
    val email: String = "",
    val loans: List<Loan> = emptyList(),
    val reservations: Table = Table(),
    val readyForPickup: Table = Table(),
    val watchlist: List<WatchItem> = emptyList(),
    /** Gesamtzahl der Merkliste; kann groesser sein als [watchlist]. Null = unbekannt. */
    val watchlistTotal: Int? = null,
    val fees: Fees = Fees(),
    /** Zeitpunkt des Abrufs, Millis seit Epoch. */
    val fetchedAt: Long = 0L,
) {
    val cardValidUntil: LocalDate? get() = parseGermanDate(cardValidUntilRaw)

    fun cardDaysLeft(today: LocalDate = LocalDate.now()): Long? =
        cardValidUntil?.let { ChronoUnit.DAYS.between(today, it) }

    /** Ausleihen, sortiert nach Faelligkeit — dringendste zuerst, ohne Frist ans Ende. */
    val loansByDueDate: List<Loan>
        get() = loans.sortedWith(compareBy(nullsLast<LocalDate>()) { it.dueDate })

    val renewableLoans: List<Loan> get() = loans.filter { it.renewable == true }
}

/**
 * Ergebnis eines Verlaengerungs-Versuchs.
 *
 * Die Bibliotheksseite bestaetigt Verlaengerungen in einem zweiten Schritt und
 * nennt dort anfallende Verlaengerungs- und Saeumnisgebuehren. Fallen welche an,
 * bricht die App ab und fragt zuerst nach: [needsConfirmation] ist dann true und
 * [feeText] enthaelt den Betrag.
 */
data class RenewResult(
    val success: Boolean,
    val message: String,
    val account: Account?,
    val needsConfirmation: Boolean = false,
    val feeText: String? = null,
    /** Medien, um die es ging - fuer die Rueckfrage nach den Gebuehren. */
    val copyIds: List<String> = emptyList(),
)
