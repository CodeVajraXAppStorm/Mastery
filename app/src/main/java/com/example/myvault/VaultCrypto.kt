package com.example.myvault

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Every photo is AES-256-GCM encrypted with a random master key.
 * The master key is wrapped by a hardware-backed Android Keystore key,
 * bound to the PIN. Unlock is instant; the key never leaves the device.
 */
object VaultCrypto {

    private const val PREFS = "vault_prefs"
    private const val KEY_SALT = "salt"
    private const val KEY_VERIFIER = "verifier" // old versions only
    private const val KEY_WRAPPED = "wrapped"
    private const val KEY_FAILS = "fails"
    private const val KEY_LOCK_UNTIL = "lock_until"
    private const val LEGACY_ITERATIONS = 150_000
    private const val KS_ALIAS = "vault_wrap_key"

    @Volatile
    private var sessionKey: SecretKey? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    // ---------- Folder ----------

    @Suppress("DEPRECATION")
    fun vaultDir(): File {
        val dir = File(Environment.getExternalStorageDirectory(), ".MyVault")
        if (!dir.exists()) {
            dir.mkdirs()
            File(dir, ".nomedia").createNewFile() // hide from Gallery
        }
        return dir
    }

    // ---------- PIN setup / unlock ----------

    fun isPinSet(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.contains(KEY_WRAPPED) || p.contains(KEY_VERIFIER)
    }

    fun setupPin(ctx: Context, pin: String) {
        val master = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        wrapMaster(ctx, master, pin, salt)
        prefs(ctx).edit().remove(KEY_VERIFIER).apply()
        sessionKey = SecretKeySpec(master, "AES")
    }

    /** True if the PIN is right (vault unlocked). Do not call on the main thread. */
    fun unlock(ctx: Context, pin: String): Boolean {
        val p = prefs(ctx)
        val master: ByteArray? = when {
            p.contains(KEY_WRAPPED) -> unwrapMaster(ctx, pin)
            p.contains(KEY_VERIFIER) -> legacyUnlock(ctx, pin)
            else -> null
        }
        return if (master != null) {
            sessionKey = SecretKeySpec(master, "AES")
            resetFailures(ctx)
            true
        } else {
            recordFailure(ctx)
            false
        }
    }

    fun lock() { sessionKey = null }

    fun isUnlocked() = sessionKey != null

    // ---------- Wrong-PIN lockout ----------

    fun lockoutMs(ctx: Context): Long =
        (prefs(ctx).getLong(KEY_LOCK_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun recordFailure(ctx: Context) {
        val p = prefs(ctx)
        val n = p.getInt(KEY_FAILS, 0) + 1
        val e = p.edit().putInt(KEY_FAILS, n)
        if (n >= 5) {
            val waitMs = 30_000L * (1L shl min(n - 5, 6))
            e.putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + waitMs)
        }
        e.apply()
    }

    private fun resetFailures(ctx: Context) {
        prefs(ctx).edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCK_UNTIL, 0L).apply()
    }

    // ---------- Master key wrapping (Android Keystore) ----------

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KS_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun aad(pin: String, salt: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray())

    private fun wrapMaster(ctx: Context, master: ByteArray, pin: String, salt: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        c.updateAAD(aad(pin, salt))
        val ct = c.doFinal(master)
        prefs(ctx).edit()
            .putString(KEY_WRAPPED, b64(c.iv) + ":" + b64(ct))
            .putString(KEY_SALT, b64(salt))
            .apply()
    }

    private fun unwrapMaster(ctx: Context, pin: String): ByteArray? = runCatching {
        val p = prefs(ctx)
        val parts = p.getString(KEY_WRAPPED, null)!!.split(":")
        val salt = unb64(p.getString(KEY_SALT, null)!!)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, unb64(parts[0])))
        c.updateAAD(aad(pin, salt))
        c.doFinal(unb64(parts[1]))
    }.getOrNull()

    // ---------- Old-version migration (runs once, slow) ----------

    private fun legacyUnlock(ctx: Context, pin: String): ByteArray? {
        val p = prefs(ctx)
        val salt = unb64(p.getString(KEY_SALT, null) ?: return null)
        val spec = PBEKeySpec(pin.toCharArray(), salt, LEGACY_ITERATIONS, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val v = b64(MessageDigest.getInstance("SHA-256").digest(key + "check".toByteArray()))
        if (v != p.getString(KEY_VERIFIER, null)) return null
        wrapMaster(ctx, key, pin, salt) // the old key becomes the master key
        p.edit().remove(KEY_VERIFIER).apply()
        return key
    }

    // ---------- Encrypt / Decrypt ----------

    /** Encrypts a shared photo (Uri) into the vault. Returns the new .vlt file. */
    fun importPhoto(ctx: Context, uri: Uri): File {
        val key = sessionKey ?: error("Vault is locked")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))

        val name = Base64.encodeToString(
            ByteArray(9).also { SecureRandom().nextBytes(it) },
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        val outFile = File(vaultDir(), "$name.vlt")

        try {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                outFile.outputStream().use { fos ->
                    fos.write(iv) // first 12 bytes = IV
                    CipherOutputStream(fos, cipher).use { input.copyTo(it) }
                }
            }
        } catch (e: Exception) {
            outFile.delete() // never leave a half-written file
            throw e
        }
        return outFile
    }

    /** Returns a decrypted stream. */
    fun openPhoto(file: File): InputStream {
        val key = sessionKey ?: error("Vault is locked")
        val fis = file.inputStream()
        val iv = ByteArray(12)
        fis.read(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return CipherInputStream(fis, cipher)
    }

    fun listPhotos(): List<File> =
        vaultDir().listFiles { f -> f.extension == "vlt" }?.toList() ?: emptyList()
}
