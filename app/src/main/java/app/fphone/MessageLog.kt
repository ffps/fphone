package app.fphone

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Event log kept in SharedPreferences. Push messages can arrive while the app is not running,
 * so the log must survive: open the app later and read what happened.
 */
object MessageLog {
    private const val PREFS = "fphone_log"
    private const val KEY = "lines"
    private const val MAX_LINES = 60

    /** Called after every change (the screen uses it to refresh itself). */
    @Volatile
    var listener: (() -> Unit)? = null

    @Synchronized
    fun add(ctx: Context, text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = (p.getString(KEY, "") ?: "").split("\n").filter { it.isNotEmpty() }
        val lines = (old + "$time  ${text.replace('\n', ' ')}").takeLast(MAX_LINES)
        p.edit().putString(KEY, lines.joinToString("\n")).apply()
        listener?.invoke()
    }

    @Synchronized
    fun text(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    @Synchronized
    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        listener?.invoke()
    }
}
