package com.ss2ux.tageditor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.LinearProgressIndicator
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
    var scanning by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var manualTick by remember { mutableIntStateOf(0) }

    // 기본은 Download, 설정에서 Music 추가
    val folders = remember(includeMusic) {
        buildSet { add("Download"); if (includeMusic) add("Music") }
    }

    // 폴더 설정이 바뀌거나, 앱으로 돌아오거나, 새로고침을 누르면 실행
    LaunchedEffect(folders, resumeTick.intValue, manualTick) {
        // 1) 먼저 DB만 조회해서 즉시 화면에 표시 (여기가 0.1초 로딩)
        val t0 = System.nanoTime()
        albums = withContext(Dispatchers.IO) { queryAlbums(ctx, folders) }
        loadMs = (System.nanoTime() - t0) / 1_000_000

        // 2) 그 다음 백그라운드에서 방금 받은 새 파일이 있는지 확인
        scanning = true
        val known = HashSet<String>()
        for (a in albums) for (t in a.tracks) known.add(a.path + t.displayName)
        val found = withContext(Dispatchers.IO) { scanNewAudio(ctx, folders, known) }
        if (found > 0) albums = withContext(Dispatchers.IO) { queryAlbums(ctx, folders) }
        scanning = false
    }

    // 앨범명만 검색 (메모리에서 걸러내므로 즉시 반응)
    val filtered = remember(albums, query) {
        val q = query.trim()
        if (q.isEmpty()) albums else albums.filter { it.name.contains(q, ignoreCase = true) }
    }
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
            IconButton(onClick = { manualTick++ }) {
                Icon(Icons.Default.Refresh, contentDescription = "새로고침")
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "설정")
            }
        }

        if (scanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
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

        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(filtered, key = { it.key }) { album ->
                AlbumCard(album, isNew = album.addedAt >= newLimit) {
                    // 3단계에서 앨범 상세 화면으로 연결할 자리
                    Toast.makeText(ctx, "${album.name} (${album.tracks.size}곡)", Toast.LENGTH_SHORT).show()
                }
            }
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
