package de.bibgl.konto.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Vermittelt zwischen UI/Worker und [LibraryClient].
 *
 * Jedes Profil bekommt einen eigenen [LibraryClient] mit eigenem Cookie-Jar -
 * die Bibliotheksseite kennt nur eine Anmeldung pro Sitzung, gemeinsame Cookies
 * wuerden die Konten durcheinanderbringen. Beim Umschalten bleibt die Sitzung des
 * anderen Ausweises dadurch bestehen.
 *
 * Die Seite wirft Sitzungen nach kurzer Zeit weg, deshalb wird bei jedem
 * Fehlschlag genau einmal automatisch neu angemeldet, bevor aufgegeben wird.
 */
class AccountRepository(private val store: Store) {

    // Ein angemeldeter Client je Profil. Nebenlaeufig, weil der Hintergrund-Worker
    // und die Oberflaeche dieselbe Instanz benutzen koennen.
    private val clients = ConcurrentHashMap<String, LibraryClient>()
    private val lock = Mutex()

    /**
     * Meldet einen neuen Ausweis an und legt ihn erst nach Erfolg als Profil an.
     *
     * @return das neue Profil samt geladenem Konto.
     * @throws LibraryException wenn die Anmeldung scheitert oder der Ausweis schon da ist.
     */
    suspend fun addProfile(user: String, password: String): Pair<Profile, Account> =
        lock.withLock {
            store.findProfileByUsername(user)?.let {
                throw LibraryException("Ausweis $user ist bereits als \"${it.label}\" hinterlegt")
            }
            val client = LibraryClient()
            val account = client.login(user, password)
            val label = account.patronName.ifBlank { user }
            val profile = store.addProfile(label, user, password)
            clients[profile.id] = client
            store.setCachedAccount(profile.id, account)
            profile to account
        }

    /** Laedt das Konto eines Profils neu und aktualisiert dessen Cache. */
    suspend fun load(profileId: String): Account = lock.withLock {
        val account = withSession(profileId) { it.refresh() }
        store.setCachedAccount(profileId, account)
        account
    }

    /** @param allowFees bestaetigt eine kostenpflichtige Verlaengerung. */
    suspend fun renew(profileId: String, copyId: String, allowFees: Boolean = false): RenewResult =
        lock.withLock {
            val result = withSession(profileId) { it.renew(copyId, allowFees) }
            result.account?.let { store.setCachedAccount(profileId, it) }
            result
        }

    suspend fun renewAll(
        profileId: String,
        copyIds: List<String>,
        allowFees: Boolean = false,
    ): RenewResult = lock.withLock {
        val result = withSession(profileId) { it.renewAll(copyIds, allowFees) }
        result.account?.let { store.setCachedAccount(profileId, it) }
        result
    }

    suspend fun removeFromWatchlist(profileId: String, mediaId: String): Account =
        lock.withLock {
            val account = withSession(profileId) { it.removeFromWatchlist(mediaId) }
            store.setCachedAccount(profileId, account)
            account
        }

    /** Entfernt ein Profil samt seiner Sitzung und Zwischenspeicher. */
    fun remove(profileId: String) {
        clients.remove(profileId)
        store.removeProfile(profileId)
    }

    fun removeAll() {
        clients.clear()
        store.removeAllProfiles()
    }

    /**
     * Fuehrt [block] auf dem Client des Profils aus und meldet sich bei Bedarf
     * (neu) an. Nach einem Re-Login hat der Client wieder eine frische Kontoseite
     * mit gueltigem VIEWSTATE, sodass auch Postbacks erneut versucht werden koennen.
     */
    private suspend fun <T> withSession(
        profileId: String,
        block: suspend (LibraryClient) -> T,
    ): T {
        val client = clients[profileId] ?: return block(relogin(profileId))
        return try {
            block(client)
        } catch (e: LibraryException) {
            block(relogin(profileId))
        }
    }

    private suspend fun relogin(profileId: String): LibraryClient {
        val (user, pass) = store.credentials(profileId)
            ?: throw LibraryException("Für dieses Konto sind keine Zugangsdaten hinterlegt")
        val client = LibraryClient()
        client.login(user, pass)
        clients[profileId] = client
        return client
    }
}
