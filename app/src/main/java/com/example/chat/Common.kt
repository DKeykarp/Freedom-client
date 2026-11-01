package com.example.chat

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import java.io.File
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Сегодня — время, раньше — дата. */
fun formatTime(ts: Double): String {
    val date = Date((ts * 1000).toLong())
    val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val pattern = if (day.format(date) == day.format(Date())) "HH:mm" else "dd.MM"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
}

/** 75.4 -> "1:15" */
fun fmtDuration(seconds: Double): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/* ---------- аватары ---------- */
// Тот же набор пар, что и в веб-клиенте (server/web/app.js): цвет аватара зависит от имени, не от того, кто смотрит.
private val AVATAR_GRADIENTS = listOf(
    0xFFff885e to 0xFFff516a, 0xFFffcd6a to 0xFFffa85c, 0xFF82b1ff to 0xFF665fff,
    0xFFa0de7e to 0xFF54cb68, 0xFF53edd6 to 0xFF28c9b7, 0xFF72d5fd to 0xFF2a9ef1, 0xFFe0a2f3 to 0xFFd669ed,
)
private fun avatarGradient(name: String): Pair<Color, Color> {
    val h = kotlin.math.abs(name.fold(0) { acc, c -> acc * 31 + c.code })
    val (a, b) = AVATAR_GRADIENTS[h % AVATAR_GRADIENTS.size]
    return Color(a) to Color(b)
}
private fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val s = (parts.getOrNull(0)?.take(1) ?: "") + (parts.getOrNull(1)?.take(1) ?: "")
    return s.uppercase().ifEmpty { "?" }
}

/** label — что показать (инициалы) или null для «#» (комната); hashName — по какому имени выбирать цвет. */
@Composable
fun Avatar(label: String?, hashName: String, size: Dp = 44.dp, avatarUrl: String? = null) {
    if (avatarUrl != null) {
        val ctx = LocalContext.current
        var file by remember(avatarUrl) { mutableStateOf<File?>(null) }
        LaunchedEffect(avatarUrl) { file = AvatarCache.file(ctx, avatarUrl) }
        val f = file
        if (f != null) {
            AsyncImage(
                model = f, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
            return
        }
    }
    val (c1, c2) = avatarGradient(hashName.ifEmpty { "?" })
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(c1, c2))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label?.let { initials(it) } ?: "#",
            color = Color.White, fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.36f).sp,
        )
    }
}

/** Показывает ошибку в Snackbar и сбрасывает её в состоянии. */
@Composable
fun ErrorEffect(error: String?, host: SnackbarHostState, onShown: () -> Unit) {
    LaunchedEffect(error) {
        if (error != null) {
            host.showSnackbar(error)
            onShown()
        }
    }
}

/**
 * Запуск действия после получения runtime-разрешений:
 * run(arrayOf(RECORD_AUDIO)) { ... } — сразу, если разрешение есть, иначе после запроса.
 */
@Composable
fun rememberPermissionRunner(onDenied: () -> Unit): (Array<String>, () -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val action = pending
        pending = null
        if (result.values.all { it }) action?.invoke() else onDenied()
    }
    return remember(launcher) {
        val run: (Array<String>, () -> Unit) -> Unit = { perms, action ->
            val granted = perms.all {
                ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
            }
            if (granted) action() else { pending = action; launcher.launch(perms) }
        }
        run
    }
}
