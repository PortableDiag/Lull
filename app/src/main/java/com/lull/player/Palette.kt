// Generated from ProjectForge/palettes.toml by scripts/palettes.py — do not edit here.
package com.lull.player

/**
 * The house palettes, in the picker's order. Saved by [key], so adding one never shifts a
 * saved choice. No Android types: the API router validates against it and the tests run it.
 *
 * [followsMode] palettes obey the light/dark setting; every other one is a single look, and
 * [dark] says which way it forces the system bars and dialogs. [background] and [accent] are
 * for the picker's swatch (null for Material You, whose colours come from the wallpaper).
 */
enum class Palette(
    val key: String,
    val label: String,
    val dark: Boolean?,
    val background: Long?,
    val accent: Long?,
    val followsMode: Boolean = false,
    val mono: Boolean = false,
) {
    MaterialYou("material-you", "Material You (wallpaper colours)", dark = null, background = null, accent = null, followsMode = true),
    Ocean("ocean", "Ocean", dark = null, background = 0xFF0E1014, accent = 0xFFAEC6FF, followsMode = true),
    Terminal("terminal", "Terminal", dark = null, background = 0xFF000300, accent = 0xFF33FF77, followsMode = true, mono = true),
    Trellis("trellis", "Trellis", dark = true, background = 0xFF0F1117, accent = 0xFFA5B4FC),
    Graphite("graphite", "Graphite", dark = true, background = 0xFF1B1B1F, accent = 0xFF7AA2FF),
    Blueprint("blueprint", "Blueprint", dark = true, background = 0xFF0B2A43, accent = 0xFF7DC3F0),
    Futuristic("futuristic", "Futuristic", dark = true, background = 0xFF030A12, accent = 0xFF4DE4FF),
    Neon("neon", "Neon", dark = true, background = 0xFF000000, accent = 0xFF29C5F6),
    SynthWave("synthwave", "SynthWave", dark = true, background = 0xFF08060D, accent = 0xFFFF3B6B),
    WhiteGlow("white-glow", "White Glow", dark = true, background = 0xFF000000, accent = 0xFFFFFFFF),
    Silkscreen("silkscreen", "Silkscreen", dark = true, background = 0xFF061F15, accent = 0xFFD9A82B),
    StickyNotes("sticky-notes", "Sticky Notes", dark = false, background = 0xFFFFF3C2, accent = 0xFF8A5300),
    AmberTerminal("amber-terminal", "Amber Terminal", dark = true, background = 0xFF120A00, accent = 0xFFFFB000, mono = true),
    PhosphorGreen("phosphor-green", "Phosphor Green", dark = true, background = 0xFF001700, accent = 0xFF00FF0F, mono = true),
    PhosphorP31("phosphor-p31", "Phosphor P31", dark = true, background = 0xFF020A09, accent = 0xFF7AF7D4, mono = true),
    Zoro("zoro", "Zoro", dark = true, background = 0xFF1A1A1A, accent = 0xFF1ED42A),
    Nebula("nebula", "Nebula", dark = true, background = 0xFF05070D, accent = 0xFF22D3EE),
    ;

    companion object {
        val DEFAULT = MaterialYou

        /** By key, or by name in any case with spaces, hyphens or underscores: `white-glow`, `White Glow`. */
        fun find(value: String): Palette? {
            val k = value.trim().lowercase().replace('_', '-').split(' ', '-').filter { it.isNotEmpty() }.joinToString("-")
            return entries.firstOrNull { it.key == k || it.name.lowercase() == k.replace("-", "") }
        }
    }
}
