package com.example.myvault

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

class NoteData(val title: String, val body: String)

/** One row in the notes list. [data] is null while the notes are locked. */
class NoteRow(val id: String, val modified: Long, val cipher: String, val data: NoteData?)

/**
 * Notes are encrypted with a key made from a separate notes password (PBKDF2 + AES-256-GCM).
 * The key exists only in memory while the notes are unlocked.
 */
object NotesCrypto {

    private const val PREFS = "notes_prefs"
    private const val K_SALT = "salt"
    private const val K_CHECK = "check"
    private const val K_FAILS = "fails"
    private const val K_LAST_FAIL = "last_fail"
    private const val K_LOCK_UNTIL = "lock_until"
    private const val ITER = 200_000
    private val CHECK_TEXT = "NOTES_OK".toByteArray()

    @Volatile
    private var key: SecretKey? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.filesDir, "notes").also { it.mkdirs() }

    fun isSet(ctx: Context): Boolean = prefs(ctx).contains(K_CHECK)
    fun isUnlocked(): Boolean = key != null
    fun lock() { key = null }

    // ---------- password ----------

    private fun derive(pw: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(pw.toCharArray(), salt, ITER, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun enc(k: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    private fun dec(k: SecretKey, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return c.doFinal(data, 12, data.size - 12)
    }

    /** First-time setup. Do not call on the main thread. */
    fun setPassword(ctx: Context, pw: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val k = derive(pw, salt)
        prefs(ctx).edit()
            .putString(K_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(K_CHECK, Base64.encodeToString(enc(k, CHECK_TEXT), Base64.NO_WRAP))
            .putInt(K_FAILS, 0)
            .putLong(K_LOCK_UNTIL, 0L)
            .apply()
        key = k
    }

    /** True if the password is right. Do not call on the main thread. */
    fun unlock(ctx: Context, pw: String): Boolean {
        val p = prefs(ctx)
        val salt = Base64.decode(p.getString(K_SALT, null) ?: return false, Base64.NO_WRAP)
        val check = Base64.decode(p.getString(K_CHECK, null) ?: return false, Base64.NO_WRAP)
        val k = derive(pw, salt)
        val ok = runCatching { dec(k, check).contentEquals(CHECK_TEXT) }.getOrDefault(false)
        if (ok) {
            key = k
            p.edit().putInt(K_FAILS, 0).putLong(K_LOCK_UNTIL, 0L).apply()
        } else {
            recordFailure(p)
        }
        return ok
    }

    fun lockoutMs(ctx: Context): Long =
        (prefs(ctx).getLong(K_LOCK_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun recordFailure(p: SharedPreferences) {
        val now = System.currentTimeMillis()
        val old = if (now - p.getLong(K_LAST_FAIL, 0L) > 6L * 3600_000L) 0 else p.getInt(K_FAILS, 0)
        val n = old + 1
        val e = p.edit().putInt(K_FAILS, n).putLong(K_LAST_FAIL, now)
        if (n >= 5) e.putLong(K_LOCK_UNTIL, now + 30_000L * (1L shl min(n - 5, 6)))
        e.apply()
    }

    // ---------- notes ----------

    private fun parse(plain: ByteArray): NoteData {
        val d = DataInputStream(ByteArrayInputStream(plain))
        val t = ByteArray(d.readInt())
        d.readFully(t)
        val b = ByteArray(d.readInt())
        d.readFully(b)
        return NoteData(String(t, Charsets.UTF_8), String(b, Charsets.UTF_8))
    }

    /** All notes, newest first. Readable only if the notes are unlocked. */
    fun list(ctx: Context): List<NoteRow> {
        val k = key
        val files = dir(ctx).listFiles { f -> f.extension == "nte" }
            ?.sortedByDescending { it.lastModified() } ?: return emptyList()
        return files.map { f ->
            val bytes = runCatching { f.readBytes() }.getOrDefault(ByteArray(0))
            val cipher = if (bytes.size > 12) {
                Base64.encodeToString(bytes, 12, min(bytes.size - 12, 72), Base64.NO_WRAP)
            } else ""
            val data = if (k != null && bytes.size > 12) {
                runCatching { parse(dec(k, bytes)) }.getOrNull()
            } else null
            NoteRow(f.nameWithoutExtension, f.lastModified(), cipher, data)
        }
    }

    /** Saves a note and returns its id. */
    fun save(ctx: Context, id: String?, title: String, body: String): String {
        val k = key ?: error("Notes are locked")
        val nid = id ?: Base64.encodeToString(
            ByteArray(9).also { SecureRandom().nextBytes(it) },
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        val t = title.toByteArray(Charsets.UTF_8)
        val b = body.toByteArray(Charsets.UTF_8)
        d.writeInt(t.size)
        d.write(t)
        d.writeInt(b.size)
        d.write(b)
        d.flush()
        val out = enc(k, bos.toByteArray())
        val f = File(dir(ctx), "$nid.nte")
        val tmp = File(dir(ctx), "$nid.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(f)) {
            f.writeBytes(out)
            tmp.delete()
        }
        return nid
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.nte").delete()
    }
}
