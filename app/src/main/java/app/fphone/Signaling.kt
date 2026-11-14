package app.fphone

import android.os.Handler
import android.os.Looper
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One WebSocket connection to the server. A new object is made for every connection attempt.
 * All listener callbacks are delivered on the main thread, and never after close().
 */
class Signaling(
    private val net: Net,
    private val prefs: Prefs,
    private val listener: Listener
) {
    interface Listener {
        fun onSigOpen(s: Signaling)
        fun onSigMessage(s: Signaling, m: JSONObject)
        fun onSigClosed(s: Signaling, code: Int, reason: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null

    @Volatile
    private var closed = false

    // The server drops connections that are silent for 60 s (Keenetic proxy). Besides the
    // WebSocket pings of OkHttp we send an application level ping every 20 s.
    private val heartbeat = object : Runnable {
        override fun run() {
            if (closed) return
            send(JSONObject().put("t", "ping"))
            main.postDelayed(this, 20_000)
        }
    }

    /** wakeCall: ID of the call that a push message woke the app for (or null). */
    fun connect(wakeCall: String?) {
        val client = net.client().newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(net.wsUrl()).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val auth = JSONObject()
                    .put("t", "auth")
                    .put("token", prefs.sessionToken)
                    .put("proto", 1)
                if (!wakeCall.isNullOrEmpty()) auth.put("call", wakeCall)
                webSocket.send(auth.toString())
                main.post {
                    if (!closed) {
                        listener.onSigOpen(this@Signaling)
                        main.postDelayed(heartbeat, 20_000)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    return
                }
                main.post { if (!closed) listener.onSigMessage(this@Signaling, m) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(1006, t.message ?: "failure")
            }
        })
    }

    private fun finish(code: Int, reason: String) {
        main.post {
            if (!closed) {
                closed = true
                main.removeCallbacks(heartbeat)
                listener.onSigClosed(this, code, reason)
            }
        }
    }

    fun send(o: JSONObject): Boolean = ws?.send(o.toString()) ?: false

    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(heartbeat)
        ws?.close(1000, "bye")
    }
}
