package com.itsmikefan.spotifastcustom

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsmikefan.spotifastcustom.ui.IconBtn
import com.itsmikefan.spotifastcustom.ui.Icons
import com.itsmikefan.spotifastcustom.ui.LocalPalette
import kotlinx.coroutines.launch

/** Songs from all of Spotify, your matching playlists first, and recent searches under an empty field. */
@Composable
fun SearchScreen(playlists: List<Playlist>, nav: Nav) {
    val p = LocalPalette.current
    val s by Settings.state.collectAsState()
    val playing by Playback.state.collectAsState()
    var field by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember(query) { mutableStateOf<List<Track>?>(null) }
    var more by remember(query) { mutableStateOf(true) }
    var loading by remember(query) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load() {
        loading = true
        scope.launch {
            runCatching { Spotify.search(query, results?.size ?: 0) }
                .onSuccess { page -> results = results.orEmpty() + page; more = page.isNotEmpty() }
                .onFailure { nav.error(it.message ?: "Search failed"); results = results.orEmpty(); more = false }
            loading = false
        }
    }
    fun submit(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        field = t
        query = t
        Settings.rememberSearch(t)
    }
    LaunchedEffect(query) { if (query.isNotEmpty()) load() }
    Column(Modifier.fillMaxSize()) {
        Text("Search", style = MaterialTheme.typography.headlineSmall, color = p.text, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
        OutlinedTextField(
            field, { field = it; if (it.isBlank()) query = "" },
            placeholder = { Text("What do you want to listen to?", color = p.dim) }, singleLine = true,
            leadingIcon = { Icon(Icons.Search, null, Modifier.size(18.dp), tint = p.secondary) },
            trailingIcon = { if (field.isNotEmpty()) IconBtn(Icons.X, "Clear", 18.dp) { field = ""; query = "" } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit(field) }),
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = p.surface, unfocusedContainerColor = p.surface,
                focusedBorderColor = p.accent, unfocusedBorderColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
        val own = if (field.isBlank()) emptyList() else playlists.filter { it.name.contains(field.trim(), ignoreCase = true) }.take(5)
        LazyColumn(Modifier.weight(1f)) {
            if (field.isBlank()) {
                if (s.searchHistory.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent searches", fontWeight = FontWeight.SemiBold, color = p.text, modifier = Modifier.weight(1f))
                        TextButton(onClick = { Settings.update { it.copy(searchHistory = emptyList()) } }) { Text("Clear", color = p.secondary) }
                    }
                }
                items(s.searchHistory) { h ->
                    Row(Modifier.fillMaxWidth().clickable { submit(h) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Search, null, Modifier.size(16.dp), tint = p.dim)
                        Text(h, Modifier.padding(start = 12.dp), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                return@LazyColumn
            }
            if (query != field.trim()) item {
                Text(
                    "Search Spotify for “${field.trim()}”", color = p.accent, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().clickable { submit(field) }.padding(16.dp, 12.dp),
                )
            }
            if (own.isNotEmpty()) {
                item { Header("Your playlists") }
                items(own, key = { "pl:" + it.uri }) { pl ->
                    Row(Modifier.fillMaxWidth().clickable { nav.openPlaylist(pl) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlaylistCover(pl, 44.dp)
                        Text(pl.name, Modifier.padding(start = 12.dp), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (query.isNotEmpty()) {
                item { Header("Songs") }
                val list = results
                if (list == null) item { Box(Modifier.fillMaxWidth().height(120.dp)) { Loading() } }
                else {
                    if (list.isEmpty()) item { Text("No songs found.", color = p.secondary, modifier = Modifier.padding(16.dp)) }
                    items(list, key = { "t:" + it.uri }) { t -> SongRow(t, s.compactList, t.uri == playing.uri, nav) { Playback.play(t.uri, null, nav.error) } }
                    if (more && list.isNotEmpty()) item {
                        TextButton(enabled = !loading, onClick = { load() }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (loading) "Loading…" else "More songs", color = p.accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = LocalPalette.current.text, modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 6.dp))
}
