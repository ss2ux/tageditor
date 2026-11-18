package com.ss2ux.subclean

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

/** 분할 탭이 필요한 글자 수 기준 (DeepL 무료 한도) */
private const val SPLIT_LIMIT_CHARS = 5000

@Composable
fun AppScreen(vm: AppViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            snack.showSnackbar(m)
            vm.clearMessage()
        }
    }
    // 저장·복사 요청이 오면 클립보드에 넣고 비운다
    LaunchedEffect(vm.pendingClipboard) {
        val t = vm.pendingClipboard
        if (t != null) {
            try {
                clipboard.setText(AnnotatedString(t))
            } catch (_: Exception) {
                // 너무 크면 실패할 수 있음 — 메시지로 이미 안내됨
            }
            vm.clearClipboard()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    label = { Text("다듬기") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    label = { Text("규칙 ${vm.rules.size}") }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.ContentCut, contentDescription = null) },
                    label = { Text("분할") }
                )
                NavigationBarItem(
                    selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("설정") }
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> CleanScreen(vm)
                1 -> RulesScreen(vm)
                2 -> SplitScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}

// =====================================================================
// 다듬기 탭: 파일 열기 -> 전/후 미리보기 -> 저장
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanScreen(vm: AppViewModel) {
    // 최근 연 파일 위치를 파일 선택기 시작점으로 사용
    val openLauncher = rememberLauncherForActivityResult(
        OpenDocumentRw { vm.lastOpenUri }
    ) { uri ->
        if (uri != null) vm.openFile(uri)
    }
    // 폴더 열기: 자막 + 같은 이름 줄기 mp3 를 미리 짝지움
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) vm.openFolder(uri)
    }
    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) vm.save(uri, false)
    }
    val clipboard = LocalClipboardManager.current
    val openDeepL = rememberDeepLOpener { vm.message = it }
    var onlyChanged by rememberSaveable { mutableStateOf(true) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    // 3번 연속 탭으로 제외: 같은 줄·짧은 간격 안에서만 카운트
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapIndex by remember { mutableIntStateOf(-1) }
    var lastTapAt by remember { mutableStateOf(0L) }
    val pullState = rememberPullToRefreshState()
    if (pullState.isRefreshing) {
        LaunchedEffect(true) { vm.reloadFile { pullState.endRefresh() } }
    }

    val doc = vm.doc
    val dec = vm.decoded
    val pv = vm.preview
    val player = vm.audioPlayer

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { folderLauncher.launch(null) },
                enabled = !vm.busy
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("폴더 열기")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { openLauncher.launch(arrayOf("*/*")) },
                enabled = !vm.busy
            ) {
                Text("파일")
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        vm.folderName != null && vm.queue.isNotEmpty() ->
                            "${vm.folderName} · ${vm.queueIndex + 1}/${vm.queue.size}"
                        else -> vm.fileName ?: "폴더를 열어 자막·mp3를 함께 불러 주세요"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = vm.fileName
                val audio = vm.currentAudioName
                if (sub != null) {
                    Text(
                        if (audio != null) "$sub + $audio" else sub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        SpeechRow(vm)

        if (doc != null && dec != null) {
            if (pv != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "다듬을 수 있는 ${doc.editableCount}줄 중 ${pv.changedCount}줄이 바뀌어요" +
                            (if (pv.excludedCount > 0) " (제외 ${pv.excludedCount}줄)" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (pv.excludedCount > 0) {
                        TextButton(onClick = { vm.clearExcluded() }) { Text("제외 취소") }
                    }
                }
                // 결과 글자 수: 5천자를 넘으면 분할 탭이 필요하다
                val over = pv.totalChars > SPLIT_LIMIT_CHARS
                Text(
                    "결과 전체 ${"%,d".format(pv.totalChars)}자 (대사만 ${"%,d".format(pv.dialogueChars)}자) · " +
                        if (over) "5천자 초과, 분할 필요" else "5천자 이하, 분할 불필요",
                    fontSize = 13.sp,
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                for (p in pv.problems) {
                    Text(p, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { saveAsLauncher.launch(suggestName(vm.fileName)) },
                        enabled = !vm.busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("다른 이름으로 저장") }
                    Button(
                        onClick = { confirmOverwrite = true },
                        enabled = !vm.busy && pv.changedCount > 0,
                        modifier = Modifier.weight(1f)
                    ) { Text("원본에 덮어쓰기") }
                }
                // 폴더 큐: 이전 / 완료 후 다음 자막
                if (vm.queue.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { vm.openPrevInQueue() },
                            enabled = !vm.busy && vm.hasPrevInQueue,
                            modifier = Modifier.weight(1f)
                        ) { Text("이전 자막") }
                        Button(
                            onClick = {
                                // 변경이 남아 있으면 덮어쓰기 확인 후 다음으로 넘기는 흐름은 사용자에게 맡김
                                // 여기서는 현재 상태를 저장하지 않고 다음으로만 이동
                                if (!vm.openNextInQueue()) {
                                    // 마지막
                                }
                            },
                            enabled = !vm.busy && vm.hasNextInQueue,
                            modifier = Modifier.weight(1f)
                        ) { Text("다음 자막 (${vm.queueIndex + 2}/${vm.queue.size})") }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { vm.copyResult() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("복사", maxLines = 1)
                    }
                    // 5천자 이하만 여기서 바로 보낸다. 넘으면 분할 탭에서 조각별로 보낸다
                    OutlinedButton(
                        onClick = {
                            vm.withResultText { text ->
                                clipboard.setText(AnnotatedString(text))   // DeepL 앱이 앞에 뜨기 전에 먼저 복사(글을 못 넘길 때를 위해). 그다음 글을 DeepL 로 바로 보낸다
                                openDeepL(text)
                            }
                        },
                        enabled = !over,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (over) "DeepL (분할 필요)" else "복사 후 DeepL 열기", maxLines = 1, softWrap = false)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = onlyChanged,
                        onClick = { onlyChanged = !onlyChanged },
                        label = { Text("바뀐 줄만 보기") }
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.showResult() }) { Text("결과 전체 보기") }
                }

                val shown = remember(pv, onlyChanged) {
                    if (onlyChanged) pv.rows.filter { it.changed } else pv.rows
                }
                val listState = rememberLazyListState()
                Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(pullState.nestedScrollConnection)) {
                    Row(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState) {
                            if (shown.isEmpty()) {
                                item {
                                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            if (pv.rows.isEmpty()) "다듬을 대사가 없어요" else "바뀌는 줄이 없어요",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                items(shown, key = { it.index }) { row ->
                                    RowCard(
                                        row = row,
                                        playing = player.playingLineIndex == row.index && player.isPlaying,
                                        onClick = {
                                            val now = System.currentTimeMillis()
                                            if (row.index == lastTapIndex && now - lastTapAt < 900) {
                                                tapCount++
                                            } else {
                                                tapCount = 1
                                            }
                                            lastTapIndex = row.index
                                            lastTapAt = now
                                            // 한 번(및 두 번) 탭: 해당 타임스탬프 재생
                                            if (row.startMs != null) {
                                                vm.playLine(row.index)
                                            }
                                            // 세 번 연속 탭: 제외/포함 토글
                                            if (tapCount >= 3) {
                                                vm.toggleExclude(row.index)
                                                tapCount = 0
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        ListScrollbar(listState)
                    }
                }
                // 미니플레이어
                MiniPlayerBar(player, audioName = vm.currentAudioName)
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("다듬는 중...")
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "규칙 탭에서 치환 목록을 만들고,\n‘폴더 열기’로 자막·mp3를 함께 불러 작업하세요.\n" +
                        "줄 한 번 탭 → 해당 구간 재생 · 세 번 탭 → 제외",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // 결과 전체 보기 (수동). 저장 후에는 자동으로 띄우지 않고 클립보드에만 넣는다.
    vm.resultText?.let { text -> ResultDialog(text, onCopied = { vm.insertCopiedToSplit(it) }) { vm.closeResult() } }

    // 덮어쓰기 확인 — 저장 직후 결과 창이 더 이상 안 뜨므로 가려지지 않음
    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("원본에 덮어쓸까요?") },
            text = {
                Text(
                    "원본 파일이 다듬은 내용으로 바뀌고 되돌릴 수 없어요. 안전하게 하려면 '다른 이름으로 저장'을 쓰세요." +
                        if (vm.hasNextInQueue) "\n\n‘덮어쓰고 다음’을 누르면 저장 후 폴더의 다음 자막으로 넘어가요." else ""
                )
            },
            confirmButton = {
                Row {
                    if (vm.hasNextInQueue) {
                        TextButton(onClick = {
                            confirmOverwrite = false
                            vm.saveThenNext()
                        }) { Text("덮어쓰고 다음") }
                    }
                    TextButton(onClick = {
                        confirmOverwrite = false
                        vm.save(null, true)
                    }) { Text("덮어쓰기") }
                }
            },
            dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("취소") } }
        )
    }

    // 1차 한글이 사라진 경우 복원 확인
    vm.pendingMissingKoreans?.let { missing ->
        MissingKoreanDialog(
            items = missing,
            rules = vm.rules,
            onApply = { lineMap -> vm.applyMissingKoreanFixes(lineMap) },
            onDismiss = { vm.dismissMissingKoreans() }
        )
    }

    // 연쇄 치환(A→B→C)은 Cleaner 가 최대 10패스까지 자동 처리한다.
}

@Composable
private fun ListScrollbar(state: LazyListState) {
    val total = state.layoutInfo.totalItemsCount
    val visible = state.layoutInfo.visibleItemsInfo.size
    if (total < 20 || visible < 1 || visible >= total) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }
    val thumbH = 56.dp

    BoxWithConstraints(Modifier.fillMaxHeight().width(24.dp)) {
        val heightPx = with(density) { maxHeight.toPx() }
        val thumbPx = with(density) { thumbH.toPx() }
        val span = (heightPx - thumbPx).coerceAtLeast(1f)
        val frac = if (dragging) dragFrac
        else state.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)
        val y = (frac.coerceIn(0f, 1f) * span).roundToInt()
        val jumpIndex = (dragFrac.coerceIn(0f, 1f) * (total - 1)).roundToInt()

        Box(
            Modifier.fillMaxSize().pointerInput(total, heightPx) {
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        dragFrac = ((o.y - thumbPx / 2) / span).coerceIn(0f, 1f)
                        scope.launch { state.scrollToItem((dragFrac * (total - 1)).roundToInt()) }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        dragFrac = ((change.position.y - thumbPx / 2) / span).coerceIn(0f, 1f)
                        scope.launch { state.scrollToItem((dragFrac * (total - 1)).roundToInt()) }
                    }
                )
            }
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, y) }
                .width(if (dragging) 10.dp else 5.dp)
                .height(thumbH)
                .background(
                    MaterialTheme.colorScheme.outline.copy(alpha = if (dragging) 0.9f else 0.6f),
                    RoundedCornerShape(50)
                )
        )
        if (dragging) {
            Surface(
                Modifier.align(Alignment.TopEnd).offset { IntOffset((-32).dp.roundToPx(), y + 8) },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface
            ) {
                Text(
                    "${jumpIndex + 1} / $total",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun SpeechRow(vm: AppViewModel) {
    val cur = vm.settings.speech
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("말투", style = MaterialTheme.typography.bodyMedium)
            for (s in Speech.values()) {
                FilterChip(
                    selected = cur == s,
                    onClick = { vm.updateSettings(vm.settings.copy(speech = s)) },
                    label = { Text(s.chipLabel) }
                )
            }
        }
        Text(
            when (cur) {
                Speech.ALL -> "공통 규칙만 적용해요"
                Speech.HONORIFIC1 -> "공통 + 높임1: 화자 연상 · 청자 연하 (존대 표현)"
                Speech.HONORIFIC2 -> "공통 + 높임2: 화자 연하 · 청자 연상 (해요체 존댓말)"
                Speech.CASUAL -> "공통 + 반말 규칙: 반말(해체)로 통일해요"
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (cur != Speech.ALL && vm.rules.none { it.speech == cur }) {
            Text(
                "이 말투의 규칙이 하나도 없어요.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun ResultDialog(text: String, onCopied: (String) -> Unit, onClose: () -> Unit) {
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var copyError by remember { mutableStateOf(false) }
    val limit = 80_000
    val truncated = text.length > limit
    val shown = if (truncated) text.substring(0, limit) else text

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("바뀐 전체 텍스트", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "닫기") }
                }
                Button(
                    onClick = {
                        try {
                            clip.setText(AnnotatedString(text))
                            onCopied(text)   // 복사한 글을 분할 탭에도 넣는다
                            copied = true
                            copyError = false
                        } catch (e: Exception) {
                            copyError = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (copied) "복사했어요 (전체 ${text.length}자)" else "전체 복사")
                }
                if (copyError) {
                    Text("복사하지 못했어요. 파일이 너무 클 수 있어요", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                if (truncated) {
                    Text(
                        "파일이 커서 앞부분 ${limit}자만 보여요. '전체 복사'는 전체를 복사해요.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(shown, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

private fun suggestName(name: String?): String {
    val n = name ?: "subtitle.txt"
    val dot = n.lastIndexOf('.')
    return if (dot > 0) n.substring(0, dot) + "_clean" + n.substring(dot) else n + "_clean"
}

@Composable
private fun EncodingRow(vm: AppViewModel, doc: Doc, dec: Decoded) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${doc.kind.label} · ${Encodings.label(dec.charset)}" + (if (dec.auto) " (자동 감지)" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Box {
            TextButton(onClick = { menu = true }) { Text("인코딩 변경") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("자동 감지") },
                    onClick = { vm.setEncoding(null); menu = false }
                )
                for (o in Encodings.options) {
                    DropdownMenuItem(
                        text = { Text(o.label) },
                        onClick = { vm.setEncoding(o.charset); menu = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun RowCard(row: PreviewRow, playing: Boolean = false, onClick: () -> Unit) {
    // 타임스탬프가 있거나 바뀌는/제외된 줄은 탭 가능 (재생 + 3탭 제외)
    val tappable = row.startMs != null || row.changed || row.excluded
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .let { if (tappable) it.clickable(onClick = onClick) else it },
        colors = if (playing) {
            androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        } else {
            androidx.compose.material3.CardDefaults.cardColors()
        }
    ) {
        Column(Modifier.padding(12.dp)) {
            val timeLabel = row.startMs?.let { " · ${Doc.formatMs(it)}" }.orEmpty()
            val actionHint = when {
                row.excluded -> " · 제외됨 (세 번 탭 → 다시 포함)"
                row.changed && row.startMs != null -> " · 탭→재생 · 세 번→제외"
                row.changed -> " · 세 번 탭 → 제외"
                row.startMs != null -> " · 탭 → 재생"
                else -> ""
            }
            Text(
                "줄 ${row.index + 1}$timeLabel$actionHint" + if (playing) " · 재생 중" else "",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (row.excluded) {
                Text(row.before)
                Text(
                    row.after.ifEmpty { "(빈 줄)" },
                    textDecoration = TextDecoration.LineThrough,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (row.changed) {
                Text(
                    row.before,
                    textDecoration = TextDecoration.LineThrough,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(row.after.ifEmpty { "(빈 줄)" }, color = MaterialTheme.colorScheme.primary)
            } else {
                Text(row.before)
            }
        }
    }
}

@Composable
private fun MiniPlayerBar(player: AudioPlayer, audioName: String?) {
    if (audioName == null && player.durationMs == 0L && player.error == null) return
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    audioName ?: "오디오 없음",
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { player.togglePlayPause() }, enabled = player.durationMs > 0) {
                    Text(if (player.isPlaying) "일시정지" else "재생")
                }
            }
            if (player.error != null) {
                Text(player.error!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            if (player.durationMs > 0) {
                val progress = (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f)
                Slider(
                    value = progress,
                    onValueChange = { player.seekTo((it * player.durationMs).toLong()) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${Doc.formatMs(player.positionMs)} / ${Doc.formatMs(player.durationMs)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// =====================================================================
// 규칙 탭: 치환 목록 관리
// =====================================================================

@Composable
private fun RulesScreen(vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Rule?>(null) }
    var showCheck by remember { mutableStateOf(false) }
    // 0 전체, 1 공통, 2 높임1, 3 높임2, 4 반말, 5 정규식만, 6 다듬기 전용, 7~ 사용자가 만든 탭(vm.ruleTabs 순서)
    var filterIdx by rememberSaveable { mutableIntStateOf(0) }
    var addingTab by remember { mutableStateOf(false) }
    var deletingTab by remember { mutableStateOf<String?>(null) }

    var exportMenuExpanded by remember { mutableStateOf(false) }
    var exportRegexOnly by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importRules(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) vm.exportRules(uri, regexOnly = exportRegexOnly)
    }

    val q = query.trim()
    val sort = vm.ruleSort
    val sortNames = listOf("최신순(등록 내림차순)", "오래된순(등록 오름차순)", "가나다순")
    val listState = rememberLazyListState()
    var prevSize by remember { mutableIntStateOf(vm.rules.size) }
    
    LaunchedEffect(vm.rules.size) {
        if (vm.rules.size > prevSize && sort == 0) listState.scrollToItem(0)
        prevSize = vm.rules.size
    }
    // 사용자 탭이 지워져서 번호가 비면 전체로 본다
    val curTab: String? = if (filterIdx >= 7) vm.ruleTabs.getOrNull(filterIdx - 7) else null
    val fIdx = if (filterIdx >= 7 && curTab == null) 0 else filterIdx
    val shown = remember(vm.rules, q, fIdx, sort, curTab) {
        val byFilter = when (fIdx) {
            1 -> vm.rules.filter { it.speech == Speech.ALL }
            2 -> vm.rules.filter { it.speech == Speech.HONORIFIC1 }
            3 -> vm.rules.filter { it.speech == Speech.HONORIFIC2 }
            4 -> vm.rules.filter { it.speech == Speech.CASUAL }
            5 -> vm.rules.filter { it.regex }
            6 -> vm.rules.filter { it.polish }
            else -> if (curTab != null) vm.rules.filter { it.tab == curTab } else vm.rules
        }
        val found = if (q.isEmpty()) byFilter
        else byFilter.filter { it.find.contains(q, ignoreCase = true) || it.replace.contains(q, ignoreCase = true) }
        
        when (sort) {
            0 -> found.sortedByDescending { it.id }
            2 -> found.sortedBy { it.find.lowercase() }
            else -> found
        }
    }
    val defaultSpeech = when (filterIdx) {
        2 -> Speech.HONORIFIC1
        3 -> Speech.HONORIFIC2
        4 -> Speech.CASUAL
        else -> Speech.ALL
    }
    val defaultPolish = filterIdx == 6

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("규칙 검색") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { adding = true }) { Text("규칙 추가") }
            TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("가져오기") }
            
            Box {
                TextButton(onClick = { exportMenuExpanded = true }) { Text("내보내기") }
                DropdownMenu(
                    expanded = exportMenuExpanded,
                    onDismissRequest = { exportMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("전체 내보내기") },
                        onClick = {
                            exportMenuExpanded = false
                            exportRegexOnly = false
                            exportLauncher.launch("치환규칙.tsv")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("정규식만 내보내기") },
                        onClick = {
                            exportMenuExpanded = false
                            exportRegexOnly = true
                            exportLauncher.launch("정규식규칙.tsv")
                        }
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val names = listOf("전체", "공통", "높임1", "높임2", "반말", "정규식만", "다듬기")
            for ((i, nm) in names.withIndex()) {
                FilterChip(
                    selected = fIdx == i,
                    onClick = { filterIdx = i },
                    label = { Text(nm, maxLines = 1, softWrap = false) }
                )
            }
            for ((j, tn) in vm.ruleTabs.withIndex()) {
                FilterChip(
                    selected = fIdx == 7 + j,
                    onClick = { filterIdx = 7 + j },
                    label = { Text(tn, maxLines = 1, softWrap = false) }
                )
            }
            TextButton(onClick = { addingTab = true }) { Text("+ 탭", maxLines = 1, softWrap = false) }
        }
        if (curTab != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‘$curTab’ 탭 · 규칙 ${shown.size}개",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { deletingTab = curTab }) {
                    Text("이 탭 지우기", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                if (vm.ruleFindings == null || vm.checkStale) vm.runRuleCheck()
                showCheck = true
            }) { Text("규칙 점검") }
            Spacer(Modifier.width(8.dp))
            Text(
                checkSummary(vm.ruleFindings, vm.checkStale, vm.checking),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "규칙 ${vm.rules.size}개 (켜짐 ${vm.rules.count { it.enabled }}개)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            var sortMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { sortMenu = true }) { Text("정렬: ${sortNames[sort].substringBefore("(")}") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    for ((i, nm) in sortNames.withIndex()) {
                        DropdownMenuItem(
                            text = { Text(nm) },
                            onClick = { vm.changeRuleSort(i); sortMenu = false }
                        )
                    }
                }
            }
        }

        if (shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (vm.rules.isEmpty()) "아직 규칙이 없어요" else "검색 결과가 없어요",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState) {
                    items(shown, key = { it.id }) { r ->
                        Row(
                            Modifier.fillMaxWidth().clickable { editing = r }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Switch(checked = r.enabled, onCheckedChange = { vm.updateRule(r.copy(enabled = it)) })
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    r.find + "  →  " + r.replace.ifEmpty { "(삭제)" },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (r.regex || r.speech != Speech.ALL || r.polish || r.tab.isNotEmpty()) {
                                    Text(
                                        listOfNotNull(
                                            if (r.regex) "정규식" else null,
                                            if (r.speech != Speech.ALL) r.speech.ruleLabel else null,
                                            if (r.polish) "다듬기" else null,
                                            if (r.tab.isNotEmpty()) r.tab else null
                                        ).joinToString(" · "),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            IconButton(onClick = { deleting = r }) {
                                Icon(Icons.Default.Delete, contentDescription = "삭제")
                            }
                        }
                    }
                }
                ListScrollbar(listState)
            }
        }
    }

    if (showCheck) {
        RuleCheckDialog(
            findings = vm.ruleFindings,
            checking = vm.checking,
            stale = vm.checkStale,
            rules = vm.rules,
            onRecheck = { vm.runRuleCheck() },
            onEditRule = { r -> showCheck = false; editing = r },
            onClose = { showCheck = false }
        )
    }
    if (adding) {
        RuleDialog(
            initial = null,
            defaultSpeech = defaultSpeech,
            defaultPolish = defaultPolish,
            tabs = vm.ruleTabs,
            defaultTab = curTab ?: "",
            onDismiss = { adding = false },
            onCheck = { f, r, rx, sp, cb -> vm.precheckRule(null, f, r, rx, sp, cb) },
            onSave = { f, r, rx, sp, pl, tb -> vm.addRule(f, r, rx, sp, pl, tb); adding = false }
        )
    }
    editing?.let { cur ->
        RuleDialog(
            initial = cur,
            defaultSpeech = cur.speech,
            defaultPolish = cur.polish,
            tabs = vm.ruleTabs,
            defaultTab = cur.tab,
            onDismiss = { editing = null },
            onCheck = { f, r, rx, sp, cb -> vm.precheckRule(cur.id, f, r, rx, sp, cb) },
            onSave = { f, r, rx, sp, pl, tb -> vm.updateRule(cur.copy(find = f, replace = r, regex = rx, speech = sp, polish = pl, tab = tb)); editing = null }
        )
    }
    if (addingTab) {
        var tabName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingTab = false },
            title = { Text("탭 추가") },
            text = {
                Column {
                    OutlinedTextField(
                        value = tabName, onValueChange = { tabName = it },
                        label = { Text("탭 이름 (예: 이름만)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "탭은 규칙을 모아서 보는 분류예요. 규칙을 추가·수정할 때 탭을 고를 수 있고, 적용 방식은 바뀌지 않아요.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (vm.addRuleTab(tabName)) {
                            filterIdx = 7 + vm.ruleTabs.size - 1
                            addingTab = false
                        }
                    },
                    enabled = tabName.isNotBlank()
                ) { Text("추가") }
            },
            dismissButton = { TextButton(onClick = { addingTab = false }) { Text("취소") } }
        )
    }
    deletingTab?.let { tn ->
        val cnt = vm.rules.count { it.tab == tn }
        AlertDialog(
            onDismissRequest = { deletingTab = null },
            title = { Text("‘$tn’ 탭을 지울까요?") },
            text = {
                Column {
                    Text(
                        if (cnt > 0) "이 탭의 규칙 ${cnt}개는 지워지지 않고 탭 표시만 없어져요. 전체 탭에서 계속 볼 수 있어요."
                        else "비어 있는 탭이에요."
                    )
                    if (cnt > 0) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { vm.removeRuleTab(tn, deleteRules = true); filterIdx = 0; deletingTab = null }) {
                            Text("탭과 규칙 ${cnt}개 모두 지우기", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.removeRuleTab(tn); filterIdx = 0; deletingTab = null }) { Text("탭만 지우기") }
            },
            dismissButton = { TextButton(onClick = { deletingTab = null }) { Text("취소") } }
        )
    }
    deleting?.let { cur ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("규칙을 지울까요?") },
            text = { Text(cur.find + "  →  " + cur.replace.ifEmpty { "(삭제)" }) },
            confirmButton = {
                TextButton(onClick = { vm.deleteRule(cur.id); deleting = null }) { Text("지우기") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } }
        )
    }
}

@Composable
private fun RuleDialog(
    initial: Rule?,
    defaultSpeech: Speech,
    defaultPolish: Boolean = false,
    tabs: List<String> = emptyList(),
    defaultTab: String = "",
    onDismiss: () -> Unit,
    onCheck: (String, String, Boolean, Speech, (List<Finding>) -> Unit) -> Unit,
    onSave: (String, String, Boolean, Speech, Boolean, String) -> Unit
) {
    // 저장 전에 다른 규칙·제외 단어와 부딪히는지 빠르게 확인한다. 문제가 있으면 경고창을 띄우고, 그래도 저장할 수 있다
    var checking by remember { mutableStateOf(false) }
    var warn by remember { mutableStateOf<List<Finding>?>(null) }
    var alive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { alive = false } }
    var find by remember { mutableStateOf(initial?.find ?: "") }
    var replace by remember { mutableStateOf(initial?.replace ?: "") }
    var regex by remember { mutableStateOf(initial?.regex ?: false) }
    var speech by remember { mutableStateOf(initial?.speech ?: defaultSpeech) }
    var polish by remember { mutableStateOf(initial?.polish ?: defaultPolish) }
    var tab by remember { mutableStateOf(initial?.tab ?: defaultTab) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "규칙 추가" else "규칙 수정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = find, onValueChange = { find = it },
                    label = { Text("찾을 말") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = replace, onValueChange = { replace = it },
                    label = { Text("바꿀 말 (비우면 삭제)") }, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = regex, onCheckedChange = { regex = it })
                    Text("정규식으로 처리")
                }
                if (regex) {
                    Text(
                        "바꿀 말에서 $1 처럼 괄호 묶음을 다시 쓸 수 있어요. " +
                            "찾을 말에는 {받침} {무받침} {ㄹ받침} {ㄴ받침} {ㅆ받침} {양성받침} 으로 받침 있는/없는 글자를 가리킬 수 있어요. 예: (?<={받침})이야",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("말투", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in Speech.values()) {
                        FilterChip(selected = speech == s, onClick = { speech = s }, label = { Text(s.ruleLabel) })
                    }
                }
                Text(
                    "공통은 어느 말투에서나 적용돼요. 높임1·높임2·반말은 그 말투를 골랐을 때만 적용돼요.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val tabChoices = (tabs + tab).filter { it.isNotEmpty() }.distinct()
                if (tabChoices.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("탭", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(selected = tab.isEmpty(), onClick = { tab = "" }, label = { Text("없음") })
                        for (tn in tabChoices) {
                            FilterChip(
                                selected = tab == tn,
                                onClick = { tab = tn },
                                label = { Text(tn, maxLines = 1, softWrap = false) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = polish, onCheckedChange = { polish = it })
                    Column {
                        Text("다듬기 전용 (한글 복구 전용)")
                        Text(
                            "실제 자막 다듬기에는 적용되지 않고, 1차 한글이 사라진 줄 찾기 예시 추천에만 씁니다.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    checking = true
                    onCheck(find, replace, regex, speech) { fs ->
                        checking = false
                        if (alive) {
                            if (fs.isEmpty()) onSave(find, replace, regex, speech, polish, tab) else warn = fs
                        }
                    }
                },
                enabled = find.isNotEmpty() && !checking
            ) { Text(if (checking) "확인 중…" else "저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
    warn?.let { fs ->
        FindingsWarningDialog(
            title = "저장하면 충돌할 수 있어요",
            findings = fs,
            forceLabel = "그래도 저장",
            onBack = { warn = null },
            onForce = { warn = null; onSave(find, replace, regex, speech, polish, tab) }
        )
    }
}

// =====================================================================
// 설정 탭: 반복 압축 등
// =====================================================================

@Composable
private fun SettingsScreen(vm: AppViewModel) {
    val o = vm.settings
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("반복 압축", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("같은 글자·단어가 ${o.threshold}번 이상 이어지면 압축해요")
        Slider(
            value = o.threshold.toFloat(),
            onValueChange = { vm.updateSettings(o.copy(threshold = it.roundToInt())) },
            valueRange = 3f..10f,
            steps = 6
        )
        val keepMax = o.threshold - 1
        if (keepMax > 1) {
            Text("압축하고 ${o.keep.coerceIn(1, keepMax)}개만 남겨요")
            Slider(
                value = o.keep.coerceIn(1, keepMax).toFloat(),
                onValueChange = { vm.updateSettings(o.copy(keep = it.roundToInt())) },
                valueRange = 1f..keepMax.toFloat(),
                steps = keepMax - 2
            )
        } else {
            Text("압축하고 1개만 남겨요")
        }
        Spacer(Modifier.height(8.dp))
        SettingSwitch(
            "한 글자·기호 반복", "ㅋㅋㅋㅋㅋㅋ → ㅋㅋ, ?????? → ??, 哈哈哈哈哈 → 哈哈 (숫자는 건드리지 않아요)",
            o.compressChars
        ) { vm.updateSettings(o.copy(compressChars = it)) }
        SettingSwitch(
            "단어 반복", "응 응 응 응 응 → 응 응 (띄어쓰기로 구분되는 단어)",
            o.compressWords
        ) { vm.updateSettings(o.copy(compressWords = it)) }
        SettingSwitch(
            "여러 글자 덩어리 반복 (실험)", "너무너무너무너무너무 → 너무너무. 띄어쓰기 없는 중국어·일본어용. 엉뚱하게 줄어드는지 미리보기로 확인하세요",
            o.compressUnits
        ) { vm.updateSettings(o.copy(compressUnits = it)) }

        Spacer(Modifier.height(16.dp))
        Text("조사", style = MaterialTheme.typography.titleMedium)
        SettingSwitch(
            "치환 뒤 조사 보정", "철수가 → 민철로 바꾸면 민철이, 영희는 → 민수로 바꾸면 민수는. 일반 규칙으로 바뀐 말 바로 뒤의 이/가 은/는 을/를 과/와 (으)로 (이)랑 (이)나 (이)라 아/야 예요/이에요만 고쳐요. 정규식 규칙은 대상이 아니에요",
            o.fixJosa
        ) { vm.updateSettings(o.copy(fixJosa = it)) }

        Spacer(Modifier.height(16.dp))
        Text("글자 정리", style = MaterialTheme.typography.titleMedium)
        SettingSwitch(
            "유니코드 정규화 (NFC)", "분리된 한글 자모·일본어 탁점 등을 하나로 합쳐서 치환 규칙이 빠짐없이 맞게 해요",
            o.normalize
        ) { vm.updateSettings(o.copy(normalize = it)) }

        Spacer(Modifier.height(16.dp))
        Text("인코딩", style = MaterialTheme.typography.titleMedium)
        val openDoc = vm.doc
        val openDec = vm.decoded
        if (openDoc != null && openDec != null) {
            EncodingRow(vm, openDoc, openDec)
        } else {
            Text(
                "다듬기 탭에서 파일을 열면 여기서 인코딩을 확인하고 바꿀 수 있어요. 보통은 자동 감지로 충분해요.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("원본 백업", style = MaterialTheme.typography.titleMedium)
        BackupSection(vm)

        Spacer(Modifier.height(16.dp))
        Text("제외 단어", style = MaterialTheme.typography.titleMedium)
        Text(
            "여기 넣은 말은 어떤 규칙(일반·정규식·말투·조사 보정)과 반복 압축에도 바뀌지 않아요. 단어 안에 들어 있는 부분도 지켜요. 여러 개는 쉼표(,)로 구분해서 한 번에 넣을 수 있어요.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        var protectInput by remember { mutableStateOf("") }
        var protectWarn by remember { mutableStateOf<Pair<List<String>, List<Finding>>?>(null) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = protectInput,
                onValueChange = { protectInput = it },
                label = { Text("제외할 단어") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val add = protectInput.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (add.isNotEmpty()) {
                    // 새 제외 단어가 기존 규칙의 찾을 말을 가리면(그 규칙이 안 먹게 되면) 먼저 알려 준다
                    val warns = vm.checkProtectWords(add)
                    if (warns.isEmpty()) {
                        vm.updateSettings(o.copy(protect = (o.protect + add).distinct()))
                        protectInput = ""
                    } else {
                        protectWarn = add to warns
                    }
                }
            }) { Text("추가") }
        }
        protectWarn?.let { (words, warns) ->
            FindingsWarningDialog(
                title = "제외 단어 때문에 규칙이 안 먹어요",
                findings = warns,
                forceLabel = "그래도 추가",
                onBack = { protectWarn = null },
                onForce = {
                    vm.updateSettings(o.copy(protect = (o.protect + words).distinct()))
                    protectInput = ""
                    protectWarn = null
                }
            )
        }
        if (o.protect.isEmpty()) {
            Text(
                "제외 단어가 없어요",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        for (w in o.protect) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(w, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.updateSettings(o.copy(protect = o.protect - w)) }) {
                    Icon(Icons.Default.Close, contentDescription = "지우기")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("다듬기 모드", style = MaterialTheme.typography.titleMedium)
        SettingSwitch(
            "다듬기 모드",
            "켜면 1차 다듬기 후 DeepL 등에서 돌아온 뒤, 사라진 한글 줄을 확인하고 고칠 수 있어요. 끄면(기본) 줄 변경 확인 없이 기존처럼 바로 다듬기만 해요.",
            o.polishMode
        ) { vm.updateSettings(o.copy(polishMode = it)) }
        if (o.polishMode) {
            Spacer(Modifier.height(8.dp))
            Text("다듬기 제외 단어", style = MaterialTheme.typography.titleSmall)
            Text(
                "줄 변경 찾기에서 이 단어는 검색·표시하지 않아요. 실제 자막 다듬기에는 영향 없어요. 여러 개는 쉼표(,)로 구분해서 한 번에 넣을 수 있어요.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            var polishExcludeInput by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = polishExcludeInput,
                    onValueChange = { polishExcludeInput = it },
                    label = { Text("제외할 단어") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val add = polishExcludeInput.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                    if (add.isNotEmpty()) {
                        vm.updateSettings(o.copy(polishExclude = (o.polishExclude + add).distinct()))
                        polishExcludeInput = ""
                    }
                }) { Text("추가") }
            }
            if (o.polishExclude.isEmpty()) {
                Text(
                    "다듬기 제외 단어가 없어요",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            for (w in o.polishExclude) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(w, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.updateSettings(o.copy(polishExclude = o.polishExclude - w)) }) {
                        Icon(Icons.Default.Close, contentDescription = "지우기")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "이 앱은 인터넷 권한이 없어 모든 처리가 기기 안에서만 이루어져요. DeepL 버튼은 DeepL 앱으로 글을 보내는 것뿐이에요.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BackupSection(vm: AppViewModel) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(OriginalBackup.hasPermission()) }
    // 권한 허용 화면에서 돌아오면 다시 확인
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = OriginalBackup.hasPermission() }

    SettingSwitch(
        "덮어쓰기 전에 원본 백업",
        "'원본에 덮어쓰기' 직전에, 그 파일이 있는 폴더 안에 '원본 자막' 폴더를 만들고 원본을 한 번만 저장해요(.srt .vtt .lrc .txt 모두 같아요). 같은 이름의 백업이 이미 있으면 다시 저장하지 않아서 처음 원본이 그대로 남아요. 백업을 못 하면 덮어쓰지 않아요. '다른 이름으로 저장'은 원본이 안 바뀌니 백업하지 않아요.",
        vm.backupOriginal
    ) { vm.updateBackupOriginal(it) }
    if (vm.backupOriginal) {
        if (!OriginalBackup.supported()) {
            Text(
                "안드로이드 11 이상에서만 쓸 수 있어요.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        } else if (granted) {
            Text(
                "'모든 파일 접근' 권한: 허용됨",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text(
                "원본 폴더에 '원본 자막' 폴더를 만들려면 '모든 파일 접근' 권한이 필요해요. 이 앱은 다른 용도로 쓰지 않아요.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
            OutlinedButton(
                onClick = { OriginalBackup.openPermissionSettings(context) },
                modifier = Modifier.padding(top = 4.dp)
            ) { Text("권한 허용하러 가기") }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}






@Composable
private fun MissingKoreanDialog(
    items: List<MissingKorean>,
    rules: List<Rule>,
    onApply: (Map<Int, String>) -> Unit,
    onDismiss: () -> Unit
) {
    val lineGroups = remember(items) {
        items.groupBy { it.lineIndex }.toList().sortedBy { it.first }
    }
    // 줄 목록에서의 위치 (스크롤용)
    val lineIndexToPos = remember(lineGroups) {
        lineGroups.mapIndexed { pos, (li, _) -> li to pos }.toMap()
    }
    val wordToLines = remember(items) {
        items.groupBy { it.word }.mapValues { (_, list) ->
            list.map { it.lineIndex }.distinct().sorted()
        }
    }
    val allMissingWords = remember(items) { items.map { it.word }.distinct() }
    val missingSet = remember(allMissingWords) { allMissingWords.toSet() }

    // 다듬기 전용 규칙만 예시 추천에 사용 (실제 다듬기 엔진에는 쓰이지 않음)
    val recommended = remember(rules, missingSet) {
        rules.filter { r ->
            r.enabled && r.polish && !r.regex &&
                isHangulOnly(r.find) && isHangulOnly(r.replace) &&
                r.replace in missingSet
        }.distinctBy { it.find to it.replace }
    }

    var selectedLines by remember(items) { mutableStateOf(emptySet<Int>()) }
    var lineTexts by remember(items) {
        mutableStateOf(items.associate { it.lineIndex to it.currentLine })
    }
    var bulkFind by remember { mutableStateOf("") }
    var bulkReplace by remember { mutableStateOf("") }
    var showLineList by remember { mutableStateOf(false) }
    // 같은 예시(단어)를 연속으로 눌렀을 때 다음 줄로 순환
    var cycleKey by remember { mutableStateOf<String?>(null) }
    var cycleIdx by remember { mutableIntStateOf(0) }

    val mainListState = rememberLazyListState()
    val popupListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun jumpToLines(key: String, lines: List<Int>, alsoCheck: Boolean, find: String? = null, replace: String? = null) {
        if (lines.isEmpty()) return
        val next = if (cycleKey == key) (cycleIdx + 1) % lines.size else 0
        cycleKey = key
        cycleIdx = next
        val targetLine = lines[next]
        if (alsoCheck) selectedLines = selectedLines + targetLine
        if (find != null) bulkFind = find
        if (replace != null) bulkReplace = replace
        val pos = lineIndexToPos[targetLine] ?: return
        scope.launch {
            if (showLineList) popupListState.animateScrollToItem(pos)
            else mainListState.animateScrollToItem(pos)
        }
    }

    @Composable
    fun LineEditorList(state: LazyListState, tall: Boolean) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (tall) Modifier.heightIn(min = 360.dp, max = 520.dp) else Modifier.height(200.dp))
        ) {
            items(lineGroups, key = { it.first }) { (lineIndex, group) ->
                val checked = lineIndex in selectedLines
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                selectedLines = if (it) selectedLines + lineIndex
                                else selectedLines - lineIndex
                            }
                        )
                        Text(
                            "줄 ${lineIndex + 1}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "사라진 1차 단어: " + group.joinToString(", ") { "「${it.word}」" },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 40.dp, bottom = 4.dp)
                    )
                    OutlinedTextField(
                        value = lineTexts[lineIndex] ?: "",
                        onValueChange = { v ->
                            lineTexts = lineTexts.toMutableMap().also { it[lineIndex] = v }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                        minLines = 1,
                        maxLines = 6,
                        enabled = checked
                    )
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("1차 한글이 사라진 줄이 있어요") },
        text = {
            Column {
                Text(
                    "1차 다듬기 때 있던 한글이 해당 줄에서 없어졌습니다.\n단어·예시를 누르면 해당 줄로 이동합니다. 다시 누르면 다음 줄로 넘어갑니다.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "사라진 단어 (${allMissingWords.size})",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 72.dp)
                ) {
                    items(allMissingWords, key = { it }) { word ->
                        val lines = wordToLines[word].orEmpty()
                        val anyChecked = lines.any { it in selectedLines }
                        Text(
                            text = "「$word」",
                            fontSize = 13.sp,
                            color = if (anyChecked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    jumpToLines(word, lines, alsoCheck = true, replace = word)
                                }
                                .padding(vertical = 3.dp, horizontal = 4.dp)
                        )
                    }
                }
                if (recommended.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "규칙 추천 (다듬기 전용 · 한글 복구 전용)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "누르면 찾을/바꿀 내용에 넣고 해당 줄로 이동합니다. 다시 누르면 다음 줄.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 88.dp)
                    ) {
                        items(recommended, key = { it.id }) { r ->
                            val lines = wordToLines[r.replace].orEmpty()
                            val anyChecked = lines.any { it in selectedLines }
                            Text(
                                text = "「${r.find}」 → 「${r.replace}」",
                                fontSize = 13.sp,
                                color = if (anyChecked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        jumpToLines(
                                            "rule:${r.id}",
                                            lines,
                                            alsoCheck = true,
                                            find = r.find,
                                            replace = r.replace
                                        )
                                    }
                                    .padding(vertical = 4.dp, horizontal = 4.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("체크한 줄 일괄 치환", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = bulkFind,
                    onValueChange = { bulkFind = it },
                    label = { Text("찾을 내용") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = bulkReplace,
                    onValueChange = { bulkReplace = it },
                    label = { Text("바꿀 내용") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            if (bulkFind.isEmpty() || selectedLines.isEmpty()) return@TextButton
                            lineTexts = lineTexts.toMutableMap().also { m ->
                                for (li in selectedLines) {
                                    val cur = m[li] ?: continue
                                    m[li] = cur.replace(bulkFind, bulkReplace)
                                }
                            }
                        },
                        enabled = bulkFind.isNotEmpty() && selectedLines.isNotEmpty()
                    ) { Text("체크한 줄에 치환") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showLineList = true }) {
                        Text("목록 보기")
                    }
                }
                Spacer(Modifier.height(4.dp))
                LineEditorList(state = mainListState, tall = false)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val map = selectedLines.associateWith { lineTexts[it] ?: "" }
                    onApply(map)
                },
                enabled = selectedLines.isNotEmpty()
            ) { Text("선택한 ${selectedLines.size}개 줄 적용") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("전부 무시") }
        }
    )

    // 줄 목록 전체 팝업: 아래에서 한 줄만 보이던 영역을 넓게 열어 수정
    if (showLineList) {
        AlertDialog(
            onDismissRequest = { showLineList = false },
            title = {
                Text("변경 줄 목록 (${lineGroups.size})")
            },
            text = {
                Column {
                    Text(
                        "체크한 줄만 수정·적용됩니다. 단어·예시로 이동한 위치도 여기에 반영됩니다.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LineEditorList(state = popupListState, tall = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { showLineList = false }) { Text("닫기") }
            }
        )
    }
}

/** 찾을 말·바꿀 말이 한글만으로 이뤄졌는지 (중국어 한자 제외). 공백·문장부호는 허용하지 않음 */
private fun isHangulOnly(s: String): Boolean {
    if (s.isBlank()) return false
    // 한글 음절/자모만 허용
    return s.all { it in '\uAC00'..'\uD7A3' || it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F' }
}
