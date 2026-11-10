package net.jandev.financialanalyst.domain.banking

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.util.Base64

class ApiTokenTest {
    // Für den Test frisch erzeugt; kein echter Zugangsschlüssel.
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun pem(label: String, der: ByteArray) =
        "-----BEGIN $label-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $label-----\n"

    @Test
    fun readsPkcs8Pem() {
        val parsed = ApiToken.parsePrivateKey(pem("PRIVATE KEY", pair.private.encoded))
        assertArrayEquals(pair.private.encoded, parsed.encoded)
    }

    @Test
    fun readsPkcs1PemByWrappingIt() {
        // PKCS#8 = SEQUENCE { version, algorithm, OCTET STRING { pkcs1 } }: der PKCS#1-Teil ist das Ende der Kodierung.
        val pkcs8 = pair.private.encoded
        val marker = byteArrayOf(0x04, 0x82.toByte())
        val index = (0 until pkcs8.size - 1).first { pkcs8[it] == marker[0] && pkcs8[it + 1] == marker[1] }
        val pkcs1 = pkcs8.copyOfRange(index + 4, pkcs8.size)
        val parsed = ApiToken.parsePrivateKey(pem("RSA PRIVATE KEY", pkcs1)) as RSAPrivateCrtKey
        assertEquals((pair.private as RSAPrivateCrtKey).modulus, parsed.modulus)
    }

    @Test
    fun rejectsGarbageAndEncryptedKeys() {
        assertThrows(ApiToken.InvalidKeyException::class.java) { ApiToken.parsePrivateKey("hallo") }
        assertThrows(ApiToken.InvalidKeyException::class.java) {
            ApiToken.parsePrivateKey("-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----")
        }
        assertThrows(ApiToken.InvalidKeyException::class.java) {
            ApiToken.parsePrivateKey("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----")
        }
    }

    @Test
    fun tokenHasExpectedClaimsAndAValidSignature() {
        val token = ApiToken.create("app-123", pair.private, 1_700_000_000)
        val parts = token.split(".")
        assertEquals(3, parts.size)
        assertTrue(parts.none { '=' in it || '+' in it || '/' in it })
        val decoder = Base64.getUrlDecoder()
        assertEquals("""{"typ":"JWT","alg":"RS256","kid":"app-123"}""", String(decoder.decode(parts[0])))
        assertEquals(
            """{"iss":"enablebanking.com","aud":"api.enablebanking.com","iat":1700000000,"exp":1700003600}""",
            String(decoder.decode(parts[1])),
        )
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(pair.public)
            update("${parts[0]}.${parts[1]}".toByteArray())
        }
        assertTrue(verifier.verify(decoder.decode(parts[2])))
    }
}
