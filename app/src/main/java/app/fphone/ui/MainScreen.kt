@file:OptIn(ExperimentalMaterial3Api::class)

package app.fphone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.fphone.CallActivity
import app.fphone.CallManager
import app.fphone.Notifications
import app.fphone.R
import kotlinx.coroutines.delay

/**
 * The main screen: tabs "Contacts" and "Recents" at the bottom, the menu with the three dots at the
 * top right (Settings). The settings are a separate screen that replaces this one.
 */
@Composable
fun MainScreen(
    startTab: Int,
    dynamicColor: Boolean,
    onDynamicColor: (Boolean) -> Unit,
    onCall: (Long, String, Boolean, Boolean) -> Unit,
    onUpdate: () -> Unit,
    onLogout: () -> Unit
) {
    val ctx = LocalContext.current
    val app = rememberApp()
    val calls = app.calls
    val prefs = app.prefs
    var tab by rememberSaveable(startTab) { mutableIntStateOf(startTab) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(tab) {
        if (tab == 1) {
            app.history.markSeen()
            Notifications.clearMissed(ctx)
        }
    }

    if (about) {
        BackHandler { about = false }
        AboutScreen(onUpdate = onUpdate, onBack = { about = false })
    } else if (settings) {
        BackHandler { settings = false }
        SettingsScreen(
            dynamicColor = dynamicColor,
            onDynamicColor = onDynamicColor,
            onLogout = {
                settings = false
                onLogout()
            },
            onBack = { settings = false }
        )
    } else {
        val missed = app.history.unseenMissed
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.padding(end = 8.dp).size(30.dp).clip(RoundedCornerShape(7.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(painterResource(R.drawable.app_icon), contentDescription = null, modifier = Modifier.size(30.dp))
                            }
                            Text(stringResource(if (tab == 0) R.string.tab_contacts else R.string.tab_recents))
                        }
                    },
                    actions = {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.cd_menu))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_settings)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    settings = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("О приложении") },
                                leadingIcon = {
                                    androidx.compose.material3.BadgedBox(badge = {
                                        if (prefs.pushIssue) Badge(containerColor = androidx.compose.ui.graphics.Color.Red)
                                    }) { Icon(painterResource(R.drawable.ic_info), contentDescription = null) }
                                },
                                onClick = {
                                    menuOpen = false
                                    prefs.pushIssue = false
                                    about = true
                                }
                            )
                            if (tab == 1) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_clear_recents)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        app.history.clear()
                                    }
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(painterResource(R.drawable.ic_person), contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_contacts)) }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = {
                            BadgedBox(badge = { if (missed > 0) Badge { Text(missed.toString()) } }) {
                                Icon(painterResource(R.drawable.ic_history), contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(R.string.tab_recents)) }
                    )
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding)) {
                // A call is going on: return to it or end it.
                val active = calls.call
                var showCallBanner by remember { mutableStateOf(false) }
                LaunchedEffect(active?.id) {
                    showCallBanner = false
                    val id = active?.id
                    if (id != null) {
                        delay(1000)
                        showCallBanner = calls.call?.id == id && calls.state != CallManager.State.IDLE
                    }
                }
                if (active != null && calls.state != CallManager.State.IDLE && showCallBanner) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    ) {
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.main_call_active, active.peerName),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall
                            )
                            TextButton(onClick = { ctx.startActivity(CallActivity.intent(ctx)) }) {
                                Text(stringResource(R.string.btn_return))
                            }
                            TextButton(onClick = { calls.hangup() }) { Text(stringResource(R.string.btn_hangup)) }
                        }
                    }
                }

                // "No connection to the server", shown only if it lasts a few seconds.
                val connected = calls.connected
                var showOffline by remember { mutableStateOf(false) }
                LaunchedEffect(connected) {
                    if (connected) {
                        showOffline = false
                    } else {
                        delay(3000)
                        showOffline = true
                    }
                }
                if (showOffline && !connected) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.banner_offline),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                if (tab == 0) {
                    ContactsTab(onCall = onCall)
                } else {
                    RecentsTab(onCall = onCall)
                }
            }
        }
    }
}
