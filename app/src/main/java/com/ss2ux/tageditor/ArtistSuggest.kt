package com.ss2ux.tageditor

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 기기 MediaStore 의 앨범 아티스트 태그 모음 (파일 미개방).
 * API 30+ 만 지원. 많이 쓰인 이름이 앞에 온다.
 */
object AlbumArtistIndex {
    @Volatile
    var names: List<String> = emptyList()

    fun load(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < 30) return names
        val counts = HashMap<String, Int>()
        try {
            val col = MediaStore.Audio.AudioColumns.ALBUM_ARTIST
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(col),
                "$col IS NOT NULL AND $col != ''",
                null,
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val v = (c.getString(0) ?: "").trim()
                    if (v.isEmpty() || v == "<unknown>") continue
                    counts[v] = (counts[v] ?: 0) + 1
                }
            }
        } catch (_: Exception) {
            return names
        }
        val merged = HashMap<String, Int>(counts)
        for ((full, n) in counts) {
            // 아티스트 형식 "A / B" 와 앨범 아티스트 형식 "A · B" 모두 조각으로 후보 추가
            val parts = when {
                " · " in full -> full.split(" · ")
                " / " in full -> full.split(" / ")
                else -> emptyList()
            }
            for (part in parts) {
                val t = part.trim()
                if (t.isNotEmpty()) merged[t] = (merged[t] ?: 0) + n
            }
        }
        names = merged.entries.sortedByDescending { it.value }.map { it.key }
        return names
    }
}

/** 목록 화면 앨범들의 아티스트 이름 (목록 갱신 시 update) */
object ArtistIndex {
    @Volatile
    var names: List<String> = emptyList()

    fun update(albums: List<Album>) {
        val set = LinkedHashSet<String>()
        for (a in albums) {
            val full = a.artist.trim()
            if (full.isEmpty() || full == "알 수 없는 아티스트") continue
            set.add(full)
            for (n in full.split(" / ")) {
                val t = n.trim()
                if (t.isNotEmpty()) set.add(t)
            }
        }
        names = set.toList()
    }
}

/** 앨범 아티스트 입력 추천 (최대 6개) */
fun suggestAlbumArtists(
    query: String,
    artistText: String,
    tags: AlbumTags,
    deviceAlbumArtists: List<String>
): List<String> {
    val pool = ArrayList<String>()
    pool.addAll(deviceAlbumArtists)
    fun addWithParts(v: String) {
        val t = v.trim()
        if (t.isEmpty()) return
        pool.add(t)
        val parts = when {
            " · " in t -> t.split(" · ")
            " / " in t -> t.split(" / ")
            else -> emptyList()
        }
        for (n in parts) {
            val x = n.trim()
            if (x.isNotEmpty()) pool.add(x)
        }
    }
    addWithParts(artistText)
    addWithParts(tags.albumArtist.value)
    addWithParts(tags.artist.value)
    for (t in tags.perTrack.values) {
        addWithParts(t.artist)
        addWithParts(t.albumArtist)
    }
    pool.addAll(ArtistIndex.names)

    val seen = HashSet<String>()
    val starts = ArrayList<String>()
    val contains = ArrayList<String>()
    for (c in pool) {
        if (c.equals(query, ignoreCase = true)) continue
        if (!seen.add(c.lowercase())) continue
        if (c.startsWith(query, ignoreCase = true)) starts.add(c)
        else if (c.contains(query, ignoreCase = true)) contains.add(c)
    }
    return (starts + contains).take(6)
}

@Composable
fun SuggestionRow(items: List<String>, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "추천 · 누르면 입력돼요",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        for (item in items) {
            Text(
                item,
                fontSize = 12.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable { onPick(item) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
