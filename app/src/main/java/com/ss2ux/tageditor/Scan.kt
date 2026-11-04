package com.ss2ux.tageditor

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val AUDIO_EXT = setOf(
    "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "m4b", "mka"
)

private fun isAudioName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in AUDIO_EXT

/**
 * 방금 다운로드해서 아직 MediaStore에 등록되지 않은 파일을 찾아 안드로이드에 등록(스캔)을 요청한다.
 * known = 이미 목록에 있는 곡의 키 집합 (RELATIVE_PATH + 파일 이름)
 * 반환: 스캔을 요청한 새 파일 수. 시간이 걸릴 수 있으므로 반드시 백그라운드에서 호출할 것.
 */
fun scanNewAudio(context: Context, folders: Set<String>, known: Set<String>): Int {
    try {
        val base = Environment.getExternalStorageDirectory()
        val basePrefix = base.absolutePath + "/"
        val fresh = ArrayList<String>()
        for (folder in folders) {
            val stack = ArrayList<File>()
            stack.add(File(base, folder))
            while (stack.isNotEmpty()) {
                val dir = stack.removeAt(stack.size - 1)
                val list = dir.listFiles() ?: continue
                for (f in list) {
                    if (f.isDirectory) {
                        if (!f.name.startsWith(".")) stack.add(f)
                    } else if (isAudioName(f.name)) {
                        val rel = f.absolutePath.removePrefix(basePrefix)
                        if (rel !in known) fresh.add(f.absolutePath)
                    }
                }
            }
        }
        if (fresh.isEmpty()) return 0
        val latch = CountDownLatch(fresh.size)
        MediaScannerConnection.scanFile(context, fresh.toTypedArray(), null) { _, _ ->
            latch.countDown()
        }
        latch.await(30, TimeUnit.SECONDS)
        return fresh.size
    } catch (e: Throwable) {
        return 0
    }
}
