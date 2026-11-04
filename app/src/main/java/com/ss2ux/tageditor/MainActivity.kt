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
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loadMs by remember { mutableStateOf(-1L) }
    var manualScanning by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
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
        albums = withContext(Dispatchers.IO) { queryAlbums(ctx, folders) }
        loadMs = (System.nanoTime() - t0) / 1_000_000

        val known = HashSet<String>()
        for (a in albums) for (t in a.tracks) known.add(a.path + t.displayName)
        val found = withContext(Dispatchers.IO) { scanNewAudio(ctx, scanFolders, known) }
        if (found > 0) albums = withContext(Dispatchers.IO) { queryAlbums(ctx, folders) }
    }

    // 자동: 폴더 설정이 바뀌거나 앱으로 돌아올 때. 새 파일은 Download 에만 생기므로 거기만 훑는다 (표시 없음)
    LaunchedEffect(folders, resumeTick.intValue) {
        refresh(setOf("Download"))
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

        val status = buildString {
            append("앨범 ${filtered.size}개")
            if (query.isNotBlank()) append(" (전체 ${albums.size}개 중)")
            if (loadMs >= 0) append(" · 목록 로딩 ${loadMs}ms")
            append(" · ")
            append(folders.joinToString("+"))
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
                    AlbumCard(album, isNew = album.addedAt >= newLimit) {
                        openKey = album.key
                    }
                }
            }
            FastScrollbar(gridState, Modifier.align(Alignment.CenterEnd))
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("폴더 설정") },
            text = {
                Column {
                    Text("Download 폴더는 항상 탐색해요.", fontSize = 14.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Music 폴더도 탐색", modifier = Modifier.weight(1f))
                        Switch(checked = includeMusic, onCheckedChange = {
                            includeMusic = it
                            Prefs.setIncludeMusic(ctx, it)
                        })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("닫기") } }
        )
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
    var bmp by remember(album.key) { mutableStateOf(ThumbCache.peek(album.key)) }
    // 화면에 보이는 앨범만 썸네일을 읽음 (스크롤로 사라지면 자동 취소)
    LaunchedEffect(album.key) { if (bmp == null) bmp = ThumbCache.load(ctx, album) }

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
