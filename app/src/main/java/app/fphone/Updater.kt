package app.fphone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Self-update from the GitHub releases of the repository that built this app.
 * The repository name is put into the app at build time (BuildConfig.UPDATE_REPO), nothing is
 * stored in the source files. The repository must be public: no login is used.
 *
 * Flow: check() asks GitHub for the latest release and compares its version with the installed
 * one; download() fetches the APK of the release into the cache; install() hands it to the system
 * installer. Android only installs an update that is signed with the same key as this app.
 */
class Updater(private val app: App) {

    enum class Kind { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, ERROR }

    interface Listener {
        fun onUpdateChanged()
    }

    private class Release(val version: String, val url: String, val size: Long)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    var kind = Kind.IDLE
        private set
    var latestVersion = ""
        private set
    var progress = 0 // percent while downloading
        private set
    var errorCode = ""
        private set

    private var assetUrl = ""
    private var assetSize = 0L
    private var apkFile: File? = null
    private var autoChecked = false

    val installedVersion: String
        get() = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
        } catch (e: Exception) {
            "0"
        }

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun set(k: Kind) {
        kind = k
        for (l in listeners) l.onUpdateChanged()
    }

    /** Checks once per process start without being asked (the manual button works any time). */
    fun autoCheck() {
        if (autoChecked) return
        autoChecked = true
        check()
    }

    fun check() {
        if (kind == Kind.CHECKING || kind == Kind.DOWNLOADING) return
        if (BuildConfig.UPDATE_REPO.isEmpty()) {
            errorCode = "no_repo"
            set(Kind.ERROR)
            return
        }
        errorCode = ""
        set(Kind.CHECKING)
        Bg.run({ fetchLatest() }) { r ->
            r.onSuccess { rel ->
                when {
                    rel == null -> {
                        errorCode = "no_release"
                        set(Kind.ERROR)
                    }
                    isNewer(rel.version, installedVersion) -> {
                        latestVersion = rel.version
                        assetUrl = rel.url
                        assetSize = rel.size
                        set(Kind.AVAILABLE)
                    }
                    else -> set(Kind.UP_TO_DATE)
                }
            }
            r.onFailure { e ->
                MessageLog.add(app, "update check failed: $e")
                errorCode = "check"
                set(Kind.ERROR)
            }
        }
    }

    /** Returns null when the repository has no release yet. */
    private fun fetchLatest(): Release? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("GitHub answered HTTP ${resp.code}")
            val j = JSONObject(resp.body?.string() ?: "")
            val version = j.optString("tag_name").removePrefix("v")
            val assets = j.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Release(version, a.getString("browser_download_url"), a.optLong("size"))
                }
            }
            return null
        }
    }

    fun download() {
        if (kind != Kind.AVAILABLE && kind != Kind.ERROR) return
        if (assetUrl.isEmpty()) return
        progress = 0
        errorCode = ""
        set(Kind.DOWNLOADING)
        Bg.run({ downloadFile() }) { r ->
            r.onSuccess { f ->
                apkFile = f
                set(Kind.READY)
            }
            r.onFailure { e ->
                MessageLog.add(app, "update download failed: $e")
                errorCode = "download"
                set(Kind.ERROR)
            }
        }
    }

    private fun downloadFile(): File {
        val dir = File(app.cacheDir, "updates")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() } // remove older downloads
        val out = File(dir, "Kukukha-$latestVersion.apk")
        http.newCall(Request.Builder().url(assetUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("download answered HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty download")
            val total = if (assetSize > 0) assetSize else body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) {
                                last = p
                                main.post {
                                    if (kind == Kind.DOWNLOADING) {
                                        progress = p
                                        set(Kind.DOWNLOADING)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (assetSize > 0 && out.length() != assetSize) {
            out.delete()
            throw IOException("size mismatch")
        }
        return out
    }

    /**
     * Starts the system installer for the downloaded APK. Returns false if the user first has to
     * allow this app to install packages (the settings screen is opened in that case).
     */
    fun install(ctx: Context): Boolean {
        val f = apkFile ?: return false
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return true
    }

    companion object {
        private fun parseVersion(v: String): List<Int> {
            val parts = v.trim().removePrefix("v").split('.')
            return (0 until 3).map { i -> parts.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0 }
        }

        /** True if version a is newer than version b ("0.2.10" > "0.2.9"; "0.0.0-dev" counts as 0.0.0). */
        fun isNewer(a: String, b: String): Boolean {
            val x = parseVersion(a)
            val y = parseVersion(b)
            for (i in 0 until 3) {
                if (x[i] != y[i]) return x[i] > y[i]
            }
            return false
        }
    }
}
