package com.lull.player.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** PhoneAPI provider contract §2 (the proof) and §3 (the key's See / Change switches). */
class PhoneApiTest {

    private val ownerKey = "owner-key-0123456789"
    private val phoneKey = "phoneapi-key-abcdef"
    private lateinit var backend: FakeBackend
    private var access: PhoneApiAccess? = null
    private lateinit var router: ApiRouter

    @Before
    fun setUp() {
        backend = FakeBackend()
        access = null
        router = ApiRouter(backend, phoneApi = { access }) { ownerKey }
    }

    private fun call(method: String, path: String, key: String?) = router.handle(
        ApiRequest(method, path, headers = if (key == null) emptyMap() else mapOf("x-api-key" to key))
    )

    @Test
    fun `the proof is base64url HMAC-SHA256 of the challenge, without padding`() {
        // RFC 4231 test case 2.
        val proof = PhoneApiProof.of("Jefe".toByteArray(), "what do ya want for nothing?")
        assertEquals("W9zBRr9gdU5qBCQmCJV1x1oAPwidJzmDnexYuWTsOEM", proof)
    }

    @Test
    fun `every answer to a challenge is signed, a 401 and a 404 included`() {
        val pk = ByteArray(32) { it.toByte() }
        val req = ApiRequest("GET", "/api/player", headers = mapOf("x-phoneapi-challenge" to "abc123"))
        for (status in listOf(200, 401, 404, 500)) {
            val signed = PhoneApiProof.sign(req, ApiResponse.error(status, "x"), pk)
            assertEquals(PhoneApiProof.of(pk, "abc123"), signed.extraHeaders[PhoneApiProof.PROOF_HEADER])
            assertEquals(status, signed.status)
        }
    }

    @Test
    fun `no proof without a key, a challenge, or with a junk challenge`() {
        val pk = ByteArray(32)
        val plain = ApiRequest("GET", "/api/health")
        assertNull(PhoneApiProof.sign(plain, ApiResponse.ok(), pk).extraHeaders[PhoneApiProof.PROOF_HEADER])
        val withC = ApiRequest("GET", "/api/health", headers = mapOf("x-phoneapi-challenge" to "c"))
        assertNull(PhoneApiProof.sign(withC, ApiResponse.ok(), null).extraHeaders[PhoneApiProof.PROOF_HEADER])
        val junk = ApiRequest("GET", "/api/health", headers = mapOf("x-phoneapi-challenge" to "x".repeat(600)))
        assertNull(PhoneApiProof.sign(junk, ApiResponse.ok(), pk).extraHeaders[PhoneApiProof.PROOF_HEADER])
        assertNull(PhoneApiProof.sign(null, ApiResponse.ok(), pk).extraHeaders[PhoneApiProof.PROOF_HEADER])
    }

    @Test
    fun `PhoneAPI's key is refused while PhoneAPI is not linked`() {
        assertEquals(401, call("GET", "/api/player", phoneKey).status)
    }

    @Test
    fun `See opens every GET and nothing else`() {
        access = PhoneApiAccess(phoneKey, see = true, change = false)
        assertEquals(200, call("GET", "/api/player", phoneKey).status)
        assertEquals(403, call("POST", "/api/player/pause", phoneKey).status)
    }

    @Test
    fun `Change opens everything but GET`() {
        access = PhoneApiAccess(phoneKey, see = false, change = true)
        assertEquals(403, call("GET", "/api/player", phoneKey).status)
        assertEquals(200, call("POST", "/api/player/pause", phoneKey).status)
    }

    @Test
    fun `the owner's key is never limited by PhoneAPI's switches`() {
        access = PhoneApiAccess(phoneKey, see = false, change = false)
        assertEquals(200, call("GET", "/api/player", ownerKey).status)
        assertEquals(200, call("POST", "/api/player/pause", ownerKey).status)
        assertEquals(401, call("GET", "/api/player", "wrong").status)
    }
}
