package com.lull.player.api

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lull.player.R
import com.lull.player.ThemeManager
import com.lull.player.databinding.ActivityPhoneapiGrantBinding

/**
 * PhoneAPI provider contract §3: PhoneAPI asks for a key with `startActivityForResult`. The key
 * travels only as this activity's result — never a file, a broadcast or the clipboard.
 *
 * Refuses (RESULT_CANCELED, nothing shown) any caller that is not PhoneAPI's release build;
 * otherwise asks the owner which of "See" and "Change" the key may do, and on Allow mints
 * PhoneAPI's own key and proof key and switches the local API on, since that is what they agreed to.
 */
class GrantKeyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applySaved(this)
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        if (intent?.action != PhoneApiLink.ACTION_GRANT_KEY ||
            !PhoneApiLink.isTrustedCaller(this, callingPackage)
        ) {
            finish()
            return
        }
        ThemeManager.reapply(this)
        val binding = ActivityPhoneapiGrantBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val body = StringBuilder(getString(R.string.phoneapi_body))
        if (!ApiPrefs.enabled(this)) body.append(' ').append(getString(R.string.phoneapi_turns_on))
        if (PhoneApiLink.linked(this)) body.append(' ').append(getString(R.string.phoneapi_replaces))
        binding.body.text = body

        // Everything starts off, and a key that may do nothing isn't worth minting.
        // A second link starts from what PhoneAPI's current key may do, not from nothing.
        PhoneApiLink.access(this)?.let {
            binding.see.isChecked = it.see
            binding.change.isChecked = it.change
        }
        val sync = { binding.allow.isEnabled = binding.see.isChecked || binding.change.isChecked }
        sync()
        binding.see.setOnCheckedChangeListener { _, _ -> sync() }
        binding.change.setOnCheckedChangeListener { _, _ -> sync() }

        binding.deny.setOnClickListener { finish() }
        binding.allow.setOnClickListener { allow(binding.see.isChecked, binding.change.isChecked) }
    }

    private fun allow(see: Boolean, change: Boolean) {
        val (apiKey, proofKey) = PhoneApiLink.grant(
            this, see, change,
            phoneApiPort = intent.getIntExtra(PhoneApiLink.EXTRA_EVENT_PORT, 0),
            eventKey = intent.getStringExtra(PhoneApiLink.EXTRA_EVENT_KEY)
        )
        ApiPrefs.setEnabled(this, true)
        ApiService.startIfEnabled(this)
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra("api_key", apiKey)
                .putExtra("proof_key", proofKey)
                .putExtra("port", ApiPrefs.port(this))
        )
        finish()
    }
}
