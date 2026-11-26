package com.ss2ux.tageditor

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 폴더명에서 **제목 부분**만 보고, 통째로 같은 문장이 연속 두 번인 경우만 한 번으로 줄인다.
 * 끝의 RJ번호(RJ123456, [RJ…], (RJ…) 등)는 비교·접기에서 **제외**하고, 결과 이름에 그대로 붙인다.
 */
object FolderNameDedup {

    private const val MIN_UNIT = 6

    private val rjTail =
        Regex("""[\s_\-]*[\[(（【]?\s*RJ\d{6,}\s*[\])）】]?\s*$""", RegexOption.IGNORE_CASE)

    data class Proposal(val dir: File, val from: String, val to: String)

    /** (제목, RJ접미) — RJ는 원문 끝에서 떼어 낸 그대로 */
    fun splitTitleAndRj(name: String): Pair<String, String> {
        var body = name
        val suf = StringBuilder()
        while (true) {
            val m = rjTail.find(body) ?: break
            suf.insert(0, body.substring(m.range))
            body = body.substring(0, m.range.first).trimEnd()
        }
        return body to suf.toString()
    }

    fun collapseOnce(s: String): String? {
        if (s.length < MIN_UNIT * 2) return null
        for (len in s.length / 2 downTo MIN_UNIT) {
            var i = 0
            while (i <= s.length - 2 * len) {
                val unit = s.substring(i, i + len)
                if (s.regionMatches(i + len, unit, 0, len)) {
                    return s.removeRange(i + len, i + 2 * len)
                }
                if (i + 2 * len + 1 <= s.length &&
                    s[i + len] == ' ' &&
                    s.regionMatches(i + len + 1, unit, 0, len)
                ) {
                    return s.removeRange(i + len, i + 2 * len + 1)
                }
                i++
            }
        }
        return null
    }

    fun collapseTitle(title: String): String? {
        var s = title
        var changed = false
        repeat(16) {
            val n = collapseOnce(s) ?: return if (changed) normalizeSpaces(s) else null
            if (n == s) return if (changed) normalizeSpaces(s) else null
            s = n
            changed = true
        }
        return if (changed) normalizeSpaces(s) else null
    }

    private fun normalizeSpaces(s: String) = s.trim().replace(Regex("\\s+"), " ")

    /** 전체 폴더명 → 접힌 전체 이름 (RJ 유지). 변화 없으면 null */
    fun collapseFolderName(name: String): String? {
        val (title, rj) = splitTitleAndRj(name)
        if (title.isEmpty()) return null
        val newTitle = collapseTitle(title) ?: return null
        if (newTitle.isEmpty()) return null
        val out = if (rj.isEmpty()) newTitle else {
            val gap = if (rj.first().isWhitespace()) "" else " "
            newTitle + gap + rj.trimStart()
        }
        return if (out == name) null else out
    }

    fun scanMusic(context: Context): List<Proposal> {
        val music = File(Environment.getExternalStorageDirectory(), "Music")
        if (!music.isDirectory) return emptyList()
        val out = ArrayList<Proposal>()
        for (dir in music.listFiles() ?: return emptyList()) {
            if (!dir.isDirectory) continue
            val from = dir.name
            val to = collapseFolderName(from) ?: continue
            out.add(Proposal(dir, from, to))
        }
        return out.sortedBy { it.from.lowercase() }
    }

    fun apply(context: Context, selected: List<Proposal>): Pair<Int, List<String>> {
        var ok = 0
        val errors = ArrayList<String>()
        val scanPaths = ArrayList<String>()
        for (p in selected) {
            val safeName = FolderRename.sanitize(p.to)
            if (safeName.isEmpty()) {
                errors.add("${p.from} → 정리 결과가 비어 있음")
                continue
            }
            val dest = File(p.dir.parentFile, safeName)
            if (dest.exists() && !dest.absolutePath.equals(p.dir.absolutePath, ignoreCase = true)) {
                errors.add("${p.from} → 같은 이름 폴더가 이미 있음")
                continue
            }
            if (!p.dir.renameTo(dest)) {
                errors.add("${p.from} → 이름 변경 실패")
                continue
            }
            ok++
            dest.walkTopDown().maxDepth(3).forEach { f ->
                if (f.isFile) scanPaths.add(f.absolutePath)
            }
        }
        if (scanPaths.isNotEmpty()) {
            try {
                val latch = CountDownLatch(1)
                MediaScannerConnection.scanFile(
                    context, scanPaths.toTypedArray(), null
                ) { _, _ -> latch.countDown() }
                latch.await(45, TimeUnit.SECONDS)
            } catch (_: Throwable) { }
        }
        return ok to errors
    }
}
