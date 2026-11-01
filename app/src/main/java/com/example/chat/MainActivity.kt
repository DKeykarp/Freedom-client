package com.example.chat

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openRoomFromIntent(intent)
        setContent { FreedomTheme { App(vm) } }
    }

    // тап по push-уведомлению, когда приложение уже открыто (launchMode = singleTop)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRoomFromIntent(intent)
    }

    // сокет держим открытым, только пока приложение на экране: так сервер знает,
    // кому слать push, а батарея не тратится в фоне
    override fun onStart() {
        super.onStart()
        vm.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.setForeground(false)
    }

    private fun openRoomFromIntent(intent: Intent) {
        val id = intent.getLongExtra("room_id", -1L)
        if (id > 0) {
            intent.removeExtra("room_id")
            vm.openRoom(id)
        }
    }
}

@Composable
fun App(vm: ChatViewModel) {
    val state by vm.state.collectAsState()
    val roomId = state.currentRoomId
    val mandatoryUpdate = state.update?.takeIf { it.severity == UpdateSeverity.MANDATORY }
    when {
        // версия ниже той, что сервер ещё поддерживает — перекрывает всё, даже экран входа
        mandatoryUpdate != null -> UpdateRequiredScreen(mandatoryUpdate, vm::openUpdateDownload)
        state.session == null -> LoginScreen(state, vm::authenticate)
        state.call != null -> CallScreen(state, vm) // звонок перекрывает всё остальное
        state.showingProfile -> {
            BackHandler { vm.dismissMyProfile() }
            ProfileScreen(state, vm)
        }
        roomId == null -> RoomListScreen(state, vm)
        else -> {
            BackHandler { vm.closeRoom() }
            ChatScreen(state, roomId, vm)
        }
    }
    if (state.session != null) AskNotificationPermission()
}

@Composable
private fun AskNotificationPermission() {
    if (Build.VERSION.SDK_INT >= 33) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}
