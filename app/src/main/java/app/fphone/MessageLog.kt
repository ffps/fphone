package app.fphone

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MessageLog {
    private const val PREFS = "fphone_log"
    private const val KEY = "lines"
    private const val MAX_LINES = 250

    @Volatile var listener: (() -> Unit)? = null

    @Synchronized fun add(ctx: Context, text: String) = add(ctx, "APP", text)

    @Synchronized fun add(ctx: Context, category: String, text: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = (p.getString(KEY, "") ?: "").split("\n").filter { it.isNotEmpty() }
        val line = "$time [$category] ${text.replace('\n', ' ')}"
        p.edit().putString(KEY, (old + line).takeLast(MAX_LINES).joinToString("\n")).apply()
        listener?.invoke()
    }

    @Synchronized fun text(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    @Synchronized fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        listener?.invoke()
    }
}
