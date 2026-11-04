package com.ss2ux.tageditor

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 트랙 하나에만 적용되는 값 (트랙번호 자동 배분 등). null = 바꾸지 않음 */
class TrackPatch(
    val trackNo: Int? = null,      // 0 = 삭제
    val trackTotal: Int? = null,
    val title: String? = null
)

/**
 * 파일 하나에 적용할 변경 목록. null = 바꾸지 않음, 빈 문자열 = 삭제.
 * year: "" / "2026" / "2026.11.04"
 */
class TagChanges(
    val title: String? = null,
    val trackNo: Int? = null,
    val trackTotal: Int? = null,
    val album: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val artJpeg: ByteArray? = null
) {
    fun isEmpty(): Boolean =
        title == null && trackNo == null && album == null && artist == null &&
            albumArtist == null && year == null && genre == null && artJpeg == null
}

private class RawFrame(val id: String, val bytes: ByteArray)

/**
 * mp3 파일의 ID3v2 태그를 고쳐 쓴다.
 *
 * 안전 장치:
 * - 원본을 바로 고치지 않고 같은 폴더에 임시 파일을 만들어 (새 태그 + 기존 오디오)를 쓴다.
 * - 임시 파일을 다시 읽어서 값이 제대로 들어갔는지 확인한 뒤에만 원본과 바꾼다.
 * - 바꾸지 않는 기존 프레임(가사, 코멘트, 재생 횟수 등)은 바이트 그대로 보존한다.
 * - 태그 구조를 이해하지 못하면 파일을 건드리지 않고 오류를 낸다.
 *
 * 저장 형식 (대부분의 플레이어가 읽는 방식):
 * - 기존 태그가 v2.3이면 v2.3, v2.4면 v2.4로 유지. 태그가 없으면 v2.3으로 새로 만든다.
 * - 연도: v2.3 = TYER(연도) + TDAT(일월), v2.4 = TDRC(2026-11-04)
 * - 글자: 영문/숫자만이면 ISO-8859-1, 한글 등이 있으면 v2.3은 UTF-16, v2.4는 UTF-8
 * - 앨범아트: APIC (image/jpeg, 앞표지) 1개. 기존 그림은 모두 교체
 */
object Id3Writer {

    fun write(file: File, ch: TagChanges) {
        if (!file.isFile) throw IOException("파일을 찾을 수 없어요")
        val origLen = file.length()

        // 1) 기존 태그 읽기
        var major = 3
        var oldTotal = 0L
        val kept = ArrayList<RawFrame>()

        RandomAccessFile(file, "r").use { raf ->
            if (origLen >= 10) {
                val head = ByteArray(10)
                raf.seek(0)
                raf.readFully(head)
                if (head[0].toInt() == 0x49 && head[1].toInt() == 0x44 && head[2].toInt() == 0x33) {
                    val maj = head[3].toInt()
                    val flags = head[5].toInt() and 0xFF
                    val size = syncsafe(head, 6)
                    val footer = maj == 4 && (flags and 0x10) != 0
                    val total = 10L + size + (if (footer) 10 else 0)
                    if (total > origLen) throw IOException("태그 크기가 이상해서 수정하지 않았어요")
                    when (maj) {
                        3, 4 -> {
                            if (maj == 4 && (flags and 0x80) != 0) {
                                throw IOException("지원하지 않는 태그 형식이에요")
                            }
                            var body = ByteArray(size)
                            raf.readFully(body)
                            if (maj == 3 && (flags and 0x80) != 0) body = deunsync(body)
                            major = maj
                            parseFrames(body, maj, flags, kept)
                            oldTotal = total
                        }
                        2 -> {
                            // 아주 오래된 v2.2: 새 v2.3 태그로 바꾼다 (기존 프레임은 옮기지 않음)
                            major = 3
                            oldTotal = total
                        }
                        else -> throw IOException("지원하지 않는 태그 버전이에요")
                    }
                }
            }
        }

        // 2) 새 프레임 만들기
        val remove = HashSet<String>()
        val added = ArrayList<ByteArray>()

        fun setText(id: String, value: String?) {
            if (value == null) return
            remove.add(id)
            if (value.isNotEmpty()) added.add(textFrame(id, value, major))
        }
        setText("TIT2", ch.title)
        setText("TALB", ch.album)
        setText("TPE1", ch.artist)
        setText("TPE2", ch.albumArtist)
        setText("TCON", ch.genre)

        val no = ch.trackNo
        if (no != null) {
            remove.add("TRCK")
            if (no > 0) {
                val t = ch.trackTotal
                added.add(textFrame("TRCK", if (t != null && t > 0) "$no/$t" else "$no", major))
            }
        }

        val year = ch.year
        if (year != null) {
            if (year.isNotEmpty() && !Regex("""^\d{4}(\.\d{2}\.\d{2})?$""").matches(year)) {
                throw IOException("연도 형식이 올바르지 않아요")
            }
            remove.addAll(listOf("TDRC", "TYER", "TDAT", "TIME", "TRDA"))
            if (year.isNotEmpty()) {
                val yr = year.substring(0, 4)
                val full = year.length == 10
                if (major == 4) {
                    val v = if (full) "$yr-${year.substring(5, 7)}-${year.substring(8, 10)}" else yr
                    added.add(textFrame("TDRC", v, 4))
                } else {
                    added.add(textFrame("TYER", yr, 3))
                    if (full) added.add(textFrame("TDAT", year.substring(8, 10) + year.substring(5, 7), 3))
                }
            }
        }

        val art = ch.artJpeg
        var apic: ByteArray? = null
        if (art != null) {
            remove.add("APIC")
            val headBytes = byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0)
            apic = frame("APIC", headBytes + art, major)
        }

        // 3) 새 태그 조립: [새 텍스트 프레임][보존한 프레임][앨범아트]
        val bodyOut = ByteArrayOutputStream()
        for (f in added) bodyOut.write(f)
        for (f in kept) if (f.id !in remove) bodyOut.write(f.bytes)
        if (apic != null) bodyOut.write(apic)
        val bodyBytes = bodyOut.toByteArray()

        val tag = ByteArray(10 + bodyBytes.size)
        tag[0] = 0x49; tag[1] = 0x44; tag[2] = 0x33
        tag[3] = major.toByte()
        tag[4] = 0
        tag[5] = 0
        putSyncsafe(tag, 6, bodyBytes.size)
        bodyBytes.copyInto(tag, 10)

        // 4) 임시 파일에 쓰기 -> 검증 -> 원본과 교체
        val tmp = File(file.parentFile, "." + file.name + ".tagtmp")
        var keepTmp = false
        try {
            FileOutputStream(tmp).use { out ->
                out.write(tag)
                FileInputStream(file).use { inp ->
                    skipLong(inp, oldTotal)
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val r = inp.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                    }
                }
                out.fd.sync()
            }
            val expected = tag.size + (origLen - oldTotal)
            if (tmp.length() != expected) throw IOException("쓰기 검증 실패(파일 크기)")
            verify(tmp, ch)

            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                // 이름 바꾸기가 안 되면 내용을 덮어쓰는 방식으로 대신한다
                keepTmp = true
                FileInputStream(tmp).use { i -> FileOutputStream(file).use { o -> i.copyTo(o) } }
                keepTmp = false
            }
        } finally {
            if (!keepTmp && tmp.exists()) tmp.delete()
        }
    }

    // ------------------------------------------------------------------

    private fun parseFrames(body: ByteArray, major: Int, flags: Int, out: MutableList<RawFrame>) {
        var pos = 0
        if ((flags and 0x40) != 0) {   // 확장 헤더 건너뛰기 (우리는 쓰지 않음)
            if (body.size < 4) throw IOException("태그 구조를 알 수 없어요")
            val ext = if (major == 4) syncsafe(body, 0) else be32(body, 0) + 4
            if (ext < 0 || ext > body.size) throw IOException("태그 구조를 알 수 없어요")
            pos = ext
        }
        while (pos + 10 <= body.size) {
            if (body[pos].toInt() == 0) break   // 여기부터는 빈 공간
            val id = String(body, pos, 4, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) {
                throw IOException("태그 구조를 알 수 없어 수정하지 않았어요")
            }
            val size = if (major == 4) syncsafe(body, pos + 4) else be32(body, pos + 4)
            if (size < 0 || pos + 10L + size > body.size) {
                throw IOException("태그 구조를 알 수 없어 수정하지 않았어요")
            }
            out.add(RawFrame(id, body.copyOfRange(pos, pos + 10 + size)))
            pos += 10 + size
        }
    }

    /** 방금 쓴 임시 파일을 다시 읽어서 값이 제대로 들어갔는지 확인 */
    private fun verify(tmp: File, ch: TagChanges) {
        val t = Id3Reader.readFile(tmp)
        if (!t.format.startsWith("ID3v2")) throw IOException("검증 실패: 쓴 태그를 읽을 수 없어요")
        fun chk(name: String, expected: String?, got: String) {
            if (expected != null && expected.isNotEmpty() && expected != got) {
                throw IOException("검증 실패: $name")
            }
        }
        chk("트랙명", ch.title, t.title)
        chk("앨범", ch.album, t.album)
        chk("아티스트", ch.artist, t.artist)
        chk("앨범 아티스트", ch.albumArtist, t.albumArtist)
        chk("연도", ch.year, t.year)
        val no = ch.trackNo
        if (no != null && no > 0 && t.trackNo != no) throw IOException("검증 실패: 트랙번호")
        if (ch.artJpeg != null && !t.hasArt) throw IOException("검증 실패: 앨범아트")
    }

    private fun textFrame(id: String, text: String, major: Int): ByteArray {
        val ascii = text.all { it.code < 0x80 }
        val body: ByteArray = when {
            ascii -> byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
            major == 4 -> byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)
            else -> byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        }
        return frame(id, body, major)
    }

    private fun frame(id: String, body: ByteArray, major: Int): ByteArray {
        val out = ByteArray(10 + body.size)
        id.toByteArray(Charsets.ISO_8859_1).copyInto(out, 0)
        if (major == 4) putSyncsafe(out, 4, body.size) else putBe32(out, 4, body.size)
        body.copyInto(out, 10)
        return out
    }

    private fun syncsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun putSyncsafe(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v shr 21) and 0x7F).toByte()
        b[o + 1] = ((v shr 14) and 0x7F).toByte()
        b[o + 2] = ((v shr 7) and 0x7F).toByte()
        b[o + 3] = (v and 0x7F).toByte()
    }

    private fun putBe32(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v shr 24) and 0xFF).toByte()
        b[o + 1] = ((v shr 16) and 0xFF).toByte()
        b[o + 2] = ((v shr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    private fun deunsync(a: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(a.size)
        var i = 0
        while (i < a.size) {
            val v = a[i]
            out.write(v.toInt())
            if (v == 0xFF.toByte() && i + 1 < a.size && a[i + 1] == 0.toByte()) i += 2 else i++
        }
        return out.toByteArray()
    }

    private fun skipLong(inp: FileInputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = inp.skip(left)
            if (s > 0) {
                left -= s
            } else {
                if (inp.read() < 0) throw IOException("파일이 예상보다 짧아요")
                left--
            }
        }
    }
}

// ----------------------------------------------------------------------
// 앨범 단위 저장 (수정 화면의 "저장"이 부르는 곳)
// ----------------------------------------------------------------------

class SaveResult(val ok: Int, val failed: List<String>)

object TagSaver {

    /** 안드로이드 11 이상에서 "모든 파일 액세스"가 허용돼 있는가 */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** 트랙의 실제 파일 경로 찾기 */
    private fun resolveFile(context: Context, album: Album, track: Track): File? {
        val f = File(Environment.getExternalStorageDirectory(), album.path + track.displayName)
        if (f.isFile) return f
        try {
            context.contentResolver.query(
                track.uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val p = c.getString(0)
                    if (p != null && File(p).isFile) return File(p)
                }
            }
        } catch (e: Exception) {
            // 무시
        }
        return null
    }

    private fun buildChanges(req: EditRequest, t: Track, tags: AlbumTags): TagChanges? {
        val p = req.perTrack?.get(t.id)
        val cur = tags.perTrack[t.id]
        val ch = TagChanges(
            title = p?.title ?: req.title,
            trackNo = p?.trackNo ?: req.trackNo,
            trackTotal = p?.trackTotal ?: cur?.trackTotal?.takeIf { it > 0 },
            album = req.album,
            artist = req.artist,
            albumArtist = req.albumArtist,
            year = req.year,
            genre = req.genre,
            artJpeg = req.artJpeg
        )
        return if (ch.isEmpty()) null else ch
    }

    /**
     * 요청을 대상 트랙 파일들에 하나씩 적용한다. 한 곡이 실패해도 나머지는 계속한다.
     * onProgress 는 호출한 쪽(화면)의 스레드에서 불린다.
     */
    suspend fun saveAll(
        context: Context,
        album: Album,
        tags: AlbumTags,
        req: EditRequest,
        onProgress: (Int, Int) -> Unit
    ): SaveResult {
        val targets = req.trackIds.mapNotNull { id -> album.tracks.find { it.id == id } }
        val failed = ArrayList<String>()
        val paths = ArrayList<String>()
        var ok = 0

        for ((i, t) in targets.withIndex()) {
            val ch = buildChanges(req, t, tags)
            if (ch != null) {
                val r: Pair<String?, String?> = withContext(Dispatchers.IO) {
                    try {
                        val f = resolveFile(context, album, t) ?: throw IOException("파일 경로를 찾지 못했어요")
                        Id3Writer.write(f, ch)
                        Pair(f.absolutePath, null)
                    } catch (e: Exception) {
                        Pair(null, e.message ?: e.javaClass.simpleName)
                    }
                }
                if (r.first != null) {
                    paths.add(r.first!!)
                    ok++
                } else {
                    failed.add("${t.displayName}: ${r.second}")
                }
            }
            onProgress(i + 1, targets.size)
        }

        // 안드로이드 미디어 DB를 새 태그로 갱신하고 끝날 때까지 기다린다
        withContext(Dispatchers.IO) {
            if (paths.isNotEmpty()) {
                val latch = CountDownLatch(paths.size)
                MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
                    latch.countDown()
                }
                latch.await(30, TimeUnit.SECONDS)
            }
        }
        return SaveResult(ok, failed)
    }
}
