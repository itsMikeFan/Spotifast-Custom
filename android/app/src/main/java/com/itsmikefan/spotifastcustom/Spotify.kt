package com.itsmikefan.spotifastcustom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

data class Playlist(val name: String, val uri: String, val tracksPath: String, val count: Int)
data class Track(val uri: String, val name: String, val artists: String, val durationMs: Int)

/**
 * Sign-in and the Web API, the same two grants as the desktop's src/auth.rs:
 * the listener's own Spotify app for the Web API, and Spotify's desktop
 * client identity for playback only.
 */
object Spotify {
    const val PLAYBACK_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
    private const val PLAYBACK_PORT = 8898
    // The redirect URI the personal app already lists for the desktop app.
    const val WEB_PORT = 8989
    private val WEB_SCOPES = listOf(
        "playlist-read-private", "playlist-read-collaborative", "playlist-modify-private",
        "playlist-modify-public", "user-library-read", "user-library-modify", "user-read-private",
    )

    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        // shortcut: the Web API refresh token sits in app-private preferences, not
        // Android Keystore; move it there if the phone may be rooted or shared.
        prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    }

    var clientId: String
        get() = prefs.getString("client_id", "") ?: ""
        set(value) = prefs.edit().putString("client_id", value.trim()).apply()

    val signedIn get() = prefs.contains("refresh_token")
    val userId get() = prefs.getString("user_id", "") ?: ""

    fun signOut() = prefs.edit().remove("refresh_token").remove("access_token").remove("user_id").apply()

    /** Browser sign-in for the Web API, with the listener's own app. */
    suspend fun signIn(context: Context) {
        val token = authorize(context, clientId, WEB_PORT, WEB_SCOPES, showDialog = false)
        save(token)
        val me = get("/me")
        prefs.edit().putString("user_id", me.getString("id")).apply()
    }

    /** Browser sign-in for playback; returns the streaming access token for the core. */
    suspend fun playbackToken(context: Context): String =
        authorize(context, PLAYBACK_CLIENT_ID, PLAYBACK_PORT, listOf("streaming"), showDialog = true)
            .getString("access_token")

    private suspend fun authorize(
        context: Context, client: String, port: Int, scopes: List<String>, showDialog: Boolean,
    ): JSONObject {
        require(client.isNotBlank()) { "Enter your Spotify app's Client ID first." }
        val verifier = random(48)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val state = random(18)
        val redirect = "http://127.0.0.1:$port/login"
        val url = "https://accounts.spotify.com/authorize?client_id=$client&response_type=code" +
            "&redirect_uri=${enc(redirect)}&code_challenge_method=S256&code_challenge=$challenge" +
            "&state=$state&scope=${enc(scopes.joinToString(" "))}" + if (showDialog) "&show_dialog=true" else ""
        val code = withContext(Dispatchers.IO) {
            ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                server.soTimeout = 5 * 60 * 1000
                waitForCode(server, state)
            }
        }
        return token(
            "client_id" to client, "grant_type" to "authorization_code", "code" to code,
            "redirect_uri" to redirect, "code_verifier" to verifier,
        )
    }

    /** Answers requests on the loopback listener until Spotify's redirect arrives. */
    private fun waitForCode(server: ServerSocket, state: String): String {
        while (true) {
            server.accept().use { socket ->
                val line = socket.getInputStream().bufferedReader().readLine() ?: return@use
                val target = line.split(" ").getOrNull(1) ?: return@use
                val uri = Uri.parse("http://127.0.0.1$target")
                if (uri.path != "/login") {
                    // A favicon request is not the redirect; keep waiting.
                    socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    return@use
                }
                val code = uri.getQueryParameter("code")
                val ok = code != null && uri.getQueryParameter("state") == state
                val body = if (ok) {
                    "<html><head><meta name=viewport content='width=device-width'>" +
                        "<meta http-equiv=refresh content='0;url=spotifastcustom://signed-in'></head>" +
                        "<body style='font-family:sans-serif;padding:2em'><h2>Signed in</h2>" +
                        "<p><a href='spotifastcustom://signed-in'>Back to Spotifast</a></p></body></html>"
                } else {
                    "<html><body><h2>Sign-in failed</h2><p>${uri.getQueryParameter("error") ?: "Try again."}</p></body></html>"
                }
                val bytes = body.toByteArray()
                socket.getOutputStream().write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes
                )
                socket.getOutputStream().flush()
                if (ok) return code!!
                throw IOException("Spotify sign-in failed: ${uri.getQueryParameter("error") ?: "state mismatch"}")
            }
        }
    }

    private suspend fun token(vararg form: Pair<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.outputStream.use { out ->
            out.write(form.joinToString("&") { (k, v) -> "$k=${enc(v)}" }.toByteArray())
        }
        val ok = connection.responseCode in 200..299
        val text = (if (ok) connection.inputStream else connection.errorStream)?.bufferedReader()?.readText() ?: ""
        if (!ok) throw IOException("Spotify refused the sign-in (${connection.responseCode}): $text")
        JSONObject(text)
    }

    private fun save(token: JSONObject) {
        prefs.edit()
            .putString("access_token", token.getString("access_token"))
            .putLong("expires_at", System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000 - 60_000)
            .apply {
                // A refresh may or may not hand out a new refresh token.
                token.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { putString("refresh_token", it) }
            }
            .apply()
    }

    private suspend fun accessToken(forceRefresh: Boolean = false): String {
        val cached = prefs.getString("access_token", null)
        if (!forceRefresh && cached != null && System.currentTimeMillis() < prefs.getLong("expires_at", 0)) return cached
        val refresh = prefs.getString("refresh_token", null) ?: throw IOException("Not signed in")
        save(token("client_id" to clientId, "grant_type" to "refresh_token", "refresh_token" to refresh))
        return prefs.getString("access_token", null)!!
    }

    /** A Web API GET; [path] is relative to /v1 or a full `next` URL. */
    suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("https://")) path else "https://api.spotify.com/v1$path"
        var refreshed = false
        var waited = false
        while (true) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("Authorization", "Bearer ${accessToken(refreshed)}")
            when (val status = connection.responseCode) {
                in 200..299 -> return@withContext JSONObject(connection.inputStream.bufferedReader().readText())
                401 -> if (!refreshed) { refreshed = true; continue }
                429 -> if (!waited) {
                    waited = true
                    Thread.sleep((connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 2).coerceAtMost(30) * 1000)
                    continue
                }
                else -> {}
            }
            val error = connection.errorStream?.bufferedReader()?.readText() ?: ""
            throw IOException("Spotify answered ${connection.responseCode}: $error")
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    suspend fun playlists(): List<Playlist> {
        val liked = Playlist("Liked Songs", "spotify:user:$userId:collection", "/me/tracks?limit=50", -1)
        val result = mutableListOf(liked)
        var next: String? = "/me/playlists?limit=50"
        while (next != null) {
            val page = get(next)
            val items = page.getJSONArray("items")
            for (i in 0 until items.length()) {
                val p = items.optJSONObject(i) ?: continue
                val id = p.getString("id")
                // Newer apps get "items", older ones "tracks", for the count.
                val count = (p.optJSONObject("items") ?: p.optJSONObject("tracks"))?.optInt("total", -1) ?: -1
                result += Playlist(p.optString("name"), p.getString("uri"), "/playlists/$id/items?limit=50", count)
            }
            next = page.optString("next").takeIf { it.isNotEmpty() && it != "null" }
        }
        return result
    }

    suspend fun tracks(playlist: Playlist): List<Track> {
        val result = mutableListOf<Track>()
        var next: String? = playlist.tracksPath
        while (next != null) {
            val page = get(next)
            val items = page.getJSONArray("items")
            for (i in 0 until items.length()) {
                val row = items.optJSONObject(i) ?: continue
                val t = row.optJSONObject("item") ?: row.optJSONObject("track") ?: continue
                val uri = t.optString("uri").takeIf { it.isNotEmpty() } ?: continue
                val artists = t.optJSONArray("artists")?.let { a ->
                    (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") }
                } ?: ""
                result += Track(uri, t.optString("name"), artists, t.optInt("duration_ms"))
            }
            next = page.optString("next").takeIf { it.isNotEmpty() && it != "null" }
        }
        return result
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun random(bytes: Int): String {
        val data = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
