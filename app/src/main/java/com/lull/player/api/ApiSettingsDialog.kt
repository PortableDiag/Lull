package com.lull.player.api

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lull.player.R
import com.lull.player.databinding.DialogApiBinding

/**
 * The Local API panel — the only place the socket can be opened from.
 *
 * It does what the Trellis panel this borrows from does, for the same reasons: the switch takes
 * effect immediately rather than on an OK button (a socket you think you closed and didn't is the
 * worst outcome available here), the key is generated rather than typed, and the LAN option says
 * out loud what it changes.
 */
object ApiSettingsDialog {

    fun show(activity: Activity) {
        val binding = DialogApiBinding.inflate(activity.layoutInflater)
        val context: Context = activity

        fun renderStatus() {
            val error = ApiStatus.error
            binding.apiStatus.text = when {
                ApiStatus.running -> context.getString(R.string.api_running, ApiStatus.address)
                error != null -> context.getString(R.string.api_failed, ApiPrefs.port(context), error)
                else -> context.getString(R.string.api_stopped)
            }
        }

        fun renderKey() {
            val key = ApiPrefs.key(context)
            binding.apiKey.text = key.ifEmpty { context.getString(R.string.api_key_none) }
        }

        binding.apiEnable.isChecked = ApiPrefs.enabled(context)
        binding.apiLan.isChecked = ApiPrefs.lan(context)
        binding.apiPort.setText(ApiPrefs.port(context).toString())
        renderKey()
        renderStatus()

        val statusListener = { activity.runOnUiThread { renderStatus() } }
        ApiStatus.addListener(statusListener)

        binding.apiEnable.setOnCheckedChangeListener { _, on ->
            ApiPrefs.setEnabled(context, on)
            renderKey()
            if (on) ApiService.startIfEnabled(context) else ApiService.stop(context)
            renderStatus()
        }

        binding.apiLan.setOnCheckedChangeListener { _, on ->
            ApiPrefs.setLan(context, on)
            // A different bind address needs a different socket, so the server comes back up.
            if (ApiPrefs.enabled(context)) ApiService.restart(context)
        }

        binding.apiCopy.setOnClickListener {
            val key = ApiPrefs.key(context)
            if (key.isEmpty()) return@setOnClickListener
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Lull API key", key))
            Toast.makeText(context, R.string.api_copied, Toast.LENGTH_SHORT).show()
        }

        binding.apiRegenerate.setOnClickListener {
            ApiPrefs.regenerateKey(context)
            renderKey()
            Toast.makeText(context, R.string.api_regenerated, Toast.LENGTH_SHORT).show()
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.api)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // The port is the one field that is typed, so it is the one that can be wrong.
                val typed = binding.apiPort.text.toString().toIntOrNull()
                if (typed == null || typed < ApiPrefs.MIN_PORT || typed > ApiPrefs.MAX_PORT) {
                    Toast.makeText(context, R.string.api_port_invalid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (typed != ApiPrefs.port(context)) {
                    ApiPrefs.setPort(context, typed)
                    if (ApiPrefs.enabled(context)) ApiService.restart(context)
                }
            }
            .setOnDismissListener { ApiStatus.removeListener(statusListener) }
            .show()
    }
}
