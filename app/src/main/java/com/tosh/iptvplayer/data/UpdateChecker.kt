package com.tosh.iptvplayer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.tosh.iptvplayer.BuildConfig
import com.tosh.iptvplayer.model.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Checks the project's GitHub Releases for a version newer than the one currently installed,
 * and — since this app isn't distributed through the Play Store — handles downloading the new
 * APK and handing it to the system installer directly.
 */
class UpdateChecker(private val context: Context) {

    private val http = OkHttpClient.Builder().build()

    /** Returns update info if a newer release is available, or null if already up to date, the
     * check failed (e.g. no network), or the latest release has no APK attached. */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(RELEASES_API_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val remoteVersion = json.optString("tag_name").removePrefix("v").trim()
            if (remoteVersion.isBlank() || !isNewer(remoteVersion, BuildConfig.VERSION_NAME)) {
                return@withContext null
            }

            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            val downloadUrl = apkUrl?.takeIf { it.isNotBlank() } ?: return@withContext null

            UpdateInfo(
                versionName = remoteVersion,
                downloadUrl = downloadUrl,
                releaseNotes = json.optString("body"),
                htmlUrl = json.optString("html_url")
            )
        }.getOrNull()
    }

    /** Numeric semantic-version comparison ("1.10.0" > "1.9.0", not a plain string compare),
     * tolerant of a different number of segments between the two versions. */
    private fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val localParts = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    /** True if this app is currently allowed to prompt the system installer. On Android 8+ the
     * user must explicitly grant this per-app (in system settings) before an install prompt can
     * be shown at all. */
    fun canRequestInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Intent that sends the user to the system screen where they can grant "install unknown
     * apps" permission for this app specifically. */
    fun installPermissionSettingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /** Downloads the update APK directly (not via DownloadManager — it refuses to write into an
     * app's private internal storage, only the public Downloads folder or app-specific external
     * storage, which would need extra handling). Returns the downloaded file, or null on any
     * failure (network error, disk full, etc.). */
    suspend fun downloadUpdate(updateInfo: UpdateInfo): File? = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val destFile = File(updatesDir, "update.apk")
            if (destFile.exists()) destFile.delete()

            val request = Request.Builder().url(updateInfo.downloadUrl).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                destFile.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
            }
            destFile
        }.getOrNull()
    }

    /** Hands the downloaded APK to the system package installer. Requires canRequestInstall() to
     * already be true — check/request that first, or the system will silently refuse. */
    fun promptInstall(apkFile: File) {
        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    companion object {
        private const val REPO_OWNER = "ToshGate"
        private const val REPO_NAME = "IPTV-Player"
        private const val RELEASES_API_URL =
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    }
}
