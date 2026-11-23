package com.itsmikefan.spotifastcustom

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsmikefan.spotifastcustom.ui.Chip
import com.itsmikefan.spotifastcustom.ui.Cover
import com.itsmikefan.spotifastcustom.ui.IconBtn
import com.itsmikefan.spotifastcustom.ui.Icons
import com.itsmikefan.spotifastcustom.ui.LocalPalette
import com.itsmikefan.spotifastcustom.ui.PlayCircle
import com.itsmikefan.spotifastcustom.ui.SearchField
import com.itsmikefan.spotifastcustom.ui.rememberArtColours
import com.itsmikefan.spotifastcustom.ui.tintFromArt
import kotlinx.coroutines.launch

/** A playlist (or album): tinted header, action row, then its songs. */
@Composable
fun PlaylistScreen(playlist: Playlist, nav: Nav, back: () -> Unit, onPublic: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val s by Settings.state.collectAsState()
    val playing by Playback.state.collectAsState()
    var tracks by remember(playlist.uri) { mutableStateOf<List<Track>?>(null) }
    var complete by remember(playlist.uri) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var unavailableOnly by remember(playlist.uri) { mutableStateOf(false) }
    var filtering by remember(playlist.uri) { mutableStateOf(false) }
    var filter by remember(playlist.uri) { mutableStateOf("") }
    var public by remember(playlist.uri) { mutableStateOf(playlist.public) }
    var shuffle by remember(playlist.uri) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(playlist.uri, reload) {
        complete = false
        // Album songs come without their album, so they borrow its cover.
        fun fill(list: List<Track>) = if (playlist.uri.startsWith("spotify:album:")) list.map { it.copy(cover = it.cover ?: playlist.cover, bigCover = it.bigCover ?: playlist.bigCover) } else list
        runCatching { Spotify.tracks(playlist) { tracks = fill(it) } }
            .onSuccess { tracks = fill(it); complete = true }
            .onFailure { nav.error(it.message ?: "Could not load songs") }
    }
    val art = rememberArtColours(if (s.accentFromArt) playlist.bigCover ?: playlist.cover else null)
    val tint = tintFromArt(art, p) ?: p.surface
    val list = tracks
    val unavailable = list.orEmpty().count { !it.playable }
    val shown = list.orEmpty().filter { (!unavailableOnly || !it.playable) && matches(it, filter) }
    val totalMs = list.orEmpty().sumOf { it.durationMs.toLong() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tint, p.window))).padding(bottom = 8.dp)) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Icons.ArrowLeft, "Back", tint = p.text, onClick = back)
                    Spacer(Modifier.weight(1f))
                    IconBtn(Icons.Search, "Filter this playlist", on = filtering) { filtering = !filtering; if (!filtering) filter = "" }
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PlaylistCover(playlist, 200.dp)
                }
                Spacer(Modifier.height(16.dp))
                Text(playlist.name, style = MaterialTheme.typography.headlineSmall, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                val facts = buildList {
                    if (playlist.owner.isNotEmpty()) add(playlist.owner)
                    if (list != null) add("${list.size} songs")
                    if (complete && totalMs > 0) add(length(totalMs))
                }.joinToString(" · ")
                Text(facts, fontSize = 13.sp, color = p.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PlayCircle(playing = false, size = 52.dp) { Playback.playShuffled(playlist.uri, shuffle, nav.error) }
                IconBtn(Icons.Shuffle, if (shuffle) "Shuffle on" else "Shuffle off", 24.dp, on = shuffle) { shuffle = !shuffle }
                if (playlist.owned) IconBtn(Icons.CopyX, "Find duplicates", 22.dp, enabled = complete && list != null) {
                    list?.let { nav.sheet(Sheet.Duplicates(playlist, it) { reload++ }) }
                }
                if (playlist.owned) {
                    // Unchanged from before the redesign, as asked.
                    Text(if (public == false) "Private" else "Public", color = p.secondary, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
                    TextButton(onClick = {
                        val was = public
                        val now = was == false
                        public = now
                        onPublic(now)
                        scope.launch {
                            runCatching { Spotify.setPublic(playlist, now) }
                                .onSuccess { Spotify.publicNow(playlist)?.takeIf { it != now }?.let {
                                    nav.error("Spotify took the change but still lists this playlist as ${if (it) "public" else "private"}.")
                                } }
                                .onFailure {
                                    public = was
                                    was?.let(onPublic)
                                    nav.error("Could not make it ${if (now) "public" else "private"}: ${it.message}")
                                }
                        }
                    }) { Text(if (public == false) "Make public" else "Make private", color = p.text) }
                }
                // As on the desktop: shows only the songs Spotify says this account can't play.
                if (unavailable > 0 || unavailableOnly) Chip(if (unavailableOnly) "Show all" else "Unavailable ($unavailable)", selected = unavailableOnly) {
                    unavailableOnly = !unavailableOnly
                }
            }
        }
        if (filtering) item { SearchField(filter, "Filter this playlist") { filter = it } }
        if (list == null) item { Box(Modifier.fillMaxWidth().height(160.dp)) { Loading() } }
        itemsIndexed(shown, key = { i, t -> "$i:${t.uri}" }) { _, t ->
            SongRow(t, s.compactList, current = t.uri == playing.uri, nav = nav) { Playback.play(playlist.uri, t.uri, nav.error) }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

private fun length(ms: Long): String {
    val minutes = ms / 60000
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

fun matches(t: Track, query: String) =
    query.isBlank() || t.name.contains(query.trim(), ignoreCase = true) || t.artists.contains(query.trim(), ignoreCase = true)

/** One song: tap plays it; the ⋮ button or a long press opens its sheet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(t: Track, compact: Boolean, current: Boolean, nav: Nav, play: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = play, onLongClick = { nav.sheet(Sheet.Song(t)) })
            .padding(start = 16.dp, end = 0.dp, top = if (compact) 2.dp else 6.dp, bottom = if (compact) 2.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!compact) Box(Modifier.alpha(if (t.playable) 1f else 0.35f)) { Cover(t.cover, 44.dp) }
        Column(Modifier.weight(1f).padding(start = if (compact) 0.dp else 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current) Icon(Icons.AudioLines, null, Modifier.size(14.dp).padding(end = 4.dp), tint = p.accent)
                Text(
                    t.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    color = when { current -> p.accent; !t.playable -> p.dim; else -> p.text },
                )
            }
            Text(
                if (t.playable) t.artists else "Unavailable · ${t.artists}",
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.secondary, fontSize = 13.sp,
            )
        }
        if (t.durationMs > 0) Text(time(t.durationMs), fontSize = 12.sp, color = p.dim, modifier = Modifier.padding(start = 8.dp))
        IconBtn(Icons.EllipsisVertical, "More", 20.dp) { nav.sheet(Sheet.Song(t)) }
    }
}
