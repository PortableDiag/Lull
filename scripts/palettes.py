#!/usr/bin/env python3
"""Regenerate Lull's copy of the house palettes from ProjectForge/palettes.toml.

    python3 scripts/palettes.py [path/to/palettes.toml]

pf generates a Compose `Palettes.kt`; Lull is a View app, so it carries the set as
theme overlays instead, and this script is what keeps them in step. Writes:

  app/src/main/res/values/palettes.xml        one ThemeOverlay.Lull.<Name> per palette
                                              (the light form, for those that follow light/dark)
  app/src/main/res/values-night/palettes.xml  the dark form of those that follow light/dark
  app/src/main/java/com/lull/player/Palette.kt        the enum — pure Kotlin, so the API router
                                                      and the tests can use it
  app/src/main/java/com/lull/player/PaletteStyles.kt  enum -> R.style

After a house change: run this, then `pf palette apply . --done`.
"""
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/media/veracrypt1/Rust/ProjectForge/palettes.toml")
HEADER = "Generated from ProjectForge/palettes.toml by scripts/palettes.py — do not edit here."


def ident(name):
    return "".join(w[:1].upper() + w[1:] for w in name.replace("-", " ").split())


def key(name):
    return "-".join(name.lower().split())


def luminance(hex_):
    def ch(c):
        c /= 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (int(hex_[i:i + 2], 16) for i in (1, 3, 5))
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)


def attr(m3):
    """A Material 3 ColorScheme role name -> the View theme attribute that holds it."""
    if m3 == "background":
        return "android:colorBackground"
    return "color" + m3[0].upper() + m3[1:]


def from_roles(r):
    """The 12 house roles as a Material 3 scheme, in Lull's conventions: a card (`surface`)
    sits a step above the page (`background`), as it always has in Lull."""
    return {
        "primary": r["accent"], "onPrimary": r["on_accent"],
        "primaryContainer": r["pressed"], "onPrimaryContainer": r["text"],
        "secondary": r["accent_secondary"], "onSecondary": r["background"],
        "secondaryContainer": r["pressed"], "onSecondaryContainer": r["text"],
        "tertiary": r["highlight"], "onTertiary": r["background"],
        "tertiaryContainer": r["pressed"], "onTertiaryContainer": r["text"],
        "background": r["background"], "onBackground": r["text"],
        "surface": r["surface"], "onSurface": r["text"],
        "surfaceVariant": r["raised"], "onSurfaceVariant": r["text_secondary"],
        "surfaceContainerLowest": r["background"], "surfaceContainerLow": r["surface"],
        "surfaceContainer": r["bars"], "surfaceContainerHigh": r["raised"],
        "surfaceContainerHighest": r["raised"],
        "surfaceBright": r["raised"], "surfaceDim": r["background"],
        "outline": r["border"], "outlineVariant": r["border"],
        "surfaceInverse": r["text"], "onSurfaceInverse": r["background"],
        "primaryInverse": r["pressed"],
    }


def style(name, scheme, mono):
    light = luminance(scheme["background"]) > 0.5
    lines = [f'    <style name="ThemeOverlay.Lull.{ident(name)}" parent="">']
    for m3, value in scheme.items():
        lines.append(f'        <item name="{attr(m3)}">{value}</item>')
    flag = "true" if light else "false"
    lines.append(f'        <item name="android:windowLightStatusBar">{flag}</item>')
    lines.append(f'        <item name="android:windowLightNavigationBar" tools:targetApi="o_mr1">{flag}</item>')
    if mono:
        lines.append('        <item name="android:fontFamily">monospace</item>')
        lines.append('        <item name="fontFamily">monospace</item>')
        lines.append('        <item name="lullFontMedium">monospace</item>')
    lines.append("    </style>")
    return "\n".join(lines)


def resources(body):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {HEADER} -->\n"
        '<resources xmlns:tools="http://schemas.android.com/tools">\n'
        + "\n\n".join(body) + "\n</resources>\n"
    )


def main():
    palettes = tomllib.loads(SOURCE.read_text())["palette"]
    day, night, entries, styles = [], [], [], []
    for p in palettes:
        name, mono = p["name"], bool(p.get("mono"))
        ident_ = ident(name)
        label = p.get("label", name)
        if p.get("dynamic"):
            entries.append((ident_, key(name), label, "followsMode = true", None, None, None))
            continue
        scheme = from_roles(p["roles"])
        scheme.update(p.get("overrides", {}))
        if "light" in p:
            # Follows light/dark: the light form in values/, the dark one in values-night/.
            day.append(style(name, p["light"], mono))
            night.append(style(name, scheme, mono))
            dark = None
        else:
            day.append(style(name, scheme, mono))
            dark = luminance(scheme["background"]) <= 0.5
        styles.append(ident_)
        args = []
        if dark is None:
            args.append("followsMode = true")
        if mono:
            args.append("mono = true")
        entries.append((ident_, key(name), label, ", ".join(args), dark,
                        p["roles"]["background"], p["roles"]["accent"]))

    (ROOT / "app/src/main/res/values/palettes.xml").write_text(resources(day))
    (ROOT / "app/src/main/res/values-night/palettes.xml").write_text(resources(night))

    def hexl(h):
        return "null" if h is None else f"0xFF{h[1:].upper()}"

    enum = []
    for ident_, k, label, flags, dark, bg, accent in entries:
        parts = [f'"{k}"', f'"{label}"']
        parts.append("dark = " + ("null" if dark is None else str(dark).lower()))
        parts.append(f"background = {hexl(bg)}")
        parts.append(f"accent = {hexl(accent)}")
        if flags:
            parts.append(flags)
        enum.append(f"    {ident_}({', '.join(parts)}),")
    (ROOT / "app/src/main/java/com/lull/player/Palette.kt").write_text(
        f"// {HEADER}\n"
        "package com.lull.player\n\n"
        "/**\n"
        " * The house palettes, in the picker's order. Saved by [key], so adding one never shifts a\n"
        " * saved choice. No Android types: the API router validates against it and the tests run it.\n"
        " *\n"
        " * [followsMode] palettes obey the light/dark setting; every other one is a single look, and\n"
        " * [dark] says which way it forces the system bars and dialogs. [background] and [accent] are\n"
        " * for the picker's swatch (null for Material You, whose colours come from the wallpaper).\n"
        " */\n"
        "enum class Palette(\n"
        "    val key: String,\n"
        "    val label: String,\n"
        "    val dark: Boolean?,\n"
        "    val background: Long?,\n"
        "    val accent: Long?,\n"
        "    val followsMode: Boolean = false,\n"
        "    val mono: Boolean = false,\n"
        ") {\n"
        + "\n".join(enum) + "\n"
        "    ;\n\n"
        "    companion object {\n"
        "        val DEFAULT = MaterialYou\n\n"
        "        /** By key, or by name in any case with spaces, hyphens or underscores: `white-glow`, `White Glow`. */\n"
        "        fun find(value: String): Palette? {\n"
        "            val k = value.trim().lowercase().replace('_', '-').split(' ', '-').filter { it.isNotEmpty() }.joinToString(\"-\")\n"
        "            return entries.firstOrNull { it.key == k || it.name.lowercase() == k.replace(\"-\", \"\") }\n"
        "        }\n"
        "    }\n"
        "}\n"
    )
    cases = "\n".join(f"        Palette.{s} -> R.style.ThemeOverlay_Lull_{s}" for s in styles)
    (ROOT / "app/src/main/java/com/lull/player/PaletteStyles.kt").write_text(
        f"// {HEADER}\n"
        "package com.lull.player\n\n"
        "/** The theme overlay that carries each palette; 0 for Material You, which has none. */\n"
        "internal fun Palette.overlay(): Int = when (this) {\n"
        "        Palette.MaterialYou -> 0\n"
        f"{cases}\n"
        "}\n"
    )
    print(f"{len(palettes)} palettes from {SOURCE}")


main()
