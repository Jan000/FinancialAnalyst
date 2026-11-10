package net.jandev.financialanalyst.domain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sicherungsdateien: ein ZIP der App-Daten, mit einem aus dem Sicherungspasswort abgeleiteten Schlüssel
 * AES-256-GCM-verschlüsselt. Ohne Passwort ist die Datei wertlos – auch für den Cloud-Anbieter.
 *
 * Aufbau: `FAB1` | Salt (16) | IV (12) | Chiffrat mit Prüfsumme.
 */
object BackupCrypto {
    private val MAGIC = "FAB1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val ITERATIONS = 210_000
    const val MIN_PASSWORD_LENGTH = 8
    const val FILE_EXTENSION = "fabackup"

    class WrongPasswordException : Exception("Das Sicherungspasswort stimmt nicht oder die Datei ist beschädigt.")
    class NotABackupException : Exception("Das ist keine Sicherungsdatei des Finanzanalysten.")

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    fun encrypt(plain: ByteArray, password: String, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(password.toCharArray(), salt), GCMParameterSpec(128, iv))
            updateAAD(MAGIC)
        }
        return MAGIC + salt + iv + cipher.doFinal(plain)
    }

    fun decrypt(data: ByteArray, password: String): ByteArray {
        val header = MAGIC.size + SALT_BYTES + IV_BYTES
        if (data.size <= header || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw NotABackupException()
        val salt = data.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val iv = data.copyOfRange(MAGIC.size + SALT_BYTES, header)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(password.toCharArray(), salt), GCMParameterSpec(128, iv))
            updateAAD(MAGIC)
        }
        return try {
            cipher.doFinal(data, header, data.size - header)
        } catch (_: GeneralSecurityException) {
            throw WrongPasswordException()
        }
    }

    fun zip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun unzip(data: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // Nur flache Dateinamen; nichts, was aus dem Zielordner ausbrechen könnte.
                if (!entry.isDirectory && '/' !in entry.name && '\\' !in entry.name && ".." !in entry.name) {
                    files[entry.name] = zip.readBytes()
                }
            }
        }
        return files
    }
}
