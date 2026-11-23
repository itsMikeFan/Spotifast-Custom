package com.itsmikefan.spotifastcustom.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.itsmikefan.spotifastcustom.R
import org.json.JSONObject
import java.io.File

/** The desktop's `Palette` (src/theme.rs), colour for colour, so its theme files work here too. */
@Immutable
data class Palette(
    val dark: Boolean,
    val window: Color,
    val panel: Color,
    val surface: Color,
    val surfaceHover: Color,
    val surfaceActive: Color,
    val outline: Color,
    val text: Color,
    val secondary: Color,
    val dim: Color,
    val accent: Color,
    val accentHover: Color,
    val onAccent: Color,
    val danger: Color,
    val warning: Color,
    val overlay: Color,
    val shadow: Color,
    val tint: Color,
) {
    companion object {
        val Dark = Palette(
            dark = true,
            window = Color(0xFF0F1114), panel = Color(0xFF15181C), surface = Color(0xFF1D2127),
            surfaceHover = Color(0xFF262B33), surfaceActive = Color(0xFF2F353F), outline = Color(0xFF2A3038),
            text = Color(0xFFF2F4F6), secondary = Color(0xFFA9B1BC), dim = Color(0xFF6E7784),
            accent = Color(0xFF1ED760), accentHover = Color(0xFF3CE87A), onAccent = Color(0xFF0A140E),
            danger = Color(0xFFF5717F), warning = Color(0xFFF2B85C), overlay = Color(0xFF22272E),
            shadow = Color(0x8C000000), tint = Color.Transparent,
        )
        val Light = Palette(
            dark = false,
            window = Color(0xFFF8F9FB), panel = Color(0xFFFFFFFF), surface = Color(0xFFEEF0F3),
            surfaceHover = Color(0xFFE3E6EB), surfaceActive = Color(0xFFD7DBE1), outline = Color(0xFFDDE1E6),
            text = Color(0xFF14171A), secondary = Color(0xFF535B66), dim = Color(0xFF8B939E),
            accent = Color(0xFF15A64A), accentHover = Color(0xFF128F40), onAccent = Color.White,
            danger = Color(0xFFD63B4C), warning = Color(0xFFB87A14), overlay = Color.White,
            shadow = Color(0x32000000), tint = Color.Transparent,
        )

        /**
         * Reads a desktop palette file: `base`, then any of the colours as #RRGGBB or #RRGGBBAA.
         * Throws with the colour's name when one is wrong, as the desktop does.
         */
        fun parse(text: String): Palette {
            val o = JSONObject(text)
            var p = if (o.optString("base", "dark") == "light") Light else Dark
            val colors = o.optJSONObject("colors") ?: JSONObject()
            val given = colors.keys().asSequence().toSet()
            for (name in given) {
                val c = color(colors.getString(name)) ?: error("$name: expected #RRGGBB or #RRGGBBAA")
                p = when (name) {
                    "window" -> p.copy(window = c)
                    "panel" -> p.copy(panel = c)
                    "surface" -> p.copy(surface = c)
                    "surface_hover" -> p.copy(surfaceHover = c)
                    "surface_active" -> p.copy(surfaceActive = c)
                    "outline" -> p.copy(outline = c)
                    "text" -> p.copy(text = c)
                    "secondary" -> p.copy(secondary = c)
                    "dim" -> p.copy(dim = c)
                    "accent" -> p.copy(accent = c)
                    "accent_hover" -> p.copy(accentHover = c)
                    "on_accent" -> p.copy(onAccent = c)
                    "danger" -> p.copy(danger = c)
                    "warning" -> p.copy(warning = c)
                    "overlay" -> p.copy(overlay = c)
                    "shadow" -> p.copy(shadow = c)
                    "tint" -> p.copy(tint = c)
                    else -> error("unknown color: $name")
                }
            }
            // As the desktop derives it: menus follow a file's window when it leaves overlay out.
            if ("window" in given && "overlay" !in given) {
                p = p.copy(overlay = if (p.dark) lerp(p.window, p.text, 0.08f) else p.panel)
            }
            return p
        }

        private fun color(hex: String): Color? {
            val h = hex.removePrefix("#")
            if (!hex.startsWith("#") || (h.length != 6 && h.length != 8)) return null
            val v = h.toLongOrNull(16) ?: return null
            return if (h.length == 6) Color(0xFF000000 or v) else Color(((v and 0xFF) shl 24) or (v shr 8))
        }
    }
}

/** A theme the picker lists: its name and colours. */
data class Theme(val name: String, val palette: Palette)

object Themes {
    const val SYSTEM = "Follow system"
    const val DARK = "Spotifast Dark"
    const val LIGHT = "Spotifast Light"

    private fun imported(context: Context) = File(context.filesDir, "themes")

    @Volatile private var cache: List<Theme>? = null

    /** The built-in two, the eight the desktop ships, then any imported files, by name. */
    fun all(context: Context): List<Theme> = cache ?: load(context).also { cache = it }

    private fun load(context: Context): List<Theme> {
        val shipped = context.assets.list("themes").orEmpty().sorted().mapNotNull { file ->
            runCatching { Theme(file.removeSuffix(".json"), Palette.parse(context.assets.open("themes/$file").bufferedReader().readText())) }.getOrNull()
        }
        val own = imported(context).listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }.mapNotNull { file ->
            runCatching { Theme(file.nameWithoutExtension, Palette.parse(file.readText())) }.getOrNull()
        }
        // An imported file with a shipped theme's name replaces it, as on the desktop.
        val byName = linkedMapOf<String, Theme>()
        (listOf(Theme(DARK, Palette.Dark), Theme(LIGHT, Palette.Light)) + shipped + own).forEach { byName[it.name] = it }
        return byName.values.toList()
    }

    /** Saves a picked palette file after checking it parses; returns its theme name. */
    fun import(context: Context, name: String, text: String): String {
        Palette.parse(text)
        val clean = name.removeSuffix(".json").replace(Regex("[/\\\\]"), "_").ifBlank { "Imported" }
        imported(context).apply { mkdirs() }.resolve("$clean.json").writeText(text)
        cache = null
        return clean
    }

    fun find(context: Context, name: String): Palette? = all(context).firstOrNull { it.name == name }?.palette
}

val LocalPalette = staticCompositionLocalOf { Palette.Dark }

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.inter() = copy(fontFamily = Inter)
    return Typography(
        displayLarge = base.displayLarge.inter(), displayMedium = base.displayMedium.inter(), displaySmall = base.displaySmall.inter(),
        headlineLarge = base.headlineLarge.inter(), headlineMedium = base.headlineMedium.inter(),
        headlineSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 24.sp),
        titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp),
        titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp),
        bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp),
        bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp),
        bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp),
        labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp),
        labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp),
    )
}

/** The palette the settings pick: Follow system takes the light or dark pair to match the phone. */
@Composable
fun chosenPalette(context: Context, theme: String, lightTheme: String, darkTheme: String): Palette {
    val name = if (theme == Themes.SYSTEM) (if (isSystemInDarkTheme()) darkTheme else lightTheme) else theme
    return Themes.find(context, name) ?: if (isSystemInDarkTheme()) Palette.Dark else Palette.Light
}

@Composable
fun SpotifastTheme(palette: Palette, content: @Composable () -> Unit) {
    // Stock Material pieces (sliders, switches, menus, fields) pick these up, so they match without restyling.
    val scheme = (if (palette.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = palette.accent, onPrimary = palette.onAccent, primaryContainer = palette.surfaceActive,
        onPrimaryContainer = palette.text, secondary = palette.accent, onSecondary = palette.onAccent,
        secondaryContainer = palette.surfaceActive, onSecondaryContainer = palette.text,
        background = palette.window, onBackground = palette.text, surface = palette.panel, onSurface = palette.text,
        surfaceVariant = palette.surface, onSurfaceVariant = palette.secondary, surfaceContainer = palette.overlay,
        surfaceContainerHigh = palette.overlay, surfaceContainerHighest = palette.surface, surfaceContainerLow = palette.panel,
        surfaceContainerLowest = palette.window, outline = palette.outline, outlineVariant = palette.outline,
        error = palette.danger, inverseSurface = palette.text, inverseOnSurface = palette.window,
    )
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = typography(), content = content)
    }
}
