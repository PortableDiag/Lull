package com.lull.player.api

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PhoneAPI's key, and what the owner let it do (provider contract §3).
 *
 * PhoneAPI exposes a REST provider as two switches, `lull.get` and `lull.request`; [see] and
 * [change] are the same split on Lull's side, so a key the owner allowed to look cannot pause the
 * music, and the other way round.
 */
class PhoneApiAccess(val key: String, val see: Boolean, val change: Boolean) {
    /** `GET` is looking; every other method changes something. */
    fun allows(method: String): Boolean = if (method == "GET") see else change
}

/**
 * PhoneAPI provider contract §2. Any app can bind Lull's port while the API is off, and a pinned
 * certificate only proves who is installed, not who is listening — so PhoneAPI sends
 * `X-PhoneAPI-Challenge` with every request and believes only an answer carrying
 * `base64url_nopad(HMAC-SHA256(proof_key, challenge))`, which only the holder of the key from the
 * handshake can produce.
 *
 * No Android types, so the proof is covered by JVM tests like the rest of the API.
 */
object PhoneApiProof {

    const val CHALLENGE_HEADER = "x-phoneapi-challenge"
    const val PROOF_HEADER = "X-PhoneAPI-Proof"

    /** A challenge longer than this is not PhoneAPI's; don't hash attacker-sized input. */
    private const val MAX_CHALLENGE = 512

    fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun of(proofKey: ByteArray, challenge: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(proofKey, "HmacSHA256")) }
        return b64url(mac.doFinal(challenge.toByteArray(Charsets.US_ASCII)))
    }

    /** A challenge worth answering: present, short, printable ASCII. It is hashed, never echoed. */
    fun usable(challenge: String?): String? {
        val c = challenge?.trim() ?: return null
        if (c.isEmpty() || c.length > MAX_CHALLENGE) return null
        return if (c.all { it in ' '..'~' }) c else null
    }

    /**
     * [response] with the proof added, when the request carried a challenge and Lull holds a proof
     * key. Every response goes through here — health, 401s, 404s and errors included.
     */
    fun sign(request: ApiRequest?, response: ApiResponse, proofKey: ByteArray?): ApiResponse {
        val challenge = usable(request?.header(CHALLENGE_HEADER)) ?: return response
        if (proofKey == null || proofKey.isEmpty()) return response
        return response.withHeader(PROOF_HEADER, of(proofKey, challenge))
    }
}
