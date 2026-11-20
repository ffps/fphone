@file:OptIn(ExperimentalFoundationApi::class)

package app.fphone.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.fphone.CallManager
import app.fphone.Contact
import app.fphone.ContactLastCall
import app.fphone.R

/** The saved order first, new contacts (alphabetical, as the server sends them) at the end. */
fun orderedIds(contacts: List<Contact>, order: List<Long>): List<Long> {
    val all = contacts.map { it.id }
    val known = all.toSet()
    val head = order.filter { it in known }.distinct()
    return head + all.filter { it !in head }
}

/** The list of contacts. A long press on a contact starts dragging it to another place. */
@Composable
fun ContactsTab(onCall: (Long, String, Boolean, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val app = rememberApp()
    val calls = app.calls
    val visibleContacts = calls.contacts.filter { it.reachable || it.online }
    val byId = visibleContacts.associateBy { it.id }
    val sortedIds = orderedIds(visibleContacts, app.prefs.contactOrder)
    val ids = remember(sortedIds) { sortedIds.toMutableStateList() }

    var dragging by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var pullDistance by remember { mutableFloatStateOf(0f) }
    var refreshing by remember { mutableStateOf(false) }
    val refreshHandler = remember { Handler(Looper.getMainLooper()) }
    var selectedContact by remember { mutableStateOf<Contact?>(null) }
    fun refreshContacts() {
        if (refreshing) return
        refreshing = true
        val startedAt = SystemClock.elapsedRealtime()
        calls.loadContacts {
            val remaining = (2000L - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L)
            refreshHandler.postDelayed({ refreshing = false }, remaining)
        }
    }
    val refreshTransition = rememberInfiniteTransition(label = "contacts-refresh")
    val refreshRotation by refreshTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "contacts-refresh-rotation"
    )
    val refreshConnection = remember(listState, density) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == NestedScrollSource.Drag && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 && available.y > 0) {
                    pullDistance += available.y
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (pullDistance > with(density) { 72.dp.toPx() }) refreshContacts()
                pullDistance = 0f
                return Velocity.Zero
            }
        }
    }

    if (byId.isEmpty()) {
        val threshold = with(density) { 72.dp.toPx() }
        var emptyPull by remember { mutableFloatStateOf(0f) }
        Box(
            modifier.fillMaxSize().pointerInput(threshold) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, drag ->
                        if (drag > 0) emptyPull += drag
                        change.consume()
                    },
                    onDragEnd = {
                        if (emptyPull > threshold) refreshContacts()
                        emptyPull = 0f
                    },
                    onDragCancel = { emptyPull = 0f }
                )
            },
            contentAlignment = Alignment.Center
        ) {
            if (refreshing) Icon(
                painterResource(R.drawable.ic_refresh),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp).graphicsLayer { rotationZ = refreshRotation; alpha = 0.5f }
            )
        }
    } else {
        val finishDrag: () -> Unit = {
            dragging = null
            dragOffset = 0f
            app.prefs.contactOrder = ids.toList()
        }
        Box(modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().nestedScroll(refreshConnection)) {
            items(ids, key = { it }) { id ->
                val c = byId[id]
                if (c != null) {
                    val isDragged = dragging == id
                    val online = c.id in calls.online
                    val canCall = (online || c.reachable) && calls.state == CallManager.State.IDLE
                    val itemModifier = if (isDragged) {
                        Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset }
                    } else {
                        Modifier.animateItemPlacement()
                    }
                    ContactRow(
                        c = c,
                        online = online,
                        canCall = canCall,
                        dragged = isDragged,
                        onCall = { id, name, video -> onCall(id, name, video, false) },
                        onDetails = { selectedContact = c },
                        modifier = itemModifier.pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragging = id
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val infos = listState.layoutInfo.visibleItemsInfo
                                    val cur = infos.firstOrNull { it.key == id }
                                    if (cur != null) {
                                        val center = cur.offset + cur.size / 2f + dragOffset
                                        val target = infos.firstOrNull {
                                            it.key != id && center >= it.offset && center < it.offset + it.size
                                        }
                                        if (target != null) {
                                            val from = ids.indexOf(id)
                                            val to = ids.indexOf(target.key as Long)
                                            if (from >= 0 && to >= 0 && from != to) {
                                                ids.add(to, ids.removeAt(from))
                                                // The item jumped to the other place: keep it under the finger.
                                                dragOffset -= (target.offset - cur.offset)
                                            }
                                        }
                                    }
                                },
                                onDragEnd = { finishDrag() },
                                onDragCancel = { finishDrag() }
                            )
                        }
                    )
                }
            }
        }
        if (refreshing) Icon(
            painterResource(R.drawable.ic_refresh),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.Center).size(48.dp).graphicsLayer { rotationZ = refreshRotation; alpha = 0.5f }
        )
        }
    }
    selectedContact?.let { c ->
        ContactDetailsDialog(c, online = c.online || c.id in calls.online, canCall = calls.state == CallManager.State.IDLE && (c.reachable || c.id in calls.online),
            onDismiss = { selectedContact = null }, onCall = { selectedContact = null; onCall(c.id,c.name,true,false) })
    }
}

@Composable
private fun ContactRow(
    c: Contact,
    online: Boolean,
    canCall: Boolean,
    dragged: Boolean,
    onCall: (Long, String, Boolean) -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier
) {
    val background = if (dragged) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    Row(
        modifier.fillMaxWidth().background(background).clickable(onClick = onDetails).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(c.id, c.name, c.avatar, 52.dp, online)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = if (online) stringResource(R.string.status_online) else lastSeenText(c.lastSeen)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val tint = if (canCall) MaterialTheme.colorScheme.primary else LocalContentColor.current
        IconButton(onClick = { onCall(c.id, c.name, true) }, enabled = canCall) {
            Icon(painterResource(R.drawable.ic_call), contentDescription = stringResource(R.string.cd_call), tint = tint)
        }
    }
}


@Composable
private fun ContactDetailsDialog(c: Contact, online: Boolean, canCall: Boolean, onDismiss: () -> Unit, onCall: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(Modifier.widthIn(min = 280.dp, max = 360.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(c.id, c.name, c.avatar, 104.dp, online)
                Spacer(Modifier.height(12.dp))
                Text(c.name, style = MaterialTheme.typography.headlineSmall)
                Text("@${c.login}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(if (online) "Сейчас в сети" else lastSeenText(c.lastSeen), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val last = c.lastCall
                if (last != null) {
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val missed = last.result == "missed"
                        val icon = when { missed -> R.drawable.ic_call_missed; last.outgoing -> R.drawable.ic_call_made; else -> R.drawable.ic_call_received }
                        val tint = when { missed -> Color(0xFFD32F2F); last.outgoing -> Color(0xFF2E7D32); else -> Color(0xFF1976D2) }
                        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(lastCallLabel(last), style = MaterialTheme.typography.bodyMedium)
                            Text(formatLastCallTime(last.ts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = onCall, enabled = canCall, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.ic_call), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Вызов")
                }
            }
        }
    }
}

private fun lastCallLabel(c: ContactLastCall): String = when (c.result) {
    "missed" -> "Пропущенный"
    "declined", "rejected" -> "Отклонённый"
    "cancelled" -> "Отменён"
    "elsewhere" -> "Ответили на другом устройстве"
    "busy" -> "Занято"
    "no_answer" -> if (c.outgoing) "Нет ответа" else "Пропущенный"
    "offline" -> "Недоступен"
    "answered", "hangup" -> if (c.outgoing) "Исходящий" else "Входящий"
    else -> if (c.outgoing) "Исходящий" else "Входящий"
}

private fun formatLastCallTime(ts: Long): String {
    if (ts <= 0L) return ""
    val date = Date(ts * 1000L)
    return SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault()).format(date)
}


private fun lastSeenText(timestamp: Long): String {
    if (timestamp <= 0L) return "давно не был в сети"
    val date = Date(timestamp * 1000L)
    return when {
        android.text.format.DateUtils.isToday(date.time) ->
            "был в сети сегодня, " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        android.text.format.DateUtils.isToday(date.time + android.text.format.DateUtils.DAY_IN_MILLIS) ->
            "был в сети вчера, " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        else -> "был в сети " + SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault()).format(date)
    }
}
