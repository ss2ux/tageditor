package com.ss2ux.tageditor

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AlbumArtistGroup(
    val name: String,
    val trackIds: List<Long>,
    val albumCount: Int
)

/** MediaStore ALBUM_ARTIST 기준 그룹 (API 30+). 하위면 빈 목록. */
fun queryAlbumArtistGroups(context: Context, folders: Set<String>): List<AlbumArtistGroup> {
    if (Build.VERSION.SDK_INT < 30 || folders.isEmpty()) return emptyList()
    val map = LinkedHashMap<String, Pair<ArrayList<Long>, HashSet<String>>>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val cols = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.ALBUM_ARTIST,
        MediaStore.Audio.Media.RELATIVE_PATH
    )
    val where = folders.joinToString(" OR ") { "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" }
    val args = folders.map { "$it/%" }.toTypedArray()
    context.contentResolver.query(collection, cols, where, args, null)?.use { c ->
        val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val iAa = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
        val iPath = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
        while (c.moveToNext()) {
            val aaRaw = (c.getString(iAa) ?: "").trim()
            if (aaRaw.isEmpty() || aaRaw == "<unknown>") continue
            val aa = aaRaw
            val id = c.getLong(iId)
            val path = (c.getString(iPath) ?: "").trimEnd('/')
            val albumKey = path // folder path as album proxy
            val slot = map.getOrPut(aa) { ArrayList<Long>() to HashSet() }
            slot.first.add(id)
            slot.second.add(albumKey)
        }
    }
    return map.map { (name, pair) ->
        AlbumArtistGroup(name, pair.first, pair.second.size)
    }.sortedBy { it.name.lowercase() }
}

@Composable
fun AlbumArtistBrowseScreen(
    folders: Set<String>,
    onClose: () -> Unit,
    onDone: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<AlbumArtistGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<AlbumArtistGroup?>(null) }
    var newName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            groups = withContext(Dispatchers.IO) { queryAlbumArtistGroups(ctx, folders) }
            loading = false
        }
    }

    LaunchedEffect(folders) { reload() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
            }
            Text("앨범 아티스트별 보기", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Text(
            "이름을 바꾸면 그 앨범 아티스트로 된 모든 곡 태그가 함께 바뀌어요.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (Build.VERSION.SDK_INT < 30) {
            Text(
                "이 기능은 Android 11 이상에서만 MediaStore 앨범 아티스트를 읽을 수 있어요.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
        }
        if (loading) {
            CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
        } else if (groups.isEmpty()) {
            Text("표시할 앨범 아티스트가 없어요.", modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(groups, key = { it.name }) { g ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                editing = g
                                newName = g.name
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(g.name, fontSize = 16.sp)
                        Text(
                            "곡 ${g.trackIds.size} · 폴더 ${g.albumCount}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
        }
    }

    editing?.let { g ->
        AlertDialog(
            onDismissRequest = { if (!busy) editing = null },
            title = { Text("앨범 아티스트 이름 변경") },
            text = {
                Column {
                    Text("곡 ${g.trackIds.size}개에 적용됩니다.", fontSize = 13.sp)
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("새 이름") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && newName.trim().isNotEmpty() && newName.trim() != g.name,
                    onClick = {
                        val nn = newName.trim()
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                val req = EditRequest(
                                    trackIds = g.trackIds,
                                    albumArtist = nn
                                )
                                // album 없이 trackIds만으로 저장 가능한지 TagSaver 확인
                                saveAlbumArtistOnly(ctx, g.trackIds, nn)
                            }
                            busy = false
                            editing = null
                            message = result
                            reload()
                            onDone()
                        }
                    }
                ) { Text(if (busy) "적용 중…" else "적용") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { editing = null }) { Text("취소") }
            }
        )
    }
}

/** 트랙 id 목록에 앨범 아티스트만 기록 */
fun saveAlbumArtistOnly(context: Context, trackIds: List<Long>, albumArtist: String): String {
    if (trackIds.isEmpty()) return "대상 곡이 없어요"
    val album = Album(
        key = "_bulk_",
        path = "",
        name = "",
        artist = "",
        addedAt = 0L,
        tracks = trackIds.map { id ->
            Track(
                id = id,
                uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                ),
                title = "",
                trackNo = 0,
                displayName = "",
                durationMs = 0L
            )
        }
    )
    val req = EditRequest(trackIds = trackIds, albumArtist = albumArtist)
    val r = TagSaver.save(context, album, req) { _, _ -> }
    return when {
        r.ok == trackIds.size -> "${r.ok}곡 앨범 아티스트를 바꿨어요"
        r.ok > 0 -> "${r.ok}/${trackIds.size}곡 반영 · 일부 실패"
        else -> r.failed.firstOrNull() ?: "저장에 실패했어요"
    }
}
