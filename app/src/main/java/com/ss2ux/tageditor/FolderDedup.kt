package com.ss2ux.tageditor

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 폴더명에서 **연속으로 100% 같은 구간**이 두 번 붙은 경우만 한 번으로 줄인다.
 * 예) "…엔드게임어벤져스 엔드게임" / "…엔드게임 어벤져스 엔드게임" 의 반복만 제거.
 * "어벤져스 모음 - 어벤져스 엔드게임" 처럼 떨어져 있는 같은 단어는 건드리지 않는다.
 */
object FolderNameDedup {

    data class Proposal(val dir: File, val from: String, val to: String)

    /** 한 번 접기. 없으면 null */
    fun collapseOnce(s: String): String? {
        // 최소 2글자 단위 (한 글자 반복은 오탐 많음)
        for (len in s.length / 2 downTo 2) {
            var i = 0
            while (i <= s.length - 2 * len) {
                val unit = s.substring(i, i + len)
                // 바로 뒤 동일
                if (s.regionMatches(i + len, unit, 0, len)) {
                    return s.removeRange(i + len, i + 2 * len)
                }
                // 사이 공백 하나
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

    /** 더 이상 안 줄 때까지 접기. 변화가 없으면 null */
    fun collapse(name: String): String? {
        var s = name
        var changed = false
        repeat(32) {
            val n = collapseOnce(s) ?: return if (changed) s else null
            if (n == s) return if (changed) s else null
            s = n
            changed = true
        }
        return if (changed) s else null
    }

    /**
     * Music 바로 아래 1단계 폴더만 검사.
     * (Music/앨범명/…)
     */
    fun scanMusic(context: Context): List<Proposal> {
        val base = Environment.getExternalStorageDirectory()
        val music = File(base, "Music")
        if (!music.isDirectory) return emptyList()
        val out = ArrayList<Proposal>()
        val children = music.listFiles() ?: return emptyList()
        for (dir in children) {
            if (!dir.isDirectory) continue
            val from = dir.name
            val to = collapse(from) ?: continue
            if (to.isEmpty() || to == from) continue
            out.add(Proposal(dir, from, to))
        }
        return out.sortedBy { it.from.lowercase() }
    }

    /** 선택한 제안만 이름 변경 + 미디어 스캔 */
    fun apply(context: Context, selected: List<Proposal>): Pair<Int, List<String>> {
        var ok = 0
        val errors = ArrayList<String>()
        val scanPaths = ArrayList<String>()
        for (p in selected) {
            val target = File(p.dir.parentFile, FolderRename.sanitize(p.to))
            if (target.exists() && !target.absolutePath.equals(p.dir.absolutePath, ignoreCase = true)) {
                errors.add("${p.from} → 같은 이름 폴더가 이미 있음")
                continue
            }
            val safeName = FolderRename.sanitize(p.to)
            if (safeName.isEmpty()) {
                errors.add("${p.from} → 정리 결과가 비어 있음")
                continue
            }
            val dest = File(p.dir.parentFile, safeName)
            if (!p.dir.renameTo(dest)) {
                errors.add("${p.from} → 이름 변경 실패")
                continue
            }
            ok++
            // 폴더 안 파일 스캔
            dest.walkTopDown().maxDepth(3).forEach { f ->
                if (f.isFile) scanPaths.add(f.absolutePath)
            }
        }
        if (scanPaths.isNotEmpty()) {
            try {
                val latch = CountDownLatch(1)
                MediaScannerConnection.scanFile(
                    context,
                    scanPaths.toTypedArray(),
                    null
                ) { _, _ -> latch.countDown() }
                latch.await(45, TimeUnit.SECONDS)
            } catch (_: Throwable) { }
        }
        return ok to errors
    }
}
