package de.bibgl.konto.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Euro
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Toys
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.bibgl.konto.data.Account
import de.bibgl.konto.data.Loan
import de.bibgl.konto.data.Table
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onRenew: (String) -> Unit,
    onRenewAll: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogoutAll: () -> Unit,
    onMessageShown: () -> Unit,
    onConfirmFees: () -> Unit,
    onDismissFees: () -> Unit,
    onSwitchProfile: (String) -> Unit,
    onAddProfile: () -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onRemoveProfile: (String) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var switcherOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var logoutAll by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val active = state.activeProfile

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }

    // Die Bibliothek verlangt fuer manche Verlaengerungen Gebuehren. Die werden
    // erst im Bestaetigungsdialog der Website sichtbar - also hier nachfragen,
    // bevor die App sie im Namen des Nutzers akzeptiert.
    val pendingFees = state.feeConfirmation
    if (pendingFees != null) {
        AlertDialog(
            onDismissRequest = onDismissFees,
            title = { Text("Verlängerung kostet Gebühren") },
            text = {
                Text(
                    if (pendingFees.bulk) {
                        "Für diese ${pendingFees.copyIds.size} Medien fallen " +
                            "${pendingFees.feeText} an Verlängerungs- und Säumnisgebühren an. " +
                            "Trotzdem verlängern?"
                    } else {
                        "Für diese Verlängerung fallen ${pendingFees.feeText} an " +
                            "Verlängerungs- und Säumnisgebühren an. Trotzdem verlängern?"
                    }
                )
            },
            confirmButton = { TextButton(onClick = onConfirmFees) { Text("Verlängern") } },
            dismissButton = { TextButton(onClick = onDismissFees) { Text("Abbrechen") } },
        )
    }

    if (renaming && active != null) {
        RenameDialog(
            current = active.label,
            onDismiss = { renaming = false },
            onConfirm = { newLabel ->
                renaming = false
                onRenameProfile(active.id, newLabel)
            },
        )
    }

    if (removing && active != null) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Konto entfernen") },
            text = {
                Text(
                    "\"${active.label}\" wird aus der App entfernt und die gespeicherten " +
                        "Zugangsdaten werden gelöscht. Das Bibliothekskonto selbst bleibt " +
                        "unverändert."
                )
            },
            confirmButton = {
                TextButton(onClick = { removing = false; onRemoveProfile(active.id) }) {
                    Text("Entfernen")
                }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Abbrechen") } },
        )
    }

    if (logoutAll) {
        AlertDialog(
            onDismissRequest = { logoutAll = false },
            title = { Text("Alle Konten abmelden") },
            text = {
                Text(
                    "Alle ${state.profiles.size} hinterlegten Ausweise werden entfernt " +
                        "und ihre Zugangsdaten gelöscht."
                )
            },
            confirmButton = {
                TextButton(onClick = { logoutAll = false; onLogoutAll() }) { Text("Abmelden") }
            },
            dismissButton = { TextButton(onClick = { logoutAll = false }) { Text("Abbrechen") } },
        )
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        ProfileSwitcherTitle(
                            label = active?.label ?: "Mein Konto",
                            onClick = { switcherOpen = true },
                        )
                        ProfileSwitcherMenu(
                            expanded = switcherOpen,
                            state = state,
                            onDismiss = { switcherOpen = false },
                            onSwitch = { id -> switcherOpen = false; onSwitchProfile(id) },
                            onAdd = { switcherOpen = false; onAddProfile() },
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menü")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Einstellungen") },
                                leadingIcon = { Icon(Icons.Outlined.Schedule, null) },
                                onClick = { menuOpen = false; onOpenSettings() },
                            )
                            DropdownMenuItem(
                                text = { Text("Konto hinzufügen") },
                                leadingIcon = { Icon(Icons.Outlined.PersonAdd, null) },
                                onClick = { menuOpen = false; onAddProfile() },
                            )
                            if (active != null) {
                                DropdownMenuItem(
                                    text = { Text("Konto umbenennen") },
                                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                                    onClick = { menuOpen = false; renaming = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Dieses Konto entfernen") },
                                    leadingIcon = { Icon(Icons.Outlined.PersonRemove, null) },
                                    onClick = { menuOpen = false; removing = true },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Über die App") },
                                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                                onClick = { menuOpen = false; showAbout = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Alle Konten abmelden") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                                onClick = { menuOpen = false; logoutAll = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val account = state.account
            if (account == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.loading) CircularProgressIndicator()
                    else Text(state.error ?: "Keine Daten")
                }
            } else {
                AccountContent(
                    account = account,
                    state = state,
                    onRenew = onRenew,
                    onRenewAll = onRenewAll,
                )
            }
        }
    }
}

@Composable
private fun AccountContent(
    account: Account,
    state: UiState,
    onRenew: (String) -> Unit,
    onRenewAll: () -> Unit,
) {
    val today = LocalDate.now()
    val loans = account.loansByDueDate

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val error = state.error
        if (error != null) {
            item { ErrorBanner(error) }
        }

        item { SummaryCard(account, today) }

        if (loans.isNotEmpty()) {
            item {
                SectionHeader(
                    icon = { Icon(Icons.Outlined.MenuBook, null) },
                    title = "Ausgeliehen",
                    count = loans.size,
                )
            }
            items(loans, key = { it.copyId }) { loan ->
                LoanCard(
                    loan = loan,
                    today = today,
                    busy = loan.copyId in state.renewing,
                    onRenew = { onRenew(loan.copyId) },
                )
            }
            val renewable = account.renewableLoans
            if (renewable.size > 1) {
                item {
                    FilledTonalButton(
                        onClick = onRenewAll,
                        enabled = state.renewing.isEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Autorenew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Alle ${renewable.size} verlängerbaren verlängern")
                    }
                }
            }
        } else {
            item { EmptyCard("Nichts ausgeliehen") }
        }

        if (!account.readyForPickup.isEmpty) {
            item {
                SectionHeader(
                    icon = { Icon(Icons.Outlined.Inventory2, null) },
                    title = "Abholbereit",
                    count = account.readyForPickup.rows.size,
                )
            }
            item { TableCard(account.readyForPickup) }
        }

        if (!account.reservations.isEmpty) {
            item {
                SectionHeader(
                    icon = { Icon(Icons.Outlined.Schedule, null) },
                    title = "Vorgemerkt",
                    count = account.reservations.rows.size,
                )
            }
            item { TableCard(account.reservations) }
        }

        item {
            SectionHeader(
                icon = { Icon(Icons.Outlined.Euro, null) },
                title = "Gebühren",
            )
        }
        item { FeesCard(account) }

        if (account.watchlist.isNotEmpty()) {
            item { WatchlistCard(account.watchlist.map { it.title to it.url }) }
        }

        item { FooterNote(account) }
    }
}

// ------------------------------------------------------- Konto-Umschalter

@Composable
private fun ProfileSwitcherTitle(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            Icons.Default.ArrowDropDown,
            contentDescription = "Konto wechseln",
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * Liste der hinterlegten Ausweise. Zu jedem steht der zuletzt geladene Stand,
 * damit man ohne Umschalten sieht, wo etwas ansteht.
 */
@Composable
private fun ProfileSwitcherMenu(
    expanded: Boolean,
    state: UiState,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val today = LocalDate.now()
    val dark = MaterialTheme.colorScheme.surface.luminanceIsDark()

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        state.profiles.forEach { profile ->
            val preview = state.previews[profile.id]
            val isActive = profile.id == state.activeProfileId
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            profile.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            profileSummary(preview, today),
                            style = MaterialTheme.typography.bodySmall,
                            color = profileSummaryColor(preview, today, dark),
                        )
                    }
                },
                leadingIcon = {
                    if (isActive) Icon(Icons.Default.Check, contentDescription = "aktiv")
                    else Icon(Icons.Outlined.Person, contentDescription = null)
                },
                onClick = { onSwitch(profile.id) },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Konto hinzufügen") },
            leadingIcon = { Icon(Icons.Outlined.PersonAdd, null) },
            onClick = onAdd,
        )
    }
}

private fun profileSummary(account: Account?, today: LocalDate): String {
    if (account == null) return "noch nicht geladen"
    val loans = account.loans
    if (loans.isEmpty()) {
        return if (account.fees.hasOpenFees) "nichts ausgeliehen · ${account.fees.open} offen"
        else "nichts ausgeliehen"
    }
    val days = account.loansByDueDate.firstOrNull()?.daysLeft(today)
    val base = when {
        days == null -> "${loans.size} ${mediaWord(loans.size)}"
        days < 0L -> "${loans.size} ${mediaWord(loans.size)} · überfällig"
        days == 0L -> "${loans.size} ${mediaWord(loans.size)} · heute fällig"
        days == 1L -> "${loans.size} ${mediaWord(loans.size)} · morgen fällig"
        else -> "${loans.size} ${mediaWord(loans.size)} · noch $days Tage"
    }
    return if (account.fees.hasOpenFees) "$base · ${account.fees.open}" else base
}

@Composable
private fun profileSummaryColor(account: Account?, today: LocalDate, dark: Boolean): Color {
    val days = account?.loansByDueDate?.firstOrNull()?.daysLeft(today)
    return when {
        account == null || days == null -> MaterialTheme.colorScheme.onSurfaceVariant
        days < 0L -> if (dark) DueColors.overdueDark else DueColors.overdue
        days <= 2L -> if (dark) DueColors.urgentDark else DueColors.urgent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun mediaWord(n: Int) = if (n == 1) "Medium" else "Medien"

@Composable
private fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Konto umbenennen") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Name") },
                singleLine = true,
                placeholder = { Text("z.B. Mia, Partner, eigener Ausweis") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

// ------------------------------------------------------------------ Bausteine

@Composable
private fun SummaryCard(account: Account, today: LocalDate) {
    val next = account.loansByDueDate.firstOrNull()
    val cardDays = account.cardDaysLeft(today)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (account.patronName.isNotBlank()) {
                Text(account.patronName, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Ausweis ${account.cardNumber}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat("Ausgeliehen", account.loans.size.toString())
                Stat(
                    "Offene Gebühren",
                    account.fees.open,
                    highlight = account.fees.hasOpenFees,
                )
            }

            if (next != null) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Nächste Rückgabe",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    CountdownChip(next, today)
                }
                Text(
                    next.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // Ausweis laeuft demnaechst ab - nur dann zeigen, sonst ist es Rauschen.
            if (cardDays != null && cardDays <= 60) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.CreditCard,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (cardDays < 0) "Ausweis abgelaufen (${account.cardValidUntilRaw})"
                        else "Ausweis läuft in $cardDays Tagen ab",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, highlight: Boolean = false) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = if (highlight) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LoanCard(loan: Loan, today: LocalDate, busy: Boolean, onRenew: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (loan.detailUrl != null) {
                    Modifier.clickable { uriHandler.openUri(loan.detailUrl) }
                } else Modifier
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row {
                AsyncImage(
                    model = loan.coverUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(width = 54.dp, height = 78.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (loan.mediaType.isNotBlank()) {
                        MediaTypeLabel(loan.mediaType)
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        loan.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 3,
                    )
                    if (loan.author.isNotBlank()) {
                        Text(
                            loan.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    CountdownChip(loan, today)
                    Text(
                        "Frist ${loan.dueDateRaw}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            RenewRow(loan, busy, onRenew)
        }
    }
}

/** Medienart wie von der Bibliothek geliefert ("Buch", "Tonie", "Spiel" ...), mit passendem Symbol. */
@Composable
private fun MediaTypeLabel(mediaType: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            mediaTypeIcon(mediaType),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            mediaType,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Die Bibliothek liefert die Medienart als Freitext. Reihenfolge beachten:
 * "Hörbuch" und "Bilderbuch" enthalten "buch", "Konsolenspiel" enthaelt "spiel".
 */
private fun mediaTypeIcon(mediaType: String): ImageVector {
    val t = mediaType.lowercase()
    return when {
        "hörbuch" in t || "hoerbuch" in t -> Icons.Outlined.Headphones
        "tonie" in t -> Icons.Outlined.Toys
        "bilderbuch" in t -> Icons.Outlined.AutoStories
        "konsole" in t || "videospiel" in t || "switch" in t || "playstation" in t ->
            Icons.Outlined.SportsEsports
        "spiel" in t -> Icons.Outlined.Extension
        "dvd" in t || "blu-ray" in t || "film" in t -> Icons.Outlined.Movie
        "cd" in t || "musik" in t -> Icons.Outlined.Album
        "zeitschrift" in t || "zeitung" in t -> Icons.Outlined.Newspaper
        "buch" in t || "roman" in t -> Icons.Outlined.MenuBook
        else -> Icons.Outlined.Category
    }
}

@Composable
private fun RenewRow(loan: Loan, busy: Boolean, onRenew: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            when {
                busy -> "wird verlängert…"
                loan.renewable == true && loan.renewToDate != null ->
                    "verlängerbar bis ${loan.renewToRaw}"
                loan.renewable == true -> "verlängerbar"
                loan.renewable == false -> loan.renewNote ?: "nicht verlängerbar"
                else -> "Status unbekannt"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (loan.renewable == false) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )

        // Bei unbekanntem Status trotzdem anbieten - der Server entscheidet ohnehin.
        if (loan.renewable != false && loan.extendTarget != null) {
            TextButton(onClick = onRenew, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Autorenew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Verlängern")
                }
            }
        }
    }
}

@Composable
private fun CountdownChip(loan: Loan, today: LocalDate) {
    val days: Long? = loan.daysLeft(today)
    val dark = MaterialTheme.colorScheme.surface.luminanceIsDark()
    val color = when {
        days == null -> MaterialTheme.colorScheme.onSurfaceVariant
        days < 0L -> if (dark) DueColors.overdueDark else DueColors.overdue
        days <= 2L -> if (dark) DueColors.urgentDark else DueColors.urgent
        days <= 7L -> if (dark) DueColors.soonDark else DueColors.soon
        else -> if (dark) DueColors.okDark else DueColors.ok
    }
    val label = when {
        days == null -> "ohne Frist"
        days < 0L -> "${-days} ${dayWord(-days)} überfällig"
        days == 0L -> "heute fällig"
        days == 1L -> "morgen fällig"
        else -> "noch $days Tage"
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun dayWord(n: Long) = if (n == 1L) "Tag" else "Tage"

/** Grobe Helligkeitsprobe, um die Ampelfarben an Hell/Dunkel anzupassen. */
private fun Color.luminanceIsDark(): Boolean = (red + green + blue) / 3f < 0.5f

@Composable
private fun SectionHeader(
    icon: @Composable () -> Unit,
    title: String,
    count: Int? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FeesCard(account: Account) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            KeyValue("Offene Gebühren", account.fees.open, account.fees.hasOpenFees)
            KeyValue("Einzahlungen", account.fees.paid)
            KeyValue("Kompletter Saldo", account.fees.balance)
            if (!account.fees.table.isEmpty) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                account.fees.table.asMaps().forEach { row ->
                    Spacer(Modifier.height(8.dp))
                    row.forEach { (k, v) -> KeyValue(k, v) }
                }
            }
            if (account.fees.hasOpenFees) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Bezahlen ist nur auf der Website der Stadtbücherei möglich.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String, highlight: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Vormerkungen und Abholbereit haben je nach Bestand andere Spalten, deshalb
 * werden sie generisch als Label/Wert-Paare gezeigt statt als feste Tabelle.
 */
@Composable
private fun TableCard(table: Table) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            table.asMaps().forEachIndexed { index, row ->
                if (index > 0) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                }
                row.forEach { (k, v) ->
                    if (k.equals("Cover", true) || k.isBlank()) return@forEach
                    KeyValue(k, v)
                }
            }
        }
    }
}

@Composable
private fun WatchlistCard(items: List<Pair<String, String?>>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Bookmarks, null)
                Spacer(Modifier.width(8.dp))
                Text("Merkliste", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(6.dp))
                Text(
                    items.size.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Zuklappen" else "Aufklappen",
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    items.forEach { (title, url) ->
                        Text(
                            title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (url != null) Modifier.clickable { uriHandler.openUri(url) }
                                    else Modifier
                                )
                                .padding(vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ErrorBanner(text: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun FooterNote(account: Account) {
    val stamp = remember(account.fetchedAt) {
        if (account.fetchedAt == 0L) "" else android.text.format.DateFormat
            .format("dd.MM.yyyy HH:mm", account.fetchedAt).toString()
    }
    Text(
        if (stamp.isEmpty()) "" else "Stand: $stamp",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    )
}
