package com.itsmikefan.spotifastcustom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsmikefan.spotifastcustom.ui.Art
import com.itsmikefan.spotifastcustom.ui.Icons
import com.itsmikefan.spotifastcustom.ui.LocalPalette
import com.itsmikefan.spotifastcustom.ui.Palette
import com.itsmikefan.spotifastcustom.ui.RowDivider
import com.itsmikefan.spotifastcustom.ui.Themes
import com.itsmikefan.spotifastcustom.ui.rememberArtColours
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * The desktop's player bar visualizer: Spectrum (75 bands with falling peak
 * caps), Waveform or Off, in the cover's colours swept from bass to treble,
 * or one colour of the listener's own.
 */
@Composable
fun Visualizer(playing: Boolean, cover: String?, modifier: Modifier, alpha: Float = 1f) {
    val s by Settings.state.collectAsState()
    val p = LocalPalette.current
    if (s.playerBarVis == AppSettings.VIS_OFF) return Box(modifier)
    val art = rememberArtColours(cover)
    val colours = remember(art, s.playerBarVisColour, p) {
        s.playerBarVisColour?.let { listOf(withinBrightness(Color(it), p.dark)) } ?: visColours(art, p.accent, p.dark)
    }
    var levels by remember { mutableStateOf(FloatArray(0)) }
    var wave by remember { mutableStateOf(FloatArray(0)) }
    var points by remember { mutableStateOf(96) }
    val waveform = s.playerBarVis == AppSettings.VIS_WAVEFORM
    LaunchedEffect(playing, waveform, s.visualizerFps) {
        val frameNs = 1_000_000_000L / s.visualizerFps.coerceIn(15, 120)
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (now - last < frameNs) continue
            last = now
            if (waveform) {
                wave = if (playing) withContext(Dispatchers.Default) { Core.scope(points) } else FloatArray(0)
                if (!playing) break
            } else {
                val next = withContext(Dispatchers.Default) { Core.bands() }
                levels = next
                // Paused: keep stepping until every bar and cap has fallen.
                if (!playing && next.all { it == 0f }) break
            }
        }
    }
    val strength = if (p.dark) 1f else 1.5f
    val density = androidx.compose.ui.platform.LocalDensity.current
    Canvas(modifier.onSizeChanged { points = (it.width / with(density) { 8.dp.toPx() }).toInt().coerceIn(24, 200) }) {
        val bandW = 4.dp.toPx()
        if (waveform) {
            if (wave.isEmpty()) return@Canvas
            val mid = size.height / 2
            val step = size.width / (wave.size - 1).coerceAtLeast(1)
            val line = Path().apply {
                wave.forEachIndexed { i, v -> val x = i * step; val y = mid - v * mid * 0.95f; if (i == 0) moveTo(x, y) else lineTo(x, y) }
            }
            val brush = if (colours.size > 1) Brush.horizontalGradient(colours.map { it.copy(alpha = alpha) }) else Brush.linearGradient(listOf(colours[0].copy(alpha = alpha), colours[0].copy(alpha = alpha)))
            val fill = Path().apply { addPath(line); lineTo(size.width, mid); lineTo(0f, mid); close() }
            drawPath(fill, brush, alpha = (0.35f * strength).coerceAtMost(1f))
            drawPath(line, brush, style = Stroke(width = 2.dp.toPx()))
            return@Canvas
        }
        val half = levels.size / 2
        if (half == 0) return@Canvas
        // One bar per 4 dp: a narrow screen groups neighbouring bands and shows their loudest.
        val bars = (size.width / bandW).toInt().coerceIn(12, half)
        val slot = size.width / bars
        val gap = (slot * 0.25f).coerceAtMost(2.dp.toPx())
        for (b in 0 until bars) {
            val from = b * half / bars
            val to = ((b + 1) * half / bars).coerceAtLeast(from + 1)
            var level = 0f
            var peak = 0f
            for (i in from until to) { level = maxOf(level, levels[i]); peak = maxOf(peak, levels[half + i]) }
            val c = colourAt(colours, if (bars > 1) b / (bars - 1f) else 0f)
            val x = b * slot
            val h = size.height * level.coerceIn(0f, 1f)
            if (h > 0f) drawRect(c, Offset(x, size.height - h), Size(slot - gap, h), alpha = (alpha * strength).coerceAtMost(1f))
            if (peak > 0f) {
                val y = size.height - size.height * peak.coerceIn(0f, 1f)
                drawRect(c, Offset(x, (y - 2.dp.toPx()).coerceAtLeast(0f)), Size(slot - gap, 2.dp.toPx()), alpha = (alpha * strength).coerceAtMost(1f))
            }
        }
    }
}

/** The colour [at] the given fraction of the way along [colours]. */
private fun colourAt(colours: List<Color>, at: Float): Color {
    if (colours.size == 1) return colours[0]
    val x = at.coerceIn(0f, 1f) * (colours.size - 1)
    val i = x.toInt().coerceAtMost(colours.size - 2)
    return lerp(colours[i], colours[i + 1], x - i)
}

/**
 * As the desktop's `vis_colours`: the cover's hues in its order then its
 * grey, each made vivid; with no cover, the accent for the bass and the
 * accent turned a sixth of the way round for the treble.
 */
fun visColours(art: List<Color>, accent: Color, dark: Boolean): List<Color> {
    fun keep(c: Color, vivid: Boolean): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), hsv)
        hsv[1] = if (vivid) maxOf(hsv[1], 0.55f) else 0f
        hsv[2] = 1f
        return withinBrightness(Color(android.graphics.Color.HSVToColor(hsv)), dark)
    }
    if (art.isNotEmpty()) return art.filter { Art.hasHue(it) }.map { keep(it, true) } + art.filterNot { Art.hasHue(it) }.map { keep(it, false) }
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
    val high = hsv.copyOf().also { it[0] = (it[0] + 60f) % 360f }
    return listOf(keep(accent, true), keep(Color(android.graphics.Color.HSVToColor(high)), true))
}

private fun linear(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
private fun gamma(c: Float) = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

/** As the desktop's `within_brightness`: hue kept, brightness moved into the band the bar's words read against. */
fun withinBrightness(colour: Color, dark: Boolean): Color {
    val r = linear(colour.red); val g = linear(colour.green); val b = linear(colour.blue)
    val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
    val ceiling = if (dark) 0.35f else 0.4f
    val floor = 0.2f
    val (nr, ng, nb) = when {
        lum > ceiling -> Triple(r * ceiling / lum, g * ceiling / lum, b * ceiling / lum)
        !dark && lum < floor -> {
            val t = (floor - lum) / maxOf(1f - lum, 1e-6f)
            Triple(r + (1 - r) * t, g + (1 - g) * t, b + (1 - b) * t)
        }
        else -> Triple(r, g, b)
    }
    return Color(gamma(nr).coerceIn(0f, 1f), gamma(ng).coerceIn(0f, 1f), gamma(nb).coerceIn(0f, 1f))
}

/** The long-press menu, as the desktop's right-click menu on the player bar: style, then colours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisualizerOptions() {
    val p = LocalPalette.current
    val s by Settings.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    Text("Visualizer", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
    for ((mode, label) in listOf(AppSettings.VIS_SPECTRUM to "Spectrum", AppSettings.VIS_WAVEFORM to "Waveform", AppSettings.VIS_OFF to "Off")) {
        CheckRow(label, s.playerBarVis == mode) { Settings.update { it.copy(playerBarVis = mode) } }
    }
    RowDivider()
    CheckRow("Colours from album art", s.playerBarVisColour == null) { Settings.update { it.copy(playerBarVisColour = null) } }
    Text("Custom colour", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.secondary, modifier = Modifier.padding(20.dp, 12.dp, 20.dp, 8.dp))
    // The accent, white, and each shipped theme's accent.
    val swatches = remember {
        (listOf(p.accent, Color.White, Palette.Dark.accent) + Themes.all(context).map { it.palette.accent }).distinctBy { it.toArgb() }.take(10)
    }
    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (c in swatches) {
            val chosen = s.playerBarVisColour == c.toArgb()
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(c)
                    .border(if (chosen) 3.dp else 1.dp, if (chosen) p.text else p.outline, CircleShape)
                    .clickable { Settings.update { it.copy(playerBarVisColour = c.toArgb()) } },
            )
        }
    }
    // Anything else: hue and brightness. Moving either picks a custom colour, as on the desktop.
    val current = s.playerBarVisColour?.let { Color(it) } ?: p.accent
    val hsv = remember(current) { FloatArray(3).also { android.graphics.Color.colorToHSV(current.toArgb(), it) } }
    fun pick(h: Float, v: Float) = Settings.update { it.copy(playerBarVisColour = android.graphics.Color.HSVToColor(floatArrayOf(h, maxOf(hsv[1], 0.6f), v))) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("Hue", fontSize = 12.sp, color = p.secondary)
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Canvas(Modifier.fillMaxWidth().height(8.dp).align(Alignment.Center).clip(CircleShape)) {
                drawRect(Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 0.8f, 1f))) }))
            }
            Slider(hsv[0], { pick(it, hsv[2]) }, valueRange = 0f..359f, colors = SliderDefaults.colors(thumbColor = current, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent))
        }
        Text("Brightness", fontSize = 12.sp, color = p.secondary)
        Slider(hsv[2], { pick(hsv[0], it.coerceAtLeast(0.15f)) }, colors = SliderDefaults.colors(thumbColor = current, activeTrackColor = current, inactiveTrackColor = p.surfaceActive))
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp, color = if (checked) p.accent else p.text)
        if (checked) Icon(Icons.Check, null, Modifier.size(18.dp), tint = p.accent)
    }
}
