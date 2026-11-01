package com.example.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class DialogKind { DM, CREATE_GROUP, CREATE_CHANNEL, DISCOVER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomListScreen(state: UiState, vm: ChatViewModel) {
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<DialogKind?>(null) }
    val snackbar = remember { SnackbarHostState() }
    ErrorEffect(state.error, snackbar, vm::clearError)

    Column(Modifier.fillMaxSize()) {
    state.update?.let { UpdateBanner(it, onDownload = vm::openUpdateDownload, onDismiss = vm::dismissUpdate) }
    Scaffold(
        modifier = Modifier.weight(1f),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = vm::openMyProfile),
                    ) {
                        Image(painterResource(R.drawable.ic_logo), contentDescription = null, Modifier.size(30.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Freedom")
                            Text(
                                if (state.connected) "${state.session?.username} · в сети" else "подключение…",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::checkForUpdateManually) { Icon(FIcons.Download, contentDescription = "Проверить обновления") }
                    IconButton(onClick = vm::logout) { Icon(FIcons.Logout, contentDescription = "Выйти") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Создать")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Личное сообщение") },
                        leadingIcon = { Icon(FIcons.Message, contentDescription = null) },
                        onClick = { menu = false; dialog = DialogKind.DM },
                    )
                    DropdownMenuItem(
                        text = { Text("Новая группа") },
                        leadingIcon = { Icon(FIcons.Users, contentDescription = null) },
                        onClick = { menu = false; dialog = DialogKind.CREATE_GROUP },
                    )
                    DropdownMenuItem(
                        text = { Text("Новый канал") },
                        leadingIcon = { Icon(FIcons.Channel, contentDescription = null) },
                        onClick = { menu = false; dialog = DialogKind.CREATE_CHANNEL },
                    )
                    DropdownMenuItem(
                        text = { Text("Найти комнаты") },
                        leadingIcon = { Icon(FIcons.Compass, contentDescription = null) },
                        onClick = { menu = false; dialog = DialogKind.DISCOVER },
                    )
                }
            }
        },
    ) { padding ->
        if (state.rooms.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Пока нет чатов")
            }
        } else {
            val archivedRooms = state.rooms.filter { state.archivedRooms.contains(it.id) }
            val visibleRooms = (if (state.showArchived) state.rooms else state.rooms.filterNot { state.archivedRooms.contains(it.id) })
                .filterNot { state.hiddenRooms.contains(it.id) }
            var deleteConfirm by remember { mutableStateOf<Room?>(null) }
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(visibleRooms, key = { it.id }) { room ->
                    val shown = if (room.isDirect) state.aliases[room.name] ?: room.name else room.name
                    RoomRow(
                        room = room, shown = shown, draft = vm.loadDraft(room.id).takeIf { it.isNotEmpty() },
                        muted = state.mutedRooms.contains(room.id), archived = state.archivedRooms.contains(room.id),
                        onClick = { vm.openRoom(room.id) },
                        onToggleMute = { vm.setMuted(room.id, !state.mutedRooms.contains(room.id)) },
                        onToggleArchive = { vm.setArchived(room.id, !state.archivedRooms.contains(room.id)) },
                        onDelete = { deleteConfirm = room },
                    )
                    HorizontalDivider()
                }
                if (archivedRooms.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.toggleShowArchived() }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(FIcons.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (state.showArchived) "Скрыть архив" else "Архив (${archivedRooms.size})",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            deleteConfirm?.let { room ->
                val shown = if (room.isDirect) state.aliases[room.name] ?: room.name else room.name
                AlertDialog(
                    onDismissRequest = { deleteConfirm = null },
                    title = { Text(if (room.isDirect) "Удалить чат?" else "Выйти из «$shown»?") },
                    text = {
                        Text(
                            if (room.isDirect) "Чат с $shown пропадёт из списка на этом устройстве. " +
                                "У собеседника переписка останется."
                            else "Вы перестанете быть участником «$shown». Чтобы вернуться, нужно будет " +
                                "вступить заново.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { vm.deleteRoom(room); deleteConfirm = null }) {
                            Text(if (room.isDirect) "Удалить" else "Выйти", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { TextButton(onClick = { deleteConfirm = null }) { Text("Отмена") } },
                )
            }
        }
    }
    }

    when (dialog) {
        DialogKind.DM -> TextInputDialog(
            title = "Личное сообщение", label = "Имя пользователя",
            onConfirm = { dialog = null; vm.openDm(it) },
            onDismiss = { dialog = null },
        )
        DialogKind.CREATE_GROUP -> TextInputDialog(
            title = "Новая группа", label = "Название",
            onConfirm = { dialog = null; vm.createRoom(it, "group") },
            onDismiss = { dialog = null },
        )
        DialogKind.CREATE_CHANNEL -> TextInputDialog(
            title = "Новый канал", label = "Название",
            onConfirm = { dialog = null; vm.createRoom(it, "channel") },
            onDismiss = { dialog = null },
        )
        DialogKind.DISCOVER -> {
            LaunchedEffect(Unit) { vm.loadDiscover() }
            AlertDialog(
                onDismissRequest = { dialog = null; vm.dismissDiscover() },
                title = { Text("Публичные комнаты") },
                text = {
                    val list = state.discover
                    when {
                        list == null -> CircularProgressIndicator()
                        list.isEmpty() -> Text("Нет доступных комнат")
                        else -> LazyColumn {
                            items(list, key = { it.id }) { r ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${r.members} участн.", style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick = { dialog = null; vm.joinRoom(r.id) }) { Text("Войти") }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { dialog = null; vm.dismissDiscover() }) { Text("Закрыть") }
                },
            )
        }
        null -> Unit
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun RoomRow(
    room: Room,
    shown: String,
    draft: String?,
    muted: Boolean,
    archived: Boolean,
    onClick: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true }),
            leadingContent = { Avatar(if (room.isDirect) shown else null, room.name, size = 52.dp, avatarUrl = room.avatar) },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        shown, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (room.unread > 0) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (muted) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            FIcons.MutedBell, contentDescription = "Заглушено",
                            modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            supportingContent = {
                if (draft != null) {
                    Row {
                        Text("Черновик: ", color = MaterialTheme.colorScheme.error, maxLines = 1)
                        Text(draft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text(room.lastPreview ?: "Нет сообщений", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    if (room.lastPreview != null) {
                        Text(formatTime(room.sortTs), style = MaterialTheme.typography.labelSmall)
                    }
                    if (room.unread > 0) {
                        Spacer(Modifier.height(4.dp))
                        Badge { Text(if (room.unread > 99) "99+" else room.unread.toString()) }
                    }
                }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (muted) "Включить уведомления" else "Заглушить") },
                leadingIcon = { Icon(if (muted) FIcons.UnmutedBell else FIcons.MutedBell, contentDescription = null) },
                onClick = { menu = false; onToggleMute() },
            )
            DropdownMenuItem(
                text = { Text(if (archived) "Вернуть из архива" else "Архивировать") },
                leadingIcon = { Icon(FIcons.Archive, contentDescription = null) },
                onClick = { menu = false; onToggleArchive() },
            )
            DropdownMenuItem(
                text = { Text(if (room.isDirect) "Удалить чат" else "Выйти") },
                leadingIcon = { Icon(FIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun TextInputDialog(title: String, label: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
