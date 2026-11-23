package com.itsmikefan.spotifastcustom.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A cover's own colours, read once per image and kept for the session. */
object Art {
    private val cache = ConcurrentHashMap<String, List<Color>>()

    /** The cover's colours, most common first, then its greys: what the desktop's player bar sweeps across. */
    suspend fun colours(context: Context, url: String): List<Color> {
        cache[url]?.let { return it }
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(96).build()
        val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.let { (it as? BitmapDrawable)?.bitmap } ?: return emptyList()
        val found = withContext(Dispatchers.Default) { swatches(bitmap) }
        cache[url] = found
        return found
    }

    private fun swatches(bitmap: Bitmap): List<Color> {
        val palette = androidx.palette.graphics.Palette.from(bitmap).maximumColorCount(12).generate()
        val all = palette.swatches.sortedByDescending { it.population }.map { Color(it.rgb) }
        val (vivid, grey) = all.partition { hasHue(it) }
        return (vivid + grey).take(6)
    }

    /** As the desktop's `has_hue`: enough saturation to read as a colour rather than a grey. */
    fun hasHue(c: Color): Boolean {
        val max = maxOf(c.red, c.green, c.blue)
        val min = minOf(c.red, c.green, c.blue)
        return max > 0.08f && (max - min) / max > 0.18f
    }
}

@Composable
fun rememberArtColours(url: String?): List<Color> {
    val context = LocalContext.current
    var colours by remember(url) { mutableStateOf<List<Color>>(emptyList()) }
    LaunchedEffect(url) { if (url != null) colours = Art.colours(context, url) }
    return colours
}

/** The colour a header or the full player fades from: the cover's first hue, softened so text stays readable. */
fun tintFromArt(art: List<Color>, palette: Palette): Color? {
    val c = art.firstOrNull { Art.hasHue(it) } ?: art.firstOrNull() ?: return null
    return if (palette.dark) lerp(palette.window, c, 0.45f) else lerp(palette.window, c, 0.30f)
}
