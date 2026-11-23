package com.itsmikefan.spotifastcustom

import androidx.compose.ui.graphics.Color
import com.itsmikefan.spotifastcustom.ui.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThemeTest {
    @Test
    fun everyShippedThemeLoads() {
        val files = File("src/main/assets/themes").listFiles()!!.filter { it.extension == "json" }
        assertEquals(8, files.size)
        for (f in files) Palette.parse(f.readText())
    }

    @Test
    fun missingColoursComeFromTheBase() {
        val p = Palette.parse("""{"base":"light","colors":{"text":"#ebdbb2","shadow":"#00000080"}}""")
        assertFalse(p.dark)
        assertEquals(Palette.Light.window, p.window)
        assertEquals(Color(0xFFEBDBB2), p.text)
        assertEquals(Color(0x80000000), p.shadow)
        assertEquals(Palette.Dark, Palette.parse("{}"))
    }

    @Test
    fun badFilesAreRefusedByName() {
        val e = runCatching { Palette.parse("""{"colors":{"window":"red"}}""") }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("window"))
        assertTrue(runCatching { Palette.parse("""{"colors":{"nope":"#000000"}}""") }.isFailure)
    }
}
