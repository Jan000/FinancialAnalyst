package net.jandev.financialanalyst.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {
    private val files = mapOf("finance.db" to ByteArray(5000) { (it % 251).toByte() }, "settings.json" to "{\"a\":1}".toByteArray())

    @Test
    fun roundTrip() {
        val encrypted = BackupCrypto.encrypt(BackupCrypto.zip(files), "korrekt-pferd-batterie")
        val restored = BackupCrypto.unzip(BackupCrypto.decrypt(encrypted, "korrekt-pferd-batterie"))
        assertEquals(files.keys, restored.keys)
        assertArrayEquals(files["finance.db"], restored["finance.db"])
        assertArrayEquals(files["settings.json"], restored["settings.json"])
    }

    @Test
    fun ciphertextRevealsNothingAndDiffersEachTime() {
        val plain = BackupCrypto.zip(files)
        val a = BackupCrypto.encrypt(plain, "passwort1")
        val b = BackupCrypto.encrypt(plain, "passwort1")
        assertFalse(a.contentEquals(b))
        assertFalse(String(a, Charsets.ISO_8859_1).contains("settings.json"))
    }

    @Test
    fun wrongPasswordAndTamperingAreDetected() {
        val encrypted = BackupCrypto.encrypt(BackupCrypto.zip(files), "passwort1")
        assertThrows(BackupCrypto.WrongPasswordException::class.java) { BackupCrypto.decrypt(encrypted, "passwort2") }
        val tampered = encrypted.copyOf().also { it[it.size - 5] = (it[it.size - 5] + 1).toByte() }
        assertThrows(BackupCrypto.WrongPasswordException::class.java) { BackupCrypto.decrypt(tampered, "passwort1") }
    }

    @Test
    fun rejectsForeignFiles() {
        assertThrows(BackupCrypto.NotABackupException::class.java) { BackupCrypto.decrypt("irgendwas anderes als eine sicherung".toByteArray(), "x") }
        assertThrows(BackupCrypto.NotABackupException::class.java) { BackupCrypto.decrypt(ByteArray(3), "x") }
    }

    @Test
    fun unzipIgnoresPathsThatWouldEscape() {
        val zipped = BackupCrypto.zip(mapOf("../evil" to byteArrayOf(1), "sub/x" to byteArrayOf(2), "ok.txt" to byteArrayOf(3)))
        assertEquals(setOf("ok.txt"), BackupCrypto.unzip(zipped).keys)
    }
}
