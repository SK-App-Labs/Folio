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

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware/Keystore-backed wrapper around the PBKDF2-derived master key used for
 * encrypted `.folio` SAF auto-mirroring without storing any plaintext passphrase.
 */
class AutoMirrorKeyVault(
    private val context: Context,
    private val wrapCipher: KeyWrapCipher = AndroidKeyStoreWrapCipher(),
) {

    interface KeyWrapCipher {
        fun hasKey(): Boolean
        fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> // iv to ciphertext
        fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray
        fun deleteKey()
    }

    class UnwrappedMirrorKey(
        val masterKeyBytes: ByteArray,
        val masterSalt: ByteArray,
        val iterations: Int,
    ) {
        fun zeroize() {
            masterKeyBytes.fill(0)
        }
    }

    companion object {
        internal const val VAULT_FILE_NAME = "folio_mirror_key.bin"
        internal const val KEYSTORE_ALIAS = "folio_saf_mirror_wrap_v1"
        private const val VAULT_FORMAT_VERSION = 1
        private const val GCM_TAG_BITS = 128
        private val secureRandom = SecureRandom()

        internal fun encodeVaultBlob(
            iterations: Int,
            masterSalt: ByteArray,
            wrapIv: ByteArray,
            wrappedMasterKey: ByteArray,
        ): ByteArray {
            val out = ByteArrayOutputStream(96)
            DataOutputStream(out).use { dataOut ->
                dataOut.writeByte(VAULT_FORMAT_VERSION)
                dataOut.writeInt(iterations)
                dataOut.writeByte(masterSalt.size)
                dataOut.write(masterSalt)
                dataOut.writeByte(wrapIv.size)
                dataOut.write(wrapIv)
                dataOut.writeInt(wrappedMasterKey.size)
                dataOut.write(wrappedMasterKey)
                dataOut.flush()
            }
            return out.toByteArray()
        }

        internal data class VaultBlob(
            val iterations: Int,
            val masterSalt: ByteArray,
            val wrapIv: ByteArray,
            val wrappedMasterKey: ByteArray,
        )

        internal fun decodeVaultBlob(bytes: ByteArray): VaultBlob? {
            return runCatching {
                DataInputStream(ByteArrayInputStream(bytes)).use { dataIn ->
                    val version = dataIn.readUnsignedByte()
                    if (version != VAULT_FORMAT_VERSION) return null
                    val iterations = dataIn.readInt()
                    if (iterations !in BackupCryptoEngine.MIN_ITERATIONS..BackupCryptoEngine.MAX_ITERATIONS) {
                        return null
                    }
                    val saltLen = dataIn.readUnsignedByte()
                    if (saltLen !in 16..64) return null
                    val salt = ByteArray(saltLen).also { dataIn.readFully(it) }

                    val ivLen = dataIn.readUnsignedByte()
                    if (ivLen != BackupCryptoEngine.GCM_IV_BYTES) return null
                    val wrapIv = ByteArray(ivLen).also { dataIn.readFully(it) }

                    val wrappedLen = dataIn.readInt()
                    if (wrappedLen !in 32..256) return null
                    val wrapped = ByteArray(wrappedLen).also { dataIn.readFully(it) }

                    VaultBlob(
                        iterations = iterations,
                        masterSalt = salt,
                        wrapIv = wrapIv,
                        wrappedMasterKey = wrapped,
                    )
                }
            }.getOrNull()
        }
    }

    private val vaultFile: File
        get() = File(context.noBackupFilesDir, VAULT_FILE_NAME)

    private val tempVaultFile: File
        get() = File(context.noBackupFilesDir, "$VAULT_FILE_NAME.tmp")

    fun hasConfiguredKey(): Boolean = vaultFile.exists() && vaultFile.isFile && vaultFile.length() > 32L && wrapCipher.hasKey()

    fun configurePassphrase(passphrase: CharArray) {
        var masterKey: ByteArray? = null
        try {
            require(passphrase.any { !it.isWhitespace() }) {
                "Mirror encryption passphrase cannot be empty."
            }
            val iterations = BackupCryptoEngine.DEFAULT_ITERATIONS
            val masterSalt = ByteArray(BackupCryptoEngine.SALT_BYTES).also(secureRandom::nextBytes)
            val derived = BackupCryptoEngine.derivePbkdf2Key(passphrase, masterSalt, iterations)
            masterKey = derived

            val (wrapIv, wrappedMasterKey) = wrapCipher.encrypt(derived)
            val blob = encodeVaultBlob(
                iterations = iterations,
                masterSalt = masterSalt,
                wrapIv = wrapIv,
                wrappedMasterKey = wrappedMasterKey,
            )
            context.noBackupFilesDir.mkdirs()
            if (tempVaultFile.exists()) tempVaultFile.delete()
            FileOutputStream(tempVaultFile).use { out ->
                out.write(blob)
                out.flush()
                runCatching { out.fd.sync() }
            }
            if (!tempVaultFile.renameTo(vaultFile)) {
                tempVaultFile.copyTo(vaultFile, overwrite = true)
            }
        } finally {
            masterKey?.fill(0)
            passphrase.fill('\u0000')
            runCatching { tempVaultFile.delete() }
        }
    }

    fun unwrapMasterKey(): UnwrappedMirrorKey? {
        if (!hasConfiguredKey()) return null
        val blobBytes = runCatching { vaultFile.readBytes() }.getOrNull() ?: return null
        val blob = decodeVaultBlob(blobBytes) ?: return null
        val masterKey = runCatching { wrapCipher.decrypt(blob.wrapIv, blob.wrappedMasterKey) }.getOrNull()
            ?: return null
        if (masterKey.size != 32) {
            masterKey.fill(0)
            return null
        }
        return UnwrappedMirrorKey(
            masterKeyBytes = masterKey,
            masterSalt = blob.masterSalt,
            iterations = blob.iterations,
        )
    }

    fun clear() {
        runCatching { tempVaultFile.delete() }
        runCatching { vaultFile.delete() }
        runCatching { wrapCipher.deleteKey() }
    }

    private class AndroidKeyStoreWrapCipher : KeyWrapCipher {
        private val androidKeyStore = "AndroidKeyStore"

        override fun hasKey(): Boolean = runCatching {
            val ks = KeyStore.getInstance(androidKeyStore).apply { load(null) }
            ks.containsAlias(KEYSTORE_ALIAS) && ks.getKey(KEYSTORE_ALIAS, null) != null
        }.getOrDefault(false)

        private fun getOrCreateKey(): SecretKey {
            val ks = KeyStore.getInstance(androidKeyStore).apply { load(null) }
            val existing = runCatching {
                (ks.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            }.getOrNull()
            if (existing != null) return existing

            runCatching {
                if (ks.containsAlias(KEYSTORE_ALIAS)) {
                    ks.deleteEntry(KEYSTORE_ALIAS)
                }
            }
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, androidKeyStore)
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
            keyGen.init(spec)
            return keyGen.generateKey()
        }

        private fun encryptWithKey(key: SecretKey, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)
            return iv to ciphertext
        }

        override fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> = try {
            encryptWithKey(getOrCreateKey(), plaintext)
        } catch (_: Exception) {
            runCatching { deleteKey() }
            encryptWithKey(getOrCreateKey(), plaintext)
        }

        override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
            val ks = KeyStore.getInstance(androidKeyStore).apply { load(null) }
            val key = (ks.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: throw IllegalStateException("Keystore wrap key unavailable")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(ciphertext)
        }

        override fun deleteKey() {
            val ks = KeyStore.getInstance(androidKeyStore).apply { load(null) }
            if (ks.containsAlias(KEYSTORE_ALIAS)) {
                ks.deleteEntry(KEYSTORE_ALIAS)
            }
        }
    }
}
