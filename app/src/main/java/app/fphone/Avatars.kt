package app.fphone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * Photos of the users. The server tells a "version" of the photo of every contact (0 = no photo).
 * A photo is downloaded once per version and kept in files/avatars, so it is not requested again.
 */
class Avatars(private val app: App) {
    private val memory = LruCache<String, Bitmap>(40)
    private val dir = File(app.filesDir, "avatars")

    private fun key(userId: Long, version: Long) = "${userId}_$version"

    private fun file(userId: Long, version: Long) = File(dir, key(userId, version) + ".jpg")

    /** The photo if it is already on this phone. Fast: may be called from the main thread. */
    fun cached(userId: Long, version: Long): Bitmap? {
        if (version <= 0L) return null
        val k = key(userId, version)
        memory.get(k)?.let { return it }
        val f = file(userId, version)
        if (!f.exists()) return null
        val bmp = decode(f) ?: return null
        memory.put(k, bmp)
        return bmp
    }

    /** The photo, downloaded if needed. Blocking: call it from a background thread. */
    fun load(userId: Long, version: Long): Bitmap? {
        cached(userId, version)?.let { return it }
        if (version <= 0L) return null
        val bytes = try {
            app.net.avatar(userId)
        } catch (e: Exception) {
            null
        } ?: return null
        dir.mkdirs()
        // Remove older versions of this user's photo.
        dir.listFiles()?.forEach { if (it.name.startsWith("${userId}_")) it.delete() }
        val f = file(userId, version)
        f.writeBytes(bytes)
        val bmp = decode(f) ?: return null
        memory.put(key(userId, version), bmp)
        return bmp
    }

    // Photos are shown at most about 250 pixels wide: decode them smaller to save memory.
    private fun decode(f: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.path, opts)
    }
}
