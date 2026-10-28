package com.lull.player.api

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Lull's side of the PhoneAPI link (PhoneAPI/docs/provider-contract.md §2–3): the key PhoneAPI
 * calls with, what that key may do, the proof key Lull answers its challenges with, and where to
 * reach PhoneAPI back.
 *
 * PhoneAPI's key is separate from the owner's own key, so linking or unlinking PhoneAPI never
 * breaks a script, and a second handshake replaces only PhoneAPI's key. SharedPreferences, like
 * [ApiPrefs], because the socket threads read it synchronously.
 */
object PhoneApiLink {

    const val PACKAGE = "com.phoneapi.app"
    const val ACTION_GRANT_KEY = "com.phoneapi.action.GRANT_KEY"
    const val EXTRA_EVENT_PORT = "phoneapi_port"
    const val EXTRA_EVENT_KEY = "phoneapi_event_key"

    /**
     * PhoneAPI's release certificate (keystore-manager record "PhoneAPI"). A debug build of
     * PhoneAPI is signed by the debug key and is refused, as PhoneAPI refuses debug builds of
     * providers.
     */
    private val CERT_SHA256: ByteArray =
        "AD:86:A7:04:86:39:86:F3:A4:ED:10:66:00:F1:EC:C2:19:EE:31:82:68:D4:84:BB:54:E9:07:9C:E3:B8:E0:02"
            .split(':').map { it.toInt(16).toByte() }.toByteArray()

    private const val FILE = "lull_phoneapi"
    private const val KEY_API = "api_key"
    private const val KEY_PROOF = "proof_key"
    private const val KEY_SEE = "see"
    private const val KEY_CHANGE = "change"
    private const val KEY_PORT = "phoneapi_port"
    private const val KEY_EVENT = "phoneapi_event_key"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun linked(c: Context): Boolean = sp(c).getString(KEY_API, "").orEmpty().isNotEmpty()

    /** PhoneAPI's key and its two switches, or null when PhoneAPI is not linked. */
    fun access(c: Context): PhoneApiAccess? {
        val p = sp(c)
        val key = p.getString(KEY_API, "").orEmpty()
        if (key.isEmpty()) return null
        return PhoneApiAccess(key, p.getBoolean(KEY_SEE, false), p.getBoolean(KEY_CHANGE, false))
    }

    fun proofKey(c: Context): ByteArray? =
        sp(c).getString(KEY_PROOF, null)?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }

    /**
     * Mints a fresh key pair for PhoneAPI, replacing any earlier one, and keeps the two values
     * PhoneAPI sent. Returns (api key, proof key as base64url).
     */
    fun grant(c: Context, see: Boolean, change: Boolean, phoneApiPort: Int, eventKey: String?): Pair<String, String> {
        val api = ApiPrefs.newKey()
        val proof = PhoneApiProof.b64url(ByteArray(32).also { SecureRandom().nextBytes(it) })
        sp(c).edit()
            .putString(KEY_API, api)
            .putString(KEY_PROOF, proof)
            .putBoolean(KEY_SEE, see)
            .putBoolean(KEY_CHANGE, change)
            .putInt(KEY_PORT, phoneApiPort)
            .putString(KEY_EVENT, eventKey)
            .commit()
        return api to proof
    }

    /** Forget PhoneAPI: its key stops working at once. */
    fun unlink(c: Context) {
        sp(c).edit().clear().commit()
    }

    /**
     * Android runs no code of ours when PhoneAPI is uninstalled, so this is asked on app start,
     * when the API comes up, and on every package change while it runs: PhoneAPI gone, or no
     * longer signed by the pin, and its key goes with it.
     */
    fun dropIfUntrusted(c: Context) {
        if (linked(c) && !isTrustedCaller(c, PACKAGE)) unlink(c)
    }

    /** True when [pkg] is PhoneAPI's release build, signed by the pinned certificate. */
    fun isTrustedCaller(c: Context, pkg: String?): Boolean {
        if (pkg != PACKAGE) return false
        val pm = c.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.hasSigningCertificate(pkg, CERT_SHA256, PackageManager.CERT_INPUT_SHA256)
            } else {
                @Suppress("DEPRECATION")
                val sigs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
                // One signer, and it is the pinned one. More than one signer is not PhoneAPI.
                sigs != null && sigs.size == 1 &&
                    MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray()), CERT_SHA256)
            }
        }.getOrDefault(false)
    }
}
