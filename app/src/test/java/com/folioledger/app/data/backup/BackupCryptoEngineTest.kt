/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.data.backup

import com.folioledger.app.domain.model.BackupInspectionResult
import com.folioledger.app.domain.model.WrongBackupPasswordException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupCryptoEngineTest {

    private val testIterations = BackupCryptoEngine.MIN_ITERATIONS

    private fun buildSampleZipBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("folio_backup_v1.json"))
            zip.write("""{"backupFormatVersion":1,"accounts":[],"categories":[],"transactions":[]}""".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun mode01_encryptWithPassword_roundTripsCleanly() {
        val sampleZip = buildSampleZipBytes()
        val password = "correct-horse-battery-staple".toCharArray()
        val encrypted = ByteArrayOutputStream()

        BackupCryptoEngine.encryptWithPassword(
            plaintextZipBytes = sampleZip,
            password = password,
            output = encrypted,
            iterations = testIterations,
        )

        val encryptedBytes = encrypted.toByteArray()
        val inspection = BackupCryptoEngine.inspectHeader(ByteArrayInputStream(encryptedBytes))
        assertEquals(
            BackupInspectionResult.EncryptedFolio(version = 1, iterations = testIterations),
            inspection,
        )

        val decrypted = BackupCryptoEngine.decryptToZipBytes(
            input = ByteArrayInputStream(encryptedBytes),
            password = "correct-horse-battery-staple".toCharArray(),
        )
        assertArrayEquals(sampleZip, decrypted)

        // Verify decrypted bytes are valid ZIP payload
        ZipInputStream(ByteArrayInputStream(decrypted)).use { zis ->
            val entry = zis.nextEntry
            assertNotNull(entry)
            assertEquals("folio_backup_v1.json", entry?.name)
        }
    }

    @Test
    fun mode02_encryptWithMasterKey_decryptsWithPassphraseOnly_andUsesUniqueSubkeys() {
        val sampleZip = buildSampleZipBytes()
        val passphrase = "offline-vault-passphrase".toCharArray()
        val masterSalt = ByteArray(16).also(SecureRandom()::nextBytes)
        val masterKey = BackupCryptoEngine.derivePbkdf2Key(passphrase, masterSalt, testIterations)

        val out1 = ByteArrayOutputStream()
        val out2 = ByteArrayOutputStream()
        try {
            BackupCryptoEngine.encryptWithMasterKey(sampleZip, masterKey, masterSalt, testIterations, out1)
            BackupCryptoEngine.encryptWithMasterKey(sampleZip, masterKey, masterSalt, testIterations, out2)
        } finally {
            masterKey.fill(0)
        }

        val bytes1 = out1.toByteArray()
        val bytes2 = out2.toByteArray()
        assertFalse("Each Mode 0x02 file must use a unique nonce/IV and ciphertext", bytes1.contentEquals(bytes2))

        val dec1 = BackupCryptoEngine.decryptToZipBytes(ByteArrayInputStream(bytes1), "offline-vault-passphrase".toCharArray())
        val dec2 = BackupCryptoEngine.decryptToZipBytes(ByteArrayInputStream(bytes2), "offline-vault-passphrase".toCharArray())
        assertArrayEquals(sampleZip, dec1)
        assertArrayEquals(sampleZip, dec2)
    }

    @Test
    fun wrongPassword_throwsWrongBackupPasswordException() {
        val sampleZip = buildSampleZipBytes()
        val encrypted = ByteArrayOutputStream()
        BackupCryptoEngine.encryptWithPassword(
            plaintextZipBytes = sampleZip,
            password = "right-password".toCharArray(),
            output = encrypted,
            iterations = testIterations,
        )

        assertThrows(WrongBackupPasswordException::class.java) {
            BackupCryptoEngine.decryptToZipBytes(
                input = ByteArrayInputStream(encrypted.toByteArray()),
                password = "wrong-password".toCharArray(),
            )
        }
    }

    @Test
    fun bitFlipInHeaderOrCiphertext_failsGcmAuthBeforeDecompression() {
        val sampleZip = buildSampleZipBytes()
        val encrypted = ByteArrayOutputStream()
        BackupCryptoEngine.encryptWithPassword(
            plaintextZipBytes = sampleZip,
            password = "secret-password".toCharArray(),
            output = encrypted,
            iterations = testIterations,
        )
        val baseBytes = encrypted.toByteArray()

        // Flip 1 bit in Salt (byte 20)
        val saltTampered = baseBytes.copyOf().apply { this[20] = (this[20].toInt() xor 0x01).toByte() }
        assertThrows(WrongBackupPasswordException::class.java) {
            BackupCryptoEngine.decryptToZipBytes(ByteArrayInputStream(saltTampered), "secret-password".toCharArray())
        }

        // Flip 1 bit in IV (header end)
        val ivTampered = baseBytes.copyOf().apply { this[35] = (this[35].toInt() xor 0x01).toByte() }
        assertThrows(WrongBackupPasswordException::class.java) {
            BackupCryptoEngine.decryptToZipBytes(ByteArrayInputStream(ivTampered), "secret-password".toCharArray())
        }

        // Flip 1 bit in last ciphertext/tag byte
        val tagTampered = baseBytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 0x80).toByte() }
        assertThrows(WrongBackupPasswordException::class.java) {
            BackupCryptoEngine.decryptToZipBytes(ByteArrayInputStream(tagTampered), "secret-password".toCharArray())
        }
    }

    @Test
    fun iterationDosAndDowngradeHeaders_rejectedImmediately() {
        fun craftedHeader(version: Int, kdfMode: Int, iterations: Int): ByteArray {
            val buf = ByteBuffer.allocate(64)
            buf.put("FOLIOENC".toByteArray(StandardCharsets.US_ASCII))
            buf.putShort(version.toShort())
            buf.put(kdfMode.toByte())
            buf.putInt(iterations)
            buf.put(16.toByte())
            buf.put(ByteArray(16))
            buf.put(0.toByte())
            buf.put(12.toByte())
            buf.put(ByteArray(12))
            buf.put(ByteArray(8))
            return buf.array()
        }

        val lowIter = craftedHeader(version = 1, kdfMode = 1, iterations = 10)
        val hugeIter = craftedHeader(version = 1, kdfMode = 1, iterations = Int.MAX_VALUE)

        assertEquals(BackupInspectionResult.InvalidFormat, BackupCryptoEngine.inspectHeader(ByteArrayInputStream(lowIter)))
        assertEquals(BackupInspectionResult.InvalidFormat, BackupCryptoEngine.inspectHeader(ByteArrayInputStream(hugeIter)))

        assertThrows(IllegalArgumentException::class.java) {
            BackupCryptoEngine.decryptToZipBytes(ByteArrayInputStream(lowIter), "passphrase123".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCryptoEngine.decryptToZipBytes(ByteArrayInputStream(hugeIter), "passphrase123".toCharArray())
        }
    }

    @Test
    fun inspectHeader_distinguishesZipEncryptedFutureVersionAndGarbage() {
        val sampleZip = buildSampleZipBytes()
        assertEquals(BackupInspectionResult.PlaintextZip, BackupCryptoEngine.inspectHeader(ByteArrayInputStream(sampleZip)))

        val encrypted = ByteArrayOutputStream()
        BackupCryptoEngine.encryptWithPassword(sampleZip, "passphrase123".toCharArray(), encrypted, testIterations)
        assertTrue(BackupCryptoEngine.inspectHeader(ByteArrayInputStream(encrypted.toByteArray())) is BackupInspectionResult.EncryptedFolio)

        val futureV2 = ByteArray(15).also { arr ->
            "FOLIOENC".toByteArray(StandardCharsets.US_ASCII).copyInto(arr, 0)
            arr[8] = 0x00
            arr[9] = 0x02
        }
        assertEquals(BackupInspectionResult.UnsupportedEncryptedVersion(2), BackupCryptoEngine.inspectHeader(ByteArrayInputStream(futureV2)))

        val garbage = "random non-backup content".toByteArray(StandardCharsets.UTF_8)
        assertEquals(BackupInspectionResult.InvalidFormat, BackupCryptoEngine.inspectHeader(ByteArrayInputStream(garbage)))
        assertEquals(BackupInspectionResult.InvalidFormat, BackupCryptoEngine.inspectHeader(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun vaultBlobRoundTrip_zeroizesPassphraseAndMasterKeyCleanly() {
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { (it + 1).toByte() }
        val wrapped = ByteArray(48) { (it + 2).toByte() }

        val encoded = AutoMirrorKeyVault.encodeVaultBlob(
            iterations = BackupCryptoEngine.DEFAULT_ITERATIONS,
            masterSalt = salt,
            wrapIv = iv,
            wrappedMasterKey = wrapped,
        )
        val decoded = AutoMirrorKeyVault.decodeVaultBlob(encoded)
        assertNotNull(decoded)
        assertEquals(BackupCryptoEngine.DEFAULT_ITERATIONS, decoded?.iterations)
        assertArrayEquals(salt, decoded?.masterSalt)
        assertArrayEquals(iv, decoded?.wrapIv)
        assertArrayEquals(wrapped, decoded?.wrappedMasterKey)

        val unwrapped = AutoMirrorKeyVault.UnwrappedMirrorKey(
            masterKeyBytes = ByteArray(32) { 0x5A },
            masterSalt = salt,
            iterations = BackupCryptoEngine.DEFAULT_ITERATIONS,
        )
        unwrapped.zeroize()
        assertTrue(unwrapped.masterKeyBytes.all { it == 0.toByte() })
    }

    @Test
    fun truncatedHeader_throwsDescriptiveIllegalArgumentException() {
        val truncated = "FOLIOENC\u0000\u0001\u0001".toByteArray(StandardCharsets.ISO_8859_1)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            BackupCryptoEngine.decryptToZipBytes(
                input = ByteArrayInputStream(truncated),
                password = "password123".toCharArray(),
            )
        }
        assertTrue(ex.message?.contains("Truncated") == true)
    }

    @Test
    fun maxEncryptedPayloadBoundary_with16ByteGcmTag_decryptsSuccessfully() {
        val maxPlain = ByteArray(BackupCryptoEngine.MAX_ENCRYPTED_BYTES) { 0x42 }
        val encrypted = ByteArrayOutputStream(maxPlain.size + 128)
        BackupCryptoEngine.encryptWithPassword(
            plaintextZipBytes = maxPlain,
            password = "boundary-password".toCharArray(),
            output = encrypted,
            iterations = testIterations,
        )
        val decrypted = BackupCryptoEngine.decryptToZipBytes(
            input = ByteArrayInputStream(encrypted.toByteArray()),
            password = "boundary-password".toCharArray(),
        )
        assertEquals(BackupCryptoEngine.MAX_ENCRYPTED_BYTES, decrypted.size)
        assertArrayEquals(maxPlain, decrypted)
    }
}
