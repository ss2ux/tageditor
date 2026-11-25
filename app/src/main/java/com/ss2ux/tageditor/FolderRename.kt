package com.ss2ux.tageditor

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 폴더 이름을 바꾼 뒤, 목록을 새로 읽고 나서 다시 열어야 할 앨범의 키 (목록 화면이 읽고 비운다) */
object ReopenRequest {
    @Volatile
    var key: String? = null
}

/** "저장할 때 폴더명도 바꾸기" 체크 상태를 기억한다 */
object FolderPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("folder_rename", Context.MODE_PRIVATE)
    fun renameOnSave(c: Context): Boolean = sp(c).getBoolean("renameOnSave", false)
    fun setRenameOnSave(c: Context, v: Boolean) = sp(c).edit().putBoolean("renameOnSave", v).apply()
}

class FolderRenameResult(
    /** 성공하면 바뀐 앨범의 키(= 새 RELATIVE_PATH), 실패하면 null */
    val newKey: String?,
    val error: String?
)

/**
 * 앨범이 들어 있는 폴더의 이름을 바꾼다. (모든 파일 액세스 권한이 있어야 한다)
 * 바꾼 뒤 옛 경로/새 경로의 곡들을 미디어 DB에 다시 스캔시켜서, 목록이 새 폴더를 바로 인식하게 한다.
 */
object FolderRename {

    /** 폴더 이름에 쓸 수 없는/문제되는 글자를 전각으로 바꾸고 공백을 정리한다 */
    fun sanitize(raw: String): String {
        val sb = StringBuilder()
        for (c in raw) {
            sb.append(
                when (c) {
                    '?' -> '？'
                    '/' -> '／'
                    '\\' -> '＼'
                    ':' -> '：'
                    '*' -> '＊'
                    '"' -> '＂'
                    '<' -> '＜'
                    '>' -> '＞'
                    '|' -> '｜'
                    '\n', '\r', '\t' -> ' '
                    else -> c
                }
            )
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ')
    }

    /** 앨범 제목 + 공백 + RJ번호. 제목에 이미 RJ번호가 들어 있으면 붙이지 않는다. 제목이 비면 "" */
    fun buildName(title: String, rj: String): String {
        val t = sanitize(title)
        if (t.isEmpty()) return ""
        return if (t.contains(rj, ignoreCase = true)) t else "$t $rj"
    }

    fun rename(context: Context, album: Album, newName: String): FolderRenameResult {
        val base = Environment.getExternalStorageDirectory()
        val rel = album.path.trimEnd('/')
        if ('/' !in rel) return FolderRenameResult(null, "Download/Music 바로 아래 곡은 폴더명을 바꿀 수 없어요")
        val oldDir = File(base, rel)
        if (!oldDir.isDirectory) return FolderRenameResult(null, "폴더를 찾을 수 없어요")
        val parent = oldDir.parentFile ?: return FolderRenameResult(null, "상위 폴더를 찾을 수 없어요")

        val name = sanitize(newName)
        if (name.isEmpty()) return FolderRenameResult(null, "폴더명이 비어 있어요")
        if (name == oldDir.name) return FolderRenameResult(null, "이미 같은 이름이에요")
        val newDir = File(parent, name)
        if (newDir.exists() && !name.equals(oldDir.name, ignoreCase = true)) {
            return FolderRenameResult(null, "같은 이름의 폴더가 이미 있어요")
        }
        if (!oldDir.renameTo(newDir)) {
            return FolderRenameResult(null, "이름을 바꾸지 못했어요 (권한이나 이름을 확인해 주세요)")
        }

        // 옛 경로(사라진 항목 정리) + 새 경로(새 항목 등록)를 스캔하고 끝날 때까지 기다린다.
        // 하위 폴더 묶기 시 트랙이 disc1/ 등 하위에 있을 수 있으므로 relPath 기준으로 매핑한다.
        try {
            val paths = ArrayList<String>()
            val oldPrefix = rel + "/"
            val newRel = rel.substringBeforeLast('/') + "/" + name
            val newPrefix = newRel + "/"
            for (t in album.tracks) {
                val oldRelFile = if (t.relPath.isNotEmpty()) t.relPath else oldPrefix + t.displayName
                val newRelFile = if (oldRelFile.startsWith(oldPrefix)) {
                    newPrefix + oldRelFile.removePrefix(oldPrefix)
                } else {
                    newPrefix + t.displayName
                }
                paths.add(File(base, oldRelFile).absolutePath)
                paths.add(File(base, newRelFile).absolutePath)
            }
            if (paths.isNotEmpty()) {
                val latch = CountDownLatch(paths.size)
                MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ -> latch.countDown() }
                latch.await(30, TimeUnit.SECONDS)
            }
        } catch (e: Throwable) {
            // 스캔이 늦어져도 폴더 이름 변경 자체는 끝난 상태
        }

        val parentRel = rel.substringBeforeLast('/')
        return FolderRenameResult("$parentRel/$name/", null)
    }
}
