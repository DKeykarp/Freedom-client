package com.example.chat

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.produceState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: UiState, roomId: Long, vm: ChatViewModel) {
    val ctx = LocalContext.current
    val me = state.session?.username.orEmpty()
    val room = state.rooms.firstOrNull { it.id == roomId }
    val msgs = state.messages[roomId].orEmpty()
    val typers = state.typing[roomId].orEmpty() - me

    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var input by rememberSaveable { mutableStateOf(vm.loadDraft(roomId)) }
    var scheduleTarget by remember { mutableStateOf<String?>(null) }   // текст сообщения, для которого выбираем время
    var roomMembers by remember(roomId) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(roomId, room?.isDirect) {
        if (room?.isDirect == false) roomMembers = vm.fetchRoomMembers(roomId)
    }
    LaunchedEffect(roomId) { vm.refreshScheduled(roomId) }
    LaunchedEffect(scheduleTarget) {
        val text = scheduleTarget ?: return@LaunchedEffect
        val now = Calendar.getInstance()
        DatePickerDialog(
            ctx,
            { _, y, mo, d ->
                TimePickerDialog(
                    ctx,
                    { _, h, mi ->
                        val cal = Calendar.getInstance()
                        cal.set(y, mo, d, h, mi, 0)
                        vm.scheduleMessage(roomId, text, cal.timeInMillis / 1000.0) { input = "" }
                        scheduleTarget = null
                    },
                    now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true,
                ).apply { setOnCancelListener { scheduleTarget = null } }.show()
            },
            now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH),
        ).apply { setOnCancelListener { scheduleTarget = null } }.show()
    }
    val mentionQuery = remember(input, room?.isDirect) {
        if (room?.isDirect != false) null
        else Regex("(?:^|\\s)@(\\w{0,32})$").find(input)?.groupValues?.get(1)
    }
    val mentionMatches = remember(mentionQuery, roomMembers) {
        val q = mentionQuery?.lowercase() ?: return@remember emptyList()
        roomMembers.filter { it.lowercase().startsWith(q) }
    }
    var viewer by remember { mutableStateOf<File?>(null) }
    var forwardTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var showPollDialog by remember { mutableStateOf(false) }
    var showNewPackDialog by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showScheduleList by remember { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var pinnedScrollTarget by remember { mutableStateOf<Long?>(null) }
    DisposableEffect(Unit) { onDispose { vm.saveDraft(roomId, input) } }

    val recorder = remember { VoiceRecorder(ctx) }
    val videoRecorder = remember { RoundVideoRecorder(ctx) }
    var recKind by remember { mutableStateOf<String?>(null) }   // null | "audio" | "video" — что сейчас пишем
    var recMode by rememberSaveable { mutableStateOf("audio") } // какой режим показывает кнопка, пока не пишем — тап переключает
    var recStart by remember { mutableStateOf<Long?>(null) }
    var recDrag by remember { mutableStateOf(0f) }              // 0f..1f — насколько близко к отмене свайпом
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    LaunchedEffect(recStart) {
        while (recStart != null) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }

    val withPerms = rememberPermissionRunner { vm.showError("Нужно разрешение для этого действия") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.sendImage(roomId, uri)
    }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.sendFile(roomId, uri)
    }

    ErrorEffect(state.error, snackbar, vm::clearError)

    // прокрутка вниз: при входе в комнату, при своих сообщениях и если читатель уже внизу
    val lastId = msgs.lastOrNull()?.id
    LaunchedEffect(lastId) {
        if (msgs.isNotEmpty()) {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearBottom = lastVisible >= info.totalItemsCount - 2
            if (nearBottom || msgs.last().username == me) listState.animateScrollToItem(msgs.lastIndex)
        }
    }

    // дошли до верха вручную -> подгружаем более старые сообщения
    LaunchedEffect(listState, roomId) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.isScrollInProgress }
            .collect { atTop -> if (atTop) vm.loadOlder(roomId) }
    }

    val subtitle = when {
        typers.isNotEmpty() ->
            if (room?.isDirect == true) "печатает…"
            else typers.joinToString(", ") + if (typers.size == 1) " печатает…" else " печатают…"
        state.connected -> "в сети"
        else -> "подключение…"
    }

    fun startCall(video: Boolean) {
        val perms = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
        withPerms(perms) { vm.startCall(roomId, video) }
    }

    // держим — идёт запись; отпустили — отправляем; смахнули влево от кнопки — отменяем (см. HoldToRecordButton)
    fun startVoice() = withPerms(arrayOf(Manifest.permission.RECORD_AUDIO)) {
        try {
            recorder.start()
            now = System.currentTimeMillis()
            recStart = now
            recKind = "audio"
        } catch (e: Exception) {
            vm.showError("Не удалось начать запись")
        }
    }

    // камеру ещё нужно привязать (асинхронно — см. RoundCameraPreview), поэтому здесь только помечаем
    // намерение записывать видео; сам videoRecorder.start() вызывается уже после готовности превью
    fun startVideo() = withPerms(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
        now = System.currentTimeMillis()
        recStart = now
        recKind = "video"
    }

    suspend fun finishRecording() {
        val kind = recKind ?: return
        recKind = null; recStart = null; recDrag = 0f
        when (kind) {
            "audio" -> {
                val r = recorder.stop()
                if (r != null) vm.sendMedia(roomId, "audio", r.first, "voice.m4a", "audio/mp4", r.second)
                else vm.showError("Слишком короткое сообщение")
            }
            "video" -> {
                val r = videoRecorder.stop()
                if (r != null) vm.sendMedia(roomId, "video", r.first, "video.mp4", "video/mp4", r.second)
                else vm.showError("Слишком короткое сообщение")
            }
        }
    }

    suspend fun cancelRecording() {
        val kind = recKind ?: return
        recKind = null; recStart = null; recDrag = 0f
        when (kind) {
            "audio" -> recorder.cancel()
            "video" -> videoRecorder.cancel()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = vm::closeRoom) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    title = {
                        val shownName = if (room?.isDirect == true) state.aliases[room.name] ?: room.name else room?.name ?: "Чат"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = if (room?.isDirect == true) {
                                Modifier.clickable(onClick = { vm.openContactProfile(room.name) })
                            } else Modifier,
                        ) {
                            Avatar(if (room?.isDirect == true) shownName else null, room?.name ?: "?", size = 34.dp, avatarUrl = room?.avatar)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(shownName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(subtitle, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { startCall(false) }) {
                            Icon(Icons.Filled.Call, contentDescription = "Аудиозвонок")
                        }
                        IconButton(onClick = { startCall(true) }) {
                            Icon(FIcons.Videocam, contentDescription = "Видеозвонок")
                        }
                        Box {
                            IconButton(onClick = { showTopMenu = true }) {
                                Icon(FIcons.More, contentDescription = "Ещё")
                            }
                            DropdownMenu(expanded = showTopMenu, onDismissRequest = { showTopMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Поиск по чату") },
                                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                    onClick = { showTopMenu = false; showSearch = !showSearch; searchQuery = "" },
                                )
                                DropdownMenuItem(
                                    text = { Text("Безопасность") },
                                    leadingIcon = { Icon(FIcons.Lock, contentDescription = null) },
                                    onClick = { showTopMenu = false; vm.showSecurity(roomId) },
                                )
                                if (!state.scheduled[roomId].isNullOrEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Запланированные сообщения") },
                                        leadingIcon = { Icon(FIcons.Clock, contentDescription = null) },
                                        onClick = { showTopMenu = false; showScheduleList = true },
                                    )
                                }
                            }
                        }
                    },
                )
                if (state.uploading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (showSearch) {
                    OutlinedTextField(
                        value = searchQuery, onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        placeholder = { Text("Поиск по загруженной истории") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { showSearch = false; searchQuery = "" }) {
                                Icon(FIcons.Close, contentDescription = "Закрыть поиск")
                            }
                        },
                    )
                } else {
                    val pins = state.pinnedBar[roomId].orEmpty()
                    val lastPin = pins.lastOrNull()
                    if (lastPin != null) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { pinnedScrollTarget = lastPin.id }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(FIcons.Pin, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                previewOf(room?.isDirect ?: true, lastPin.username, lastPin.text, lastPin.kind, lastPin.fileName),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                    if (state.replyTo != null || state.editingId != null) {
                        ComposerBar(
                            editing = state.editingId != null,
                            reply = state.replyTo,
                            onClose = {
                                if (state.editingId != null) { vm.cancelEdit(); input = "" } else vm.cancelReply()
                            },
                        )
                    }
                    if (mentionMatches.isNotEmpty()) {
                        Surface(tonalElevation = 4.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(horizontal = 8.dp).heightIn(max = 220.dp)) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                for (u in mentionMatches.take(8)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable {
                                                val at = input.length - (mentionQuery?.length ?: 0)
                                                input = input.substring(0, at) + u + " "
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                    ) {
                                        Avatar(state.aliases[u] ?: u, u, size = 26.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(state.aliases[u] ?: u)
                                    }
                                }
                            }
                        }
                    }
                    if (state.stickerTrayOpen) StickerTray(state, vm, roomId)
                    if (recKind == "video") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            RoundCameraPreview(videoRecorder, size = 220.dp)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { showAttachMenu = true }) {
                                Icon(Icons.Filled.Add, contentDescription = "Прикрепить")
                            }
                            DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Фото") },
                                    leadingIcon = { Icon(FIcons.Image, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Файл") },
                                    leadingIcon = { Icon(FIcons.File, contentDescription = null) },
                                    onClick = { showAttachMenu = false; docPicker.launch(arrayOf("*/*")) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Опрос") },
                                    leadingIcon = { Icon(FIcons.Poll, contentDescription = null) },
                                    onClick = { showAttachMenu = false; showPollDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Стикер") },
                                    leadingIcon = { Icon(FIcons.Sticker, contentDescription = null) },
                                    onClick = { showAttachMenu = false; vm.toggleStickerTray() },
                                )
                            }
                        }
                        if (recKind != null) {
                            // на время записи поле ввода уступает место таймеру и подсказке — кнопка записи
                            // при этом остаётся на прежнем месте в дереве композиции (палец её не отпускал)
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Text("●", color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text(fmtDuration((now - (recStart ?: now)) / 1000.0))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (recDrag > 0.6f) "Отпустите — отменится" else "← Смахните для отмены",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            OutlinedTextField(
                                value = input,
                                onValueChange = {
                                    input = it
                                    if (it.isNotEmpty()) vm.typing(roomId)
                                },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Сообщение") },
                                shape = RoundedCornerShape(22.dp),
                                maxLines = 4,
                            )
                        }
                        if (input.isBlank() || recKind != null) {
                            HoldToRecordButton(
                                icon = if (recMode == "video") FIcons.Videocam else FIcons.Mic,
                                contentDescription = if (recMode == "video") "Видеосообщение" else "Голосовое сообщение",
                                recording = recKind != null,
                                onTap = { recMode = if (recMode == "video") "audio" else "video" },
                                onStart = { if (recMode == "video") startVideo() else startVoice() },
                                onCancel = ::cancelRecording, onFinish = ::finishRecording,
                                onDragProgress = { recDrag = it },
                            )
                        } else {
                            if (state.editingId == null) {
                                IconButton(onClick = { scheduleTarget = input }) {
                                    Icon(FIcons.Clock, contentDescription = "Запланировать отправку")
                                }
                            }
                            IconButton(onClick = {
                                val text = input
                                val editing = state.editingId
                                if (editing != null) vm.editMessage(roomId, editing, text) { input = "" }
                                else vm.send(roomId, text) { if (input == text) input = "" }
                            }) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                            }
                        }
                    }
            }
        },
    ) { padding ->
        val searchHits = remember(msgs, searchQuery) {
            val q = searchQuery.trim()
            if (q.isEmpty()) emptyList() else msgs.filter { it.kind == "text" && it.text.contains(q, ignoreCase = true) }.reversed()
        }
        LaunchedEffect(pinnedScrollTarget) {
            val target = pinnedScrollTarget ?: return@LaunchedEffect
            val idx = msgs.indexOfFirst { it.id == target }
            if (idx >= 0) listState.animateScrollToItem(idx)
            pinnedScrollTarget = null
        }
        if (showSearch && searchQuery.isNotBlank()) {
            if (searchHits.isEmpty()) {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Ничего не найдено в загруженной истории", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                    items(searchHits, key = { it.id }) { hit ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { showSearch = false; searchQuery = ""; pinnedScrollTarget = hit.id }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            if (room?.isDirect != true) {
                                Text(state.aliases[hit.username] ?: hit.username, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(hit.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        HorizontalDivider()
                    }
                }
            }
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(msgs, key = { it.id }) { m ->
                if (m.kind == "call") {
                    Text(
                        "${m.text} · ${formatTime(m.ts)}",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (m.kind == "locked") {
                    Text(
                        m.text,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MessageBubble(
                        m = m,
                        mine = m.username == me,
                        showName = room?.isDirect != true,
                        shownName = state.aliases[m.username] ?: m.username,
                        aliases = state.aliases,
                        readState = state.readState[roomId] ?: emptyMap(),
                        vm = vm,
                        onImage = { viewer = it },
                        onAudio = { vm.toggleAudio(m) },
                        onVideo = { vm.openVideo(m) },
                        onReply = { vm.startReply(m) },
                        onEdit = { vm.startEdit(m); input = m.text },
                        onForward = { forwardTarget = m },
                        onDeleteMine = { vm.deleteMessage(m, false) },
                        onDeleteAll = { vm.deleteMessage(m, true) },
                    )
                }
            }
        }
    }

    viewer?.let { file ->
        Dialog(onDismissRequest = { viewer = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { viewer = null }) {
                AsyncImage(model = file, contentDescription = "Фото", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }

    state.security?.let { info ->
        AlertDialog(
            onDismissRequest = vm::dismissSecurity,
            confirmButton = { TextButton(onClick = vm::dismissSecurity) { Text("Закрыть") } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(FIcons.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(info.title)
                }
            },
            text = {
                Column {
                    Text(info.text)
                    info.number?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 14.dp))
                    Text(
                        "Самоудаление сообщений — новые сообщения будут стираться у всех автоматически.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    for ((label, seconds) in AUTODELETE_OPTIONS) {
                        val active = (room?.autodeleteSeconds ?: 0) == (seconds ?: 0)
                        Text(
                            label,
                            color = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { vm.setAutodelete(roomId, seconds) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
        )
    }

    state.contactProfile?.let { info ->
        var alias by remember(info.username) { mutableStateOf(info.alias) }
        AlertDialog(
            onDismissRequest = vm::dismissContactProfile,
            confirmButton = { TextButton(onClick = { vm.saveAlias(info.username, alias) }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = vm::dismissContactProfile) { Text("Закрыть") } },
            title = { Text(state.aliases[info.username] ?: info.username) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Avatar(state.aliases[info.username] ?: info.username, info.username, size = 72.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        info.bio?.ifEmpty { "Пользователь ничего не написал о себе" } ?: "…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Псевдоним (виден только вам на этом устройстве)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = alias, onValueChange = { alias = it },
                        placeholder = { Text("Например, Мама") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }

    forwardTarget?.let { msg ->
        AlertDialog(
            onDismissRequest = { forwardTarget = null },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { forwardTarget = null }) { Text("Отмена") } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(FIcons.Forward, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Переслать")
                }
            },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    for (r in state.rooms) {
                        Text(
                            if (r.isDirect) state.aliases[r.name] ?: r.name else r.name,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { forwardTarget = null; vm.forwardMessage(msg, r.id) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
        )
    }

    if (showPollDialog) {
        var question by remember { mutableStateOf("") }
        val options = remember { mutableStateListOf("", "") }
        var err by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPollDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val cleanOptions = options.map { it.trim() }.filter { it.isNotEmpty() }
                    when {
                        question.isBlank() -> err = "Введите вопрос"
                        cleanOptions.size < 2 -> err = "Нужно хотя бы два варианта"
                        else -> { vm.createPoll(roomId, question.trim(), cleanOptions); showPollDialog = false }
                    }
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { showPollDialog = false }) { Text("Отмена") } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(FIcons.Poll, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Новый опрос")
                }
            },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = question, onValueChange = { question = it },
                        placeholder = { Text("Вопрос") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    options.forEachIndexed { i, opt ->
                        OutlinedTextField(
                            value = opt, onValueChange = { options[i] = it },
                            placeholder = { Text("Вариант") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true,
                        )
                    }
                    if (options.size < 10) {
                        TextButton(onClick = { options.add("") }, modifier = Modifier.padding(top = 6.dp)) {
                            Text("+ Добавить вариант")
                        }
                    }
                    if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            },
        )
    }

    if (state.stickerCatalog != null) {
        StickerCatalogDialog(state, vm, onOpenNewPack = { vm.closeStickerCatalog(); showNewPackDialog = true })
    }
    if (showNewPackDialog) {
        NewStickerPackDialog(
            onDismiss = { showNewPackDialog = false },
            onCreate = { name -> showNewPackDialog = false; vm.createStickerPack(name) },
        )
    }
    if (state.stickerPackDetail != null) {
        StickerPackDetailDialog(state, vm, roomId)
    }

    if (showScheduleList) {
        val list = state.scheduled[roomId].orEmpty()
        AlertDialog(
            onDismissRequest = { showScheduleList = false },
            confirmButton = { TextButton(onClick = { showScheduleList = false }) { Text("Закрыть") } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(FIcons.Clock, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Запланированные сообщения")
                }
            },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (list.isEmpty()) Text("Нет запланированных сообщений")
                    for (s in list) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val fmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
                            Text(fmt.format(java.util.Date((s.sendAt * 1000).toLong())))
                            TextButton(onClick = { vm.cancelScheduled(roomId, s.id) }) {
                                Text("Отменить", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            },
        )
    }

    state.video?.let { file ->
        Dialog(onDismissRequest = vm::closeVideo, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { c ->
                        VideoView(c).apply {
                            setVideoPath(file.path)
                            setMediaController(MediaController(c).also { it.setAnchorView(this) })
                            setOnPreparedListener { start() }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(onClick = vm::closeVideo, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(FIcons.Close, contentDescription = "Закрыть", tint = Color.White)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MessageBubble(
    m: ChatMessage,
    mine: Boolean,
    showName: Boolean,
    shownName: String,
    aliases: Map<String, String>,
    readState: Map<String, Long>,
    vm: ChatViewModel,
    onImage: (File) -> Unit,
    onAudio: () -> Unit,
    onVideo: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onForward: () -> Unit,
    onDeleteMine: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (mine) 18.dp else 4.dp,
                    bottomEnd = if (mine) 4.dp else 18.dp,
                ),
                color = if (mine) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.widthIn(max = 300.dp)
                    .combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (!mine && showName) {
                        Text(
                            shownName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (m.pinned) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                            Icon(FIcons.Pin, contentDescription = null, modifier = Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(3.dp))
                            Text("Закреплено", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    m.forwardedFrom?.let { from ->
                        Text(
                            "Переслано от ${aliases[from] ?: from}",
                            style = MaterialTheme.typography.labelSmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    m.replyTo?.let { ReplyQuote(it, aliases) }
                    when (m.kind) {
                        "image" -> EncryptedImage(m, vm, onImage)
                        "audio" -> { AudioRow(m, onAudio); TranscribeRow(m, vm) }
                        "video" -> { VideoTile(m, onVideo); TranscribeRow(m, vm) }
                        "file" -> FileRow(m, vm)
                        "poll" -> PollCard(m, vm)
                    }
                    if (m.text.isNotEmpty() && m.kind != "file") MessageTextWithSpoilers(m.text)
                    if (m.reactionsDecoded.isNotEmpty()) ReactionsRow(m, vm)
                    Row(modifier = Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (m.editedTs != null) "изм. ${formatTime(m.ts)}" else formatTime(m.ts),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (mine && m.kind != "call") ReadTicks(m, readState)
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    for (emoji in REACTION_EMOJI) {
                        Text(
                            emoji, fontSize = 20.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { menu = false; vm.toggleReaction(m, emoji) }
                                .padding(6.dp),
                        )
                    }
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Ответить") }, leadingIcon = { Icon(FIcons.Reply, contentDescription = null) },
                    onClick = { menu = false; onReply() },
                )
                if (m.kind != "locked") {
                    DropdownMenuItem(
                        text = { Text("Переслать") }, leadingIcon = { Icon(FIcons.Forward, contentDescription = null) },
                        onClick = { menu = false; onForward() },
                    )
                }
                if (mine && m.kind == "text") {
                    DropdownMenuItem(
                        text = { Text("Редактировать") }, leadingIcon = { Icon(FIcons.Edit, contentDescription = null) },
                        onClick = { menu = false; onEdit() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (m.pinned) "Открепить" else "Закрепить") },
                    leadingIcon = { Icon(FIcons.Pin, contentDescription = null) },
                    onClick = { menu = false; if (m.pinned) vm.unpinMessage(m) else vm.pinMessage(m) },
                )
                DropdownMenuItem(
                    text = { Text("Удалить у себя") }, leadingIcon = { Icon(FIcons.Trash, contentDescription = null) },
                    onClick = { menu = false; onDeleteMine() },
                )
                if (mine) {
                    DropdownMenuItem(
                        text = { Text("Удалить у всех") }, leadingIcon = { Icon(FIcons.Trash, contentDescription = null) },
                        onClick = { menu = false; onDeleteAll() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReplyQuote(rt: ReplyRef, aliases: Map<String, String>) {
    Column(
        Modifier.padding(bottom = 4.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
    ) {
        Text(aliases[rt.username] ?: rt.username, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(rt.text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Карточка опроса: вопрос + варианты-полоски (% и число голосов); своя отметка — рамка вокруг варианта,
    повторный тап по своему же варианту снимает голос. Одиночный выбор — без множественных ответов и «викторин». */
@Composable
private fun PollCard(m: ChatMessage, vm: ChatViewModel) {
    val me = vm.currentUsername()
    val votes = m.votesDecoded
    val total = votes.size
    val mine = votes.firstOrNull { it.username == me }
    Column(Modifier.padding(vertical = 4.dp).widthIn(min = 200.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(m.question ?: "", fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
        (m.options ?: emptyList()).forEachIndexed { i, label ->
            val count = votes.count { it.option == i }
            val pct = if (total > 0) count.toFloat() / total else 0f
            val isMine = mine?.option == i
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(if (isMine) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { vm.toggleVote(m, i) },
            ) {
                if (total > 0) {
                    Box(
                        Modifier.fillMaxWidth(pct).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                    )
                }
                Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, fontSize = 13.5.sp)
                        if (total > 0) {
                            Text(
                                "${Math.round(pct * 100)}%", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (total > 0) {
                        Text(
                            "$count ${voteWord(count)}", fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            if (total > 0) "$total ${voteWord(total)}" else "Нет голосов",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
private fun voteWord(n: Int): String {
    val mod10 = n % 10; val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> "голос"
        mod10 in 2..4 && mod100 !in 12..14 -> "голоса"
        else -> "голосов"
    }
}
private fun stickerWord(n: Int): String {
    val mod10 = n % 10; val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> "стикер"
        mod10 in 2..4 && mod100 !in 12..14 -> "стикера"
        else -> "стикеров"
    }
}

/** Кнопка «тап — переключить режим (голос/видео), держать — идёт запись в текущем режиме, отпустить —
    отправить, смахнуть влево — отменить». Различить тап и удержание нужно, даже когда палец не двигается
    вовсе (обычные события пальца приходят только при движении, не по таймеру) — поэтому первая фаза ждёт
    либо порога по времени, либо отпускания, что наступит раньше; запись стартует только после порога, так
    что короткий тап никогда не создаёт даже самую короткую запись. Не использует IconButton намеренно:
    его собственный clickable конкурировал бы за те же события с нашим ручным отслеживанием. */
@Composable
private fun HoldToRecordButton(
    icon: ImageVector,
    contentDescription: String,
    recording: Boolean,
    onTap: () -> Unit,
    onStart: () -> Unit,
    onCancel: suspend () -> Unit,
    onFinish: suspend () -> Unit,
    onDragProgress: (Float) -> Unit = {},
) {
    val cancelPx = with(LocalDensity.current) { 96.dp.toPx() }
    val holdThresholdMs = 200L
    Box(
        modifier = Modifier
            .size(48.dp)
            .pointerInput(Unit) {
                while (true) {
                    awaitPointerEventScope {
                        awaitFirstDown(requireUnconsumed = false)
                        val downTime = System.currentTimeMillis()
                        var releasedEarly = false
                        // Фаза 1: ждём порог по времени ИЛИ отпускание — что раньше. До порога запись не
                        // стартует вовсе, поэтому обычный тап никогда не превращается в короткую запись.
                        while (true) {
                            val elapsed = System.currentTimeMillis() - downTime
                            if (elapsed >= holdThresholdMs) break
                            val event = withTimeoutOrNull(holdThresholdMs - elapsed) { awaitPointerEvent() }
                            if (event == null) break   // порог истёк, новых событий не пришло — считаем удержанием
                            val change = event.changes.firstOrNull()
                            if (change == null || !change.pressed) { releasedEarly = true; break }
                            change.consume()
                        }
                        if (releasedEarly) {
                            onTap()
                        } else {
                            // Фаза 2: порог пройден — запись действительно идёт, дальше отслеживаем смахивание
                            var dragX = 0f
                            onStart()
                            var event: PointerEvent
                            do {
                                event = awaitPointerEvent()
                                val change = event.changes.firstOrNull()
                                if (change != null && change.pressed) {
                                    dragX += change.positionChange().x
                                    onDragProgress((-dragX / cancelPx).coerceIn(0f, 1f))
                                    change.consume()
                                }
                            } while (event.changes.any { it.pressed })
                            onDragProgress(0f)
                            if (dragX < -cancelPx) onCancel() else onFinish()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = contentDescription,
            tint = if (recording) MaterialTheme.colorScheme.error else LocalContentColor.current,
        )
    }
}

/** Круглое превью фронтальной камеры, пока идёт запись видеосообщения-кружка — обычный PreviewView (View,
    не Compose) через AndroidView, камера привязывается и отвязывается вместе с появлением/исчезновением. */
@Composable
private fun RoundCameraPreview(recorder: RoundVideoRecorder, size: Dp) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    Box(Modifier.size(size).clip(CircleShape).background(Color.Black)) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { ctx ->
                PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                    .also { previewView = it }
            },
        )
    }
    LaunchedEffect(previewView) {
        val pv = previewView ?: return@LaunchedEffect
        recorder.bind(lifecycleOwner, pv)
        recorder.start()
    }
    DisposableEffect(Unit) { onDispose { recorder.unbind() } }
}

/** Одна картинка стикера — скачивается один раз и кэшируется (см. ChatViewModel.stickerThumbFile), каталог
    не шифруется, в отличие от обычных вложений. */
@Composable
private fun StickerThumb(sticker: Sticker, vm: ChatViewModel, size: Dp, onClick: (() -> Unit)?) {
    val file by produceState<File?>(null, sticker.id) { value = vm.stickerThumbFile(sticker) }
    val f = file
    val shape = RoundedCornerShape(8.dp)
    val mod = Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    if (f != null) {
        AsyncImage(model = f, contentDescription = null, contentScale = ContentScale.Fit, modifier = mod)
    } else {
        Box(mod)
    }
}

/** Лоток установленных паков над composer'ом — тап по стикеру шлёт его как обычное фото. */
@Composable
private fun StickerTray(state: UiState, vm: ChatViewModel, roomId: Long) {
    Surface(tonalElevation = 4.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(horizontal = 8.dp).heightIn(max = 260.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.closeStickerTray(); vm.openStickerCatalog() }) {
                    Icon(FIcons.Sticker, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Каталог", fontSize = 13.sp)
                }
            }
            if (state.installedStickerPacks.isEmpty()) {
                Text(
                    "Нет установленных паков — загляните в каталог", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                for (pack in state.installedStickerPacks) {
                    FlowRowStickers(pack.stickers) { s -> StickerThumb(s, vm, 64.dp) { vm.sendSticker(roomId, s) } }
                }
            }
        }
    }
}

/** Простая обёртка-сетка без зависимости от экспериментального FlowRow — фиксированное число столбцов. */
@Composable
private fun FlowRowStickers(stickers: List<Sticker>, itemContent: @Composable (Sticker) -> Unit) {
    val columns = 5
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        stickers.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { itemContent(it) }
            }
        }
    }
}

@Composable
private fun StickerCatalogDialog(state: UiState, vm: ChatViewModel, onOpenNewPack: () -> Unit) {
    AlertDialog(
        onDismissRequest = { vm.closeStickerCatalog() },
        confirmButton = { TextButton(onClick = onOpenNewPack) { Text("+ Создать пак") } },
        dismissButton = { TextButton(onClick = { vm.closeStickerCatalog() }) { Text("Закрыть") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(FIcons.Sticker, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Каталог стикеров")
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = state.stickerCatalogQuery, onValueChange = { vm.searchStickerPacks(it) },
                    placeholder = { Text("Поиск пака по названию") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val packs = state.stickerCatalog
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (packs != null && packs.isEmpty()) {
                        Text(
                            if (state.stickerCatalogQuery.isBlank()) "Пока нет ни одного пака — станьте первым" else "Ничего не нашлось",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    packs?.forEach { p ->
                        Column(
                            Modifier.fillMaxWidth().clickable { vm.openStickerPackDetail(p.id) }.padding(vertical = 10.dp),
                        ) {
                            Text(p.name, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                            Text(
                                "${p.creatorUsername} · ${p.stickerCount} ${stickerWord(p.stickerCount)}",
                                fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun NewStickerPackDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onCreate(name) }) { Text("Создать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text("Новый пак стикеров") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, placeholder = { Text("Название пака") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@Composable
private fun StickerPackDetailDialog(state: UiState, vm: ChatViewModel, roomId: Long) {
    val pack = state.stickerPackDetail ?: return
    val mine = pack.creatorUsername == vm.currentUsername()
    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addStickerToPack(pack.id, uri)
    }
    AlertDialog(
        onDismissRequest = { vm.closeStickerPackDetail() },
        confirmButton = {
            TextButton(onClick = { vm.toggleInstallPack(pack) }) { Text(if (pack.installed) "Установлено ✓" else "Установить") }
        },
        dismissButton = { TextButton(onClick = { vm.closeStickerPackDetail() }) { Text("Закрыть") } },
        title = { Text(pack.name) },
        text = {
            Column {
                Text(
                    "${pack.creatorUsername} · ${pack.stickerCount} ${stickerWord(pack.stickerCount)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp,
                )
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    FlowRowStickers(pack.stickers) { s ->
                        Box {
                            StickerThumb(s, vm, 64.dp, if (mine) null else { { vm.sendSticker(roomId, s) } })
                            if (mine) {
                                IconButton(
                                    onClick = { vm.deleteStickerFromPack(pack.id, s.id) },
                                    modifier = Modifier.size(20.dp).align(Alignment.TopEnd),
                                ) { Icon(FIcons.Close, contentDescription = "Удалить стикер", modifier = Modifier.size(14.dp)) }
                            }
                        }
                    }
                }
                if (mine) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { addPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text("+ Добавить стикер")
                    }
                    TextButton(onClick = { vm.deleteStickerPackAction(pack.id) }) {
                        Text("Удалить пак", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    )
}

/** Пилюли «эмодзи × количество»; своя реакция подсвечена, повторный тап тем же эмодзи снимает её. */
@Composable
private fun ReactionsRow(m: ChatMessage, vm: ChatViewModel) {
    val me = vm.currentUsername()
    val byEmoji = m.reactionsDecoded.groupBy({ it.emoji }, { it.username })
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((emoji, users) in byEmoji) {
            val mine = users.contains(me)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { vm.toggleReaction(m, emoji) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(emoji, fontSize = 13.sp)
                Spacer(Modifier.width(3.dp))
                Text(
                    users.size.toString(), fontSize = 12.sp,
                    color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Одна галочка — отправлено; две — прочитано ВСЕМИ остальными участниками (не первым попавшимся). */
@Composable
private fun ReadTicks(m: ChatMessage, readState: Map<String, Long>) {
    val read = readState.isNotEmpty() && readState.values.all { it >= m.id }
    Icon(
        FIcons.Check, contentDescription = null,
        modifier = Modifier.padding(start = 3.dp).size(13.dp),
        tint = if (read) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (read) {
        Icon(
            FIcons.Check, contentDescription = null,
            // offset, не padding: отрицательный сдвиг нужен, чтобы вторая галочка наезжала на первую
            // (Modifier.padding с отрицательным значением бросает IllegalArgumentException в рантайме)
            modifier = Modifier.offset(x = (-7).dp).size(13.dp),
            tint = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun FileRow(m: ChatMessage, vm: ChatViewModel) {
    var downloading by remember(m.id) { mutableStateOf(false) }
    Row(Modifier.widthIn(min = 200.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(FIcons.File, contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(m.fileName ?: "Файл", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatBytes(m.file?.size), style = MaterialTheme.typography.labelSmall)
        }
        IconButton(onClick = {
            if (!downloading) { downloading = true; vm.saveFileToDownloads(m) { downloading = false } }
        }) {
            if (downloading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(FIcons.Download, contentDescription = "Скачать")
        }
    }
}

private fun formatBytes(n: Long?): String {
    if (n == null || n <= 0) return ""
    if (n < 1024) return "$n Б"
    if (n < 1_048_576) return "${n / 1024} КБ"
    return "%.1f МБ".format(n / 1_048_576.0)
}

/** Лёгкая разметка в духе Telegram: **жирный**, __жирный__, *курсив*, _курсив_, `код`, ~~зачёркнутый~~,
    @упоминание (только подсветка — без открытия профиля по тапу, в отличие от веб-версии). Портировано с
    server/web/app.js (та же регулярка), без вложенности форматов — этого достаточно для сообщений. */
/** Текст сообщения; если в нём есть спойлер (||..||), тап по тексту раскрывает все спойлеры разом. */
@Composable
private fun MessageTextWithSpoilers(text: String) {
    val hasSpoiler = remember(text) { text.contains("||") }
    var revealed by remember(text) { mutableStateOf(false) }
    Text(
        formattedText(text, revealed),
        modifier = if (hasSpoiler) Modifier.clickable { revealed = !revealed } else Modifier,
    )
}

private val FORMAT_RE = Regex(
    "`([^`\n]+)`|\\*\\*([^*\n]+)\\*\\*|__([^_\n]+)__|~~([^~\n]+)~~|\\*([^*\n]+)\\*|_([^_\n]+)_|(?<![\\w@])@(\\w{3,32})(?!\\w)"
)
private val MENTION_COLOR = Color(0xFF3390EC)
private val SPOILER_COLOR = Color(0xFF808080)
private val REACTION_EMOJI = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
private val AUTODELETE_OPTIONS: List<Pair<String, Int?>> = listOf(
    "Выключено" to null,
    "1 час" to 3600,
    "1 день" to 86400,
    "1 неделя" to 604800,
    "1 месяц" to 2592000,
)

fun formattedText(text: String, spoilersRevealed: Boolean = false): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in FORMAT_RE.findAll(text)) {
        if (m.range.first > last) append(text.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22808080))) { append(g[1]) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[2]) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[4]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[5]) }
            g[6].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[6]) }
            g[7].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MENTION_COLOR)) { append("@" + g[7]) }
            else -> if (spoilersRevealed) {
                append(g[8])
            } else {
                withStyle(SpanStyle(background = SPOILER_COLOR, color = SPOILER_COLOR)) { append(g[8]) }
            }
        }
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

@Composable
private fun ComposerBar(editing: Boolean, reply: ChatMessage?, onClose: () -> Unit) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (editing) FIcons.Edit else FIcons.Reply, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (editing) "Редактирование" else reply?.username.orEmpty(),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                )
                if (!editing && reply != null) {
                    Text(reply.text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onClose) { Icon(FIcons.Close, contentDescription = "Отменить") }
        }
    }
}

@Composable
private fun AudioRow(m: ChatMessage, onToggle: () -> Unit) {
    val playing by VoicePlayer.playingId.collectAsState()
    val pos by VoicePlayer.positionMs.collectAsState()
    val active = playing == m.id
    val totalMs = ((m.duration ?: 0.0) * 1000).toLong().coerceAtLeast(1)
    Row(Modifier.widthIn(min = 220.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggle) { Icon(if (active) FIcons.Pause else FIcons.Play, contentDescription = null) }
        Column(Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { if (active) (pos.toFloat() / totalMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                fmtDuration(if (active) pos / 1000.0 else m.duration ?: 0.0),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** «Распознать текст» — для голосового и видеосообщения одинаково: тот же запрос на сервер, см. README §14.
    Результат живёт только в памяти этого экрана (vm.state), не сохраняется и не синхронизируется. */
@Composable
private fun TranscribeRow(m: ChatMessage, vm: ChatViewModel) {
    val state by vm.state.collectAsState()
    val text = state.transcripts[m.id]
    val loading = state.transcribing.contains(m.id)
    when {
        text != null -> Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        loading -> Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text("Распознаём…", style = MaterialTheme.typography.labelSmall)
        }
        else -> TextButton(onClick = { vm.transcribe(m) }, contentPadding = PaddingValues(0.dp)) {
            Text("Распознать текст", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun VideoTile(m: ChatMessage, onClick: () -> Unit) {
    // видеосообщение — «кружок», как и при записи: то же обрезание, что и в RoundCameraPreview
    Box(
        Modifier.size(200.dp).clip(CircleShape).background(Color(0xFF1B1B1F)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(FIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
        Text(
            fmtDuration(m.duration ?: 0.0),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
        )
    }
}

/** Фото лежит на сервере зашифрованным: скачиваем, расшифровываем ключом из сообщения и показываем. */
@Composable
private fun EncryptedImage(m: ChatMessage, vm: ChatViewModel, onClick: (File) -> Unit) {
    val file by produceState<File?>(null, m.id) { value = vm.mediaFile(m) }
    val shape = RoundedCornerShape(12.dp)
    val f = file
    if (f != null) {
        AsyncImage(
            model = f, contentDescription = "Фото", contentScale = ContentScale.Crop,
            modifier = Modifier.size(220.dp).clip(shape).clickable { onClick(f) },
        )
    } else {
        Box(Modifier.size(220.dp).clip(shape).background(Color(0x22000000)), contentAlignment = Alignment.Center) {
            Text("🔒", fontSize = 28.sp)
        }
    }
}
