package com.lull.player

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The theme picker both screens open: every house palette with a swatch of its own background
 * and accent, and — for the palettes that follow it — the light/dark/system choice behind the
 * neutral button. The fixed palettes have no light/dark, so the button is not offered for them.
 */
object ThemeDialog {

    fun show(activity: AppCompatActivity) {
        val palettes = Palette.entries
        val current = ThemeManager.savedPalette(activity)
        val density = activity.resources.displayMetrics.density
        val adapter = object : ArrayAdapter<Palette>(
            activity, com.google.android.material.R.layout.mtrl_alert_select_dialog_singlechoice, palettes
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val p = palettes[position]
                view.text = p.label
                // The start drawable is the radio button; the swatch goes at the end.
                val start = view.compoundDrawablesRelative[0]
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(start, null, swatch(activity, p, density), null)
                view.compoundDrawablePadding = (14 * density).toInt()
                return view
            }
        }
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(adapter, palettes.indexOf(current)) { d, which ->
                d.dismiss()
                if (palettes[which] != current) ThemeManager.setPalette(activity, palettes[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (current.followsMode) builder.setNeutralButton(R.string.theme_mode) { _, _ -> showMode(activity) }
        builder.show()
    }

    private fun showMode(activity: AppCompatActivity) {
        val options = arrayOf(
            activity.getString(R.string.theme_system),
            activity.getString(R.string.theme_light),
            activity.getString(R.string.theme_dark)
        )
        val current = ThemeManager.savedMode(activity)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.theme_mode)
            .setSingleChoiceItems(options, current) { d, which ->
                d.dismiss(); if (which != current) ThemeManager.setMode(activity, which)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The palette's background as a disc with its accent as a dot in the middle. */
    private fun swatch(activity: AppCompatActivity, p: Palette, density: Float): LayerDrawable {
        val (bg, accent) = if (p.background != null && p.accent != null) {
            p.background.toInt() to p.accent.toInt()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.getColor(activity, android.R.color.system_neutral1_900) to
                ContextCompat.getColor(activity, android.R.color.system_accent1_200)
        } else {
            ContextCompat.getColor(activity, R.color.dark_bg) to ContextCompat.getColor(activity, R.color.brand_light)
        }
        val size = (28 * density).toInt()
        val disc = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bg)
            setStroke((1 * density).toInt().coerceAtLeast(1), 0x40808080)
            setSize(size, size)
        }
        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        val inset = (8 * density).toInt()
        return LayerDrawable(arrayOf(disc, dot)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
    }
}
