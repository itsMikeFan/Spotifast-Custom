package com.itsmikefan.spotifastcustom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

data class Playlist(
    val name: String, val uri: String, val tracksPath: String, val count: Int,
    val id: String = "", val owned: Boolean = false, val public: Boolean? = null, val cover: String? = null,
)
data class Track(
    val uri: String, val name: String, val artists: String, val lead: String, val durationMs: Int,
    val cover: String? = null,
    /** False when Spotify says this account can't play it, as the desktop greys out. */
    val playable: Boolean = true,
)
data class Device(val id: String, val name: String, val type: String, val active: Boolean)
/** What plays on another Spotify Connect device. */
data class Remote(val device: String, val playing: Boolean, val name: String, val artists: String, val cover: String?)

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
        "user-read-playback-state", "user-modify-playback-state",
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

    // A sign-in from before the device scopes were asked for counts as signed out, so step 1 asks again.
    val signedIn get() = prefs.contains("refresh_token") && prefs.getString("scopes", "") == WEB_SCOPES.joinToString(" ")
    val userId get() = prefs.getString("user_id", "") ?: ""

    fun signOut() = prefs.edit().remove("refresh_token").remove("access_token").remove("user_id").apply()

    /** Browser sign-in for the Web API, with the listener's own app. */
    suspend fun signIn(context: Context) {
        val token = authorize(context, clientId, WEB_PORT, WEB_SCOPES, showDialog = false)
        save(token)
        val me = get("/me")
        prefs.edit().putString("user_id", me.getString("id")).putString("scopes", WEB_SCOPES.joinToString(" ")).apply()
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

    private val http = okhttp3.OkHttpClient()

    /** Every song seen in a loaded playlist, so "Up next" can name the queue's URIs. */
    val known = java.util.concurrent.ConcurrentHashMap<String, Track>()

    /** A Web API GET; [path] is relative to /v1 or a full `next` URL. */
    suspend fun get(path: String): JSONObject = call("GET", path, null) ?: JSONObject()

    /** A Web API request, refreshing the token once on 401 and waiting once on 429. Returns the JSON body, if any. */
    private suspend fun call(method: String, path: String, body: JSONObject?): JSONObject? = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("https://")) path else "https://api.spotify.com/v1$path"
        // OkHttp needs a body for PUT and POST, even an empty one.
        val payload = (body?.toString() ?: if (method == "PUT" || method == "POST") "" else null)
            ?.toRequestBody("application/json".toMediaType())
        var refreshed = false
        var waited = false
        while (true) {
            val request = okhttp3.Request.Builder().url(url)
                .header("Authorization", "Bearer ${accessToken(refreshed)}")
                .method(method, payload)
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> return@withContext text.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                    response.code == 401 && !refreshed -> refreshed = true
                    response.code == 429 && !waited -> {
                        waited = true
                        Thread.sleep((response.header("Retry-After")?.toLongOrNull() ?: 2).coerceAtMost(30) * 1000)
                    }
                    else -> throw IOException("Spotify answered ${response.code}: $text")
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    suspend fun add(playlist: Playlist, uris: List<String>, position: Int? = null) {
        val body = JSONObject().put("uris", JSONArray(uris))
        position?.let { body.put("position", it) }
        call("POST", "/playlists/${playlist.id}/items", body)
    }

    /** Removes every copy of each URI; see [Dupes.plan] to keep some. */
    suspend fun remove(playlist: Playlist, uris: List<String>) {
        // Spotify takes at most 100 per request.
        for (chunk in uris.chunked(100)) {
            val items = JSONArray(chunk.map { JSONObject().put("uri", it) })
            call("DELETE", "/playlists/${playlist.id}/items", JSONObject().put("items", items))
        }
    }

    suspend fun setPublic(playlist: Playlist, public: Boolean) {
        // Sent with the name, as the desktop's update_playlist does.
        call("PUT", "/playlists/${playlist.id}", JSONObject().put("name", playlist.name).put("public", public))
    }

    suspend fun playlists(): List<Playlist> {
        val liked = Playlist("Liked Songs", "spotify:user:$userId:collection", "/me/tracks?limit=50&market=from_token", -1)
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
                val owner = p.optJSONObject("owner")?.optString("id")
                val public = if (p.isNull("public")) null else p.optBoolean("public")
                result += Playlist(
                    p.optString("name"), p.getString("uri"), "/playlists/$id/items?limit=50&market=from_token&fields=$ITEM_FIELDS", count,
                    id, owner == userId, public, image(p.optJSONArray("images")),
                )
            }
            next = page.optString("next").takeIf { it.isNotEmpty() && it != "null" }
        }
        return result
    }

    /**
     * Every song in [playlist]. The first page goes to [onPage] at once and the
     * rest follow a few pages at a time, fetched side by side, so a long
     * playlist shows its top while the remainder loads.
     */
    suspend fun tracks(playlist: Playlist, onPage: (List<Track>) -> Unit = {}): List<Track> = coroutineScope {
        val first = get(playlist.tracksPath)
        val result = songs(first).toMutableList()
        onPage(result.toList())
        val total = first.optInt("total", result.size)
        val offsets = (PAGE until total step PAGE).toList()
        for (batch in offsets.chunked(4)) {
            batch.map { offset -> async { songs(get("${playlist.tracksPath}&offset=$offset")) } }
                .awaitAll().forEach { result += it }
            onPage(result.toList())
        }
        result
    }

    private const val PAGE = 50
    /** Only what a song row needs; without it every song carries its full album and market list. */
    private val ITEM_FIELDS = "uri,name,duration_ms,is_playable,is_local,artists(name),album(images)".let { song ->
        URLEncoder.encode("total,next,items(item($song),track($song))", "UTF-8")
    }

    private fun songs(page: JSONObject): List<Track> {
        val items = page.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val row = items.optJSONObject(i) ?: return@mapNotNull null
            song(row.optJSONObject("item") ?: row.optJSONObject("track") ?: return@mapNotNull null)
        }
    }

    private fun song(t: JSONObject): Track? {
        val uri = t.optString("uri").takeIf { it.isNotEmpty() } ?: return null
        val names = t.optJSONArray("artists")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it).optString("name") }
        } ?: emptyList()
        return Track(
            uri, t.optString("name"), names.joinToString(", "), names.firstOrNull() ?: "", t.optInt("duration_ms"),
            image(t.optJSONObject("album")?.optJSONArray("images")),
            // Only a definite "false" counts; local files and missing answers stay playable, as on the desktop.
            playable = t.optBoolean("is_local") || !t.has("is_playable") || t.optBoolean("is_playable", true),
        ).also { known[uri] = it }
    }

    /** A song Spotify queued that no loaded playlist holds, such as one queued in the Spotify app. */
    suspend fun lookup(uri: String): Track? {
        known[uri]?.let { return it }
        val id = uri.removePrefix("spotify:track:").takeIf { it != uri } ?: return null
        return runCatching { song(get("/tracks/$id?market=from_token")) }.getOrNull()
    }

    /** The smallest image, which is plenty for a row; Spotify lists the largest first. */
    private fun image(images: JSONArray?): String? =
        images?.takeIf { it.length() > 0 }?.let { it.optJSONObject(it.length() - 1) }?.optString("url")?.takeIf { it.isNotEmpty() }

    suspend fun devices(): List<Device> {
        val list = get("/me/player/devices").optJSONArray("devices") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }.filter { !it.isNull("id") }.map {
            Device(it.getString("id"), it.optString("name"), it.optString("type"), it.optBoolean("is_active"))
        }
    }

    suspend fun transfer(deviceId: String) {
        call("PUT", "/me/player", JSONObject().put("device_ids", JSONArray(listOf(deviceId))).put("play", true))
    }

    /** What plays now on whichever device is active, or null when nothing is. */
    suspend fun remote(): Remote? {
        val o = call("GET", "/me/player", null) ?: return null
        val item = o.optJSONObject("item") ?: return null
        val artists = item.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") } } ?: ""
        return Remote(
            o.optJSONObject("device")?.optString("name") ?: "", o.optBoolean("is_playing"), item.optString("name"), artists,
            image(item.optJSONObject("album")?.optJSONArray("images")),
        )
    }

    /** What plays after the current song on whichever device is active. */
    suspend fun remoteQueue(): List<String> {
        val queue = get("/me/player/queue").optJSONArray("queue") ?: return emptyList()
        return (0 until queue.length()).mapNotNull { queue.optJSONObject(it)?.let(::song)?.uri }
    }

    /** Play, pause, next or previous on the active device. */
    suspend fun remoteCommand(command: Int) {
        when (command) {
            Core.PLAY -> call("PUT", "/me/player/play", null)
            Core.PAUSE -> call("PUT", "/me/player/pause", null)
            Core.NEXT -> call("POST", "/me/player/next", null)
            Core.PREVIOUS -> call("POST", "/me/player/previous", null)
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun random(bytes: Int): String {
        val data = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
