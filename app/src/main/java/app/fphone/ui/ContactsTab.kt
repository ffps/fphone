@file:OptIn(ExperimentalFoundationApi::class)

package app.fphone.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun ContactsTab(onCall: (Long, String, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val app = rememberApp()
    val calls = app.calls
    val byId = calls.contacts.associateBy { it.id }
    val sortedIds = orderedIds(calls.contacts, app.prefs.contactOrder)
    val ids = remember(sortedIds) { sortedIds.toMutableStateList() }

    var dragging by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()

    if (byId.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.contacts_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        val finishDrag: () -> Unit = {
            dragging = null
            dragOffset = 0f
            app.prefs.contactOrder = ids.toList()
        }
        LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
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
                        onCall = onCall,
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
            item {
                Text(
                    stringResource(R.string.contacts_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ContactRow(
    c: Contact,
    online: Boolean,
    canCall: Boolean,
    dragged: Boolean,
    onCall: (Long, String, Boolean) -> Unit,
    modifier: Modifier
) {
    val background = if (dragged) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    Row(
        modifier.fillMaxWidth().background(background).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(c.id, c.name, c.avatar, 52.dp, online)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when {
                online -> R.string.status_online
                c.reachable -> R.string.status_offline
                else -> R.string.status_unavailable
            }
            Text(
                stringResource(status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val tint = if (canCall) MaterialTheme.colorScheme.primary else LocalContentColor.current
        IconButton(onClick = { onCall(c.id, c.name, false) }, enabled = canCall) {
            Icon(painterResource(R.drawable.ic_call), contentDescription = stringResource(R.string.cd_call), tint = tint)
        }
        IconButton(onClick = { onCall(c.id, c.name, true) }, enabled = canCall) {
            Icon(painterResource(R.drawable.ic_videocam), contentDescription = stringResource(R.string.cd_video), tint = tint)
        }
    }
}
