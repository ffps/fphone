package app.fphone

import android.content.Context

/** Maps codes from the server and from the call logic to texts for the user (see strings.xml). */
object Texts {
    fun reason(ctx: Context, code: String?): String = ctx.getString(
        when (code) {
            "hangup" -> R.string.reason_hangup
            "rejected" -> R.string.reason_rejected
            "declined" -> R.string.reason_declined
            "cancelled" -> R.string.reason_cancelled
            "no_answer" -> R.string.reason_no_answer
            "busy" -> R.string.reason_busy
            "offline" -> R.string.reason_offline
            "server_unavailable" -> R.string.reason_server_unavailable
            "timeout" -> R.string.reason_timeout
            "answered_elsewhere" -> R.string.reason_answered_elsewhere
            "gone" -> R.string.reason_gone
            "unknown_user" -> R.string.reason_unknown_user
            "already_in_call", "server_busy" -> R.string.reason_server_busy
            else -> R.string.reason_error
        }
    )

    fun apiError(ctx: Context, e: Throwable?): String = ctx.getString(
        when ((e as? ApiException)?.code) {
            "invalid_credentials" -> R.string.err_invalid_credentials
            "rate_limited" -> R.string.err_rate_limited
            "network" -> R.string.err_network
            "cert" -> R.string.err_cert
            else -> R.string.err_server
        }
    )
}
