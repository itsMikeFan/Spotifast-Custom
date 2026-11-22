package com.itsmikefan.spotifastcustom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DupesTest {
    private fun t(uri: String, name: String, artist: String = "Ari") = Track(uri, name, artist, artist, 0)

    @Test
    fun remastersMatchButLiveAndEditsDoNot() {
        val honey = Dupes.key(t("a", "Honey"))
        assertEquals(honey, Dupes.key(t("b", "Honey - Remastered 2011")))
        assertEquals(honey, Dupes.key(t("c", "  honey  (2009 Remaster)")))
        assertEquals(honey, Dupes.key(t("d", "Honey [Remastered]")))
        assertNotEquals(honey, Dupes.key(t("e", "Honey - Live")))
        assertNotEquals(honey, Dupes.key(t("f", "Honey (Radio Edit)")))
        assertNotEquals(honey, Dupes.key(t("g", "Honey", "Someone Else")))
    }

    @Test
    fun groupsAndPlanKeepTheFirstCopy() {
        val rows = listOf(t("x", "Honey"), t("y", "Other"), t("x", "Honey"), t("z", "Honey (Remaster)"))
        assertEquals(listOf(listOf(0, 2, 3)), Dupes.groups(rows))
        val (remove, readd) = Dupes.plan(rows.map { it.uri }, setOf(2, 3))
        assertEquals(listOf("x", "z"), remove)
        assertEquals(listOf("x" to 0), readd)
    }
}
