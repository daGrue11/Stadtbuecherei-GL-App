package de.bibgl.konto.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.bibgl.konto.data.Account
import de.bibgl.konto.data.AccountRepository
import de.bibgl.konto.data.LibraryException
import de.bibgl.konto.data.Profile
import de.bibgl.konto.data.Store
import de.bibgl.konto.data.WatchItem
import de.bibgl.konto.work.DueDateWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** Offene Rueckfrage, weil die Verlaengerung Gebuehren kosten wuerde. */
data class FeeConfirmation(
    val copyIds: List<String>,
    val feeText: String,
    val bulk: Boolean,
)

data class UiState(
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: String? = null,
    /** Zeigt den Anmeldebildschirm, um einen weiteren Ausweis hinzuzufuegen. */
    val addingProfile: Boolean = false,
    val loading: Boolean = false,
    /** Medien, die gerade verlaengert werden - fuer den Spinner am Button. */
    val renewing: Set<String> = emptySet(),
    /** Merklisten-Eintraege (Mediennummern), die gerade entfernt werden. */
    val removingWatch: Set<String> = emptySet(),
    val account: Account? = null,
    val error: String? = null,
    /** Einmalige Rueckmeldung, z.B. "Verlängert bis 12.11.2026". */
    val message: String? = null,
    val reminderDays: Int = 5,
    val notificationsEnabled: Boolean = true,
    val feeConfirmation: FeeConfirmation? = null,
    /** Zwischengespeicherte Konten aller Profile - fuer die Vorschau im Umschalter. */
    val previews: Map<String, Account> = emptyMap(),
) {
    val activeProfile: Profile? get() = profiles.firstOrNull { it.id == activeProfileId }

    /** Anmeldebildschirm zeigen, wenn es noch kein Profil gibt oder eins dazukommt. */
    val showLogin: Boolean get() = profiles.isEmpty() || addingProfile
}

class AccountViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    private val repo = AccountRepository(store)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val profiles = store.profiles
        val active = store.activeProfileId
        _state.value = UiState(
            profiles = profiles,
            activeProfileId = active,
            account = active?.let { store.cachedAccount(it) },
            reminderDays = store.reminderDays,
            notificationsEnabled = store.notificationsEnabled,
            previews = profiles.mapNotNull { p ->
                store.cachedAccount(p.id)?.let { p.id to it }
            }.toMap(),
        )
        if (active != null) refresh()
    }

    // ----------------------------------------------------------------- Profile

    /** Meldet einen Ausweis an und legt ihn als weiteres Profil an. */
    fun addProfile(user: String, password: String) {
        if (user.isBlank() || password.isBlank()) {
            _state.update { it.copy(error = "Bitte Ausweisnummer und Passwort eingeben") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.addProfile(user.trim(), password) }
                .onSuccess { (profile, account) ->
                    _state.update {
                        it.copy(
                            profiles = store.profiles,
                            activeProfileId = profile.id,
                            addingProfile = false,
                            loading = false,
                            account = account,
                            error = null,
                            previews = it.previews + (profile.id to account),
                        )
                    }
                    DueDateWorker.schedule(getApplication<Application>())
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = describe(e)) }
                }
        }
    }

    /** Wechselt den Ausweis: erst der zwischengespeicherte Stand, dann neu laden. */
    fun switchProfile(profileId: String) {
        if (profileId == _state.value.activeProfileId) return
        // Kann aus einer Benachrichtigung kommen, deren Konto inzwischen weg ist.
        if (_state.value.profiles.none { it.id == profileId }) return
        store.activeProfileId = profileId
        _state.update {
            it.copy(
                activeProfileId = profileId,
                account = store.cachedAccount(profileId),
                error = null,
                message = null,
                renewing = emptySet(),
                removingWatch = emptySet(),
                feeConfirmation = null,
            )
        }
        refresh()
    }

    fun beginAddProfile() = _state.update { it.copy(addingProfile = true, error = null) }

    fun cancelAddProfile() = _state.update { it.copy(addingProfile = false, error = null) }

    fun renameProfile(profileId: String, label: String) {
        store.renameProfile(profileId, label)
        _state.update { it.copy(profiles = store.profiles) }
    }

    /** Entfernt einen Ausweis samt gespeicherten Zugangsdaten. */
    fun removeProfile(profileId: String) {
        repo.remove(profileId)
        val profiles = store.profiles
        val active = store.activeProfileId
        _state.update {
            it.copy(
                profiles = profiles,
                activeProfileId = active,
                account = active?.let { id -> store.cachedAccount(id) },
                previews = it.previews - profileId,
                error = null,
                message = null,
                renewing = emptySet(),
                removingWatch = emptySet(),
                feeConfirmation = null,
            )
        }
        if (profiles.isEmpty()) DueDateWorker.cancel(getApplication<Application>())
        else if (active != null) refresh()
    }

    // ------------------------------------------------------------------ Konto

    fun refresh() {
        val profileId = _state.value.activeProfileId ?: return
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.load(profileId) }
                .onSuccess { account ->
                    _state.update {
                        // Nur uebernehmen, wenn inzwischen nicht umgeschaltet wurde.
                        if (it.activeProfileId != profileId) {
                            it.copy(loading = false, previews = it.previews + (profileId to account))
                        } else it.copy(
                            loading = false,
                            account = account,
                            error = null,
                            previews = it.previews + (profileId to account),
                        )
                    }
                }
                .onFailure { e ->
                    // Cache stehen lassen: veraltete Fristen sind besser als nichts.
                    _state.update { it.copy(loading = false, error = describe(e)) }
                }
        }
    }

    fun renew(copyId: String, allowFees: Boolean = false) =
        runRenew(listOf(copyId), bulk = false, allowFees = allowFees)

    fun renewAll(allowFees: Boolean = false) {
        val ids = _state.value.account?.renewableLoans?.map { it.copyId }.orEmpty()
        if (ids.isNotEmpty()) runRenew(ids, bulk = true, allowFees = allowFees)
    }

    /** Bestaetigt die zuvor abgelehnte, kostenpflichtige Verlaengerung. */
    fun confirmFees() {
        val pending = _state.value.feeConfirmation ?: return
        _state.update { it.copy(feeConfirmation = null) }
        runRenew(pending.copyIds, bulk = pending.bulk, allowFees = true)
    }

    fun dismissFees() = _state.update { it.copy(feeConfirmation = null) }

    private fun runRenew(ids: List<String>, bulk: Boolean, allowFees: Boolean) {
        val profileId = _state.value.activeProfileId ?: return
        if (ids.isEmpty() || ids.any { it in _state.value.renewing }) return
        val idSet = ids.toSet()
        _state.update { it.copy(renewing = it.renewing + idSet, error = null) }
        viewModelScope.launch {
            runCatching {
                if (bulk) repo.renewAll(profileId, ids, allowFees)
                else repo.renew(profileId, ids.first(), allowFees)
            }
                .onSuccess { result ->
                    _state.update {
                        if (it.activeProfileId != profileId) {
                            it.copy(renewing = it.renewing - idSet)
                        } else it.copy(
                            renewing = it.renewing - idSet,
                            account = result.account ?: it.account,
                            previews = result.account
                                ?.let { acc -> it.previews + (profileId to acc) }
                                ?: it.previews,
                            // Bei einer Gebuehren-Rueckfrage nicht als Meldung zeigen,
                            // sondern als Dialog - sonst quittiert die App eine
                            // Verlaengerung, die noch gar nicht passiert ist.
                            message = if (result.needsConfirmation) null else result.message,
                            feeConfirmation = if (result.needsConfirmation) {
                                FeeConfirmation(
                                    copyIds = result.copyIds.ifEmpty { ids },
                                    feeText = result.feeText.orEmpty(),
                                    bulk = bulk,
                                )
                            } else null,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(renewing = it.renewing - idSet, error = describe(e)) }
                }
        }
    }

    // -------------------------------------------------------------- Merkliste

    fun removeFromWatchlist(item: WatchItem) {
        val profileId = _state.value.activeProfileId ?: return
        val mediaId = item.mediaId
        if (mediaId.isEmpty() || mediaId in _state.value.removingWatch) return
        _state.update { it.copy(removingWatch = it.removingWatch + mediaId, error = null) }
        viewModelScope.launch {
            runCatching { repo.removeFromWatchlist(profileId, mediaId) }
                .onSuccess { account ->
                    _state.update {
                        if (it.activeProfileId != profileId) {
                            it.copy(removingWatch = it.removingWatch - mediaId)
                        } else it.copy(
                            removingWatch = it.removingWatch - mediaId,
                            account = account,
                            previews = it.previews + (profileId to account),
                            message = "\"${item.title}\" von der Merkliste entfernt",
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(removingWatch = it.removingWatch - mediaId, error = describe(e))
                    }
                }
        }
    }

    // ----------------------------------------------------------- Einstellungen

    fun setReminderDays(days: Int) {
        store.reminderDays = days
        _state.update { it.copy(reminderDays = days) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        store.notificationsEnabled = enabled
        _state.update { it.copy(notificationsEnabled = enabled) }
        if (enabled) DueDateWorker.schedule(getApplication<Application>())
        else DueDateWorker.cancel(getApplication<Application>())
    }

    /** Meldet alle Ausweise ab und loescht deren Zugangsdaten. */
    fun logoutAll() {
        repo.removeAll()
        DueDateWorker.cancel(getApplication<Application>())
        _state.value = UiState(
            reminderDays = store.reminderDays,
            notificationsEnabled = store.notificationsEnabled,
        )
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun consumeError() = _state.update { it.copy(error = null) }

    private fun describe(e: Throwable): String = when (e) {
        is LibraryException -> e.message ?: "Fehler bei der Bibliothek"
        is IOException -> "Keine Verbindung zur Stadtbücherei"
        else -> e.message ?: "Unerwarteter Fehler"
    }
}
