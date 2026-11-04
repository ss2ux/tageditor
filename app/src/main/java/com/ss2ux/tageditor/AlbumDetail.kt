package com.ss2ux.tageditor

import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
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
                    // 5단계에서 수정 창으로 연결할 자리
                    val msg = if (selected == null) "앨범 전체 수정 (${album.tracks.size}곡)"
                    else "트랙 수정: ${selected.title}"
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
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
}
