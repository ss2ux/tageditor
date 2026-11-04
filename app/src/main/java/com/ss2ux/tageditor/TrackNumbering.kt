package com.ss2ux.tageditor

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 7단계: 파일명 맨 앞의 숫자로 트랙번호를 알아내는 도구.
 * - 접두어(tr, track, 트랙, t)가 있으면 뒤에 숫자만 와도 인식: track1, 트랙 2, t3
 * - 접두어가 없으면 숫자 뒤에 _ - . 가 있거나 숫자만 있어야 인식: 01_, 1-, 1
 *   (곡 제목이 "1999" 처럼 숫자로 시작하는 경우를 트랙번호로 오인하지 않기 위함)
 * 트랙명(제목 태그)은 절대 바꾸지 않고, 트랙번호만 쓴다.
 */
object TrackNumbering {
    private val withPrefix =
        Regex("""^(?:tr(?:ack)?|트랙|t)\s*0*(\d{1,3})(?=[\s_\-.]|$)""", RegexOption.IGNORE_CASE)
    private val plainNumber = Regex("""^0*(\d{1,3})(?:[_\-.]|$)""")

    fun parse(fileName: String): Int? {
        val name = fileName.substringBeforeLast('.').trim()
        val m = withPrefix.find(name) ?: plainNumber.find(name) ?: return null
        return m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
    }

    /** 미리보기 한 줄. guessed = 파일명에서 못 읽어서 순서대로 매긴 번호 */
    class Item(val track: Track, val number: Int, val guessed: Boolean)

    /** 번호 배분 계획. 못 읽은 파일은 목록 순서대로, 이미 쓰인 번호를 피해서 빈 번호를 준다. */
    fun plan(tracks: List<Track>): List<Item> {
        val parsed = tracks.map { parse(it.displayName) }
        val used = HashSet<Int>()
        for (p in parsed) if (p != null) used.add(p)
        var next = 1
        val out = ArrayList<Item>(tracks.size)
        for (i in tracks.indices) {
            val p = parsed[i]
            if (p != null) {
                out.add(Item(tracks[i], p, false))
            } else {
                while (next in used) next++
                out.add(Item(tracks[i], next, true))
                next++
            }
        }
        return out
    }

    /** 같은 번호를 가진 곡이 둘 이상이면 그 번호들 (예: CD1/CD2 를 합친 폴더) */
    fun duplicates(items: List<Item>): List<Int> =
        items.groupBy { it.number }.filter { it.value.size > 1 }.keys.sorted()

    /** 곡마다 다른 번호를 쓴다. 번호(TRCK)만 바꾸고 나머지 태그는 그대로. 반드시 백그라운드에서 호출. */
    fun save(
        context: Context,
        album: Album,
        numbers: Map<Long, Int>,
        onProgress: (Int, Int) -> Unit
    ): SaveResult {
        val base = Environment.getExternalStorageDirectory()
        val targets = album.tracks.filter { numbers.containsKey(it.id) }
        val done = ArrayList<String>()
        val failed = ArrayList<String>()

        targets.forEachIndexed { i, t ->
            val f = File(base, album.path + t.displayName)
            try {
                if (!f.name.endsWith(".mp3", ignoreCase = true)) {
                    throw IOException("mp3 파일만 수정할 수 있어요")
                }
                val n = numbers[t.id] ?: 0
                Id3Writer.write(f, EditRequest(trackIds = listOf(t.id), trackNo = n))
                done.add(f.absolutePath)
            } catch (e: Throwable) {
                failed.add("${t.displayName} (${e.message ?: e.javaClass.simpleName})")
            }
            onProgress(i + 1, targets.size)
        }

        if (done.isNotEmpty()) {
            try {
                val latch = CountDownLatch(done.size)
                MediaScannerConnection.scanFile(context, done.toTypedArray(), null) { _, _ -> latch.countDown() }
                latch.await(30, TimeUnit.SECONDS)
            } catch (e: Throwable) {
                // 갱신이 늦어도 저장 자체는 끝난 상태
            }
        }
        return SaveResult(done.size, failed)
    }
}
