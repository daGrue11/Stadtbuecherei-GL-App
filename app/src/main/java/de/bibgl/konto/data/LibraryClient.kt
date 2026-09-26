package de.bibgl.konto.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.TimeUnit

class LibraryException(message: String) : IOException(message)

/**
 * Liest das Benutzerkonto der Stadtbuecherei Bergisch Gladbach aus.
 *
 * Die Seite laeuft auf OCLC OPEN / DotNetNuke (ASP.NET WebForms). Es gibt keine
 * offizielle API, deshalb:
 *  - Login = WebForms-Postback auf der /Login-Seite,
 *  - das gesamte Konto (Ausleihen, Vormerkungen, Gebuehren, Merkliste, Stammdaten)
 *    steht danach in EINER Seite /Mein-Konto,
 *  - nur der Verlaengerbar-Status wird per AJAX nachgeladen; dafuer gibt es einen
 *    sauberen JSON-Endpunkt (IsCatalogueCopyExtendable),
 *  - Verlaengern selbst ist wieder ein Postback.
 *
 * Eine Instanz haelt eine Sitzung (Cookies + zuletzt geladene Seite mit ihrem
 * VIEWSTATE). Postbacks brauchen immer das VIEWSTATE der zuletzt gesehenen Seite.
 */
class LibraryClient {

    companion object {
        const val BASE = "https://www.stadtbuecherei-gl.de"
        const val LOGIN_URL = "$BASE/Login?returnurl=%2f&focusModule=login"
        const val ACCOUNT_URL = "$BASE/Mein-Konto"

        /** So viele Merklisten-Eintraege liefert eine Seite; ohne Angabe sind es nur 10. */
        const val WATCHLIST_PAGE_SIZE = 25
        private const val ACCOUNT_PAGE_URL = "$ACCOUNT_URL?page=1&pagesize=$WATCHLIST_PAGE_SIZE"

        private const val EXTENDABLE_URL = BASE +
            "/DesktopModules/OCLC.OPEN.PL.DNN.PatronAccountModule/PatronAccountService.asmx/IsCatalogueCopyExtendable"
        private const val RESX =
            "~/DesktopModules/OCLC.OPEN.PL.DNN.PatronAccountModule/App_LocalResources/PatronAccountModule.resx"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"

        // Feldnamen des Login-Formulars (DNN-Namenskonvention mit $-Trennern).
        private const val F_USER = "dnn\$ctr\$Login\$Login_COP\$txtUsername"
        private const val F_PASS = "dnn\$ctr\$Login\$Login_COP\$txtPassword"
        private const val F_SUBMIT = "dnn\$ctr\$Login\$Login_COP\$cmdLogin"

        // Attributwert in Anfuehrungszeichen, weil er selbst ein '$' enthaelt.
        private const val SEL_COPY_ID = "input[name\$='\$CopyId']"

        private val JSON = "application/json;charset=utf-8".toMediaType()
        private val GERMAN_DATE_RE = Regex("""\d{2}\.\d{2}\.\d{4}""")
        private val POSTBACK_RE = Regex("""__doPostBack\('([^']+)'""")
    }

    private val cookieJar = object : CookieJar {
        private val store = mutableMapOf<String, Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(store) { cookies.forEach { store[it.name] = it } }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            synchronized(store) { store.values.filter { it.matches(url) } }
    }

    private val http = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Zuletzt geladene Kontoseite - Quelle fuer VIEWSTATE und Postback-Ziele. */
    private var lastDoc: Document? = null
    private var lastUrl: String = ACCOUNT_URL

    // ------------------------------------------------------------------ HTTP

    private fun get(url: String): Document {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "de-DE,de;q=0.9")
            .build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw LibraryException("Server antwortete mit ${res.code}")
            lastUrl = res.request.url.toString()
            return Jsoup.parse(body, lastUrl)
        }
    }

    private fun postForm(url: String, fields: Map<String, String>, referer: String): Document {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(url).post(form)
            .header("User-Agent", UA)
            .header("Accept-Language", "de-DE,de;q=0.9")
            .header("Referer", referer)
            .build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw LibraryException("Server antwortete mit ${res.code}")
            lastUrl = res.request.url.toString()
            return Jsoup.parse(body, lastUrl)
        }
    }

    /** Alle Hidden-Felder der Seite - enthaelt __VIEWSTATE und __EVENTVALIDATION. */
    private fun hiddenFields(doc: Document): MutableMap<String, String> {
        val map = LinkedHashMap<String, String>()
        doc.select("input[type=hidden]").forEach { input ->
            val name = input.attr("name")
            if (name.isNotEmpty()) map[name] = input.attr("value")
        }
        return map
    }

    private fun formAction(doc: Document): String =
        doc.selectFirst("form")?.absUrl("action")?.ifEmpty { null } ?: lastUrl

    // ----------------------------------------------------------------- Login

    /** Meldet an und liefert das vollstaendig gefuellte Konto zurueck. */
    suspend fun login(user: String, password: String): Account = withContext(Dispatchers.IO) {
        val loginPage = get(LOGIN_URL)
        val fields = hiddenFields(loginPage)
        fields["__EVENTTARGET"] = F_SUBMIT
        fields["__EVENTARGUMENT"] = ""
        fields[F_USER] = user
        fields[F_PASS] = password

        val result = postForm(formAction(loginPage), fields, LOGIN_URL)
        if (!isLoggedIn(result)) throw LibraryException(loginError(result))
        // Die Seite nach dem Login zeigt nur 10 Merklisten-Eintraege - einmal mit
        // groesserer Seitengroesse nachladen, notfalls mit dem Login-Ergebnis weiter.
        val doc = runCatching { get(ACCOUNT_PAGE_URL) }.getOrNull()?.takeIf { isLoggedIn(it) }
            ?: result
        lastDoc = doc
        buildAccount(doc)
    }

    private fun isLoggedIn(doc: Document): Boolean =
        doc.selectFirst("a[href*=Logoff]") != null || doc.selectFirst("div[id\$=tpnlLoans]") != null

    private fun loginError(doc: Document): String =
        doc.select(".dnnFormMessage, .dnnFormValidationSummary, span[id*=lblMessage]")
            .map { it.text().trim() }
            .firstOrNull { it.isNotEmpty() && !it.contains("Keine Daten vorhanden") }
            ?: "Anmeldung fehlgeschlagen. Ausweisnummer oder Passwort falsch?"

    /** Laedt die Kontoseite neu; die Sitzung muss noch bestehen. */
    suspend fun refresh(): Account = withContext(Dispatchers.IO) {
        val doc = get(ACCOUNT_PAGE_URL)
        if (!isLoggedIn(doc)) throw LibraryException("Sitzung abgelaufen")
        lastDoc = doc
        buildAccount(doc)
    }

    // ------------------------------------------------------------ Verlaengern

    /**
     * Verlaengert ein einzelnes Medium.
     *
     * @param allowFees erlaubt die Verlaengerung auch dann, wenn der
     *   Bestaetigungsdialog Gebuehren ankuendigt. Ohne das Flag bricht die
     *   Methode in dem Fall ab und meldet [RenewResult.needsConfirmation].
     */
    suspend fun renew(copyId: String, allowFees: Boolean = false): RenewResult =
        withContext(Dispatchers.IO) {
            val doc = lastDoc ?: throw LibraryException("Keine aktive Sitzung")
            val row = loanRows(doc).firstOrNull { copyIdOf(it) == copyId }
                ?: throw LibraryException("Medium ist nicht mehr im Konto")
            val target = extendTargetOf(row)
                ?: throw LibraryException("Für dieses Medium gibt es keinen Verlängern-Button")

            val before = parseLoans(doc)
                .filter { it.copyId == copyId }
                .associate { it.copyId to it.dueDateRaw }

            val fields = hiddenFields(doc)
            fields["__EVENTTARGET"] = target
            fields["__EVENTARGUMENT"] = ""
            runExtension(postForm(formAction(doc), fields, lastUrl), listOf(copyId), before, allowFees)
        }

    /** Verlaengert mehrere Medien in einem Rutsch ueber den Sammel-Button der Seite. */
    suspend fun renewAll(copyIds: List<String>, allowFees: Boolean = false): RenewResult =
        withContext(Dispatchers.IO) {
            if (copyIds.isEmpty()) return@withContext RenewResult(false, "Nichts ausgewählt", null)
            val doc = lastDoc ?: throw LibraryException("Keine aktive Sitzung")
            val button = doc.selectFirst("input[id\$=BtnExtendMediums]")
                ?: throw LibraryException("Sammel-Verlängerung nicht verfügbar")

            val before = parseLoans(doc).associate { it.copyId to it.dueDateRaw }
            val fields = hiddenFields(doc)
            fields["__EVENTTARGET"] = ""
            fields["__EVENTARGUMENT"] = ""
            // Checkboxen der gewaehlten Zeilen mitschicken; WebForms erwartet "on".
            loanRows(doc).forEach { row ->
                if (copyIdOf(row) in copyIds) {
                    val name = row.selectFirst("input[name\$=chkSelect]")?.attr("name")
                    if (!name.isNullOrEmpty()) fields[name] = "on"
                }
            }
            // Ein Submit-Button postet seinen Namen und Wert mit.
            fields[button.attr("name")] = button.attr("value")

            runExtension(postForm(formAction(doc), fields, lastUrl), copyIds, before, allowFees)
        }

    /**
     * Zweiter Schritt der Verlaengerung.
     *
     * Der Klick auf "Verlängern" verlaengert noch nichts, sondern blendet den
     * Dialog "Verlängerung bestätigen" ein, der die anfallenden Verlaengerungs-
     * und Saeumnisgebuehren nennt. Erst der Postback seines Buttons
     * "Verlängerung durchführen" fuehrt sie aus.
     */
    private fun runExtension(
        afterClick: Document,
        copyIds: List<String>,
        before: Map<String, String>,
        allowFees: Boolean,
    ): RenewResult {
        if (!isLoggedIn(afterClick)) throw LibraryException("Sitzung abgelaufen")
        val popup = visibleExtensionPopup(afterClick)
        val confirm = popup?.selectFirst("input[id\$=btnDefault]")

        // Kein Dialog: entweder direkt verlaengert oder abgelehnt - Fristen entscheiden.
        if (popup == null || confirm == null) {
            return finishRenew(afterClick, copyIds, before)
        }

        val feeText = textIn(popup, "FeeTotalData")
        if (parseEuroAmount(feeText) > 0.0 && !allowFees) {
            // Dialog wieder schliessen, damit die Sitzung nicht in ihm haengen bleibt.
            popup.selectFirst("input[id\$=btnCancel]")?.let { cancel ->
                val fields = hiddenFields(afterClick)
                fields[cancel.attr("name")] = cancel.attr("value")
                runCatching { lastDoc = postForm(formAction(afterClick), fields, lastUrl) }
            }
            return RenewResult(
                success = false,
                message = "Diese Verlängerung kostet ${feeText.orEmpty()}",
                account = null,
                needsConfirmation = true,
                feeText = feeText,
                copyIds = copyIds,
            )
        }

        val fields = hiddenFields(afterClick)
        fields[confirm.attr("name")] = confirm.attr("value")
        return finishRenew(postForm(formAction(afterClick), fields, lastUrl), copyIds, before)
    }

    /**
     * Der Bestaetigungsdialog steckt immer im HTML, im Normalfall aber mit
     * "display: none". Nur ein eingeblendeter Dialog erwartet eine Antwort.
     *
     * Der Container heisst "..._loansExtensionPopup_popup" - auf
     * "loansExtensionPopup" endet KEINE ID der Seite.
     */
    private fun visibleExtensionPopup(doc: Document): Element? {
        val popup = doc.selectFirst("div[id\$=loansExtensionPopup_popup]") ?: return null
        val hiddenByStyle = popup.attr("style")
            .replace(" ", "")
            .contains("display:none", ignoreCase = true)
        val shown = popup.hasClass("oclc-in-module-popup") || !hiddenByStyle
        return if (shown) popup else null
    }

    /**
     * Wertet die Antwort nach einem Verlaengerungs-Postback aus. Massgeblich ist,
     * ob sich die Frist tatsaechlich verschoben hat - die Meldungstexte der Seite
     * sind dafuer nicht zuverlaessig genug.
     */
    private fun finishRenew(
        after: Document,
        copyIds: List<String>,
        before: Map<String, String>,
    ): RenewResult {
        if (!isLoggedIn(after)) throw LibraryException("Sitzung abgelaufen")
        lastDoc = after
        val account = buildAccount(after)
        val changed = account.loans.filter {
            it.copyId in copyIds && before[it.copyId].orEmpty() != it.dueDateRaw
        }
        val serverMessage = after.select(".dnnFormMessage, .oclc-module-message")
            .map { it.text().trim() }
            .firstOrNull { it.isNotEmpty() && !it.contains("Keine Daten vorhanden") }

        return when (changed.size) {
            0 -> RenewResult(false, serverMessage ?: "Verlängerung nicht möglich", account)
            1 -> RenewResult(true, "Verlängert bis ${changed[0].dueDateRaw}", account)
            else -> RenewResult(true, "${changed.size} Medien verlängert", account)
        }
    }

    // -------------------------------------------------------------- Merkliste

    /**
     * Entfernt einen Eintrag von der Merkliste ueber den Postback seines Links.
     * Erfolg heisst: der Eintrag ist danach wirklich nicht mehr auf der Liste.
     */
    suspend fun removeFromWatchlist(mediaId: String): Account = withContext(Dispatchers.IO) {
        val doc = lastDoc ?: throw LibraryException("Keine aktive Sitzung")
        val target = parseWatchlist(doc).firstOrNull { it.mediaId == mediaId }?.removeTarget
            ?: throw LibraryException("Titel ist nicht mehr auf der Merkliste")

        val fields = hiddenFields(doc)
        fields["__EVENTTARGET"] = target
        fields["__EVENTARGUMENT"] = ""
        val after = postForm(formAction(doc), fields, lastUrl)
        if (!isLoggedIn(after)) throw LibraryException("Sitzung abgelaufen")

        // Die Antwort auf den Postback kann wieder nur 10 Eintraege zeigen; ein
        // Eintrag weiter hinten saehe dann faelschlich entfernt aus. Daher neu laden.
        val reloaded = get(ACCOUNT_PAGE_URL)
        if (!isLoggedIn(reloaded)) throw LibraryException("Sitzung abgelaufen")
        lastDoc = reloaded

        val account = buildAccount(reloaded)
        if (account.watchlist.any { it.mediaId == mediaId }) {
            throw LibraryException("Entfernen von der Merkliste hat nicht geklappt")
        }
        account
    }

    // -------------------------------------------- Verlaengerbarkeit (JSON-API)

    /**
     * Fragt fuer alle Ausleihen ab, ob sie verlaengerbar sind. Die Kontoseite
     * liefert diesen Status nicht im HTML mit, sondern laedt ihn per AJAX nach.
     */
    private fun fetchRenewability(doc: Document, loans: List<Loan>): List<Loan> {
        if (loans.isEmpty()) return loans
        val patronRndId = doc.selectFirst("[id\$=DivPatronAccountView] [name\$=PatronRndId]")
            ?.attr("value").orEmpty()
        val culture = doc.selectFirst("input[name\$=Culture]")?.attr("value")
            ?.ifEmpty { null } ?: "de-DE"

        val payload = JSONObject()
            .put("portalId", "0")
            .put("userName", patronRndId)
            .put("copyIds", loans.joinToString(",") { it.copyId })
            .put("culture", culture)
            .put("localResourceFile", RESX)
            .toString()

        val req = Request.Builder().url(EXTENDABLE_URL)
            .post(payload.toRequestBody(JSON))
            .header("User-Agent", UA)
            .header("Referer", lastUrl)
            .build()

        val byCopyId: Map<String, JSONObject> = try {
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return loans
                val arr = JSONObject(res.body?.string().orEmpty()).getJSONArray("d")
                (0 until arr.length())
                    .map { arr.getJSONObject(it) }
                    .associateBy { it.getString("CopyId") }
            }
        } catch (e: Exception) {
            // Ohne diesen Status bleibt die App nutzbar: Ausleihen und Fristen
            // stehen, nur der Verlaengern-Button ist dann nicht vorab bewertet.
            return loans
        }

        return loans.map { loan ->
            val o = byCopyId[loan.copyId] ?: return@map loan
            loan.copy(
                renewable = o.optBoolean("IsExtendable", false),
                renewNote = o.optString("StatusMessages").trim().ifEmpty { null },
                // "Verlängern auf den 01.11.2026" -> "01.11.2026".
                // Bei nicht verlaengerbaren Medien liefert der Server den
                // Platzhalter 01.01.1800, den parseGermanDate() verwirft.
                renewToRaw = GERMAN_DATE_RE.find(o.optString("ExtendText"))?.value
                    ?.takeIf { parseGermanDate(it) != null },
            )
        }
    }

    // --------------------------------------------------------------- Parsing

    private fun buildAccount(doc: Document): Account {
        // Primaer ueber die stabilen Element-IDs, ersatzweise ueber die Beschriftungen.
        val patronPanel = doc.selectFirst("div[id\$=tpnlPatronAccount]")
        val patron = parsePatron(doc)
        return Account(
            patronName = textIn(patronPanel, "LblNameData") ?: patron["Name, Vorname"].orEmpty(),
            cardNumber = textIn(patronPanel, "LblMembershipNumberData")
                ?: patron["Ausweisnummer"].orEmpty(),
            cardValidUntilRaw = textIn(patronPanel, "LblMembershipValidUntilData")
                ?: patron["Ausweis gültig bis"].orEmpty(),
            email = textIn(patronPanel, "LblEmailAddressData") ?: patron["E-Mail"].orEmpty(),
            loans = fetchRenewability(doc, parseLoans(doc)),
            reservations = parseTable(doc, "table[id\$=grdViewReservations]"),
            readyForPickup = parseTable(doc, "table[id\$=grdViewReadyForPickups]"),
            watchlist = parseWatchlist(doc),
            watchlistTotal = watchlistTotal(doc),
            fees = parseFees(doc),
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun loanRows(doc: Document): List<Element> =
        doc.select("table[id\$=grdViewLoans] tr")
            .filter { it.selectFirst(SEL_COPY_ID) != null }

    private fun copyIdOf(row: Element): String =
        row.selectFirst(SEL_COPY_ID)?.attr("value").orEmpty()

    private fun extendTargetOf(row: Element): String? {
        val href = row.selectFirst("a.oclc-patronaccountmodule-extendThis")?.attr("href").orEmpty()
        return POSTBACK_RE.find(href)?.groupValues?.get(1)
    }

    private fun parseLoans(doc: Document): List<Loan> = loanRows(doc).map { row ->
        // children() eines <tr> sind genau die <td> - robuster als ein CSS-Kombinator.
        val cells = row.children()
        val titleLink = row.selectFirst("a[id*=lnkTitle]")
        Loan(
            copyId = copyIdOf(row),
            title = titleLink?.text()?.trim()?.ifEmpty { null } ?: cellText(cells.getOrNull(2)),
            author = cellText(cells.getOrNull(3)),
            mediaType = cellText(cells.getOrNull(4)),
            branch = cellText(cells.getOrNull(5)),
            dueDateRaw = cellText(cells.getOrNull(6)),
            coverUrl = coverOf(row),
            detailUrl = titleLink?.absUrl("href")?.ifEmpty { null },
            extendTarget = extendTargetOf(row),
        )
    }

    /**
     * Zelleninhalt ohne die nur auf Schmalbildschirmen sichtbaren Label-Spans
     * ("Verfasser:", "Aktuelle Frist:") und ohne Screenreader-Texte.
     */
    private fun cellText(cell: Element?): String {
        if (cell == null) return ""
        val copy = cell.clone()
        copy.select("span.oclc-module-label, span.sr-only, .oclc-screen-reader-only").remove()
        return copy.text().trim()
    }

    /**
     * Cover-URLs stecken im Attribut data-sources, Aufbau:
     * "SetSimpleCover|a|<bild>|a|<shoplink>|c|SetSimpleCover|a|<bild2>|a|..."
     * Gesucht ist die erste echte Bild-URL, also jeweils Index 1 eines Abschnitts.
     */
    private fun coverOf(row: Element): String? {
        val sources = row.selectFirst("img.coverSmall")?.attr("data-sources").orEmpty()
        if (sources.isEmpty()) return null
        return sources.split("|c|")
            .mapNotNull { it.split("|a|").getOrNull(1) }
            .firstOrNull { it.startsWith("http") && !it.contains("emptyURL") }
    }

    private fun parseTable(doc: Document, selector: String): Table {
        val table = doc.selectFirst(selector) ?: return Table()
        if (table.text().contains("Keine Daten vorhanden")) return Table()
        val rows = table.select("tr")
        if (rows.isEmpty()) return Table()
        val headers = rows[0].select("th, td").map { cellText(it) }
        val data = rows.drop(1)
            .map { tr -> tr.children().map { cellText(it) } }
            .filter { row -> row.any { it.isNotBlank() } }
        return Table(headers, data)
    }

    /**
     * Liest ein Feld ueber die Endung seiner Element-ID. Die OPEN-Software vergibt
     * sprechende, stabile IDs (z.B. "..._lblFeeTotalData"), waehrend die Praefixe
     * (Modulnummer) sich aendern koennen. jsoup vergleicht Attributwerte
     * gross-/kleinschreibungsunabhaengig, "Lbl" und "lbl" sind also beide getroffen.
     *
     * Die Suche MUSS auf das jeweilige Panel eingegrenzt werden: die Seite enthaelt
     * ausgeblendete Dialoge (Daten aendern, Verlaengerungs-Popup) mit genau denselben
     * ID-Endungen, die leer sind und im Dokument weiter vorne stehen.
     */
    private fun textIn(root: Element?, idSuffix: String): String? =
        root?.selectFirst("[id\$=$idSuffix]")?.text()?.trim()?.ifEmpty { null }

    private fun parseFees(doc: Document): Fees {
        val panel = doc.selectFirst("div[id\$=tpnlFees]") ?: return Fees()
        return Fees(
            open = textIn(panel, "lblFeeTotalData") ?: "0,00 EUR",
            paid = textIn(panel, "lblDepositData") ?: "0,00 EUR",
            balance = textIn(panel, "lblTotalSaldoData") ?: "0,00 EUR",
            table = parseTable(doc, "table[id\$=grdViewFees]"),
        )
    }

    /**
     * Stammdaten stehen im Tab "Persönliche Daten" als Paare aus Label-Span
     * (Klasse oclc-module-label) und direkt folgendem Wert-Span.
     */
    private fun parsePatron(doc: Document): Map<String, String> {
        val panel = doc.selectFirst("div[id\$=tpnlPatronAccount]") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        panel.select("span.oclc-module-label").forEach { label ->
            val valueSpan = label.nextElementSibling() ?: return@forEach
            if (!valueSpan.tagName().equals("span", ignoreCase = true)) return@forEach
            val key = label.text().trim().removeSuffix(":").trim()
            val value = valueSpan.text().trim()
            if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
        }
        return out
    }

    /** Gesamtzahl laut Reiter "Merkliste" - auch Eintraege, die nicht auf der Seite stehen. */
    private fun watchlistTotal(doc: Document): Int? =
        doc.selectFirst("span[id\$=tpnlWatchList_l] .DnnTabFlag")?.text()?.trim()?.toIntOrNull()

    /**
     * Jeder Eintrag steckt in einem "..._mdv<Mediennummer>_divMedium" mit Titel-Link,
     * verstecktem Feld "mednr" und dem Postback-Link "von der Merkliste entfernen".
     * Gelesen wird nur die erste Seite der Merkliste ([WATCHLIST_PAGE_SIZE] Eintraege).
     */
    private fun parseWatchlist(doc: Document): List<WatchItem> {
        val panel = doc.selectFirst("div[id\$=tpnlWatchList]") ?: return emptyList()
        val entries = panel.select("div[id\$=_divMedium]").mapNotNull { entry ->
            val titleLink = entry.selectFirst("a[id\$=LbtnShortDescriptionValue]")
                ?: return@mapNotNull null
            val title = titleLink.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            WatchItem(
                title = title,
                url = titleLink.absUrl("href").ifEmpty { null },
                mediaId = entry.selectFirst("input[name\$='\$mednr']")?.attr("value").orEmpty(),
                removeTarget = entry.selectFirst("a[id\$=LbtnWatchlist]")?.attr("href")
                    ?.let { POSTBACK_RE.find(it)?.groupValues?.get(1) },
            )
        }
        if (entries.isNotEmpty()) return entries

        // Rueckfall, falls sich der Aufbau aendert: wenigstens Titel und Links zeigen.
        return panel.select("a[href*=Mediensuche]")
            .mapNotNull { a ->
                val t = a.text().trim()
                if (t.isEmpty()) null else WatchItem(t, a.absUrl("href").ifEmpty { null })
            }
            .distinctBy { it.url ?: it.title }
    }
}
