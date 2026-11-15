@file:OptIn(ExperimentalMaterial3Api::class)

package app.fphone.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.fphone.MessageLog
import app.fphone.R

@Composable
fun SettingsScreen(
    dynamicColor: Boolean,
    onDynamicColor: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = rememberApp().prefs
    var confirmLogout by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.section_account))
            ListItem(
                headlineContent = { Text(prefs.userName) },
                supportingContent = { Text(prefs.login) },
                trailingContent = {
                    OutlinedButton(onClick = { confirmLogout = true }) { Text(stringResource(R.string.btn_logout)) }
                }
            )

            SectionTitle(stringResource(R.string.section_server))
            ListItem(
                headlineContent = { Text(stringResource(R.string.label_server)) },
                supportingContent = { Text(prefs.server) }
            )
            if (prefs.pin.isNotEmpty()) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.label_pin)) },
                    supportingContent = { Text(prefs.pin, fontFamily = FontFamily.Monospace) }
                )
            }

            SectionTitle(stringResource(R.string.section_look))
            ListItem(
                headlineContent = { Text(stringResource(R.string.dynamic_color)) },
                supportingContent = { Text(stringResource(R.string.dynamic_color_hint)) },
                trailingContent = {
                    Switch(
                        checked = dynamicColor,
                        onCheckedChange = onDynamicColor,
                        enabled = Build.VERSION.SDK_INT >= 31
                    )
                }
            )

            SectionTitle(stringResource(R.string.section_reliability))
            val notifOk = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
            StatusRow(
                stringResource(R.string.setting_notifications), notifOk,
                stringResource(R.string.perm_ok), stringResource(R.string.perm_no)
            ) {
                ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                )
            }
            val fullScreenOk = Build.VERSION.SDK_INT < 34 ||
                ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            StatusRow(
                stringResource(R.string.setting_fullscreen), fullScreenOk,
                stringResource(R.string.perm_ok), stringResource(R.string.fullscreen_no)
            ) {
                if (Build.VERSION.SDK_INT >= 34) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName))
                    )
                }
            }
            val batteryOk = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(ctx.packageName)
            StatusRow(
                stringResource(R.string.setting_battery), batteryOk,
                stringResource(R.string.battery_free), stringResource(R.string.battery_limited)
            ) {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            val appDetails = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            }
            StatusRow(
                stringResource(R.string.setting_mic), granted(ctx, Manifest.permission.RECORD_AUDIO),
                stringResource(R.string.perm_ok), stringResource(R.string.perm_no), appDetails
            )
            StatusRow(
                stringResource(R.string.setting_camera), granted(ctx, Manifest.permission.CAMERA),
                stringResource(R.string.perm_ok), stringResource(R.string.perm_no), appDetails
            )

            SectionTitle(stringResource(R.string.section_updates))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                UpdateSection(onUpdate)
            }

            SectionTitle(stringResource(R.string.section_diag))
            ListItem(
                headlineContent = { Text(stringResource(R.string.btn_log)) },
                supportingContent = { Text(stringResource(R.string.diag_hint)) },
                trailingContent = {
                    OutlinedButton(onClick = { showLog = true }) { Text(stringResource(R.string.btn_log)) }
                }
            )
            HorizontalDivider(Modifier.padding(top = 24.dp))
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.logout_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogout()
                }) { Text(stringResource(R.string.btn_logout)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text(stringResource(R.string.btn_log)) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        MessageLog.text(ctx) + "\n\nPush token:\n" + prefs.fcmToken,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLog = false }) { Text(stringResource(R.string.btn_close)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("push token", prefs.fcmToken))
                }) { Text(stringResource(R.string.btn_copy_token)) }
            }
        )
    }
}

private fun granted(ctx: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun StatusRow(title: String, ok: Boolean, okText: String, badText: String, onAction: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                if (ok) okText else badText,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )
        },
        trailingContent = {
            if (!ok) {
                OutlinedButton(onClick = onAction) { Text(stringResource(R.string.btn_allow)) }
            }
        }
    )
}
