package com.example.chat

import android.Manifest
import android.content.Context
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private val Red = Color(0xFFD32F2F)
private val Green = Color(0xFF2E7D32)
private val Glass = Color(0x33FFFFFF)

@Composable
fun CallScreen(state: UiState, vm: ChatViewModel) {
    val call = state.call ?: return
    val mgr = vm.callManager
    val remoteVideos by mgr.remoteVideos.collectAsState()
    val local by mgr.localVideo.collectAsState()
    val withPerms = rememberPermissionRunner { vm.showError("Для звонка нужен доступ к микрофону и камере") }

    BackHandler { /* выход из звонка только кнопкой */ }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // аудиозвонок: гасим экран, когда телефон поднесён к уху — как в обычном телефонном приложении;
    // для видеозвонка датчик не трогаем, экран должен оставаться включённым, чтобы видеть собеседника
    val ctx = LocalContext.current
    val proximityLock = remember {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "chat:proximity")
        } else null
    }
    DisposableEffect(call.video, call.phase) {
        val wantOn = !call.video && call.phase == CallPhase.ACTIVE
        if (wantOn) { if (proximityLock?.isHeld == false) proximityLock.acquire() }
        else { if (proximityLock?.isHeld == true) proximityLock.release() }
        onDispose { if (proximityLock?.isHeld == true) proximityLock.release() }
    }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.startedAt) {
        while (call.startedAt != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val status = when {
        call.phase == CallPhase.ACTIVE && call.onHold -> "На удержании"
        call.phase == CallPhase.RINGING -> "Вызов…"
        call.phase == CallPhase.INCOMING -> if (call.video) "Входящий видеозвонок" else "Входящий аудиозвонок"
        call.phase == CallPhase.CONNECTING -> "Соединение…"
        else -> fmtDuration(((now - (call.startedAt ?: now)) / 1000.0))
    }
    val showTiles = call.participants.isNotEmpty() && call.phase != CallPhase.INCOMING

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1016))) {
        if (showTiles) {
            CallTileGrid(call.participants, remoteVideos, mgr.eglBase)
        }

        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (showTiles) Arrangement.Top else Arrangement.Center,
        ) {
            if (!showTiles) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(110.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(call.roomName.firstOrNull()?.uppercase() ?: "?", fontSize = 44.sp)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            Text(call.roomName, color = Color.White, fontSize = 26.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(status, color = Color(0xFFB0BEC5), fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text("🔒 Сквозное шифрование", color = Color(0xFF78909C), fontSize = 12.sp)
        }

        // своё изображение в углу
        if (call.video && local != null && !call.cameraOff && call.phase != CallPhase.INCOMING) {
            VideoRenderer(
                local, mgr.eglBase, mirror = true, overlay = true,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    .size(width = 110.dp, height = 150.dp).clip(RoundedCornerShape(12.dp)),
            )
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (call.phase == CallPhase.INCOMING) {
                RoundButton("✕", Red) { vm.hangUp() }
                RoundButton("📞", Green) {
                    val perms = if (call.video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                    else arrayOf(Manifest.permission.RECORD_AUDIO)
                    withPerms(perms) { vm.acceptCall() }
                }
            } else {
                RoundButton(if (call.muted) "🔇" else "🎤", if (call.muted) Color.White else Glass, dark = call.muted) {
                    vm.toggleMute()
                }
                RoundButton(if (call.speaker) "🔊" else "🔈", if (call.speaker) Color.White else Glass, dark = call.speaker) {
                    vm.toggleSpeaker()
                }
                if (call.video) {
                    RoundButton("📷", if (call.cameraOff) Color.White else Glass, dark = call.cameraOff) {
                        vm.toggleCamera()
                    }
                    RoundButton("🔄", Glass) { vm.switchCamera() }
                }
                RoundButton("✕", Red) { vm.hangUp() }
            }
        }
    }
}

/** Одна плитка на участника: видео, если оно уже идёт, иначе — аватар с именем (аудиозвонок или ещё не
    подключились). Один участник — на весь экран, больше — сетка. Перерисовывается по составу участников;
    их мало (см. MAX_CALL_PARTICIPANTS на сервере), так что это не накладно. */
@Composable
private fun CallTileGrid(participants: Map<Long, String>, remoteVideos: Map<Long, VideoTrack>, egl: EglBase) {
    val ids = participants.keys.toList()
    if (ids.size <= 1) {
        val id = ids.firstOrNull()
        CallTile(participants[id] ?: "", remoteVideos[id], egl, Modifier.fillMaxSize())
        return
    }
    val columns = if (ids.size <= 2) 1 else 2
    LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize()) {
        items(ids, key = { it }) { id ->
            CallTile(participants[id] ?: "", remoteVideos[id], egl, Modifier.fillMaxWidth().aspectRatio(0.8f))
        }
    }
}

@Composable
private fun CallTile(username: String, track: VideoTrack?, egl: EglBase, modifier: Modifier) {
    Box(modifier.background(Color(0xFF131C26)), contentAlignment = Alignment.Center) {
        if (track != null) {
            VideoRenderer(track, egl, mirror = false, modifier = Modifier.fillMaxSize())
        } else {
            Avatar(username.firstOrNull()?.uppercase(), username, size = 64.dp)
        }
        Surface(
            color = Color(0x990B1016), shape = RoundedCornerShape(10.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
        ) {
            Text(username, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
        }
    }
}

@Composable
private fun RoundButton(label: String, color: Color, dark: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        modifier = Modifier.size(60.dp),
    ) {
        Text(label, fontSize = 24.sp, color = if (dark) Color.Black else Color.White)
    }
}

/** Отрисовка WebRTC-видео внутри Compose. */
@Composable
fun VideoRenderer(
    track: VideoTrack?,
    egl: EglBase,
    mirror: Boolean,
    modifier: Modifier,
    overlay: Boolean = false,
) {
    var view by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(egl.eglBaseContext, null)
                setEnableHardwareScaler(true)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setMirror(mirror)
                if (overlay) setZOrderMediaOverlay(true)
                view = this
            }
        },
        modifier = modifier,
    )
    DisposableEffect(view, track) {
        val v = view
        if (v != null && track != null) track.addSink(v)
        onDispose { if (v != null && track != null) runCatching { track.removeSink(v) } }
    }
    DisposableEffect(Unit) { onDispose { view?.release() } }
}
