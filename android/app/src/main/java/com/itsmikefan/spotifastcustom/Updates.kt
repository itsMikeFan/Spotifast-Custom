package com.itsmikefan.spotifastcustom

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Finds a newer build on the android-latest GitHub release and hands it to Android's installer. */
object Updates {
    private const val RELEASE = "https://api.github.com/repos/itsMikeFan/Spotifast-Custom/releases/tags/android-latest"
    private val http = okhttp3.OkHttpClient()

    data class Update(val build: Long, val url: String)

    /** The release's build when it is newer than this one, else null. Failures count as "no update". */
    suspend fun check(context: Context): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val request = okhttp3.Request.Builder().url(RELEASE).header("Accept", "application/vnd.github+json").build()
            val release = http.newCall(request).execute().use { JSONObject(it.body!!.string()) }
            val build = buildNumber(release.optString("name")) ?: return@runCatching null
            val assets = release.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: return@runCatching null
            Update(build, apk.getString("browser_download_url")).takeIf { build > installed(context) }
        }.getOrNull()
    }

    /** Downloads [update] and opens Android's install screen for it. */
    suspend fun install(context: Context, update: Update) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            File(dir, "spotifast-custom.apk").also { out ->
                http.newCall(okhttp3.Request.Builder().url(update.url).build()).execute().use { response ->
                    if (!response.isSuccessful) error("Download failed: ${response.code}")
                    out.outputStream().use { response.body!!.byteStream().copyTo(it) }
                }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** CI titles the release "Android build N", where N is also the APK's versionCode. */
    fun buildNumber(title: String): Long? = Regex("""build (\d+)""").find(title)?.groupValues?.get(1)?.toLongOrNull()

    @Suppress("DEPRECATION")
    private fun installed(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }
}
