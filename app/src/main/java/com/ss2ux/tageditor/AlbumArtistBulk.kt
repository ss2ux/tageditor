package com.ss2ux.tageditor

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.clickable
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class AlbumArtistGroup(
    val name: String,
    /** trackId → 현재 앨범 아티스트 전체 문자열 */
    val trackFull: Map<Long, String>
) {
    val trackCount: Int get() = trackFull.size
}

/** "A · B" / "A / B" / 단일 → 조각 목록 */
fun splitAlbumArtistParts(full: String): List<String> {
    val t = full.trim()
    if (t.isEmpty()) return emptyList()
    return when {
        " · " in t -> t.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        " / " in t -> t.split(" / ").map { it.trim() }.filter { it.isNotEmpty() }
        else -> listOf(t)
    }
}

/** full 안에서 from 이름을 to 로 바꿈. 구분자는 원본 유지. 변화 없으면 null */
fun replaceAlbumArtistPart(full: String, from: String, to: String): String? {
    val t = full.trim()
    if (t.isEmpty()) return null
    val sep = when {
        " · " in t -> " · "
        " / " in t -> " / "
        else -> null
    }
    if (sep == null) {
        return if (t == from) to else null
    }
    val parts = t.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
    var changed = false
    val next = parts.map {
        if (it == from) {
            changed = true
            to
        } else it
    }
    return if (changed) next.joinToString(sep) else null
}

fun queryAlbumArtistGroups(context: Context, folders: Set<String>): List<AlbumArtistGroup> {
    if (Build.VERSION.SDK_INT < 30 || folders.isEmpty()) return emptyList()
    // partName → (id → full)
    val map = LinkedHashMap<String, LinkedHashMap<Long, String>>()
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
        while (c.moveToNext()) {
            val full = (c.getString(iAa) ?: "").trim()
            if (full.isEmpty() || full == "<unknown>") continue
            val id = c.getLong(iId)
            for (part in splitAlbumArtistParts(full)) {
                map.getOrPut(part) { LinkedHashMap() }[id] = full
            }
        }
    }
    return map.map { (name, tracks) ->
        AlbumArtistGroup(name, tracks)
    }.sortedBy { it.name.lowercase() }
}

/** 곡마다 앨범 아티스트 문자열이 다를 수 있으므로 파일별로 기록 */
fun renameAlbumArtistPart(
    context: Context,
    from: String,
    to: String,
    trackFull: Map<Long, String>
): String {
    if (trackFull.isEmpty()) return "대상 곡이 없어요"
    val base = Environment.getExternalStorageDirectory()
    val done = ArrayList<String>()
    val failed = ArrayList<String>()
    for ((id, full) in trackFull) {
        val newFull = replaceAlbumArtistPart(full, from, to) ?: continue
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        var rel: String? = null
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val path = c.getString(0) ?: ""
                val name = c.getString(1) ?: ""
                rel = path + name
            }
        }
        if (rel.isNullOrEmpty()) {
            failed.add("id=$id (경로 없음)")
            continue
        }
        val f = File(base, rel)
        try {
            if (!f.name.endsWith(".mp3", ignoreCase = true)) {
                failed.add("${f.name} (mp3만)")
                continue
            }
            Id3Writer.write(f, EditRequest(trackIds = listOf(id), albumArtist = newFull))
            done.add(f.absolutePath)
        } catch (e: Throwable) {
            failed.add("${f.name} (${e.message ?: e.javaClass.simpleName})")
        }
    }
    if (done.isNotEmpty()) {
        try {
            val latch = CountDownLatch(done.size)
            MediaScannerConnection.scanFile(context, done.toTypedArray(), null) { _, _ -> latch.countDown() }
            latch.await(30, TimeUnit.SECONDS)
        } catch (_: Throwable) { }
    }
    return when {
        done.size == trackFull.size -> "${done.size}곡 반영 ($from → $to)"
        done.isNotEmpty() -> "${done.size}/${trackFull.size}곡 반영 · 실패 ${failed.size}"
        else -> failed.firstOrNull() ?: "저장에 실패했어요"
    }
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
            Text(
                "앨범 아티스트별 보기",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "한 이름을 바꾸면, 그 이름이 들어 있는 묶음(예: 아이유 · 로꼬)도 같이 바뀌어요.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (Build.VERSION.SDK_INT < 30) {
            Text(
                "Android 11 이상에서만 사용할 수 있어요.",
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
                            "곡 ${g.trackCount}개 (단독·묶음 포함)",
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
                    Text(
                        "「${g.name}」이(가) 들어 있는 곡 ${g.trackCount}개에 적용됩니다. " +
                            "묶음이면 그 자리만 바뀌고 나머지는 유지돼요.",
                        fontSize = 13.sp
                    )
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
                                renameAlbumArtistPart(ctx, g.name, nn, g.trackFull)
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
