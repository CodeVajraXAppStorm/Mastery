package com.example.myvault

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Photos are AES-256-GCM encrypted with a key derived from the 6-digit PIN.
 * Files are saved as random-name .vlt files in a hidden folder.
 *
 * Needs (Android 11+): MANAGE_EXTERNAL_STORAGE permission for the visible folder,
 * or change vaultDir() to context.filesDir for a private folder.
 */
object VaultCrypto {

    private const val PREFS = "vault_prefs"
    private const val KEY_SALT = "salt"
    private const val KEY_VERIFIER = "verifier"
    private const val ITERATIONS = 150_000

    // Held in memory only while the app is unlocked. Clear on lock.
    private var sessionKey: SecretKey? = null

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

    fun isPinSet(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_VERIFIER)

    fun setupPin(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(pin, salt)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_VERIFIER, verifier(key))
            .apply()
        sessionKey = key
    }

    /** Returns true if PIN is correct and unlocks the vault. */
    fun unlock(ctx: Context, pin: String): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val salt = Base64.decode(p.getString(KEY_SALT, null) ?: return false, Base64.NO_WRAP)
        val key = deriveKey(pin, salt)
        return if (verifier(key) == p.getString(KEY_VERIFIER, null)) {
            sessionKey = key
            true
        } else false
    }

    fun lock() { sessionKey = null }

    fun isUnlocked() = sessionKey != null

    private fun deriveKey(pin: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun verifier(key: SecretKey): String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(key.encoded + "check".toByteArray()),
            Base64.NO_WRAP
        )

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

    /** Returns a decrypted stream. Use BitmapFactory.decodeStream() to show it. */
    fun openPhoto(file: File): InputStream {
        val key = sessionKey ?: error("Vault is locked")
        val fis = file.inputStream()
        val iv = ByteArray(12)
        fis.read(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return CipherInputStream(fis, cipher)
    }

    /** Decrypts back to a normal file (used for Restore to Gallery). */
    fun exportPhoto(file: File, dest: File) {
        openPhoto(file).use { input -> dest.outputStream().use { input.copyTo(it) } }
    }

    fun listPhotos(): List<File> =
        vaultDir().listFiles { f -> f.extension == "vlt" }?.toList() ?: emptyList()
}
