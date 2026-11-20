package app.fphone.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fphone.CallManager
import app.fphone.HistoryEntry
import app.fphone.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Call history: who called when, missed calls in red; a tap on the button calls back. */
@Composable
fun RecentsTab(onCall: (Long, String, Boolean, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val app = rememberApp()
    val entries = app.history.entries
    val byId = app.calls.contacts.associateBy { it.id }

    if (entries.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Список пуст", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(modifier.fillMaxSize()) {
            items(entries) { e ->
                val contact = byId[e.peerId]
                val missed = e.result == "missed"
                val canCall = contact != null && (contact.reachable || contact.id in app.calls.online) &&
                    app.calls.state == CallManager.State.IDLE
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val directionIcon = when {
                        missed -> R.drawable.ic_call_missed
                        e.outgoing -> R.drawable.ic_call_made
                        else -> R.drawable.ic_call_received
                    }
                    val directionTint = when {
                        missed -> androidx.compose.ui.graphics.Color(0xFFD32F2F)
                        e.outgoing -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
                        else -> androidx.compose.ui.graphics.Color(0xFF1976D2)
                    }
                    Icon(painterResource(directionIcon), contentDescription = null, tint = directionTint, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Avatar(e.peerId, e.peerName, contact?.avatar ?: 0L, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            e.peerName,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            subtitle(e),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val tint = if (canCall) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    IconButton(onClick = { onCall(e.peerId, e.peerName, false, true) }, enabled = canCall) {
                        Icon(
                            painterResource(R.drawable.ic_call),
                            contentDescription = stringResource(R.string.cd_call),
                            tint = tint
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun subtitle(e: HistoryEntry): String {
    val what = stringResource(
        when (e.result) {
            "answered" -> if (e.outgoing) R.string.h_outgoing else R.string.h_incoming
            "missed" -> R.string.h_missed
            "declined" -> R.string.h_declined
            "rejected" -> R.string.h_rejected
            "no_answer" -> R.string.h_no_answer
            "busy" -> R.string.h_busy
            "offline" -> R.string.h_offline
            "cancelled" -> R.string.h_cancelled
            "elsewhere" -> R.string.h_elsewhere
            else -> R.string.h_failed
        }
    )
    val yesterday = stringResource(R.string.yesterday)
    val time = when {
        DateUtils.isToday(e.ts) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(e.ts))
        DateUtils.isToday(e.ts + DateUtils.DAY_IN_MILLIS) ->
            yesterday + ", " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(e.ts))
        else -> SimpleDateFormat("dd.MM, HH:mm", Locale.getDefault()).format(Date(e.ts))
    }
    val duration = if (e.result == "answered" && e.durationSec > 0) " \u00B7 %d:%02d".format(e.durationSec / 60, e.durationSec % 60) else ""
    return "$what \u00B7 $time$duration"
}
