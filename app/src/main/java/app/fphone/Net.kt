package app.fphone

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Error from the server (code from the JSON error body) or a local network problem. */
class ApiException(val code: String, val status: Int = 0) : Exception(code)

data class Contact(val id: Long, val name: String, val online: Boolean)

/** Runs blocking work on a background thread and delivers the result on the main thread. */
object Bg {
    private val pool = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val r = runCatching(work)
            main.post { done(r) }
        }
    }
}

/** Accepts only a server whose public key matches the pin ("sha256/<base64 of SHA-256(SPKI)>"). */
private class PinTrustManager(private val pin: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("client certificates are not supported")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("empty certificate chain")
        val sum = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
        val got = "sha256/" + Base64.encodeToString(sum, Base64.NO_WRAP)
        if (got != pin) throw CertificateException("the server key does not match the pinned fingerprint")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** REST client for the fPhone server. All calls block: use them from a background thread (Bg.run). */
class Net(private val prefs: Prefs) {
    @Volatile private var cached: OkHttpClient? = null
    @Volatile private var cachedKey = ""

    fun client(): OkHttpClient {
        val key = prefs.server + "|" + prefs.pin
        val c = cached
        if (c != null && key == cachedKey) return c
        val b = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
        val pin = prefs.pin
        if (pin.isNotEmpty()) {
            val tm = PinTrustManager(pin)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(tm), null)
            b.sslSocketFactory(ctx.socketFactory, tm)
            // The pin identifies the server key, so the host name does not matter.
            b.hostnameVerifier { _, _ -> true }
        }
        val built = b.build()
        cached = built
        cachedKey = key
        return built
    }

    fun wsUrl(): String =
        prefs.server.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"

    private fun request(method: String, path: String, body: JSONObject?, auth: Boolean): JSONObject {
        val rb = Request.Builder().url(prefs.server + "/api/v1" + path)
        if (auth) rb.header("Authorization", "Bearer " + prefs.sessionToken)
        val json = "application/json; charset=utf-8".toMediaType()
        when (method) {
            "POST" -> rb.post((body ?: JSONObject()).toString().toRequestBody(json))
            "PUT" -> rb.put((body ?: JSONObject()).toString().toRequestBody(json))
            else -> rb.get()
        }
        val result: JSONObject = try {
            client().newCall(rb.build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                val parsed = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    JSONObject()
                }
                if (!resp.isSuccessful) {
                    val code = parsed.optJSONObject("error")?.optString("code") ?: ""
                    throw ApiException(if (code.isEmpty()) "http_${resp.code}" else code, resp.code)
                }
                parsed
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: SSLException) {
            throw ApiException("cert")
        } catch (e: IOException) {
            throw ApiException("network")
        }
        return result
    }

    /** Returns the login response: token, expires_at, user. */
    fun login(login: String, password: String, deviceName: String, fcmToken: String): JSONObject {
        val device = JSONObject()
            .put("id", prefs.deviceId)
            .put("name", deviceName)
        if (fcmToken.isNotEmpty()) device.put("fcm_token", fcmToken)
        val body = JSONObject()
            .put("login", login)
            .put("password", password)
            .put("device", device)
        return request("POST", "/login", body, false)
    }

    fun logout() {
        request("POST", "/logout", null, true)
    }

    fun putPushToken(fcmToken: String) {
        request("PUT", "/device", JSONObject().put("fcm_token", fcmToken), true)
    }

    fun contacts(): List<Contact> {
        val arr = request("GET", "/contacts", null, true).optJSONArray("contacts") ?: return emptyList()
        val out = ArrayList<Contact>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Contact(o.getLong("id"), o.optString("name"), o.optBoolean("online")))
        }
        return out
    }
}
