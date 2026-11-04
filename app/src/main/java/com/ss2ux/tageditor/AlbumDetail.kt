package com.ss2ux.tageditor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 앨범 상세 화면: 큰 앨범아트 + 앨범 정보 + 트랙 목록, 우측 상단 수정 버튼.
 * 트랙을 선택하지 않으면 "앨범 전체 수정", 트랙을 선택하면 "그 트랙만 수정".
 */
@Composable
fun AlbumDetailScreen(album: Album, onBack: () -> Unit) {
    val ctx = LocalContext.current
    // 선택된 트랙 (null = 앨범 전체)
    var selectedId by remember(album.key) { mutableStateOf<Long?>(null) }
    val selected = album.tracks.find { it.id == selectedId }

    // 목록에서 쓰던 작은 썸네일을 먼저 보여주고, 큰 이미지가 준비되면 교체
    var art by remember(album.key) {
        mutableStateOf(LargeArt.peek(album.key) ?: ThumbCache.peek(album.key))
    }
    LaunchedEffect(album.key) {
        LargeArt.load(ctx, album)?.let { art = it }
    }

    val scope = rememberCoroutineScope()
    // 6단계: 저장 진행 상황 (null = 저장 중 아님), 권한 안내창
    var saveProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showPermission by remember { mutableStateOf(false) }
    // 7단계: 트랙번호 자동 배분 미리보기 창
    var showNumbering by remember(album.key) { mutableStateOf(false) }

    // 5단계: 수정 화면 (editTarget == null 이면 앨범 전체, 아니면 그 트랙만)
    var editing by remember(album.key) { mutableStateOf(false) }
    var editTarget by remember(album.key) { mutableStateOf<Track?>(null) }

    // 4단계: 이 앨범의 mp3 파일에서 ID3 태그를 읽는다 (상세 화면을 열 때만)
    var tags by remember(album.key, album.tracks) { mutableStateOf(AlbumTagLoader.peek(album)) }
    LaunchedEffect(album.key, album.tracks) {
        if (tags == null) tags = AlbumTagLoader.load(ctx, album)
    }

    // 저장 공통 처리: 권한 확인 -> 진행 표시 -> 저장 -> 화면/캐시 새로 읽기
    fun startSave(total: Int, work: ((Int, Int) -> Unit) -> SaveResult) {
        if (saveProgress != null) {
            // 이미 저장 중
        } else if (!TagSaver.hasAllFilesAccess()) {
            showPermission = true
        } else {
            saveProgress = 0 to total
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    work { done, all -> saveProgress = done to all }
                }
                // 옛 값/옛 이미지를 버리고 새로 읽는다
                AlbumTagLoader.invalidate()
                ThumbCache.clear()
                LargeArt.clear()
                saveProgress = null
                if (r.ok > 0) editing = false
                val msg = if (r.failed.isEmpty()) "${r.ok}곡 저장 완료"
                else "${r.ok}곡 저장, ${r.failed.size}곡 실패\n" + r.failed.take(3).joinToString("\n")
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                if (r.ok > 0) {
                    RefreshBus.tick.intValue++   // 목록과 썸네일도 새로 읽게 함
                    tags = withContext(Dispatchers.IO) { AlbumTagLoader.load(ctx, album) }
                    LargeArt.load(ctx, album)?.let { art = it }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 상단 바: 뒤로가기 / 수정 버튼
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
                Box(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (album.tracks.isEmpty()) {
                        Toast.makeText(ctx, "곡이 없어요", Toast.LENGTH_SHORT).show()
                    } else {
                        showNumbering = true
                    }
                }) {
                    Icon(Icons.Default.FormatListNumbered, contentDescription = null)
                    Text("  번호", fontSize = 15.sp)
                }
                TextButton(onClick = {
                    if (tags == null) {
                        Toast.makeText(ctx, "태그를 읽는 중이에요. 잠시 후 다시 눌러 주세요", Toast.LENGTH_SHORT).show()
                    } else {
                        editTarget = selected
                        editing = true
                    }
                }) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Text(
                        if (selected == null) "  앨범 수정" else "  트랙 수정",
                        fontSize = 15.sp
                    )
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // 큰 앨범아트
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        val b = art
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
                    }
                }
                // 앨범 정보
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            album.name,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            album.artist,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            if (selected == null) "${album.tracks.size}곡 · 트랙을 누르지 않으면 앨범 전체가 수정돼요"
                            else "‘${selected.title}’ 트랙만 수정돼요 (한 번 더 누르면 선택 해제)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                // 태그 정보 (트랙을 선택하면 그 트랙의 태그, 아니면 앨범 전체 요약)
                item {
                    val tg = tags
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        if (tg == null) {
                            Text(
                                "태그 읽는 중…",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (selected != null) {
                            val x = tg.perTrack[selected.id]
                            if (x == null) {
                                Text("태그를 읽지 못했어요", fontSize = 13.sp)
                            } else {
                                InfoRow("제목", x.title)
                                InfoRow(
                                    "트랙번호",
                                    if (x.trackNo <= 0) "" else if (x.trackTotal > 0) "${x.trackNo}/${x.trackTotal}" else "${x.trackNo}"
                                )
                                InfoRow("아티스트", x.artist)
                                InfoRow("앨범 아티스트", x.albumArtist)
                                InfoRow("앨범", x.album)
                                InfoRow("연도", x.year)
                                InfoRow("장르", x.genre)
                                InfoRow("앨범아트", if (x.hasArt) "있음 (${x.artBytes / 1024}KB)" else "없음")
                                InfoRow("태그 형식", x.format)
                            }
                        } else {
                            InfoRow("앨범", tg.album.value, tg.album.mixed)
                            InfoRow("아티스트", tg.artist.value, tg.artist.mixed)
                            InfoRow("앨범 아티스트", tg.albumArtist.value, tg.albumArtist.mixed)
                            InfoRow("연도", tg.year.value, tg.year.mixed)
                            InfoRow("장르", tg.genre.value, tg.genre.mixed)
                            InfoRow("앨범아트", "${tg.artCount}/${tg.total}곡에 있음", tg.artCount in 1 until tg.total)
                        }
                        if (tg != null) {
                            Text(
                                "태그 읽기 ${tg.elapsedMs}ms",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
                // 트랙 목록
                items(album.tracks, key = { it.id }) { t ->
                    val isSel = t.id == selectedId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSel) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { selectedId = if (isSel) null else t.id }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            if (t.trackNo > 0) t.trackNo.toString() else "-",
                            modifier = Modifier.width(28.dp),
                            fontSize = 14.sp,
                            textAlign = TextAlign.End,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            t.title,
                            modifier = Modifier.weight(1f),
                            fontSize = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            formatTime(t.durationMs),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item { Box(modifier = Modifier.padding(bottom = 32.dp)) }
            }
        }
    }

    // 수정 화면: 상세 화면 위에 전체 화면으로 덮는다
    val tg = tags
    if (editing && tg != null) {
        EditScreen(
            album = album,
            tags = tg,
            track = editTarget,
            currentArt = art,
            onClose = { editing = false },
            onSave = { req ->
                startSave(req.trackIds.size) { p -> TagSaver.save(ctx, album, req, p) }
            }
        )
    }

    // 저장 중에는 화면을 덮고 뒤로가기도 막는다
    val sp = saveProgress
    if (sp != null) {
        BackHandler { }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(enabled = true, onClick = { }),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Text(
                    "저장 중 ${sp.first} / ${sp.second}",
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }

    if (showNumbering) {
        val plan = remember(album.tracks) { TrackNumbering.plan(album.tracks) }
        val dups = remember(plan) { TrackNumbering.duplicates(plan) }
        val guessedCount = plan.count { it.guessed }
        AlertDialog(
            onDismissRequest = { showNumbering = false },
            title = { Text("트랙번호 자동 배분") },
            text = {
                Column {
                    Text(
                        "파일명 앞의 숫자로 트랙번호를 정해요. 트랙명(제목)은 바꾸지 않아요.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (guessedCount > 0) {
                        Text(
                            "※ ${guessedCount}곡은 파일명에서 번호를 못 읽어서 목록 순서대로 빈 번호를 줬어요 (표시: 순서)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    if (dups.isNotEmpty()) {
                        Text(
                            "※ 같은 번호가 겹쳐요: " + dups.joinToString(", "),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp).padding(top = 8.dp)) {
                        items(plan) { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    row.track.displayName,
                                    modifier = Modifier.weight(1f),
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    (if (row.guessed) "순서 " else "") + "→ " + row.number,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (row.guessed) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showNumbering = false
                    // 이미 같은 번호인 곡은 건드리지 않는다
                    val cur = tags
                    val map = HashMap<Long, Int>()
                    for (row in plan) {
                        if (cur?.perTrack?.get(row.track.id)?.trackNo != row.number) map[row.track.id] = row.number
                    }
                    if (map.isEmpty()) {
                        Toast.makeText(ctx, "이미 번호가 모두 맞아요", Toast.LENGTH_SHORT).show()
                    } else {
                        startSave(map.size) { p -> TrackNumbering.save(ctx, album, map, p) }
                    }
                }) { Text("적용") }
            },
            dismissButton = {
                TextButton(onClick = { showNumbering = false }) { Text("취소") }
            }
        )
    }

    if (showPermission) {
        AlertDialog(
            onDismissRequest = { showPermission = false },
            title = { Text("파일 수정 권한이 필요해요") },
            text = {
                Text(
                    "mp3 파일에 태그를 쓰려면 \"모든 파일 액세스\" 권한이 필요해요.\n\n" +
                        "‘설정 열기’를 누른 뒤 Tag Editor를 켜고, 앱으로 돌아와서 저장을 다시 눌러 주세요."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermission = false
                    TagSaver.openAllFilesSettings(ctx)
                }) { Text("설정 열기") }
            },
            dismissButton = {
                TextButton(onClick = { showPermission = false }) { Text("취소") }
            }
        )
    }
    }
}

/** 태그 정보 한 줄: 왼쪽 항목 이름, 오른쪽 값. mixed=true 면 "여러 값" 표시 */
@Composable
private fun InfoRow(label: String, value: String, mixed: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            modifier = Modifier.width(96.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (value.isBlank()) "-" else value,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp
        )
        if (mixed) {
            Text(
                "여러 값",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
