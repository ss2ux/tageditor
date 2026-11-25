package com.ss2ux.tageditor

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 앨범 폴더 안의 하위 폴더에 흩어진 음원(+짝 자막)을 가장 바깥 앨범 폴더로 모은다.
 *
 * 규칙:
 *  - 음원이 있는 하위 폴더만 대상
 *  - 자막은 **같은 폴더**에 이름 줄기가 맞는 음원이 있을 때만 함께 이동
 *  - 음원 없이 자막만 있는 폴더(예: disc3/dd.vtt 만)는 건드리지 않음
 */
object AudioOrganize {

    private val AUDIO_EXT = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "m4b", "mka"
    )
    private val SUB_EXT = setOf("srt", "vtt", "lrc", "txt")

    class Result(
        val movedAudio: Int,
        val movedSub: Int,
        val failed: List<String>,
        val error: String? = null
    ) {
        val ok: Boolean get() = error == null && failed.isEmpty()
    }

    /** 이 앨범에 하위 폴더에 있는 음원이 하나라도 있으면 true */
    fun needsOrganize(album: Album): Boolean {
        val root = album.path.trimEnd('/').lowercase() + "/"
        for (t in album.tracks) {
            val rel = t.relPath.ifEmpty { album.path + t.displayName }
            val norm = rel.replace('\\', '/').lowercase()
            if (!norm.startsWith(root)) continue
            val rest = norm.removePrefix(root)
            if ('/' in rest) return true
        }
        return false
    }

    /**
     * album.path (= Download/앨범명/) 기준으로 하위 폴더 파일들을 바깥으로 옮긴다.
     * 모든 파일 접근 권한이 필요하다.
     */
    fun flatten(context: Context, album: Album): Result {
        val base = Environment.getExternalStorageDirectory()
        val relAlbum = album.path.trimEnd('/')
        if (relAlbum.isEmpty() || '/' !in relAlbum) {
            return Result(0, 0, emptyList(), "Download/Music 바로 아래 낱개 곡은 정리할 폴더가 없어요")
        }
        val albumDir = File(base, relAlbum)
        if (!albumDir.isDirectory) {
            return Result(0, 0, emptyList(), "앨범 폴더를 찾을 수 없어요")
        }

        val moves = ArrayList<Pair<File, File>>() // src → dest
        val scheduled = HashSet<String>() // 이미 옮기기로 한 원본 경로
        val reservedDest = HashSet<String>() // 이번에 쓸 대상 파일명 (아직 디스크에 없을 수 있음)
        val failed = ArrayList<String>()

        fun schedule(src: File, dest: File) {
            val key = src.absolutePath
            if (key in scheduled) return
            if (src.absolutePath == dest.absolutePath) return
            scheduled.add(key)
            reservedDest.add(dest.name.lowercase())
            moves.add(src to dest)
        }

        fun uniqueDest(dir: File, fileName: String): File {
            val dot = fileName.lastIndexOf('.')
            val stem = if (dot > 0) fileName.substring(0, dot) else fileName
            val ext = if (dot > 0) fileName.substring(dot) else ""
            var n = 1
            while (true) {
                val name = if (n == 1) fileName else "${stem}_$n$ext"
                val dest = File(dir, name)
                if (!dest.exists() && name.lowercase() !in reservedDest) return dest
                n++
            }
        }

        fun stemKeys(fileName: String): Set<String> {
            val lower = fileName.lowercase()
            val keys = linkedSetOf<String>()
            if ('.' !in lower) {
                keys.add(lower)
                return keys
            }
            var cur = lower.substringBeforeLast('.')
            keys.add(cur)
            // song.mp3.vtt → song.mp3, song
            while ('.' in cur) {
                val ext = cur.substringAfterLast('.')
                if (ext in AUDIO_EXT) {
                    keys.add(cur)
                    cur = cur.substringBeforeLast('.')
                    keys.add(cur)
                } else break
            }
            return keys
        }

        fun walk(dir: File) {
            val list = dir.listFiles() ?: return
            val files = list.filter { it.isFile }
            val dirs = list.filter { it.isDirectory && !it.name.startsWith(".") }

            if (dir != albumDir) {
                val audios = files.filter {
                    it.name.substringAfterLast('.', "").lowercase() in AUDIO_EXT
                }
                val subs = files.filter {
                    it.name.substringAfterLast('.', "").lowercase() in SUB_EXT
                }
                for (audio in audios) {
                    val dest = uniqueDest(albumDir, audio.name)
                    schedule(audio, dest)
                    val keys = stemKeys(audio.name)
                    for (sub in subs) {
                        val subKeys = stemKeys(sub.name)
                        // 같은 폴더에서 이름 줄기가 맞는 자막만
                        if (keys.intersect(subKeys).isNotEmpty()) {
                            schedule(sub, uniqueDest(albumDir, sub.name))
                        }
                    }
                }
            }
            for (d in dirs) walk(d)
        }

        walk(albumDir)

        if (moves.isEmpty()) {
            return Result(0, 0, emptyList(), null)
        }

        var movedAudio = 0
        var movedSub = 0
        val scanPaths = ArrayList<String>()

        for ((src, dest) in moves) {
            try {
                if (src.absolutePath == dest.absolutePath) continue
                val ok = src.renameTo(dest)
                if (!ok) {
                    // rename 실패 시 복사 후 삭제 시도
                    src.copyTo(dest, overwrite = false)
                    if (!src.delete()) {
                        dest.delete()
                        failed.add(src.name)
                        continue
                    }
                }
                val ext = src.name.substringAfterLast('.', "").lowercase()
                if (ext in AUDIO_EXT) movedAudio++ else movedSub++
                scanPaths.add(src.absolutePath)
                scanPaths.add(dest.absolutePath)
            } catch (e: Exception) {
                failed.add("${src.name} (${e.message ?: e.javaClass.simpleName})")
            }
        }

        // 빈 하위 폴더 정리 (깊은 것부터)
        fun deleteEmpty(dir: File) {
            if (dir == albumDir) return
            val children = dir.listFiles() ?: return
            for (c in children) {
                if (c.isDirectory) deleteEmpty(c)
            }
            val left = dir.listFiles()
            if (left != null && left.isEmpty()) {
                dir.delete()
            }
        }
        albumDir.listFiles()?.filter { it.isDirectory }?.forEach { deleteEmpty(it) }

        if (scanPaths.isNotEmpty()) {
            try {
                val latch = CountDownLatch(scanPaths.size)
                MediaScannerConnection.scanFile(
                    context,
                    scanPaths.toTypedArray(),
                    null
                ) { _, _ -> latch.countDown() }
                latch.await(60, TimeUnit.SECONDS)
            } catch (_: Throwable) {
            }
        }

        return Result(movedAudio, movedSub, failed)
    }
}
