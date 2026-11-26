package com.ss2ux.tageditor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 앱 설정 (폴더 선택) */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun includeMusic(c: Context) = sp(c).getBoolean("includeMusic", false)
    fun setIncludeMusic(c: Context, v: Boolean) = sp(c).edit().putBoolean("includeMusic", v).apply()

    /**
     * 하위 폴더를 하나의 앨범으로 묶기.
     * ON: Download/앨범명/disc1/ 과 disc2/ 를 하나의 "앨범명" 앨범으로 합쳐서 작업한다.
     * OFF(기본): 하위 폴더마다 별도 앨범으로 나눈다.
     */
    fun groupSubfolders(c: Context) = sp(c).getBoolean("groupSubfolders", false)
    fun setGroupSubfolders(c: Context, v: Boolean) = sp(c).edit().putBoolean("groupSubfolders", v).apply()

    /** RJ 검색 주소 (RJ 번호가 들어갈 자리를 "RJ값" 이라고 적은 주소) */
    fun rjUrl(c: Context): String = sp(c).getString("rjUrl", "") ?: ""
    fun setRjUrl(c: Context, v: String) = sp(c).edit().putString("rjUrl", v).apply()
}

/**
 * NEW 표시를 끄는 기록. 앨범을 수정(저장)하면 그 시각을 적어 두고,
 * 앨범의 최근 추가 시각이 그보다 뒤일 때(= 그 뒤에 새 곡이 들어왔을 때)만 다시 NEW를 보여준다.
 */
object NewSeen {
    val tick = mutableIntStateOf(0)   // 값이 바뀌면 목록이 NEW 표시를 다시 계산
    private fun sp(c: Context) = c.getSharedPreferences("new_seen", Context.MODE_PRIVATE)
    fun mark(c: Context, key: String) {
        sp(c).edit().putLong(key, System.currentTimeMillis() / 1000).apply()
        tick.intValue++
    }
    fun seenAt(c: Context, key: String): Long = sp(c).getLong(key, 0L)
}

// 앱으로 돌아올 때마다 올라가는 숫자 (목록을 자동으로 다시 불러오는 신호)
private val resumeTick = mutableIntStateOf(0)

private val AUDIO_PERMISSION =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

class MainActivity : ComponentActivity() {
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { AppRoot() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 다운로드 앱 등을 갔다가 돌아오면 새 파일을 자동으로 확인
        if (firstResume) firstResume = false else resumeTick.intValue++
    }
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, AUDIO_PERMISSION) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(AUDIO_PERMISSION) }

    if (granted) {
        LibraryScreen()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("음악 파일 목록을 읽으려면 권한이 필요해요.", fontSize = 16.sp)
            Button(onClick = { launcher.launch(AUDIO_PERMISSION) }, modifier = Modifier.padding(top = 16.dp)) {
                Text("권한 허용")
            }
        }
    }
}

@Composable
fun LibraryScreen() {
    val ctx = LocalContext.current
    var includeMusic by remember { mutableStateOf(Prefs.includeMusic(ctx)) }
    var groupSubfolders by remember { mutableStateOf(Prefs.groupSubfolders(ctx)) }
    var rjUrl by remember { mutableStateOf(Prefs.rjUrl(ctx)) }
    var showRules by remember { mutableStateOf(false) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loadMs by remember { mutableStateOf(-1L) }
    var manualScanning by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showFolderDedup by remember { mutableStateOf(false) }
    var showAlbumArtistBrowse by remember { mutableStateOf(false) }
    var dedupProps by remember { mutableStateOf<List<FolderNameDedup.Proposal>>(emptyList()) }
    var dedupChecked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dedupBusy by remember { mutableStateOf(false) }
    var dedupMsg by remember { mutableStateOf<String?>(null) }
    var manualTick by remember { mutableIntStateOf(0) }
    var openKey by remember { mutableStateOf<String?>(null) }   // 상세 화면으로 열린 앨범
    val gridState = rememberLazyGridState()

    // 기본은 Download, 설정에서 Music 추가
    val folders = remember(includeMusic) {
        buildSet { add("Download"); if (includeMusic) add("Music") }
    }

    // 목록을 DB에서 즉시 불러오고, scanFolders 안에서만 새 파일을 찾는다
    suspend fun refresh(scanFolders: Set<String>) {
        val t0 = System.nanoTime()
        albums = withContext(Dispatchers.IO) { queryAlbums(ctx, folders, groupSubfolders) }
        loadMs = (System.nanoTime() - t0) / 1_000_000

        // 실제 파일 상대경로로 known 구성 (하위 폴더 묶기 시에도 스캔이 맞도록)
        val known = HashSet<String>()
        for (a in albums) for (t in a.tracks) {
            known.add(if (t.relPath.isNotEmpty()) t.relPath else a.path + t.displayName)
        }
        val found = withContext(Dispatchers.IO) { scanNewAudio(ctx, scanFolders, known) }
        if (found > 0) albums = withContext(Dispatchers.IO) { queryAlbums(ctx, folders, groupSubfolders) }
    }

    // 자동: 폴더/묶기 설정이 바뀌거나 앱으로 돌아올 때. 새 파일은 Download 에만 생기므로 거기만 훑는다 (표시 없음)
    LaunchedEffect(folders, groupSubfolders, resumeTick.intValue, RefreshBus.tick.intValue) {
        refresh(setOf("Download"))
        // 폴더명을 바꾼 직후라면, 새 폴더의 그 앨범을 다시 연다
        ReopenRequest.key?.let { k ->
            ReopenRequest.key = null
            if (albums.any { it.key == k }) openKey = k
        }
    }
    // 수동: 새로고침 버튼. 선택한 폴더 전체를 훑고, 버튼 자리에 작은 빙글이를 표시
    LaunchedEffect(manualTick) {
        if (manualTick > 0) {
            manualScanning = true
            refresh(folders)
            manualScanning = false
        }
    }

    // 앨범명만 검색 (메모리에서 걸러내므로 즉시 반응)
    val filtered = remember(albums, query) {
        val q = query.trim()
        if (q.isEmpty()) albums else albums.filter { it.name.contains(q, ignoreCase = true) }
    }
    // 검색어가 바뀌면 맨 위로
    LaunchedEffect(query) { gridState.scrollToItem(0) }

    val newLimit = System.currentTimeMillis() / 1000 - 3 * 24 * 3600
    val newSeenTick = NewSeen.tick.intValue   // 수정 후 NEW가 바로 사라지도록 읽어 둠

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("앨범명 검색") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "지우기")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )
            IconButton(onClick = { if (!manualScanning) manualTick++ }) {
                if (manualScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                }
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "설정")
            }
        }

        // 지금 설치된 앱의 버전 (새 빌드가 제대로 설치됐는지 확인용)
        val appVersion = remember {
            try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
            } catch (e: Exception) {
                "?"
            }
        }
        val status = buildString {
            append("앨범 ${filtered.size}개")
            if (query.isNotBlank()) append(" (전체 ${albums.size}개 중)")
            if (loadMs >= 0) append(" · 목록 로딩 ${loadMs}ms")
            append(" · ")
            append(folders.joinToString("+"))
            append(" · v")
            append(appVersion)
        }
        Text(
            status,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 32.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(filtered, key = { it.key }) { album ->
                    AlbumCard(
                        album,
                        isNew = newSeenTick >= 0 &&
                            album.addedAt >= newLimit &&
                            album.addedAt > NewSeen.seenAt(ctx, album.key)
                    ) {
                        openKey = album.key
                    }
                }
            }
            FastScrollbar(gridState, Modifier.align(Alignment.CenterEnd))
        }
    }

    // 앨범 아티스트 추천용: 목록에 있는 아티스트 이름을 모아 둔다
    LaunchedEffect(albums) { ArtistIndex.update(albums) }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("설정") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // --- 폴더 ---
                    Text("폴더", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Text("Download 폴더는 항상 탐색해요.", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Music 폴더도 탐색", modifier = Modifier.weight(1f))
                        Switch(checked = includeMusic, onCheckedChange = {
                            includeMusic = it
                            Prefs.setIncludeMusic(ctx, it)
                        })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("폴더 단위로 적용하기")
                            Text(
                                "하위 폴더(disc1 등)를 상위 앨범 폴더 하나로 묶어서 한 번에 작업해요.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Switch(checked = groupSubfolders, onCheckedChange = {
                            groupSubfolders = it
                            Prefs.setGroupSubfolders(ctx, it)
                        })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    // --- RJ 검색 ---
                    Text("RJ 검색", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "앨범 수정 화면에서 폴더명의 RJ 번호로 검색할 주소예요. " +
                            "RJ 번호가 들어갈 자리에 RJ값 이라고 적어 주세요.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    OutlinedTextField(
                        value = rjUrl,
                        onValueChange = {
                            rjUrl = it
                            Prefs.setRjUrl(ctx, it.trim())
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        label = { Text("검색 주소") },
                        placeholder = { Text("https://사이트주소/product/RJ값.html") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        minLines = 2,
                        maxLines = 4,
                        isError = rjUrl.isNotBlank() && !RjSearch.hasPlaceholder(rjUrl)
                    )
                    if (rjUrl.isNotBlank() && !RjSearch.hasPlaceholder(rjUrl)) {
                        Text(
                            "주소 안에 RJ값 이라는 글자가 없어요. RJ 번호가 들어갈 자리에 적어 주세요.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    // --- 정리 · 일괄 ---
                    Text("정리 · 일괄", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Music 폴더 바로 아래 앨범 폴더 이름에서, 같은 문구가 연속으로 두 번 붙은 경우만 한 번으로 줄여요.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    TextButton(
                        onClick = {
                            showSettings = false
                            dedupMsg = null
                            dedupBusy = true
                            // scan on UI thread via coroutine - use blocking IO through rememberCoroutineScope outside; use simple thread
                            Thread {
                                val list = FolderNameDedup.scanMusic(ctx)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    dedupProps = list
                                    dedupChecked = list.map { it.from }.toSet()
                                    dedupBusy = false
                                    showFolderDedup = true
                                    if (list.isEmpty()) dedupMsg = "고칠 폴더명이 없어요"
                                }
                            }.start()
                        },
                        modifier = Modifier.padding(top = 4.dp)
                    ) { Text("폴더명 중복 정리") }
                    Text(
                        "앨범 아티스트 이름으로 묶어서 보고, 이름을 바꾸면 해당 곡 전부 태그를 고쳐요.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    TextButton(
                        onClick = {
                            showSettings = false
                            showAlbumArtistBrowse = true
                        },
                        modifier = Modifier.padding(top = 4.dp)
                    ) { Text("앨범 아티스트별 보기") }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    // --- 치환 규칙 ---
                    Text("치환 규칙", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "저장할 때 입력한 값에서 틀리게 번역된 말 등을 자동으로 바꿔요. 바뀐 내용은 알림으로 알려 줘요.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    TextButton(
                        onClick = {
                            showSettings = false
                            showRules = true
                        },
                        modifier = Modifier.padding(top = 4.dp)
                    ) { Text("규칙 관리") }
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("닫기") } }
        )
    }

    
    if (showAlbumArtistBrowse) {
        Surface(Modifier.fillMaxSize()) {
            AlbumArtistBrowseScreen(
                folders = folders,
                onClose = { showAlbumArtistBrowse = false },
                onDone = { manualTick++ }
            )
        }
    }

    if (showFolderDedup) {
        AlertDialog(
            onDismissRequest = { if (!dedupBusy) showFolderDedup = false },
            title = { Text("폴더명 중복 정리") },
            text = {
                Column {
                    Text(
                        "연속으로 같은 문구가 두 번 붙은 폴더만 목록에 있어요. 적용할 항목을 고르세요.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { dedupChecked = dedupProps.map { it.from }.toSet() }) { Text("모두 선택") }
                        TextButton(onClick = { dedupChecked = emptySet() }) { Text("모두 해제") }
                    }
                    if (dedupProps.isEmpty()) {
                        Text(dedupMsg ?: "항목 없음", modifier = Modifier.padding(top = 12.dp))
                    } else {
                        // 설정 다이얼로그 안이라 높이 제한
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                            dedupProps.forEach { p ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = p.from in dedupChecked,
                                        onCheckedChange = { on ->
                                            dedupChecked = if (on) dedupChecked + p.from else dedupChecked - p.from
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(p.from, fontSize = 13.sp)
                                        Text("→ ${p.to}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                    dedupMsg?.let {
                        if (dedupProps.isNotEmpty()) Text(it, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !dedupBusy && dedupChecked.isNotEmpty(),
                    onClick = {
                        dedupBusy = true
                        val chosen = dedupProps.filter { it.from in dedupChecked }
                        Thread {
                            val (ok, errs) = FolderNameDedup.apply(ctx, chosen)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                dedupBusy = false
                                dedupMsg = "${ok}개 변경" + (if (errs.isNotEmpty()) " · 실패 ${errs.size}" else "")
                                if (ok > 0) {
                                    showFolderDedup = false
                                    manualTick++
                                }
                            }
                        }.start()
                    }
                ) { Text(if (dedupBusy) "적용 중…" else "적용") }
            },
            dismissButton = {
                TextButton(enabled = !dedupBusy, onClick = { showFolderDedup = false }) { Text("닫기") }
            }
        )
    }

if (showRules) {
        TagRulesDialog(onClose = { showRules = false })
    }

    // 앨범 상세 화면: 목록 위에 덮어서 표시 (닫으면 목록의 스크롤 위치/검색어가 그대로 남음)
    val open = openKey?.let { k -> albums.find { it.key == k } }
    if (open != null) {
        BackHandler { openKey = null }
        AlbumDetailScreen(open, onBack = { openKey = null })
    }
}

/** 오른쪽 가장자리를 잡고 끌어서 전체를 빠르게 이동하는 스크롤바 */
@Composable
fun FastScrollbar(state: LazyGridState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var trackH by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val thumbH = with(density) { 56.dp.toPx() }
    val total = state.layoutInfo.totalItemsCount
    val visible = state.layoutInfo.visibleItemsInfo.size
    val maxFirst = max(1, total - visible)
    val natural = (state.firstVisibleItemIndex.toFloat() / maxFirst).coerceIn(0f, 1f)
    val fraction = if (dragging) dragFraction else natural
    val travel = (trackH - thumbH).coerceAtLeast(1f)
    val active = dragging || state.isScrollInProgress
    val alpha by animateFloatAsState(if (active) 0.95f else 0.35f, label = "scrollbarAlpha")
    val show = total > visible && total > 0

    if (show) {
        Box(
            modifier = modifier
                .fillMaxHeight()
                .width(28.dp)
                .onSizeChanged { trackH = it.height }
                .pointerInput(total, visible, trackH) {
                    fun move(y: Float) {
                        val f = ((y - thumbH / 2f) / travel).coerceIn(0f, 1f)
                        dragFraction = f
                        scope.launch { state.scrollToItem((f * maxFirst).roundToInt()) }
                    }
                    detectVerticalDragGestures(
                        onDragStart = { off -> dragging = true; move(off.y) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ -> change.consume(); move(change.position.y) }
                    )
                }
        ) {
            val y = (fraction * travel).roundToInt()
            // 손잡이
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, y) }
                    .padding(end = 4.dp)
                    .width(8.dp)
                    .size(width = 8.dp, height = 56.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            )
            // 끄는 동안 현재 위치 표시
            if (dragging) {
                Text(
                    "${state.firstVisibleItemIndex + 1} / $total",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, y + 12.dp.roundToPx()) }
                        .layout { measurable, _ ->
                            val p = measurable.measure(Constraints())
                            layout(0, p.height) { p.place(-p.width - 16.dp.roundToPx(), 0) }
                        }
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
fun AlbumCard(album: Album, isNew: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(album.key, RefreshBus.tick.intValue) { mutableStateOf(ThumbCache.peek(album.key)) }
    // 화면에 보이는 앨범만 썸네일을 읽음 (스크롤로 사라지면 자동 취소)
    LaunchedEffect(album.key, RefreshBus.tick.intValue) { if (bmp == null) bmp = ThumbCache.load(ctx, album) }

    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isNew) {
                Text(
                    "NEW",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFE53935))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            album.name,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            album.artist,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
