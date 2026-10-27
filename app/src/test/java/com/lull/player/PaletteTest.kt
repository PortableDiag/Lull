package com.lull.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The palette is saved and sent over the API by key, so a key must be stable and findable from
 * any spelling a person would type; and the generated overlays must carry every palette the
 * enum offers, or picking one would silently fall back to the base theme.
 */
class PaletteTest {

    @Test
    fun `all seventeen house palettes, Material You first`() {
        assertEquals(17, Palette.entries.size)
        assertEquals(Palette.MaterialYou, Palette.entries.first())
        assertEquals(Palette.DEFAULT, Palette.MaterialYou)
    }

    @Test
    fun `keys are unique, lowercase and hyphenated`() {
        val keys = Palette.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { assertTrue(it, it.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*"))) }
    }

    @Test
    fun `found by key, by label, by enum name, in any case`() {
        assertEquals(Palette.WhiteGlow, Palette.find("white-glow"))
        assertEquals(Palette.WhiteGlow, Palette.find("White Glow"))
        assertEquals(Palette.WhiteGlow, Palette.find("WHITE_GLOW"))
        assertEquals(Palette.WhiteGlow, Palette.find("WhiteGlow"))
        assertEquals(Palette.PhosphorP31, Palette.find(" phosphor p31 "))
        assertEquals(Palette.SynthWave, Palette.find("synthwave"))
        Palette.entries.forEach { assertEquals(it, Palette.find(it.key)) }
    }

    @Test
    fun `an unknown name is nothing, not a default`() {
        assertNull(Palette.find("solarized"))
        assertNull(Palette.find(""))
    }

    @Test
    fun `only Material You, Ocean and Terminal follow light and dark, and the rest say which they are`() {
        assertEquals(
            setOf(Palette.MaterialYou, Palette.Ocean, Palette.Terminal),
            Palette.entries.filter { it.followsMode }.toSet()
        )
        Palette.entries.forEach { assertEquals(it.name, it.followsMode, it.dark == null) }
        assertEquals(false, Palette.StickyNotes.dark)
    }

    @Test
    fun `every palette but Material You has an overlay, and those that follow the mode have a night one too`() {
        val day = File("src/main/res/values/palettes.xml").readText()
        val night = File("src/main/res/values-night/palettes.xml").readText()
        Palette.entries.filter { it != Palette.MaterialYou }.forEach {
            val style = """name="ThemeOverlay.Lull.${it.name}""""
            assertTrue(it.name, style in day)
            assertEquals(it.name, it.followsMode, style in night)
        }
    }

    @Test
    fun `mono palettes set the monospace face`() {
        val day = File("src/main/res/values/palettes.xml").readText()
        Palette.entries.filter { it != Palette.MaterialYou }.forEach {
            val body = day.substringAfter("""name="ThemeOverlay.Lull.${it.name}"""").substringBefore("</style>")
            assertEquals(it.name, it.mono, "monospace" in body)
        }
    }
}
