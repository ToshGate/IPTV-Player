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
import java.io.File
import java.io.StringReader
import java.security.KeyStore
import java.util.UUID

/** Several imported WireGuard configurations can be stored side by side — securely (each
 * contains a private key) — with one marked as "selected", which is the one connect() and the
 * player's quick-toggle both act on. */
class VpnRepository(private val context: Context) {

    private val store = SecureStore(context, STORE_FILE_NAME, STORE_KEY_ALIAS)

    private val backend: GoBackend by lazy { GoBackend(context) }

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            // No-op: callers poll currentState() after each action instead of observing this,
            // since nothing else in the app drives a state change.
        }
    }

    init {
        migrateFromEncryptedSharedPreferences()
    }

    fun getProfiles(): List<VpnProfile> {
        val array = JSONArray(store.getString(PREF_PROFILES_JSON) ?: "[]")
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
            val next = remaining.firstOrNull()?.id
            if (next != null) store.putString(PREF_SELECTED_ID, next) else store.remove(PREF_SELECTED_ID)
        }
    }

    fun getSelectedProfileId(): String? = store.getString(PREF_SELECTED_ID)

    fun setSelectedProfileId(id: String) {
        store.putString(PREF_SELECTED_ID, id)
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
        store.putString(PREF_PROFILES_JSON, array.toString())
    }

    /** One-time move of already-saved profiles out of the old EncryptedSharedPreferences file
     * (that library is deprecated) into [SecureStore]. Runs on every launch but is a no-op once
     * the old file is gone. Never allowed to throw: this runs from Application.onCreate(), and
     * anything unreadable in the old file could not have been used again anyway. The deprecated
     * classes are used here, and only here, on purpose — this whole function (and the
     * security-crypto dependency) can be deleted once everyone has upgraded past this version. */
    @Suppress("DEPRECATION")
    private fun migrateFromEncryptedSharedPreferences() {
        runCatching {
            val legacyFile = File(context.applicationInfo.dataDir, "shared_prefs/$LEGACY_PREFS_FILE_NAME.xml")
            if (!legacyFile.exists()) return

            runCatching {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val legacy = EncryptedSharedPreferences.create(
                    context,
                    LEGACY_PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                val profiles = legacy.getString(PREF_PROFILES_JSON, null)
                val selected = legacy.getString(PREF_SELECTED_ID, null)
                // Only fill an empty new store, so a half-finished earlier attempt can't
                // overwrite anything already migrated.
                if (profiles != null && store.getString(PREF_PROFILES_JSON) == null) {
                    store.putString(PREF_PROFILES_JSON, profiles)
                    if (selected != null) store.putString(PREF_SELECTED_ID, selected)
                }
            }

            context.deleteSharedPreferences(LEGACY_PREFS_FILE_NAME)
            runCatching {
                val keyStore = KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                // The alias EncryptedSharedPreferences' MasterKey uses by default.
                keyStore.deleteEntry(LEGACY_MASTER_KEY_ALIAS)
            }
        }
    }

    companion object {
        private const val TUNNEL_NAME = "iptvplayer_wg"
        private const val STORE_FILE_NAME = "vpn_profiles_store"
        private const val STORE_KEY_ALIAS = "iptvplayer_vpn_store_key"
        private const val PREF_PROFILES_JSON = "profiles_json"
        private const val PREF_SELECTED_ID = "selected_profile_id"
        private const val LEGACY_PREFS_FILE_NAME = "vpn_secure_prefs"
        private const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    }
}
