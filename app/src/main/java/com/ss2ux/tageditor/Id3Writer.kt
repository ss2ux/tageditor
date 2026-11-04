package com.ss2ux.tageditor

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.mutableIntStateOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 태그를 저장한 뒤 목록/썸네일을 새로 불러오게 하는 신호 (값이 바뀌면 화면들이 다시 읽는다) */
object RefreshBus {
    val tick = mutableIntStateOf(0)
}

/**
 * 외부 라이브러리 없이 직접 만든 ID3 쓰기. 결과는 항상 ID3v2.4.
 *
 * - 바꾸라고 한 항목만 바꾸고, 나머지 프레임(가사, 코멘트, 재생 횟수 등)은 그대로 보존한다.
 * - 안전하게: 새 임시 파일에 (새 태그 + 기존 오디오 데이터)를 쓰고, 검증한 뒤에만 원본과 바꾼다.
 *   중간에 실패하면 원본은 건드리지 않는다.
 * - v2.2 / v2.3 태그는 v2.4로 변환한다 (연도: TYER+TDAT -> TDRC).
 * - 파일 끝의 ID3v1은 그대로 둔다.
 */
object Id3Writer {

    private class Frame(val id: String, val flags: Int, val data: ByteArray)

    private class Existing(
        val frames: MutableList<Frame>,
        val audioStart: Long,     // 오디오 데이터가 시작되는 위치
        val hadV2: Boolean,
        val major: Int,
        val oldYear: String,      // v2.3 이하의 TYER/TDAT 에서 만든 "2026-11-04" 형태 (없으면 "")
        val oldTotal: Int         // TRCK 의 전체 트랙 수 (없으면 0)
    )

    // v2.2 (3글자) -> v2.4 (4글자). 여기 없는 v2.2 프레임은 버린다.
    private val V22 = mapOf(
        "TT1" to "TIT1", "TT2" to "TIT2", "TT3" to "TIT3", "TP1" to "TPE1", "TP2" to "TPE2",
        "TP3" to "TPE3", "TAL" to "TALB", "TRK" to "TRCK", "TPA" to "TPOS", "TCO" to "TCON",
        "TCM" to "TCOM", "TCR" to "TCOP", "TPB" to "TPUB", "TEN" to "TENC", "TLA" to "TLAN",
        "TYE" to "TYER", "TDA" to "TDAT", "TXT" to "TEXT", "TXX" to "TXXX", "COM" to "COMM",
        "ULT" to "USLT", "PIC" to "APIC", "WXX" to "WXXX", "UFI" to "UFID"
    )

    // v2.4 에서 없어진 프레임 (v2.2/2.3 에서 올 때 버림. 연도는 따로 TDRC 로 만든다)
    private val DEPRECATED = setOf("TYER", "TDAT", "TIME", "TRDA", "TORY", "TSIZ", "IPLS", "EQUA", "RVAD")

    // ------------------------------------------------------------------
    // 공개 함수: 파일 하나에 수정 요청을 적용한다. 실패하면 예외를 던진다.
    // ------------------------------------------------------------------
    fun write(file: File, req: EditRequest) {
        if (!file.isFile) throw IOException("파일을 찾을 수 없어요")
        val ex = readExisting(file)
        val frames = ex.frames

        fun drop(vararg ids: String) {
            frames.removeAll { it.id in ids }
        }

        req.title?.let { v ->
            drop("TIT2"); if (v.isNotEmpty()) frames.add(textFrame("TIT2", v))
        }
        req.album?.let { v ->
            drop("TALB"); if (v.isNotEmpty()) frames.add(textFrame("TALB", v))
        }
        req.artist?.let { v ->
            drop("TPE1"); if (v.isNotEmpty()) frames.add(textFrame("TPE1", v))
        }
        req.albumArtist?.let { v ->
            drop("TPE2"); if (v.isNotEmpty()) frames.add(textFrame("TPE2", v))
        }
        req.genre?.let { v ->
            drop("TCON"); if (v.isNotEmpty()) frames.add(textFrame("TCON", v))
        }
        req.trackNo?.let { n ->
            drop("TRCK")
            if (n >= 0) {   // 0 은 "0번 트랙"으로 저장, 지우기는 음수
                frames.add(textFrame("TRCK", if (ex.oldTotal > 0) "$n/${ex.oldTotal}" else "$n"))
            }
        }

        // 연도: 플레이어마다 읽는 프레임이 달라서 TDRC(v2.4) + TYER + TDAT(v2.3) 를 모두 써 둔다
        val y = req.year
        val iso: String? = when {
            y != null -> y.replace('.', '-')                 // "" 이면 지우기
            ex.oldYear.isNotEmpty() -> ex.oldYear            // 옛 v2.3 (TYER+TDAT)
            frames.none { it.id == "TYER" } ->               // v2.4 인데 TYER 가 없으면 TDRC 에서 만들어 준다
                frames.firstOrNull { it.id == "TDRC" && it.flags == 0 }
                    ?.let { decodeText(it.data).take(10) }
                    ?.takeIf { YEAR_RE.matches(it) }
            else -> null
        }
        if (y != null || iso != null) {
            drop("TDRC", "TYER", "TDAT", "TIME", "TRDA")
            if (!iso.isNullOrEmpty()) frames.addAll(yearFrames(iso))
        }

        req.artJpeg?.let { jpeg ->
            drop("APIC")
            frames.add(apicFrame(jpeg))
        }

        val tag = buildTag(frames)
        val tmp = File(file.parentFile, "." + file.name + ".tagedit.tmp")
        try {
            val origLen = file.length()
            RandomAccessFile(file, "r").use { src ->
                FileOutputStream(tmp).use { out ->
                    out.write(tag)
                    src.seek(ex.audioStart)
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            // 검증: 길이가 맞고, 새로 쓴 태그를 다시 읽었을 때 프레임 수와 시작 위치가 같아야 한다
            val expected = tag.size.toLong() + (origLen - ex.audioStart)
            if (tmp.length() != expected) throw IOException("저장 검증 실패 (길이)")
            val chk = readExisting(tmp)
            if (!chk.hadV2 || chk.audioStart != tag.size.toLong() || chk.frames.size != frames.size) {
                throw IOException("저장 검증 실패 (태그)")
            }
            // 원본 교체
            if (!tmp.renameTo(file)) throw IOException("파일 교체 실패")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    // ------------------------------------------------------------------
    // 기존 태그 읽기 (모든 프레임을 원본 그대로 보관)
    // ------------------------------------------------------------------
    private fun readExisting(f: File): Existing {
        return RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val frames = ArrayList<Frame>()
            val head: ByteArray? = if (len >= 10) {
                val h = ByteArray(10)
                raf.seek(0)
                raf.readFully(h)
                h
            } else null
            val valid = head != null &&
                head[0].toInt() == 0x49 && head[1].toInt() == 0x44 && head[2].toInt() == 0x33 &&
                head[3].toInt() in 2..4 &&
                (6..9).all { (head[it].toInt() and 0x80) == 0 } &&
                10L + syncsafe(head, 6) <= len

            if (!valid || head == null) {
                // ID3v2 가 없음: ID3v1 이 있으면 그 값을 v2 프레임으로 옮겨 담는다
                seedFromV1(raf, len, frames)
                return Existing(frames, 0L, false, 4, "", 0)
            }

            val major = head[3].toInt()
            val flags = head[5].toInt() and 0xFF
            val size = syncsafe(head, 6)
            val footer = if (major == 4 && (flags and 0x10) != 0) 10L else 0L
            val audioStart = minOf(10L + size + footer, len)

            val body = ByteArray(size)
            raf.seek(10)
            raf.readFully(body)

            var data = body
            if (major <= 3 && (flags and 0x80) != 0) data = deunsync(data)

            var pos = 0
            var broken = major == 2 && (flags and 0x40) != 0   // v2.2 압축 태그는 읽지 않음
            if (!broken && major >= 3 && (flags and 0x40) != 0 && data.size >= 4) {
                val ext = if (major == 4) syncsafe(data, 0) else be32(data, 0) + 4
                if (ext in 4..data.size) pos = ext else broken = true
            }

            var tyer = ""
            var tdat = ""
            var oldTotal = 0
            val hLen = if (major == 2) 6 else 10
            val idLen = if (major == 2) 3 else 4

            while (!broken && pos + hLen <= data.size) {
                if (data[pos].toInt() == 0) break   // 여기부터는 빈 공간(padding)
                val rawId = String(data, pos, idLen, Charsets.ISO_8859_1)
                if (!validId(rawId)) break
                val fsize = when (major) {
                    2 -> (u8(data, pos + 3) shl 16) or (u8(data, pos + 4) shl 8) or u8(data, pos + 5)
                    3 -> be32(data, pos + 4)
                    else -> syncsafe(data, pos + 4)
                }
                val f1 = if (major >= 3) u8(data, pos + 8) else 0
                val f2 = if (major >= 3) u8(data, pos + 9) else 0
                if (fsize < 0 || pos + hLen + fsize > data.size) break
                val fd = data.copyOfRange(pos + hLen, pos + hLen + fsize)
                pos += hLen + fsize

                var id = rawId
                var fl = 0
                var payload = fd
                var keep = true
                when (major) {
                    2 -> {
                        val mapped = V22[rawId]
                        if (mapped == null) {
                            keep = false
                        } else {
                            id = mapped
                            if (rawId == "PIC") {
                                val c = convertPic(fd)
                                if (c == null) keep = false else payload = c
                            }
                        }
                    }
                    3 -> {
                        if ((f2 and 0xC0) != 0) {
                            keep = false   // 압축/암호화된 프레임은 버림
                        } else {
                            fl = (((f1 and 0xE0) shr 1) shl 8) or (if ((f2 and 0x20) != 0) 0x40 else 0)
                        }
                    }
                    else -> fl = (f1 shl 8) or f2
                }
                if (!keep || id == "SEEK") continue

                if (major <= 3) {
                    if (id == "TYER" && fl == 0) tyer = decodeText(payload)
                    if (id == "TDAT" && fl == 0) tdat = decodeText(payload)
                    if (id in DEPRECATED) continue
                }
                if (id == "TRCK" && fl == 0) {
                    val m = Regex("""/\s*(\d+)""").find(decodeText(payload))
                    oldTotal = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                frames.add(Frame(id, fl, payload))
            }

            var oldYear = ""
            if (major <= 3) {
                val y = tyer.filter { it.isDigit() }.take(4)
                if (y.length == 4) {
                    oldYear = y
                    val d = tdat.trim()
                    if (d.length == 4 && d.all { it.isDigit() }) {
                        val dd = d.substring(0, 2).toInt()
                        val mm = d.substring(2, 4).toInt()
                        if (mm in 1..12 && dd in 1..31) {
                            oldYear = "$y-${mm.toString().padStart(2, '0')}-${dd.toString().padStart(2, '0')}"
                        }
                    }
                }
            }
            Existing(frames, audioStart, true, major, oldYear, oldTotal)
        }
    }

    /** ID3v1(파일 끝 128바이트)의 값을 v2 프레임으로 옮긴다. (장르 번호는 옮기지 않음) */
    private fun seedFromV1(raf: RandomAccessFile, len: Long, frames: MutableList<Frame>) {
        if (len < 128) return
        try {
            val t = ByteArray(128)
            raf.seek(len - 128)
            raf.readFully(t)
            if (t[0].toInt() != 0x54 || t[1].toInt() != 0x41 || t[2].toInt() != 0x47) return
            fun field(from: Int, to: Int): String {
                var e = to
                while (e > from && (t[e - 1].toInt() == 0 || t[e - 1].toInt() == 0x20)) e--
                return if (e <= from) "" else decodeLegacy(t.copyOfRange(from, e)).trim()
            }
            val title = field(3, 33)
            val artist = field(33, 63)
            val album = field(63, 93)
            val year = field(93, 97)
            if (title.isNotEmpty()) frames.add(textFrame("TIT2", title))
            if (artist.isNotEmpty()) frames.add(textFrame("TPE1", artist))
            if (album.isNotEmpty()) frames.add(textFrame("TALB", album))
            if (year.length == 4 && year.all { it.isDigit() }) frames.add(textFrame("TDRC", year))
            if (t[125].toInt() == 0 && t[126].toInt() != 0) {
                frames.add(textFrame("TRCK", (t[126].toInt() and 0xFF).toString()))
            }
        } catch (e: Exception) {
            // v1 을 못 읽어도 저장은 계속
        }
    }

    // ------------------------------------------------------------------
    // 프레임 만들기
    // ------------------------------------------------------------------

    /** 영문/숫자뿐이면 ISO-8859-1, 한글 등이 있으면 UTF-16(BOM) — 거의 모든 플레이어가 읽는다 */
    private fun textFrame(id: String, text: String): Frame {
        val data = if (text.all { it.code < 0x80 }) {
            byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
        } else {
            byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        }
        return Frame(id, 0, data)
    }

    private val YEAR_RE = Regex("""^(\d{4})(?:-(\d{2})(?:-(\d{2}))?)?$""")

    /** "2026" 또는 "2026-11-04" -> TDRC + TYER (+ TDAT "DDMM") */
    private fun yearFrames(iso: String): List<Frame> {
        val list = ArrayList<Frame>()
        list.add(textFrame("TDRC", iso))
        val m = YEAR_RE.find(iso) ?: return list
        list.add(textFrame("TYER", m.groupValues[1]))
        val mm = m.groupValues[2]
        val dd = m.groupValues[3]
        if (mm.isNotEmpty() && dd.isNotEmpty()) list.add(textFrame("TDAT", dd + mm))
        return list
    }

    /** 앞면 커버(type 3) JPEG */
    private fun apicFrame(jpeg: ByteArray): Frame {
        val bos = ByteArrayOutputStream(jpeg.size + 32)
        bos.write(0)                                              // 텍스트 인코딩
        bos.write("image/jpeg".toByteArray(Charsets.ISO_8859_1))
        bos.write(0)
        bos.write(3)                                              // Cover (front)
        bos.write(0)                                              // 설명(빈 값)
        bos.write(jpeg)
        return Frame("APIC", 0, bos.toByteArray())
    }

    /** v2.2 PIC -> v2.4 APIC 데이터 */
    private fun convertPic(d: ByteArray): ByteArray? {
        if (d.size < 6) return null
        val fmt = String(d, 1, 3, Charsets.ISO_8859_1).uppercase()
        val mime = when (fmt) {
            "JPG" -> "image/jpeg"
            "PNG" -> "image/png"
            else -> "image/" + fmt.lowercase()
        }
        val bos = ByteArrayOutputStream(d.size + 16)
        bos.write(d[0].toInt())                                   // 인코딩
        bos.write(mime.toByteArray(Charsets.ISO_8859_1))
        bos.write(0)
        bos.write(d, 4, d.size - 4)                               // 그림 종류 + 설명 + 이미지 데이터
        return bos.toByteArray()
    }

    private fun buildTag(frames: List<Frame>): ByteArray {
        var bodySize = 0
        for (f in frames) bodySize += 10 + f.data.size
        val out = ByteArrayOutputStream(bodySize + 10)
        out.write(byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0))         // "ID3" v2.4.0, 플래그 없음
        writeSyncsafe(out, bodySize)
        for (f in frames) {
            out.write(f.id.toByteArray(Charsets.ISO_8859_1))
            writeSyncsafe(out, f.data.size)
            out.write((f.flags shr 8) and 0xFF)
            out.write(f.flags and 0xFF)
            out.write(f.data)
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // 바이트/문자열 도구
    // ------------------------------------------------------------------

    private fun validId(id: String): Boolean = id.all { it in 'A'..'Z' || it in '0'..'9' }

    private fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF

    private fun be32(b: ByteArray, o: Int): Int =
        (u8(b, o) shl 24) or (u8(b, o + 1) shl 16) or (u8(b, o + 2) shl 8) or u8(b, o + 3)

    private fun syncsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun writeSyncsafe(out: ByteArrayOutputStream, n: Int) {
        out.write((n shr 21) and 0x7F)
        out.write((n shr 14) and 0x7F)
        out.write((n shr 7) and 0x7F)
        out.write(n and 0x7F)
    }

    private fun deunsync(a: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(a.size)
        var i = 0
        while (i < a.size) {
            val v = a[i].toInt()
            o.write(v)
            if (v == -1 && i + 1 < a.size && a[i + 1].toInt() == 0) i += 2 else i++
        }
        return o.toByteArray()
    }

    /** 텍스트 프레임 내용을 문자열로 (연도/트랙 전체 수처럼 숫자만 볼 때 쓰는 간단한 해석) */
    private fun decodeText(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val enc = data[0].toInt() and 0xFF
        val body = data.copyOfRange(1, data.size)
        val s = when (enc) {
            1 -> decode16(body)
            2 -> String(body, Charsets.UTF_16BE)
            3 -> String(body, Charsets.UTF_8)
            else -> String(body, Charsets.ISO_8859_1)
        }
        return s.substringBefore('\u0000').trim()
    }

    private fun decode16(b: ByteArray): String {
        if (b.size >= 2) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        }
        return String(b, Charsets.UTF_16LE)
    }

    private fun strict(cs: Charset, b: ByteArray): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** ID3v1 문자열: UTF-8 -> CP949(한글 옛 태그) -> ISO-8859-1 순서로 시도 */
    private fun decodeLegacy(b: ByteArray): String {
        if (b.all { it.toInt() >= 0 }) return String(b, Charsets.ISO_8859_1)
        strict(Charsets.UTF_8, b)?.let { return it }
        for (name in listOf("x-windows-949", "MS949", "EUC-KR")) {
            try {
                strict(Charset.forName(name), b)?.let { return it }
            } catch (e: Exception) {
                // 다음 이름 시도
            }
        }
        return String(b, Charsets.ISO_8859_1)
    }
}

// ----------------------------------------------------------------------
// 저장 실행: 권한 확인, 앨범의 여러 곡에 적용, 미디어 DB 갱신
// ----------------------------------------------------------------------

class SaveResult(val ok: Int, val failed: List<String>)

object TagSaver {

    /** Download 폴더의 파일을 직접 고치려면 "모든 파일 액세스" 권한이 필요하다 (안드로이드 11+) */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    fun openAllFilesSettings(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
            )
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                // 설정 화면을 못 열면 사용자가 직접 찾아가야 함
            }
        }
    }

    /** 시간이 걸리므로 반드시 백그라운드에서 호출. onProgress(끝난 곡 수, 전체 곡 수) */
    fun save(context: Context, album: Album, req: EditRequest, onProgress: (Int, Int) -> Unit): SaveResult {
        val base = Environment.getExternalStorageDirectory()
        val byId = album.tracks.associateBy { it.id }
        val targets = req.trackIds.mapNotNull { byId[it] }
        val done = ArrayList<String>()
        val failed = ArrayList<String>()

        targets.forEachIndexed { i, t ->
            val f = File(base, album.path + t.displayName)
            try {
                if (!f.name.endsWith(".mp3", ignoreCase = true)) {
                    throw IOException("mp3 파일만 수정할 수 있어요")
                }
                Id3Writer.write(f, req)
                done.add(f.absolutePath)
            } catch (e: Throwable) {
                failed.add("${t.displayName} (${e.message ?: e.javaClass.simpleName})")
            }
            onProgress(i + 1, targets.size)
        }

        // 안드로이드 미디어 DB를 새 태그로 갱신 (끝날 때까지 기다림)
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
