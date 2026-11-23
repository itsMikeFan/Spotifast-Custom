package com.itsmikefan.spotifastcustom

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsmikefan.spotifastcustom.ui.Chip
import com.itsmikefan.spotifastcustom.ui.Cover
import com.itsmikefan.spotifastcustom.ui.IconBtn
import com.itsmikefan.spotifastcustom.ui.Icons
import com.itsmikefan.spotifastcustom.ui.LocalPalette
import com.itsmikefan.spotifastcustom.ui.Radius
import com.itsmikefan.spotifastcustom.ui.RadiusSmall
import com.itsmikefan.spotifastcustom.ui.SearchField

private enum class Sort(val label: String) { Recent("Recently added"), Name("Name"), Owner("Owner") }

/** Your playlists, Liked Songs first, as rows or cover cards. */
@Composable
fun LibraryScreen(playlists: List<Playlist>?, nav: Nav) {
    val s by Settings.state.collectAsState()
    val p = LocalPalette.current
    var filter by rememberSaveable { mutableStateOf("") }
    var byYou by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(Sort.Recent) }
    var sorting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your library", style = MaterialTheme.typography.headlineSmall, color = p.text, modifier = Modifier.weight(1f))
            Box {
                IconBtn(Icons.ArrowDownUp, "Sort") { sorting = true }
                DropdownMenu(sorting, { sorting = false }) {
                    for (o in Sort.entries) DropdownMenuItem(
                        text = { Text(o.label, color = if (o == sort) p.accent else p.text) },
                        onClick = { sort = o; sorting = false },
                    )
                }
            }
            IconBtn(if (s.libraryGrid) Icons.List else Icons.LayoutGrid, if (s.libraryGrid) "Show as list" else "Show as grid") {
                Settings.update { it.copy(libraryGrid = !it.libraryGrid) }
            }
        }
        SearchField(filter, "Filter your library") { filter = it }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("All", selected = !byYou) { byYou = false }
            Chip("By you", selected = byYou) { byYou = true }
        }
        val list = playlists ?: return@Column Loading()
        val liked = list.firstOrNull()
        val rest = list.drop(1).filter { (!byYou || it.owned) && (filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true)) }
            .let { r -> when (sort) { Sort.Recent -> r; Sort.Name -> r.sortedBy { it.name.lowercase() }; Sort.Owner -> r.sortedBy { it.owner.lowercase() } } }
        val shown = listOfNotNull(liked?.takeIf { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }) + rest
        if (s.libraryGrid) LazyVerticalGrid(
            GridCells.Fixed(2), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(shown, key = { it.uri }) { pl -> PlaylistCard(pl, nav) }
        } else LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
            items(shown, key = { it.uri }) { pl -> PlaylistRow(pl, nav) }
        }
    }
}

/** Liked Songs has no image of its own: the desktop's purple-to-blue square with a heart. */
@Composable
fun PlaylistCover(pl: Playlist, size: Dp, modifier: Modifier = Modifier) {
    if (pl.cover == null && pl.uri.endsWith(":collection")) Box(
        modifier.size(size).clip(RadiusSmall).background(Brush.linearGradient(listOf(Color(0xFF4A2FBD), Color(0xFF8DA4E6)))),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.HeartFilled, null, Modifier.size(size * 0.4f), tint = Color.White) }
    else Cover(pl.bigCover ?: pl.cover, size, modifier)
}

private fun detail(pl: Playlist) = buildList {
    add("Playlist")
    if (pl.owner.isNotEmpty()) add(pl.owner)
    if (pl.count >= 0) add("${pl.count} songs")
}.joinToString(" · ")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(pl: Playlist, nav: Nav) {
    val p = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = { nav.openPlaylist(pl) }, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaylistCover(pl, 48.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(pl.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail(pl), fontSize = 13.sp, color = p.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        PlaylistMenu(pl, menu, nav) { menu = false }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCard(pl: Playlist, nav: Nav) {
    val p = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(Modifier.fillMaxWidth().clip(Radius).combinedClickable(onClick = { nav.openPlaylist(pl) }, onLongClick = { menu = true })) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) { PlaylistCover(pl, 400.dp, Modifier.fillMaxSize()) }
            Spacer(Modifier.size(6.dp))
            Text(pl.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail(pl), fontSize = 12.sp, color = p.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlaylistMenu(pl, menu, nav) { menu = false }
    }
}

@Composable
private fun PlaylistMenu(pl: Playlist, open: Boolean, nav: Nav, close: () -> Unit) {
    DropdownMenu(open, close) {
        DropdownMenuItem(text = { Text("Play") }, onClick = { close(); Playback.playShuffled(pl.uri, false, nav.error) })
        DropdownMenuItem(text = { Text("Shuffle play") }, onClick = { close(); Playback.playShuffled(pl.uri, true, nav.error) })
        DropdownMenuItem(text = { Text("Open") }, onClick = { close(); nav.openPlaylist(pl) })
    }
}

