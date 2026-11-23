package com.example.myvault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

// ------------------------------------------------------------ file format
// New format (.vlx): "VLX1" | fileId(8) | plainSize(8) | chunks...
// Each chunk = AES-256-GCM of up to 128 KB (+16 byte tag), IV = fileId + chunk index.
// This allows streaming and seeking (video playback) without loading the whole file.

internal const val CHUNK = 128 * 1024
internal const val HEADER = 20L
private const val THUMB = 360
internal val MAGIC: ByteArray = "VLX1".toByteArray()

internal fun chunkIv(fileId: ByteArray, idx: Long): ByteArray =
    ByteBuffer.allocate(12).put(fileId).putInt(idx.toInt()).array()

class ItemInfo(val isVideo: Boolean, val mime: String, val thumb: Bitmap?)

/** Random-access decrypting reader for .vlx files. */
class VaultReader(file: File, private val key: SecretKey) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val fileId = ByteArray(8)
    var size: Long = 0L
        private set
    private var cachedIdx = -1L
    private var cached = ByteArray(0)

    init {
        try {
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw IOException("Not a vault file")
            raf.readFully(fileId)
            size = raf.readLong()
        } catch (e: Throwable) {
            raf.close()
            throw e
        }
    }

    @Synchronized
    fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        if (len == 0) return 0
        val idx = pos / CHUNK
        val chunk = load(idx)
        val co = (pos - idx * CHUNK).toInt()
        val n = minOf(len, chunk.size - co)
        System.arraycopy(chunk, co, buf, off, n)
        return n
    }

    private fun load(idx: Long): ByteArray {
        if (idx == cachedIdx) return cached
        val plainLen = minOf(CHUNK.toLong(), size - idx * CHUNK).toInt()
        val enc = ByteArray(plainLen + 16)
        raf.seek(HEADER + idx * (CHUNK + 16))
        raf.readFully(enc)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, chunkIv(fileId, idx)))
        cached = c.doFinal(enc)
        cachedIdx = idx
        return cached
    }

    fun readAll(): ByteArray {
        if (size > Int.MAX_VALUE) throw IOException("File too large")
        val out = ByteArray(size.toInt())
        var p = 0
        while (p < out.size) {
            val n = readAt(p.toLong(), out, p, out.size - p)
            if (n <= 0) throw IOException("Unexpected end of file")
            p += n
        }
        return out
    }

    fun copyTo(out: OutputStream) {
        val buf = ByteArray(CHUNK)
        var pos = 0L
        while (pos < size) {
            val n = readAt(pos, buf, 0, buf.size)
            if (n <= 0) throw IOException("Unexpected end of file")
            out.write(buf, 0, n)
            pos += n
        }
    }

    override fun close() { raf.close() }
}

// ------------------------------------------------------------ image helpers

/** Decodes with downsampling and fixes camera rotation. */
fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    if (o.outWidth <= 0 || o.outHeight <= 0) return null
    var s = 1
    while (o.outWidth / (s * 2) >= maxDim && o.outHeight / (s * 2) >= maxDim) s *= 2
    val bmp = BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }
    ) ?: return null
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val deg = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    return if (deg == 0f) bmp
    else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
}

private fun toJpeg(src: Bitmap): ByteArray {
    val m = maxOf(src.width, src.height)
    val b = if (m > THUMB) {
        Bitmap.createScaledBitmap(
            src,
            (src.width * THUMB / m).coerceAtLeast(1),
            (src.height * THUMB / m).coerceAtLeast(1),
            true
        )
    } else src
    val bos = ByteArrayOutputStream()
    b.compress(Bitmap.CompressFormat.JPEG, 82, bos)
    return bos.toByteArray()
}

private fun photoThumb(bytes: ByteArray): ByteArray? =
    runCatching { decodeSampled(bytes, THUMB)?.let { toJpeg(it) } }.getOrNull()

private fun videoThumb(ctx: Context, uri: Uri): ByteArray? = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        if (frame == null) null else toJpeg(frame)
    } finally {
        r.release()
    }
}.getOrNull()

// ------------------------------------------------------------ vault

/**
 * Every file is encrypted with a random master key.
 * The master key is wrapped by an Android Keystore key bound to the PIN.
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

    // ---------- Encrypt ----------

    private fun readFully(i: InputStream, b: ByteArray): Int {
        var t = 0
        while (t < b.size) {
            val n = i.read(b, t, b.size - t)
            if (n < 0) break
            t += n
        }
        return t
    }

    private fun encryptTo(input: InputStream, out: File, key: SecretKey) {
        if (out.exists()) out.delete()
        val fileId = ByteArray(8).also { SecureRandom().nextBytes(it) }
        try {
            RandomAccessFile(out, "rw").use { raf ->
                raf.write(MAGIC)
                raf.write(fileId)
                raf.writeLong(0L) // patched at the end
                val buf = ByteArray(CHUNK)
                var idx = 0L
                var total = 0L
                while (true) {
                    val n = readFully(input, buf)
                    if (n <= 0) break
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, chunkIv(fileId, idx)))
                    raf.write(c.doFinal(buf, 0, n))
                    total += n
                    idx++
                    if (n < CHUNK) break
                }
                raf.seek(12)
                raf.writeLong(total)
            }
        } catch (e: Throwable) {
            out.delete() // never leave a half-written file
            throw e
        }
    }

    // ---------- Meta / thumbnails ----------

    fun thumbFile(file: File) = File(file.parentFile, file.nameWithoutExtension + ".vtn")

    private fun writeMeta(f: File, key: SecretKey, isVideo: Boolean, mime: String, thumb: ByteArray?) {
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        val t = thumb ?: ByteArray(0)
        d.writeBoolean(isVideo)
        d.writeUTF(mime)
        d.writeInt(t.size)
        d.write(t)
        d.flush()
        encryptTo(ByteArrayInputStream(bos.toByteArray()), f, key)
    }

    private fun parseMeta(f: File, key: SecretKey): ItemInfo {
        VaultReader(f, key).use { r ->
            val d = DataInputStream(ByteArrayInputStream(r.readAll()))
            val isVideo = d.readBoolean()
            val mime = d.readUTF()
            val n = d.readInt()
            val t = ByteArray(n)
            d.readFully(t)
            val bmp = if (n > 0) BitmapFactory.decodeByteArray(t, 0, n) else null
            return ItemInfo(isVideo, mime, bmp)
        }
    }

    /** Type, mime and thumbnail of an item. Creates the thumbnail for old files on first use. */
    fun info(file: File): ItemInfo {
        val key = sessionKey ?: error("Vault is locked")
        val vtn = thumbFile(file)
        if (vtn.exists()) {
            val parsed = runCatching { parseMeta(vtn, key) }.getOrNull()
            if (parsed != null) return parsed
        }
        if (file.extension == "vlt") {
            // File saved by an older version of the app
            if (file.length() > 40L * 1024 * 1024) return legacyVideo(vtn, key)
            val bytes = readBytes(file)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0) return legacyVideo(vtn, key)
            val mime = o.outMimeType ?: "image/jpeg"
            val jpg = photoThumb(bytes)
            runCatching { writeMeta(vtn, key, false, mime, jpg) }
            return ItemInfo(false, mime, jpg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) })
        }
        return ItemInfo(false, "image/jpeg", null)
    }

    private fun legacyVideo(vtn: File, key: SecretKey): ItemInfo {
        runCatching { writeMeta(vtn, key, true, "video/mp4", null) }
        return ItemInfo(true, "video/mp4", null)
    }

    // ---------- Import ----------

    /** Encrypts a shared photo or video into the vault. Returns the new .vlx file. */
    fun importMedia(ctx: Context, uri: Uri): File {
        val key = sessionKey ?: error("Vault is locked")
        val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
        val isVideo = mime.startsWith("video/")
        if (!isVideo && !mime.startsWith("image/")) throw IllegalArgumentException("Unsupported type")

        val id = Base64.encodeToString(
            ByteArray(9).also { SecureRandom().nextBytes(it) },
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        val dir = vaultDir()
        val out = File(dir, "$id.vlx")
        val vtn = File(dir, "$id.vtn")
        try {
            val thumb: ByteArray?
            if (isVideo) {
                ctx.contentResolver.openInputStream(uri)!!.use { encryptTo(it, out, key) }
                thumb = videoThumb(ctx, uri)
            } else {
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                encryptTo(ByteArrayInputStream(bytes), out, key)
                thumb = photoThumb(bytes)
            }
            writeMeta(vtn, key, isVideo, mime, thumb)
        } catch (e: Throwable) {
            out.delete()
            vtn.delete()
            throw e
        }
        return out
    }

    // ---------- Read ----------

    private fun legacyStream(file: File, key: SecretKey): InputStream {
        val fis = file.inputStream()
        val iv = ByteArray(12)
        fis.read(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return CipherInputStream(fis, cipher)
    }

    /** Whole decrypted file in memory. Use for photos only. */
    fun readBytes(file: File): ByteArray {
        val key = sessionKey ?: error("Vault is locked")
        return if (file.extension == "vlt") legacyStream(file, key).use { it.readBytes() }
        else VaultReader(file, key).use { it.readAll() }
    }

    /** Random-access reader (used by the video player). New .vlx files only. */
    fun openReader(file: File): VaultReader {
        val key = sessionKey ?: error("Vault is locked")
        return VaultReader(file, key)
    }

    /** Streams the decrypted file into [out] (used by Restore). */
    fun copyDecrypted(file: File, out: OutputStream) {
        val key = sessionKey ?: error("Vault is locked")
        if (file.extension == "vlt") legacyStream(file, key).use { it.copyTo(out) }
        else VaultReader(file, key).use { it.copyTo(out) }
    }

    fun deleteItem(file: File) {
        file.delete()
        thumbFile(file).delete()
    }

    fun listItems(): List<File> =
        vaultDir().listFiles { f -> f.extension == "vlx" || f.extension == "vlt" }?.toList()
            ?: emptyList()
}
