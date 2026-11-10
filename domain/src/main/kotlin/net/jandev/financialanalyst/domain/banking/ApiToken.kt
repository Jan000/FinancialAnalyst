package net.jandev.financialanalyst.domain.banking

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Liest RSA-Schlüssel aus PEM-Dateien und signiert die Zugangstoken für Enable Banking (JWT, RS256). */
object ApiToken {
    /** Enable Banking akzeptiert höchstens 24 Stunden; eine Stunde reicht für jeden Abruf. */
    const val LIFETIME_SECONDS = 3600L

    class InvalidKeyException(message: String) : Exception(message)

    /**
     * Akzeptiert PKCS#8 (`BEGIN PRIVATE KEY`) und das ältere PKCS#1 (`BEGIN RSA PRIVATE KEY`).
     * Verschlüsselte Schlüssel werden abgelehnt.
     */
    fun parsePrivateKey(pem: String): PrivateKey {
        val text = pem.trim()
        if ("ENCRYPTED" in text) throw InvalidKeyException("Der Schlüssel ist mit einem Passwort geschützt. Bitte einen unverschlüsselten Schlüssel verwenden.")
        val pkcs1 = "BEGIN RSA PRIVATE KEY" in text
        if (!pkcs1 && "BEGIN PRIVATE KEY" !in text) {
            throw InvalidKeyException("Das ist keine PEM-Datei mit einem privaten Schlüssel.")
        }
        val body = text.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").filterNot { it.isWhitespace() }
        val der = try {
            Base64.getDecoder().decode(body)
        } catch (_: IllegalArgumentException) {
            throw InvalidKeyException("Die Schlüsseldatei ist beschädigt.")
        }
        return try {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(if (pkcs1) wrapPkcs1(der) else der))
        } catch (e: Exception) {
            throw InvalidKeyException("Der Schlüssel konnte nicht gelesen werden. Erwartet wird ein RSA-Schlüssel.")
        }
    }

    /** Packt einen PKCS#1-Schlüssel in die PKCS#8-Hülle, die Java versteht. */
    private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
        // SEQUENCE { OID 1.2.840.113549.1.1.1 (rsaEncryption), NULL }
        val algorithm = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val key = der(0x04, pkcs1)
        return der(0x30, version + algorithm + key)
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val length = content.size
        when {
            length < 0x80 -> out.write(length)
            length < 0x100 -> {
                out.write(0x81)
                out.write(length)
            }
            length < 0x10000 -> {
                out.write(0x82)
                out.write(length shr 8)
                out.write(length and 0xff)
            }
            else -> {
                out.write(0x83)
                out.write(length shr 16)
                out.write((length shr 8) and 0xff)
                out.write(length and 0xff)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    fun create(applicationId: String, key: PrivateKey, nowEpochSeconds: Long): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = """{"typ":"JWT","alg":"RS256","kid":"${escape(applicationId)}"}"""
        val body = """{"iss":"enablebanking.com","aud":"api.enablebanking.com","iat":$nowEpochSeconds,"exp":${nowEpochSeconds + LIFETIME_SECONDS}}"""
        val signingInput = encoder.encodeToString(header.toByteArray()) + "." + encoder.encodeToString(body.toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(signingInput.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return signingInput + "." + encoder.encodeToString(signature)
    }

    private fun escape(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")
}
