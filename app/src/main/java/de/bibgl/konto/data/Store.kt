package de.bibgl.konto.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Ablage auf dem Geraet.
 *
 * Die App verwaltet mehrere Bibliotheksausweise ("Profile"). Zugangsdaten liegen
 * pro Profil in EncryptedSharedPreferences (AES-256, Schluessel im Android
 * Keystore) - sie verlassen das Geraet nur an die Bibliotheks-Website selbst.
 * Profilnamen, Einstellungen und der Konto-Cache sind unkritisch und liegen in
 * normalen SharedPreferences.
 */
class Store(private val context: Context) {

    companion object {
        private const val SECURE_FILE = "bibgl_secure"
        private const val PLAIN_FILE = "bibgl_prefs"

        private const val K_PROFILE_IDS = "profile_ids"
        private const val K_ACTIVE = "active_profile"
        private const val K_REMINDER_DAYS = "reminder_days"
        private const val K_NOTIFY = "notify_enabled"

        // Schluessel der frueheren Einkonto-Version, nur noch fuer die Migration.
        private const val K_LEGACY_USER = "user"
        private const val K_LEGACY_PASS = "pass"
        private const val K_LEGACY_ACCOUNT = "account_cache"
        private const val K_LEGACY_NOTIFIED = "last_notified_day"

        private fun kLabel(id: String) = "profile_label_$id"
        private fun kAccount(id: String) = "account_cache_$id"
        private fun kNotified(id: String) = "last_notified_$id"
        private fun kCardNotified(id: String) = "last_card_notified_$id"
        private fun kUser(id: String) = "user_$id"
        private fun kPass(id: String) = "pass_$id"

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val idListSerializer = ListSerializer(String.serializer())
    }

    private val plain: SharedPreferences =
        context.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)

    private val secure: SharedPreferences by lazy { openSecure() }

    init {
        migrateSingleAccountSetup()
    }

    private fun openSecure(): SharedPreferences {
        fun build(): SharedPreferences {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                SECURE_FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try {
            build()
        } catch (e: Exception) {
            // Kann passieren, wenn der Keystore-Schluessel ungueltig wurde (z.B. nach
            // Wiederherstellung eines Backups). Dann Datei verwerfen und neu anlegen -
            // der Nutzer muss sich einmal neu anmelden.
            context.deleteSharedPreferences(SECURE_FILE)
            build()
        }
    }

    /**
     * Uebernimmt die Zugangsdaten der Version ohne Konto-Umschalter in ein erstes
     * Profil, damit nach dem Update niemand neu anmelden muss.
     */
    private fun migrateSingleAccountSetup() {
        if (plain.contains(K_PROFILE_IDS)) return
        val legacyUser = secure.getString(K_LEGACY_USER, null)
        val legacyPass = secure.getString(K_LEGACY_PASS, null)
        if (legacyUser.isNullOrBlank() || legacyPass.isNullOrBlank()) {
            plain.edit().putString(K_PROFILE_IDS, json.encodeToString(idListSerializer, emptyList()))
                .apply()
            return
        }

        val id = newId()
        val cached = plain.getString(K_LEGACY_ACCOUNT, null)
        val label = cached
            ?.let { runCatching { json.decodeFromString(Account.serializer(), it) }.getOrNull() }
            ?.patronName?.takeIf { it.isNotBlank() }
            ?: legacyUser

        secure.edit()
            .putString(kUser(id), legacyUser)
            .putString(kPass(id), legacyPass)
            .remove(K_LEGACY_USER)
            .remove(K_LEGACY_PASS)
            .apply()

        val editor = plain.edit()
        editor.putString(K_PROFILE_IDS, json.encodeToString(idListSerializer, listOf(id)))
        editor.putString(K_ACTIVE, id)
        editor.putString(kLabel(id), label)
        editor.putLong(kNotified(id), plain.getLong(K_LEGACY_NOTIFIED, 0L))
        if (cached != null) editor.putString(kAccount(id), cached)
        editor.remove(K_LEGACY_ACCOUNT)
        editor.remove(K_LEGACY_NOTIFIED)
        editor.apply()
    }

    private fun newId(): String = UUID.randomUUID().toString().take(8)

    // ----------------------------------------------------------------- Profile

    private var profileIds: List<String>
        get() = plain.getString(K_PROFILE_IDS, null)
            ?.let { runCatching { json.decodeFromString(idListSerializer, it) }.getOrNull() }
            ?: emptyList()
        set(value) = plain.edit()
            .putString(K_PROFILE_IDS, json.encodeToString(idListSerializer, value))
            .apply()

    /** Alle hinterlegten Ausweise in der vom Nutzer gesehenen Reihenfolge. */
    val profiles: List<Profile>
        get() = profileIds.map { id ->
            Profile(id = id, label = plain.getString(kLabel(id), null).orEmpty().ifEmpty { "Konto" })
        }

    val hasProfiles: Boolean get() = profileIds.isNotEmpty()

    /** Aktives Profil; faellt auf das erste zurueck, falls die Auswahl veraltet ist. */
    var activeProfileId: String?
        get() {
            val ids = profileIds
            val current = plain.getString(K_ACTIVE, null)
            return if (current != null && current in ids) current else ids.firstOrNull()
        }
        set(value) = plain.edit().putString(K_ACTIVE, value).apply()

    /** Legt ein Profil an und gibt es zurueck. Der Aufrufer prueft die Daten vorher. */
    fun addProfile(label: String, user: String, password: String): Profile {
        val id = newId()
        secure.edit().putString(kUser(id), user).putString(kPass(id), password).apply()
        plain.edit()
            .putString(kLabel(id), label.ifBlank { user })
            .apply()
        profileIds = profileIds + id
        activeProfileId = id
        return Profile(id, label.ifBlank { user })
    }

    fun renameProfile(id: String, label: String) {
        if (label.isBlank()) return
        plain.edit().putString(kLabel(id), label.trim()).apply()
    }

    fun removeProfile(id: String) {
        secure.edit().remove(kUser(id)).remove(kPass(id)).apply()
        plain.edit()
            .remove(kLabel(id))
            .remove(kAccount(id))
            .remove(kNotified(id))
            .remove(kCardNotified(id))
            .apply()
        val remaining = profileIds - id
        profileIds = remaining
        if (plain.getString(K_ACTIVE, null) == id) {
            activeProfileId = remaining.firstOrNull()
        }
    }

    fun removeAllProfiles() {
        profileIds.forEach { removeProfile(it) }
    }

    // ------------------------------------------------------------ Zugangsdaten

    fun credentials(id: String): Pair<String, String>? {
        val user = secure.getString(kUser(id), null)
        val pass = secure.getString(kPass(id), null)
        return if (user.isNullOrBlank() || pass.isNullOrBlank()) null else user to pass
    }

    /** True, wenn fuer dieses Profil bereits genau diese Ausweisnummer hinterlegt ist. */
    fun findProfileByUsername(user: String): Profile? =
        profiles.firstOrNull { credentials(it.id)?.first == user }

    // ---------------------------------------------------------- Einstellungen

    /** Ab wie vielen Tagen vor Fristende erinnert wird. Gilt fuer alle Profile. */
    var reminderDays: Int
        get() = plain.getInt(K_REMINDER_DAYS, 5)
        set(value) = plain.edit().putInt(K_REMINDER_DAYS, value).apply()

    var notificationsEnabled: Boolean
        get() = plain.getBoolean(K_NOTIFY, true)
        set(value) = plain.edit().putBoolean(K_NOTIFY, value).apply()

    /** Tag der letzten Erinnerung je Profil (epochDay), damit hoechstens einmal taeglich. */
    fun lastNotifiedDay(id: String): Long = plain.getLong(kNotified(id), 0L)

    fun setLastNotifiedDay(id: String, day: Long) {
        plain.edit().putLong(kNotified(id), day).apply()
    }

    /** Tag der letzten Warnung zum Ausweisablauf je Profil (epochDay), fuer den Wochenrhythmus. */
    fun lastCardNotifiedDay(id: String): Long = plain.getLong(kCardNotified(id), 0L)

    fun setLastCardNotifiedDay(id: String, day: Long) {
        plain.edit().putLong(kCardNotified(id), day).apply()
    }

    // ------------------------------------------------------------------ Cache

    /** Zuletzt geladenes Konto eines Profils - damit die App offline etwas zeigt. */
    fun cachedAccount(id: String): Account? =
        plain.getString(kAccount(id), null)?.let {
            runCatching { json.decodeFromString(Account.serializer(), it) }.getOrNull()
        }

    fun setCachedAccount(id: String, account: Account?) {
        plain.edit().apply {
            if (account == null) remove(kAccount(id))
            else putString(kAccount(id), json.encodeToString(Account.serializer(), account))
        }.apply()
    }
}
