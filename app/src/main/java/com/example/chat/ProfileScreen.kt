package com.example.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Свой профиль: аватар, открытое «о себе» (видно всем по имени пользователя) и QR-код для добавления
    в контакты — его сканируют на другом устройстве. Само сканирование в Android-клиенте пока не сделано
    (см. README) — добавить контакт по-прежнему можно только вводом имени, как раньше. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(state: UiState, vm: ChatViewModel) {
    val me = state.session?.username ?: return
    val snackbar = remember { SnackbarHostState() }
    ErrorEffect(state.error, snackbar, vm::clearError)

    var bio by remember(state.myProfile) { mutableStateOf(state.myProfile?.bio ?: "") }
    var saving by remember { mutableStateOf(false) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.uploadMyAvatar(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Профиль") },
                navigationIcon = {
                    IconButton(onClick = vm::dismissMyProfile) { Icon(FIcons.Close, contentDescription = "Закрыть") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(me, me, size = 96.dp, avatarUrl = state.myProfile?.avatar)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(if (state.myProfile?.avatar != null) "Сменить фото" else "Добавить фото")
                }
                if (state.myProfile?.avatar != null) {
                    TextButton(onClick = vm::deleteMyAvatar) {
                        Text("Удалить фото", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(me, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = bio,
                onValueChange = { if (it.length <= 200) bio = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("О себе (видно всем, кто найдёт вас по имени)") },
                minLines = 3, maxLines = 5,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { saving = true; vm.saveMyProfile(bio) { saving = false } },
                enabled = state.myProfile != null && !saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saving) "Сохранение…" else "Сохранить") }

            Spacer(Modifier.height(28.dp))
            Text(
                "Покажите этот код, чтобы вас быстро добавили",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            QrCode(text = "frdm:u:$me", modifier = Modifier.size(220.dp))
        }
    }
}

/** Рисует QR-код (Qr.kt) на Canvas — по клетке модуля за раз, без растровых артефактов на любом размере. */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val (matrix, count) = remember(text) { Qr.encode(text) }
    Surface(color = Color.White, shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Canvas(Modifier.padding(16.dp).fillMaxWidth().size(220.dp)) {
            val cell = size.width / count
            for (r in 0 until count) {
                for (c in 0 until count) {
                    if (matrix[r][c]) {
                        drawRect(color = Color.Black, topLeft = Offset(c * cell, r * cell), size = Size(cell, cell))
                    }
                }
            }
        }
    }
}
