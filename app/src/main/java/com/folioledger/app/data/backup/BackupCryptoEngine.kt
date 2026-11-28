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
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Zero-dependency AES-256-GCM envelope crypto engine for `.folio` encrypted backups.
 *
 * Binary Envelope v1:
 * - Magic (8B): "FOLIOENC"
 * - FormatVersion (2B, uint16 BE): 0x0001
 * - KdfMode (1B): 0x01 (Direct PBKDF2-HMAC-SHA256) | 0x02 (PBKDF2 master + HMAC-SHA256 per-file subkey)
 * - Iterations (4B, int32 BE): 100_000..2_000_000 (default 310_000)
 * - SaltLen (1B): 16..64
 * - KdfSalt (SaltLen B)
 * - FileNonceLen (1B): 0 (mode 0x01) | 16..32 (mode 0x02)
 * - FileNonce (FileNonceLen B)
 * - IvLen (1B): 12
 * - Iv (12B)
 * - Ciphertext || 128-bit GCM AuthTag (entire header bound as GCM AAD)
 */
object BackupCryptoEngine {
    private val MAGIC = "FOLIOENC".toByteArray(StandardCharsets.US_ASCII)
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // PK\x03\x04
    private val SUBKEY_DOMAIN = "folio-enc-v1".toByteArray(StandardCharsets.UTF_8)

    const val FORMAT_VERSION: Int = 1
    const val KDF_MODE_DIRECT_PBKDF2: Int = 1
    const val KDF_MODE_HKDF_SUBKEY: Int = 2

    const val DEFAULT_ITERATIONS: Int = 310_000
    const val MIN_ITERATIONS: Int = 100_000
    const val MAX_ITERATIONS: Int = 2_000_000
    const val SALT_BYTES: Int = 16
    const val FILE_NONCE_BYTES: Int = 16
    const val GCM_IV_BYTES: Int = 12
    const val GCM_TAG_BITS: Int = 128
    const val MAX_ENCRYPTED_BYTES: Int = 34 * 1024 * 1024

    private val secureRandom = SecureRandom()

    fun inspectHeader(input: InputStream): BackupInspectionResult {
        val prefix = ByteArray(15)
        val read = readUpTo(input, prefix)
        if (read >= 4 && prefix.sliceArray(0 until 4).contentEquals(ZIP_MAGIC)) {
            return BackupInspectionResult.PlaintextZip
        }
        if (read >= 10 && prefix.sliceArray(0 until 8).contentEquals(MAGIC)) {
            val version = ((prefix[8].toInt() and 0xFF) shl 8) or (prefix[9].toInt() and 0xFF)
            if (version != FORMAT_VERSION) {
                return BackupInspectionResult.UnsupportedEncryptedVersion(version)
            }
            if (read < 15) {
                return BackupInspectionResult.InvalidFormat
            }
            val kdfMode = prefix[10].toInt() and 0xFF
            if (kdfMode != KDF_MODE_DIRECT_PBKDF2 && kdfMode != KDF_MODE_HKDF_SUBKEY) {
                return BackupInspectionResult.InvalidFormat
            }
            val iterations = ((prefix[11].toInt() and 0xFF) shl 24) or
                ((prefix[12].toInt() and 0xFF) shl 16) or
                ((prefix[13].toInt() and 0xFF) shl 8) or
                (prefix[14].toInt() and 0xFF)
            if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
                return BackupInspectionResult.InvalidFormat
            }
            return BackupInspectionResult.EncryptedFolio(version = version, iterations = iterations)
        }
        return BackupInspectionResult.InvalidFormat
    }

    /** Mode 0x01: Manual password-protected export (fresh random salt & IV per file). */
    fun encryptWithPassword(
        plaintextZipBytes: ByteArray,
        password: CharArray,
        output: OutputStream,
        iterations: Int = DEFAULT_ITERATIONS,
    ) {
        require(password.isNotEmpty()) { "Backup password cannot be empty." }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Invalid backup KDF iteration count." }
        require(plaintextZipBytes.size <= MAX_ENCRYPTED_BYTES) {
            "Backup archive exceeds maximum allowed encrypted size."
        }
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
        val keyBytes = derivePbkdf2Key(password, salt, iterations)
        try {
            writeEncryptedEnvelope(
                plaintext = plaintextZipBytes,
                keyBytes = keyBytes,
                kdfMode = KDF_MODE_DIRECT_PBKDF2,
                iterations = iterations,
                salt = salt,
                fileNonce = ByteArray(0),
                iv = iv,
                output = output,
            )
        } finally {
            keyBytes.fill(0)
        }
    }

    /** Mode 0x02: Background SAF auto-mirror using Keystore-unwrapped master key (unique per-file subkey + IV). */
    fun encryptWithMasterKey(
        plaintextZipBytes: ByteArray,
        masterKeyBytes: ByteArray,
        masterSalt: ByteArray,
        iterations: Int,
        output: OutputStream,
    ) {
        require(masterKeyBytes.size == 32) { "Invalid master key length." }
        require(masterSalt.size in 16..64) { "Invalid master salt length." }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Invalid backup KDF iteration count." }
        require(plaintextZipBytes.size <= MAX_ENCRYPTED_BYTES) {
            "Backup archive exceeds maximum allowed encrypted size."
        }
        val fileNonce = ByteArray(FILE_NONCE_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
        val perFileKey = deriveSubkeyHmacSha256(masterKeyBytes, fileNonce)
        try {
            writeEncryptedEnvelope(
                plaintext = plaintextZipBytes,
                keyBytes = perFileKey,
                kdfMode = KDF_MODE_HKDF_SUBKEY,
                iterations = iterations,
                salt = masterSalt,
                fileNonce = fileNonce,
                iv = iv,
                output = output,
            )
        } finally {
            perFileKey.fill(0)
        }
    }

    /** Decrypts both Mode 0x01 and Mode 0x02 using only the user's passphrase (works on any new phone). */
    fun decryptToZipBytes(input: InputStream, password: CharArray): ByteArray {
        if (password.isEmpty()) {
            throw WrongBackupPasswordException()
        }
        val dataIn = DataInputStream(input)
        val headerOut = ByteArrayOutputStream(64)
        val headerMirror = DataOutputStream(headerOut)

        val iterations: Int
        val salt: ByteArray
        val kdfMode: Int
        val fileNonce: ByteArray
        val iv: ByteArray
        try {
            val magic = ByteArray(8).also {
                dataIn.readFully(it)
                headerMirror.write(it)
            }
            require(magic.contentEquals(MAGIC)) { "Not an encrypted Folio backup (.folio)." }

            val version = dataIn.readUnsignedShort().also { headerMirror.writeShort(it) }
            require(version == FORMAT_VERSION) {
                "Unsupported encrypted backup version v$version. Please update Folio."
            }
            kdfMode = dataIn.readUnsignedByte().also { headerMirror.writeByte(it) }
            require(kdfMode == KDF_MODE_DIRECT_PBKDF2 || kdfMode == KDF_MODE_HKDF_SUBKEY) {
                "Unsupported backup encryption mode."
            }
            iterations = dataIn.readInt().also { headerMirror.writeInt(it) }
            require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) {
                "Invalid backup KDF iteration count."
            }
            val saltLen = dataIn.readUnsignedByte().also { headerMirror.writeByte(it) }
            require(saltLen in 16..64) { "Invalid backup salt length." }
            salt = ByteArray(saltLen).also {
                dataIn.readFully(it)
                headerMirror.write(it)
            }

            val nonceLen = dataIn.readUnsignedByte().also { headerMirror.writeByte(it) }
            require(
                (kdfMode == KDF_MODE_DIRECT_PBKDF2 && nonceLen == 0) ||
                    (kdfMode == KDF_MODE_HKDF_SUBKEY && nonceLen in 16..32),
            ) {
                "Invalid backup file nonce length."
            }
            fileNonce = ByteArray(nonceLen).also {
                if (nonceLen > 0) {
                    dataIn.readFully(it)
                    headerMirror.write(it)
                }
            }

            val ivLen = dataIn.readUnsignedByte().also { headerMirror.writeByte(it) }
            require(ivLen == GCM_IV_BYTES) { "Invalid GCM IV length." }
            iv = ByteArray(ivLen).also {
                dataIn.readFully(it)
                headerMirror.write(it)
            }
        } catch (e: EOFException) {
            throw IllegalArgumentException("Truncated or corrupted encrypted backup file.", e)
        }

        val headerBytes = headerOut.toByteArray()
        val ciphertext = readCappedBytes(dataIn, MAX_ENCRYPTED_BYTES + (GCM_TAG_BITS / 8))
        require(ciphertext.size > GCM_TAG_BITS / 8) { "Truncated encrypted backup file." }

        val masterKey = derivePbkdf2Key(password, salt, iterations)
        val contentKey = if (kdfMode == KDF_MODE_HKDF_SUBKEY) {
            deriveSubkeyHmacSha256(masterKey, fileNonce)
        } else {
            masterKey
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(headerBytes)
            return try {
                cipher.doFinal(ciphertext)
            } catch (e: AEADBadTagException) {
                throw WrongBackupPasswordException(e)
            }
        } finally {
            if (contentKey !== masterKey) contentKey.fill(0)
            masterKey.fill(0)
        }
    }

    internal fun derivePbkdf2Key(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun deriveSubkeyHmacSha256(masterKey: ByteArray, fileNonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(masterKey, "HmacSHA256"))
        mac.update(SUBKEY_DOMAIN)
        mac.update(fileNonce)
        return mac.doFinal()
    }

    private fun writeEncryptedEnvelope(
        plaintext: ByteArray,
        keyBytes: ByteArray,
        kdfMode: Int,
        iterations: Int,
        salt: ByteArray,
        fileNonce: ByteArray,
        iv: ByteArray,
        output: OutputStream,
    ) {
        val headerOut = ByteArrayOutputStream(64)
        DataOutputStream(headerOut).apply {
            write(MAGIC)
            writeShort(FORMAT_VERSION)
            writeByte(kdfMode)
            writeInt(iterations)
            writeByte(salt.size)
            write(salt)
            writeByte(fileNonce.size)
            if (fileNonce.isNotEmpty()) write(fileNonce)
            writeByte(iv.size)
            write(iv)
            flush()
        }
        val headerBytes = headerOut.toByteArray()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(headerBytes)
        val ciphertextWithTag = cipher.doFinal(plaintext)

        output.write(headerBytes)
        output.write(ciphertextWithTag)
        output.flush()
    }

    private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n == -1) break
            total += n
        }
        return total
    }

    private fun readCappedBytes(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            total += n
            if (total > maxBytes) {
                throw IllegalArgumentException("Encrypted backup exceeds maximum allowed size.")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
