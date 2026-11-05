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
 * - 1a, 1B, 1c 처럼 숫자 뒤에 영문 한 글자가 붙은 것도 그 숫자(1)로 인식
 * - 맨 앞에 #(또는 # 와 공백)이 있어도 없는 것처럼 똑같이 처리: #1, #1-A, #1-b -> 1
 * - 2-A, 2-B, 3_a, 2 - A, 2–B(긴 하이픈) 처럼 숫자 뒤에 구분 기호(- _ .)와 영문 한 글자도 그 숫자(2)로 인식
 * - 접두어가 없으면 숫자 뒤에 _ - . 가 있거나 숫자만 있어야 인식: 01_, 1-, 1
 *   (곡 제목이 "1999" 처럼 숫자로 시작하는 경우를 트랙번호로 오인하지 않기 위함)
 * 트랙명(제목 태그)은 절대 바꾸지 않고, 트랙번호만 쓴다.
 */
object TrackNumbering {
    private val withPrefix =
        Regex("""^(?:tr(?:ack)?|트랙|t)\s*0*(\d{1,3})[A-Za-z]?(?=[\s_\-.]|$)""", RegexOption.IGNORE_CASE)
    private val plainNumber = Regex("""^0*(\d{1,3})(?:[_\-.]|$)""")
    // 1a, 1B, 02c 처럼 숫자 바로 뒤에 영문 한 글자: 모두 그 숫자 트랙으로 본다 (뒤에는 공백/_/-/. 또는 끝)
    private val numberLetter = Regex("""^0*(\d{1,3})[A-Za-z](?=[\s_\-.]|$)""")

    // 2-A, 2-B, 3_a, 2 - A, 2–B 처럼 숫자 + (공백) + 구분 기호 + (공백) + 영문 한 글자: 모두 그 숫자 트랙으로 본다.
    // 구분 기호에는 일반 하이픈 외에 – — ‐ ‑ ‒ ― － 같은 비슷한 줄표도 포함 (글자 한 개 뒤에는 공백/구분 기호/끝)
    // 구분 기호 없이 "2 A" 처럼 공백만 있는 건 곡 제목일 수 있어서 제외
    private val numberSepLetter =
        Regex("""^0*(\d{1,3})\s*[\-_.\u2010-\u2015\uFF0D]\s*[A-Za-z](?=[\s_\-.\u2010-\u2015\uFF0D]|$)""")

    fun parse(fileName: String): Int? {
        // 맨 앞의 # (＃ 포함)은 없는 것으로 본다: #1 -> 1, #1-A -> 1, # 2_b -> 2
        val name = fileName.substringBeforeLast('.').trim().trimStart('#', '\uFF03', ' ', '\t')
        val m = withPrefix.find(name) ?: plainNumber.find(name) ?: numberLetter.find(name)
            ?: numberSepLetter.find(name) ?: return null
        return m.groupValues[1].toIntOrNull()?.takeIf { it >= 0 }
    }

    /** 미리보기 한 줄. guessed = 파일명에서 못 읽어서 순서대로 매긴 번호 */
    class Item(val track: Track, val number: Int, val guessed: Boolean)

    /** 번호 배분 계획. 못 읽은 파일은 목록 순서대로, 이미 쓰인 번호를 피해서 빈 번호를 준다. */
    fun plan(tracks: List<Track>): List<Item> {
        val parsed = tracks.map { parse(it.displayName) }
        val used = HashSet<Int>()
        for (p in parsed) if (p != null) used.add(p)
        // 0번(0, tr0, 0_ 등)은 번호 없음으로 둔다: 빈 번호를 채우지 않고 그대로 0
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
        items.filter { it.number > 0 }.groupBy { it.number }.filter { it.value.size > 1 }.keys.sorted()

    /** 곡마다 다른 번호를 쓴다. 번호(TRCK)만 바꾸고 나머지 태그는 그대로. 반드시 백그라운드에서 호출. */
    fun save(
        context: Context,
        album: Album,
        numbers: Map<Long, Int>,
        onProgress: (Int, Int) -> Unit
    ): SaveResult =
        saveEach(context, album, numbers.keys, onProgress) { t ->
            EditRequest(trackIds = listOf(t.id), trackNo = numbers[t.id] ?: 0)
        }
}

/**
 * 파일명으로 트랙명(제목 태그) 일괄 바꾸기.
 * 파일명에서 .mp3(삭제문구)만 빼고 그대로 쓴다. 앞의 01_ 같은 번호는 지우지 않는다.
 * 파일명이 한글인데 태그 제목이 일본어/중국어일 때 한 번에 맞추려고 만든 기능.
 */
object FileTitles {
    class Item(val track: Track, val before: String, val after: String)

    fun fileTitle(track: Track): String = NameCleaner.clean(track.displayName)

    /** 바뀌는 곡만 돌려준다 (이미 파일명과 같은 곡, 파일명이 빈 곡은 제외) */
    fun plan(tracks: List<Track>, tags: AlbumTags): List<Item> {
        val out = ArrayList<Item>()
        for (t in tracks) {
            val after = fileTitle(t)
            if (after.isBlank()) continue
            val before = tags.perTrack[t.id]?.title ?: ""
            if (before.trim() != after) out.add(Item(t, before, after))
        }
        return out
    }

    /** 곡마다 다른 트랙명을 쓴다. 트랙명(TIT2)만 바꾸고 나머지 태그는 그대로. 반드시 백그라운드에서 호출. */
    fun save(
        context: Context,
        album: Album,
        titles: Map<Long, String>,
        onProgress: (Int, Int) -> Unit
    ): SaveResult =
        saveEach(context, album, titles.keys, onProgress) { t ->
            EditRequest(trackIds = listOf(t.id), title = titles[t.id] ?: "")
        }
}

/** 곡마다 다른 요청을 한 곡씩 저장하고, 끝나면 미디어 DB를 새로 읽게 한다. */
private fun saveEach(
    context: Context,
    album: Album,
    ids: Set<Long>,
    onProgress: (Int, Int) -> Unit,
    makeRequest: (Track) -> EditRequest
): SaveResult {
    val base = Environment.getExternalStorageDirectory()
    val targets = album.tracks.filter { it.id in ids }
    val done = ArrayList<String>()
    val failed = ArrayList<String>()

    targets.forEachIndexed { i, t ->
        val f = File(base, album.path + t.displayName)
        try {
            if (!f.name.endsWith(".mp3", ignoreCase = true)) {
                throw IOException("mp3 파일만 수정할 수 있어요")
            }
            Id3Writer.write(f, makeRequest(t))
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
