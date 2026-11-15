package app.fphone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One line of the call history ("Recents").
 * result: answered, missed, declined, rejected, no_answer, busy, offline, cancelled, elsewhere, failed
 */
data class HistoryEntry(
    val ts: Long,
    val peerId: Long,
    val peerName: String,
    val outgoing: Boolean,
    val video: Boolean,
    val result: String,
    val durationSec: Int
)

/** Call history kept on this phone (the server does not store it). */
class History(app: Context) {
    interface Listener {
        fun onHistoryChanged()
    }

    private val sp = app.getSharedPreferences("fphone_history", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<Listener>()

    var entries: List<HistoryEntry> = load()
        private set

    /** Missed calls that the user has not seen yet (shown as a badge on the tab). */
    var unseenMissed: Int
        get() = sp.getInt("unseen", 0)
        private set(v) = sp.edit().putInt("unseen", v).apply()

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        for (l in listeners) l.onHistoryChanged()
    }

    @Synchronized
    fun add(e: HistoryEntry) {
        entries = (listOf(e) + entries).take(MAX_ENTRIES)
        if (e.result == "missed") unseenMissed += 1
        save()
        notifyChanged()
    }

    @Synchronized
    fun markSeen() {
        if (unseenMissed != 0) {
            unseenMissed = 0
            notifyChanged()
        }
    }

    @Synchronized
    fun clear() {
        entries = emptyList()
        unseenMissed = 0
        save()
        notifyChanged()
    }

    private fun save() {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("ts", e.ts).put("peer", e.peerId).put("name", e.peerName)
                    .put("out", e.outgoing).put("video", e.video)
                    .put("result", e.result).put("dur", e.durationSec)
            )
        }
        sp.edit().putString("entries", arr.toString()).apply()
    }

    private fun load(): List<HistoryEntry> {
        val text = sp.getString("entries", null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    o.getLong("ts"), o.getLong("peer"), o.optString("name"),
                    o.optBoolean("out"), o.optBoolean("video"), o.optString("result"), o.optInt("dur")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val MAX_ENTRIES = 200
    }
}
