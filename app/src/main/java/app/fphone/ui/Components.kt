package app.fphone.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.fphone.App
import app.fphone.R
import app.fphone.Updater
import app.fphone.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The App object. Reading it in a composable also makes that composable redraw whenever the call
 * manager, the updater or the history changes.
 */
@Composable
fun rememberApp(): App {
    val app = LocalContext.current.app
    app.uiTick.intValue // reading the counter is what subscribes this composable to changes
    return app
}

/**
 * Round photo of a user, or a colored circle with the first letter when there is no photo.
 * A green dot in the corner shows that the user is online.
 */
@Composable
fun Avatar(userId: Long, name: String, version: Long, size: Dp, online: Boolean = false, modifier: Modifier = Modifier) {
    val app = LocalContext.current.app
    var bitmap by remember(userId, version) { mutableStateOf(app.avatars.cached(userId, version)) }
    LaunchedEffect(userId, version) {
        if (bitmap == null && version > 0L) {
            bitmap = withContext(Dispatchers.IO) { app.avatars.load(userId, version) }
        }
    }
    Box(modifier.size(size)) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        } else {
            val hue = ((userId * 47L) % 360L).toFloat()
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(Color.hsv(hue, 0.45f, 0.70f)),
                contentAlignment = Alignment.Center
            ) {
                val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                // The letter has a fixed size relative to the circle (it must not outgrow it
                // when the user sets a large system font).
                val fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
                Text(letter, color = Color.White, fontSize = fontSize)
            }
        }
        if (online) {
            Box(
                Modifier
                    .size(size * 0.28f)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(CallGreen)
            )
        }
    }
}

/** Round button with a caption, as in phone apps. */
@Composable
fun CallButton(
    icon: Int,
    label: String,
    container: Color,
    content: Color,
    size: Dp = 64.dp,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(size),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content)
        ) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(size * 0.42f))
        }
        if (label.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

/** Installed version, "Check for updates" and "Update" buttons (login screen and settings). */
@Composable
fun UpdateSection(onUpdate: () -> Unit) {
    val ctx = LocalContext.current
    val u = rememberApp().updater
    val status = when (u.kind) {
        Updater.Kind.IDLE -> ""
        Updater.Kind.CHECKING -> stringResource(R.string.update_checking)
        Updater.Kind.UP_TO_DATE -> stringResource(R.string.update_latest)
        Updater.Kind.AVAILABLE -> stringResource(R.string.update_available, u.latestVersion)
        Updater.Kind.DOWNLOADING -> stringResource(R.string.update_downloading, u.progress)
        Updater.Kind.READY -> stringResource(R.string.update_ready, u.latestVersion)
        Updater.Kind.ERROR -> stringResource(
            when (u.errorCode) {
                "no_repo" -> R.string.update_not_configured
                "no_release" -> R.string.update_no_release
                "download" -> R.string.update_error_download
                else -> R.string.update_error_check
            }
        )
    }
    val busy = u.kind == Updater.Kind.CHECKING || u.kind == Updater.Kind.DOWNLOADING
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.update_version, u.installedVersion), style = MaterialTheme.typography.bodyLarge)
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { u.check() }, enabled = !busy) {
                Text(stringResource(R.string.btn_check_update))
            }
            when (u.kind) {
                Updater.Kind.AVAILABLE -> Button(onClick = onUpdate) { Text(stringResource(R.string.btn_update)) }
                Updater.Kind.READY -> Button(onClick = { u.install(ctx) }) { Text(stringResource(R.string.btn_install)) }
                else -> {}
            }
        }
    }
}
