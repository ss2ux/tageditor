package com.ss2ux.tageditor

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val trackNo: Int,
    val displayName: String,  // 파일 이름 (예: 곡.mp3)
    val durationMs: Long
)

data class Album(
    val key: String,        // 목록에서 앨범을 구분하는 값
    val path: String,       // MediaStore RELATIVE_PATH (예: Download/앨범폴더/)
    val name: String,       // 앨범명 (태그 기준, 없으면 폴더 이름)
    val artist: String,     // 가장 많은 곡의 아티스트
    val addedAt: Long,      // 가장 최근 추가된 곡의 추가 시각(초)
    val tracks: List<Track>
)

private class Entry(val track: Track, val artist: String, val album: String, val added: Long)

private fun mostCommon(list: List<String>): String? =
    list.filter { it.isNotBlank() }
        .groupingBy { it }.eachCount()
        .maxByOrNull { it.value }?.key

/**
 * 핵심: 파일을 하나도 열지 않고 MediaStore(안드로이드가 만들어 둔 미디어 DB)만 조회한다.
 * 그래서 곡이 수만 개여도 수십~수백 ms에 끝난다.
 *
 * folders: "Download", "Music" 처럼 최상위 폴더 이름들
 */
fun queryAlbums(context: Context, folders: Set<String>): List<Album> {
    if (folders.isEmpty()) return emptyList()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val cols = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.RELATIVE_PATH,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.DURATION
    )
    val selection = folders.joinToString(" OR ") { "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" }
    val args = folders.map { "$it/%" }.toTypedArray()

    val groups = LinkedHashMap<String, MutableList<Entry>>()
    val groupPath = HashMap<String, String>()
    context.contentResolver.query(collection, cols, selection, args, null)?.use { c ->
        val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        val iPath = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
        val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        while (c.moveToNext()) {
            val path = c.getString(iPath) ?: continue
            val id = c.getLong(iId)
            val artist = c.getString(iArtist)?.takeIf { it != "<unknown>" } ?: ""
            val album = c.getString(iAlbum)?.takeIf { it != "<unknown>" } ?: ""
            // 트랙번호가 1001처럼 (디스크*1000+트랙) 으로 저장된 경우 대비
            val trackNo = c.getInt(iTrack).let { if (it > 1000) it % 1000 else it }
            val track = Track(
                id = id,
                uri = ContentUris.withAppendedId(collection, id),
                title = c.getString(iTitle) ?: "(제목 없음)",
                trackNo = trackNo,
                displayName = c.getString(iName) ?: "",
                durationMs = c.getLong(iDur)
            )
            // 폴더 단위 = 앨범. 단, Download 바로 아래에 낱개로 있는 파일들은
            // 한 앨범으로 뭉치지 않도록 앨범 태그로 다시 나눈다.
            val isRoot = path.trimEnd('/') in folders
            val key = if (isRoot) "$path#$album" else path
            groups.getOrPut(key) { mutableListOf() }.add(Entry(track, artist, album, c.getLong(iAdded)))
            groupPath[key] = path
        }
    }

    return groups.map { (key, rows) ->
        val path = groupPath[key] ?: ""
        val folderName = path.trimEnd('/').substringAfterLast('/')
        val tracks = rows.map { it.track }
            .sortedWith(compareBy<Track>({ it.trackNo }, { it.displayName.lowercase() }))
        val albumName = mostCommon(rows.map { it.album }.filter { it !in folders })
            ?: folderName
        Album(
            key = key,
            path = path,
            name = albumName,
            artist = mostCommon(rows.map { it.artist }) ?: "알 수 없는 아티스트",
            addedAt = rows.maxOf { it.added },
            tracks = tracks
        )
    }.sortedByDescending { it.addedAt }   // 새로 받은 앨범이 맨 위
}

/** 화면에 보이는 앨범만 작은 썸네일을 읽어서 캐시한다 */
object ThumbCache {
    private val cache = LruCache<String, Bitmap>(250)
    private val missing = HashSet<String>()

    fun peek(key: String): Bitmap? = cache.get(key)

    /** 태그/앨범아트를 저장한 뒤 옛 썸네일을 버린다 */
    fun clear() {
        cache.evictAll()
        missing.clear()
    }

    suspend fun load(context: Context, album: Album): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(album.key)?.let { return@withContext it }
        if (album.key in missing) return@withContext null
        for (t in album.tracks.take(3)) {
            try {
                val bmp = context.contentResolver.loadThumbnail(t.uri, Size(300, 300), null)
                cache.put(album.key, bmp)
                return@withContext bmp
            } catch (e: Exception) {
                // 이 곡에는 내장 앨범아트가 없음 -> 다음 곡 시도
            }
        }
        missing.add(album.key)
        null
    }
}

/** 상세 화면용 큰 앨범아트 (한 번에 몇 장만 캐시) */
object LargeArt {
    private val cache = LruCache<String, Bitmap>(3)

    fun peek(key: String): Bitmap? = cache.get(key)

    fun clear() = cache.evictAll()

    suspend fun load(context: Context, album: Album): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(album.key)?.let { return@withContext it }
        for (t in album.tracks.take(5)) {
            try {
                val bmp = context.contentResolver.loadThumbnail(t.uri, Size(1000, 1000), null)
                cache.put(album.key, bmp)
                return@withContext bmp
            } catch (e: Exception) {
                // 이 곡에는 내장 앨범아트가 없음 -> 다음 곡 시도
            }
        }
        null
    }
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
