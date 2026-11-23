package com.itsmikefan.spotifastcustom.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Generated from Lucide 0.460 (ISC licence), the desktop app's icon set.
object Icons {
    val Play by lazy { icon("play", true, "M6 3L20 12L6 21L6 3z") }
    val Pause by lazy { icon("pause", true, "M15.0 4.0h2.0a1.0 1.0 0 0 1 1.0 1.0v14.0a1.0 1.0 0 0 1 -1.0 1.0h-2.0a1.0 1.0 0 0 1 -1.0 -1.0v-14.0a1.0 1.0 0 0 1 1.0 -1.0z", "M7.0 4.0h2.0a1.0 1.0 0 0 1 1.0 1.0v14.0a1.0 1.0 0 0 1 -1.0 1.0h-2.0a1.0 1.0 0 0 1 -1.0 -1.0v-14.0a1.0 1.0 0 0 1 1.0 -1.0z") }
    val SkipBack by lazy { icon("skip-back", true, "M19 20L9 12L19 4L19 20z", "M5 19L5 5") }
    val SkipForward by lazy { icon("skip-forward", true, "M5 4L15 12L5 20L5 4z", "M19 5L19 19") }
    val Shuffle by lazy { icon("shuffle", false, "M2 18h1.4c1.3 0 2.5-.6 3.3-1.7l6.1-8.6c.7-1.1 2-1.7 3.3-1.7H22", "m18 2 4 4-4 4", "M2 6h1.9c1.5 0 2.9.9 3.6 2.2", "M22 18h-5.9c-1.3 0-2.6-.7-3.3-1.8l-.5-.8", "m18 14 4 4-4 4") }
    val Repeat by lazy { icon("repeat", false, "m17 2 4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "m7 22-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3") }
    val Repeat1 by lazy { icon("repeat-1", false, "m17 2 4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "m7 22-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3", "M11 10h1v4") }
    val Heart by lazy { icon("heart", false, "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z") }
    val HeartFilled by lazy { icon("heart-filled", true, "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z") }
    val ListMusic by lazy { icon("list-music", false, "M21 15V6", "M18.5 18a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z", "M12 12H3", "M16 6H3", "M12 18H3") }
    val Speaker by lazy { icon("speaker", false, "M6.0 2.0h12.0a2.0 2.0 0 0 1 2.0 2.0v16.0a2.0 2.0 0 0 1 -2.0 2.0h-12.0a2.0 2.0 0 0 1 -2.0 -2.0v-16.0a2.0 2.0 0 0 1 2.0 -2.0z", "M12 6h.01", "M8.0 14.0a4.0 4.0 0 1 0 8.0 0a4.0 4.0 0 1 0 -8.0 0z", "M12 14h.01") }
    val AudioLines by lazy { icon("audio-lines", false, "M2 10v3", "M6 6v11", "M10 3v18", "M14 8v7", "M18 5v13", "M22 10v3") }
    val Volume by lazy { icon("volume", false, "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z") }
    val Volume1 by lazy { icon("volume-1", false, "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z", "M16 9a5 5 0 0 1 0 6") }
    val Volume2 by lazy { icon("volume-2", false, "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z", "M16 9a5 5 0 0 1 0 6", "M19.364 18.364a9 9 0 0 0 0-12.728") }
    val VolumeX by lazy { icon("volume-x", false, "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z", "M22 9L16 15", "M16 9L22 15") }
    val Settings by lazy { icon("settings", false, "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z", "M9.0 12.0a3.0 3.0 0 1 0 6.0 0a3.0 3.0 0 1 0 -6.0 0z") }
    val Search by lazy { icon("search", false, "M3.0 11.0a8.0 8.0 0 1 0 16.0 0a8.0 8.0 0 1 0 -16.0 0z", "m21 21-4.3-4.3") }
    val EllipsisVertical by lazy { icon("ellipsis-vertical", false, "M11.0 12.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0z", "M11.0 5.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0z", "M11.0 19.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0z") }
    val ChevronDown by lazy { icon("chevron-down", false, "m6 9 6 6 6-6") }
    val ArrowLeft by lazy { icon("arrow-left", false, "m12 19-7-7 7-7", "M19 12H5") }
    val Check by lazy { icon("check", false, "M20 6 9 17l-5-5") }
    val CopyX by lazy { icon("copy-x", false, "M12 12L18 18", "M12 18L18 12", "M10.0 8.0h10.0a2.0 2.0 0 0 1 2.0 2.0v10.0a2.0 2.0 0 0 1 -2.0 2.0h-10.0a2.0 2.0 0 0 1 -2.0 -2.0v-10.0a2.0 2.0 0 0 1 2.0 -2.0z", "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2") }
    val LibraryBig by lazy { icon("library-big", false, "M4.0 3.0h6.0a1.0 1.0 0 0 1 1.0 1.0v16.0a1.0 1.0 0 0 1 -1.0 1.0h-6.0a1.0 1.0 0 0 1 -1.0 -1.0v-16.0a1.0 1.0 0 0 1 1.0 -1.0z", "M7 3v18", "M20.4 18.9c.2.5-.1 1.1-.6 1.3l-1.9.7c-.5.2-1.1-.1-1.3-.6L11.1 5.1c-.2-.5.1-1.1.6-1.3l1.9-.7c.5-.2 1.1.1 1.3.6Z") }
    val Smartphone by lazy { icon("smartphone", false, "M7.0 2.0h10.0a2.0 2.0 0 0 1 2.0 2.0v16.0a2.0 2.0 0 0 1 -2.0 2.0h-10.0a2.0 2.0 0 0 1 -2.0 -2.0v-16.0a2.0 2.0 0 0 1 2.0 -2.0z", "M12 18h.01") }
    val Monitor by lazy { icon("monitor", false, "M4.0 3.0h16.0a2.0 2.0 0 0 1 2.0 2.0v10.0a2.0 2.0 0 0 1 -2.0 2.0h-16.0a2.0 2.0 0 0 1 -2.0 -2.0v-10.0a2.0 2.0 0 0 1 2.0 -2.0z", "M8 21L16 21", "M12 17L12 21") }
    val Cast by lazy { icon("cast", false, "M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6", "M2 12a9 9 0 0 1 8 8", "M2 16a5 5 0 0 1 4 4", "M2 20L2.01 20") }
    val X by lazy { icon("x", false, "M18 6 6 18", "m6 6 12 12") }
    val LayoutGrid by lazy { icon("layout-grid", false, "M4.0 3.0h5.0a1.0 1.0 0 0 1 1.0 1.0v5.0a1.0 1.0 0 0 1 -1.0 1.0h-5.0a1.0 1.0 0 0 1 -1.0 -1.0v-5.0a1.0 1.0 0 0 1 1.0 -1.0z", "M15.0 3.0h5.0a1.0 1.0 0 0 1 1.0 1.0v5.0a1.0 1.0 0 0 1 -1.0 1.0h-5.0a1.0 1.0 0 0 1 -1.0 -1.0v-5.0a1.0 1.0 0 0 1 1.0 -1.0z", "M15.0 14.0h5.0a1.0 1.0 0 0 1 1.0 1.0v5.0a1.0 1.0 0 0 1 -1.0 1.0h-5.0a1.0 1.0 0 0 1 -1.0 -1.0v-5.0a1.0 1.0 0 0 1 1.0 -1.0z", "M4.0 14.0h5.0a1.0 1.0 0 0 1 1.0 1.0v5.0a1.0 1.0 0 0 1 -1.0 1.0h-5.0a1.0 1.0 0 0 1 -1.0 -1.0v-5.0a1.0 1.0 0 0 1 1.0 -1.0z") }
    val List by lazy { icon("list", false, "M3 12h.01", "M3 18h.01", "M3 6h.01", "M8 12h13", "M8 18h13", "M8 6h13") }
    val ArrowDownUp by lazy { icon("arrow-down-up", false, "m3 16 4 4 4-4", "M7 20V4", "m21 8-4-4-4 4", "M17 4v16") }
    val Plus by lazy { icon("plus", false, "M5 12h14", "M12 5v14") }
    val Music by lazy { icon("music", false, "M9 18V5l12-2v13", "M3.0 18.0a3.0 3.0 0 1 0 6.0 0a3.0 3.0 0 1 0 -6.0 0z", "M15.0 16.0a3.0 3.0 0 1 0 6.0 0a3.0 3.0 0 1 0 -6.0 0z") }
    val SlidersHorizontal by lazy { icon("sliders-horizontal", false, "M21 4L14 4", "M10 4L3 4", "M21 12L12 12", "M8 12L3 12", "M21 20L16 20", "M12 20L3 20", "M14 2L14 6", "M8 10L8 14", "M16 18L16 22") }

    private fun icon(name: String, filled: Boolean, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) addPath(
                addPathNodes(d),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
}
