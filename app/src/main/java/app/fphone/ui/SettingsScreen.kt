@file:OptIn(ExperimentalMaterial3Api::class)

package app.fphone.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.fphone.Bg
import app.fphone.ApiException
import app.fphone.app
import app.fphone.R

@Composable
fun SettingsScreen(
    dynamicColor: Boolean,
    onDynamicColor: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val app = ctx.app
    val prefs = app.prefs
    var confirmLogout by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf(prefs.userName) }
    var profileBusy by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf<String?>(null) }
    var ownAvatarVersion by remember { mutableLongStateOf(0L) }
    var photoSettings by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Bg.run({ app.net.myProfile().optLong("avatar", 0L) }) { result ->
            result.onSuccess { ownAvatarVersion = it }
        }
    }


    if (photoSettings) {
        PhotoSettingsScreen(
            currentAvatar = { Avatar(prefs.userId, prefs.userName, ownAvatarVersion, 180.dp) },
            errorText = profileError,
            onBack = { photoSettings = false },
            onUpload = { bytes ->
                profileBusy = true
                Bg.run({ app.net.uploadAvatar(bytes) }) { result ->
                    profileBusy = false
                    result.onSuccess {
                        profileError = null
                        photoSettings = false
                        app.calls.loadContacts()
                        Bg.run({ app.net.myProfile().optLong("avatar", 0L) }) { r -> r.onSuccess { ownAvatarVersion = it } }
                    }
                    result.onFailure { profileError = it.message ?: "Ошибка загрузки фото" }
                }
            },
            onDelete = {
                profileBusy = true
                Bg.run({ app.net.deleteOwnAvatar() }) { result ->
                    profileBusy = false
                    result.onSuccess {
                        profileError = null
                        photoSettings = false
                        app.calls.loadContacts()
                        Bg.run({ app.net.myProfile().optLong("avatar", 0L) }) { r -> r.onSuccess { ownAvatarVersion = it } }
                    }
                    result.onFailure { profileError = it.message ?: "Ошибка удаления фото" }
                }
            },
            busy = profileBusy
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
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
                headlineContent = { Text(prefs.login) },
                supportingContent = { Text("Логин") },
                trailingContent = {
                    OutlinedButton(onClick = { confirmLogout = true }) { Text(stringResource(R.string.btn_logout)) }
                }
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Avatar(prefs.userId, prefs.userName, ownAvatarVersion, 64.dp, modifier = Modifier.clickable { profileError = null; photoSettings = true })
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(prefs.userName, style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = displayName, onValueChange = {
                        displayName = it
                        profileError = null
                    },
                    label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            profileBusy = true
                            Bg.run({ app.net.updateName(displayName.trim()) }) { result ->
                                profileBusy = false
                                result.onSuccess {
                                    prefs.userName = displayName.trim()
                                    app.calls.loadContacts()
                                    app.uiTick.intValue++
                                }
                                result.onFailure { e ->
                                    profileError = if ((e as? ApiException)?.code == "name_taken") "Это имя или логин уже используется другим контактом" else (e.message ?: "Ошибка сохранения имени")
                                }
                            }
                        }, enabled = !profileBusy && displayName.trim().isNotEmpty()) {
                            Icon(painterResource(R.drawable.ic_save), contentDescription = "Сохранить имя")
                        }
                    }
                )
                if (profileBusy) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(22.dp), strokeWidth = 2.dp)
                profileError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

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
            val appDetails: () -> Unit = {
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


private fun decodePreview(ctx: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = maxOf(1, (maxOf(bounds.outWidth, bounds.outHeight) + 999) / 1000)
    return ctx.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

private fun appBgRun(
    ctx: Context,
    uri: Uri,
    rotation: Int,
    scale: Float,
    panX: Float,
    panY: Float,
    done: (Result<ByteArray>) -> Unit
) {
    Bg.run({
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Некорректное изображение" }
        val sample = maxOf(1, (maxOf(bounds.outWidth, bounds.outHeight) + 1599) / 1600)
        val source = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Не удалось открыть фото")
        val rotateMatrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, rotateMatrix, true)
        val side = minOf(rotated.width, rotated.height).toFloat()
        val output = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val outputCanvas = android.graphics.Canvas(output)
        val outputScale = 400f / side * scale
        val outputMatrix = Matrix().apply {
            setValues(floatArrayOf(
                outputScale, 0f, 200f + panX * 400f - rotated.width * outputScale / 2f,
                0f, outputScale, 200f + panY * 400f - rotated.height * outputScale / 2f,
                0f, 0f, 1f
            ))
        }
        outputCanvas.drawBitmap(rotated, outputMatrix, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
        ByteArrayOutputStream().also { output.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }, done)
}

@Composable
private fun PhotoSettingsScreen(
    currentAvatar: @Composable () -> Unit,
    errorText: String?,
    onBack: () -> Unit,
    onUpload: (ByteArray) -> Unit,
    onDelete: () -> Unit,
    busy: Boolean
) {
    val ctx = LocalContext.current
    var uri by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var rotation by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableStateOf(1f) }
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var processing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
        uri = selected; rotation = 0; zoom = 1f; panX = 0f; panY = 0f; error = null
    }
    LaunchedEffect(uri) {
        val selected = uri ?: return@LaunchedEffect
        preview = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { decodePreview(ctx, selected) }
        } catch (e: Exception) {
            error = e.message ?: "Не удалось открыть изображение"
            null
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Фото профиля") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Назад") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxWidth().padding(24.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            val bmp = preview
            if (bmp == null) {
                currentAvatar()
            } else {
                Box(
                    Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .pointerInput(uri) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                val nextZoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                                zoom = nextZoom
                                val minSide = minOf(bmp.width, bmp.height).toFloat()
                                val imageWidthRatio = (if (rotation % 180 == 0) bmp.width else bmp.height) / minSide
                                val imageHeightRatio = (if (rotation % 180 == 0) bmp.height else bmp.width) / minSide
                                val limitX = (nextZoom * imageWidthRatio - 1f) / 2f
                                val limitY = (nextZoom * imageHeightRatio - 1f) / 2f
                                panX = (panX + pan.x / size.width).coerceIn(-limitX, limitX)
                                panY = (panY + pan.y / size.height).coerceIn(-limitY, limitY)
                            }
                        }
                ) {
                    Image(
                        bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                rotationZ = rotation.toFloat()
                                scaleX = zoom
                                scaleY = zoom
                                translationX = panX * size.width
                                translationY = panY * size.height
                            }
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy && !processing) { Text("Выбрать фото") }
            if (bmp != null) {
                OutlinedButton(onClick = {
                    rotation = (rotation + 90) % 360
                    zoom = 1f; panX = 0f; panY = 0f
                }, enabled = !busy && !processing) { Text("Повернуть на 90°") }
                Button(onClick = {
                    val selected = uri ?: return@Button
                    processing = true
                    appBgRun(ctx, selected, rotation, zoom, panX, panY) { result ->
                        processing = false
                        result.onSuccess(onUpload)
                        result.onFailure { error = it.message ?: "Ошибка обработки фото" }
                    }
                }, enabled = !busy && !processing) { Text("Сохранить фото") }
            }
            OutlinedButton(onClick = onDelete, enabled = !busy && !processing) { Text("Удалить фото") }
            if (busy || processing) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(24.dp), strokeWidth = 2.dp)
            errorText?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
