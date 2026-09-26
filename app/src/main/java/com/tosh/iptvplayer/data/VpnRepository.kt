package com.tosh.iptvplayer.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tosh.iptvplayer.model.VpnProfile
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.StringReader
import java.util.UUID

/** Several imported WireGuard configurations can be stored side by side — securely (each
 * contains a private key) — with one marked as "selected", which is the one connect() and the
 * player's quick-toggle both act on. */
class VpnRepository(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = createEncryptedPrefs()

    /** EncryptedSharedPreferences can end up unreadable even though the file and Keystore key
     * both still exist — e.g. after "clear storage" wipes the prefs file but an old Keystore key
     * entry lingers, or the OS's keystore gets reset independently of app data. When that
     * happens decrypting throws (AEADBadTagException / KeyStoreException), and since the
     * content can never be decrypted either way, the only way forward is to wipe the corrupted
     * prefs file and stale key and start fresh — the alternative is the app never being able to
     * launch again at all, since this constructor runs from Application.onCreate(). */
    private fun createEncryptedPrefs(): android.content.SharedPreferences {
        return runCatching {
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            context.deleteSharedPreferences(PREFS_FILE_NAME)
            runCatching {
                val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    private val backend: GoBackend by lazy { GoBackend(context) }

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            // No-op: callers poll currentState() after each action instead of observing this,
            // since nothing else in the app drives a state change.
        }
    }

    init {
        migrateLegacySingleProfileIfNeeded()
    }

    fun getProfiles(): List<VpnProfile> {
        val array = JSONArray(prefs.getString(PREF_PROFILES_JSON, "[]"))
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            VpnProfile(obj.getString("id"), obj.getString("name"), obj.getString("config"))
        }
    }

    /** Parses before saving so a malformed file is rejected up front rather than failing later,
     * silently, at connect time. Throws on invalid config — the caller shows the error. Newly
     * added profiles become the selected one, matching the expectation that importing something
     * makes it the one that's about to be used. */
    fun addProfile(name: String, configText: String): VpnProfile {
        Config.parse(BufferedReader(StringReader(configText)))
        val profile = VpnProfile(UUID.randomUUID().toString(), name, configText)
        val profiles = getProfiles() + profile
        saveProfiles(profiles)
        setSelectedProfileId(profile.id)
        return profile
    }

    fun deleteProfile(id: String) {
        val remaining = getProfiles().filterNot { it.id == id }
        saveProfiles(remaining)
        if (getSelectedProfileId() == id) {
            prefs.edit().putString(PREF_SELECTED_ID, remaining.firstOrNull()?.id).apply()
        }
    }

    fun getSelectedProfileId(): String? = prefs.getString(PREF_SELECTED_ID, null)

    fun setSelectedProfileId(id: String) {
        prefs.edit().putString(PREF_SELECTED_ID, id).apply()
    }

    fun getSelectedProfile(): VpnProfile? {
        val id = getSelectedProfileId() ?: return null
        return getProfiles().find { it.id == id }
    }

    /** VpnService.prepare(activity) must already have been called — and its returned Intent (if
     * any) launched for the user's explicit consent — before this runs; that one-time system
     * permission dialog can only be triggered from an Activity, so it's the caller's job. */
    suspend fun connect(): Result<Tunnel.State> = withContext(Dispatchers.IO) {
        runCatching {
            val profile = getSelectedProfile()
                ?: throw IllegalStateException("Nenhum perfil selecionado")
            val config = Config.parse(BufferedReader(StringReader(profile.configText)))
            backend.setState(tunnel, Tunnel.State.UP, config)
        }
    }

    suspend fun disconnect(): Result<Tunnel.State> = withContext(Dispatchers.IO) {
        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
    }

    fun currentState(): Tunnel.State =
        runCatching { backend.getState(tunnel) }.getOrDefault(Tunnel.State.DOWN)

    private fun saveProfiles(profiles: List<VpnProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(JSONObject().put("id", p.id).put("name", p.name).put("config", p.configText))
        }
        prefs.edit().putString(PREF_PROFILES_JSON, array.toString()).apply()
    }

    /** One-time upgrade from the earlier single-profile version of this repository — a person
     * who already imported a config before this change shouldn't lose it. */
    private fun migrateLegacySingleProfileIfNeeded() {
        val legacyText = prefs.getString(PREF_LEGACY_CONFIG_TEXT, null) ?: return
        if (getProfiles().isNotEmpty()) {
            prefs.edit().remove(PREF_LEGACY_CONFIG_TEXT).remove(PREF_LEGACY_CONFIG_NAME).apply()
            return
        }
        val legacyName = prefs.getString(PREF_LEGACY_CONFIG_NAME, null) ?: "Configuração importada"
        runCatching { addProfile(legacyName, legacyText) }
        prefs.edit().remove(PREF_LEGACY_CONFIG_TEXT).remove(PREF_LEGACY_CONFIG_NAME).apply()
    }

    companion object {
        private const val TUNNEL_NAME = "iptvplayer_wg"
        private const val PREFS_FILE_NAME = "vpn_secure_prefs"
        private const val PREF_PROFILES_JSON = "profiles_json"
        private const val PREF_SELECTED_ID = "selected_profile_id"
        private const val PREF_LEGACY_CONFIG_TEXT = "config_text"
        private const val PREF_LEGACY_CONFIG_NAME = "config_name"
    }
}
