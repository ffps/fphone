package app.fphone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import app.fphone.MessageLog
import app.fphone.R
import app.fphone.BuildConfig
import app.fphone.app

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onUpdate: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.app.prefs
    LaunchedEffect(Unit) { prefs.pushIssue = false }
    var showLog by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf(MessageLog.text(ctx)) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("О приложении") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.cd_back))
                }
            }
        )
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Image(painterResource(R.drawable.app_icon), contentDescription = null, modifier = Modifier.size(56.dp))
                Text("Кукуха", modifier = Modifier.padding(start = 16.dp), style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            }
            SectionTitle("Версия")
            UpdateSection(onUpdate)
            SectionTitle("Журнал")
            ListItem(
                headlineContent = { Text("Журнал событий") },
                supportingContent = { Text("Просмотр, копирование и очистка") },
                trailingContent = { OutlinedButton(onClick = { logText = MessageLog.text(ctx); showLog = true }) { Text("Открыть") } }
            )
            SectionTitle("Проект")
            ListItem(
                headlineContent = { Text("Исходный код проекта на GitHub") },
                supportingContent = { Text(BuildConfig.UPDATE_REPO.ifBlank { "Репозиторий не указан в сборке" }) },
                trailingContent = {
                    if (BuildConfig.UPDATE_REPO.isNotBlank()) OutlinedButton(onClick = {
                        val url = if (BuildConfig.UPDATE_REPO.startsWith("http")) BuildConfig.UPDATE_REPO else "https://github.com/" + BuildConfig.UPDATE_REPO
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }) { Text("Открыть") }
                }
            )
        }
    }
    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("Журнал") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(logText, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = { showLog = false }) { Text("Закрыть") }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Кукуха log", logText))
                    }) { Text("Скопировать всё") }
                    TextButton(onClick = {
                        MessageLog.clear(ctx)
                        logText = ""
                    }) { Text("Очистить") }
                }
            }
        )
    }
}
