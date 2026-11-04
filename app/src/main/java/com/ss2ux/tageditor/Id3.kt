package com.ss2ux.tageditor

import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** mp3 파일 하나에서 읽은 태그 값 (없는 항목은 빈 문자열 / 0) */
data class Id3Tag(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: String = "",        // 화면 표시용: "2024" 또는 "2024.03.15"
    val genre: String = "",
    val trackNo: Int = 0,
    val trackTotal: Int = 0,
    val disc: String = "",        // 예: "1/2"
    val hasArt: Boolean = false,  // 앨범아트(APIC) 포함 여부
    val artBytes: Int = 0,        // 앨범아트 프레임 크기(바이트)
    val format: String = "없음",   // "ID3v2.3", "ID3v2.4", "ID3v1" 등
    val hasTrackNo: Boolean = false  // 트랙번호가 태그에 있는지 (0 도 "있음")
)

/**
 * 외부 라이브러리 없이 직접 만든 ID3 읽기.
 * - ID3v2.2 / 2.3 / 2.4 지원, ID3v2가 없으면 파일 끝의 ID3v1을 읽는다.
 * - 파일 앞부분의 태그 영역만 읽고, 앨범아트 이미지 데이터는 읽지 않고 건너뛴다(빠름).
 * - 한글이 ISO-8859-1로 잘못 표시된 옛날 태그(CP949)도 자동으로 알아본다.
 */
object Id3Reader {

    private const val MAX_TEXT_FRAME = 1 shl 20   // 텍스트 프레임은 1MB까지만 읽음

    private val WANTED = setOf(
        "TIT2", "TPE1", "TALB", "TPE2", "TDRC", "TYER", "TDAT", "TCON", "TRCK", "TPOS"
    )

    // ID3v2.2 (3글자) -> v2.3/2.4 (4글자) 이름 변환
    private val V22_NAMES = mapOf(
        "TT2" to "TIT2", "TP1" to "TPE1", "TAL" to "TALB", "TP2" to "TPE2",
        "TYE" to "TYER", "TDA" to "TDAT", "TCO" to "TCON", "TRK" to "TRCK",
        "TPA" to "TPOS", "PIC" to "APIC"
    )

    private val GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop",
        "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock",
        "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack",
        "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance",
        "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop",
        "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial", "Electronic",
        "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40",
        "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave",
        "Psychedelic", "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk",
        "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock"
    )

    private val cp949: Charset? by lazy {
        var found: Charset? = null
        for (name in listOf("x-windows-949", "windows-949", "MS949", "EUC-KR")) {
            try {
                found = Charset.forName(name)
                break
            } catch (e: Exception) {
                // 다음 이름 시도
            }
        }
        found
    }

    private class Builder {
        var title = ""
        var artist = ""
        var album = ""
        var albumArtist = ""
        var tdrc = ""
        var tyer = ""
        var tdat = ""
        var genre = ""
        var trck = ""
        var tpos = ""
        var year = ""       // ID3v1 연도용
        var trackNo1 = 0    // ID3v1 트랙번호용
        var hasArt = false
        var artBytes = 0
        var format = "없음"
    }

    // ------------------------------------------------------------------
    // 공개 함수
    // ------------------------------------------------------------------

    /** 파일 하나의 태그를 읽는다. 실패하면 format = "읽기 실패" 인 빈 태그를 돌려준다. */
    fun read(context: Context, uri: android.net.Uri): Id3Tag {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return Id3Tag(format = "읽기 실패")
            val length = pfd.statSize
            // AutoCloseInputStream: 닫을 때 pfd도 같이 안전하게 닫아 준다
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { fis ->
                readFrom(fis, length)
            }
        } catch (e: Exception) {
            Id3Tag(format = "읽기 실패")
        }
    }

    // ------------------------------------------------------------------
    // 읽기 본체
    // ------------------------------------------------------------------

    private fun readFrom(fis: FileInputStream, length: Long): Id3Tag {
        val b = Builder()
        var foundV2 = false

        val inp = BufferedInputStream(fis, 32 * 1024)
        val head = readFully(inp, 10)
        if (head != null &&
            head[0].toInt() == 0x49 && head[1].toInt() == 0x44 && head[2].toInt() == 0x33
        ) {
            val major = head[3].toInt()
            val flags = head[5].toInt() and 0xFF
            val size = syncsafe(head, 6)
            if (major in 2..4 && size > 0 && size.toLong() <= length) {
                foundV2 = true
                b.format = "ID3v2.$major"
                try {
                    parseV2(inp, major, flags, size, b)
                } catch (e: Exception) {
                    // 일부만 읽혀도 그때까지 읽은 값은 사용
                }
            }
        }

        // ID3v2가 없을 때만 파일 끝의 ID3v1(128바이트)을 읽는다
        if (!foundV2 && length >= 128) {
            try {
                fis.channel.position(length - 128)
                val t = readFully(fis, 128)
                if (t != null && t[0].toInt() == 0x54 && t[1].toInt() == 0x41 && t[2].toInt() == 0x47) {
                    parseV1(t, b)
                    b.format = "ID3v1"
                }
            } catch (e: Exception) {
                // 무시
            }
        }

        return build(b)
    }

    private fun parseV2(src: InputStream, major: Int, flags: Int, size: Int, b: Builder) {
        var inp: InputStream = src
        var remaining = size

        // v2.2: 0x40 = 압축 (지원 안 함), v2.2/2.3: 0x80 = 태그 전체 unsynchronization
        if (major == 2 && (flags and 0x40) != 0) return
        if (major <= 3 && (flags and 0x80) != 0) {
            val raw = readFully(src, size) ?: return
            val data = deunsync(raw)
            inp = ByteArrayInputStream(data)
            remaining = data.size
        }

        // 확장 헤더 건너뛰기 (v2.3/2.4)
        if (major >= 3 && (flags and 0x40) != 0) {
            val h = readFully(inp, 4) ?: return
            remaining -= 4
            val extSize = if (major == 4) syncsafe(h, 0) - 4 else be32(h, 0)
            if (extSize < 0 || extSize > remaining) return
            if (!skipFully(inp, extSize)) return
            remaining -= extSize
        }

        val idLen = if (major == 2) 3 else 4
        val hLen = if (major == 2) 6 else 10

        while (remaining >= hLen) {
            val h = readFully(inp, hLen) ?: return
            remaining -= hLen
            if (h[0].toInt() == 0) return   // 여기부터는 빈 공간(padding)

            val rawId = String(h, 0, idLen, Charsets.ISO_8859_1)
            val fsize = when (major) {
                2 -> ((h[3].toInt() and 0xFF) shl 16) or ((h[4].toInt() and 0xFF) shl 8) or (h[5].toInt() and 0xFF)
                3 -> be32(h, 4)
                else -> syncsafe(h, 4)
            }
            val formatFlags = if (major >= 3) (h[9].toInt() and 0xFF) else 0
            if (fsize < 0 || fsize > remaining) return
            remaining -= fsize

            val key = if (major == 2) (V22_NAMES[rawId] ?: rawId) else rawId

            if (key == "APIC") {
                b.hasArt = true
                b.artBytes = fsize
                if (!skipFully(inp, fsize)) return
            } else if (key in WANTED && fsize in 1..MAX_TEXT_FRAME) {
                val data = readFully(inp, fsize) ?: return
                handleTextFrame(key, data, major, formatFlags, b)
            } else {
                if (!skipFully(inp, fsize)) return
            }
        }
    }

    private fun handleTextFrame(key: String, data: ByteArray, major: Int, formatFlags: Int, b: Builder) {
        var body = data
        var off = 0
        if (major == 4) {
            if ((formatFlags and 0x0C) != 0) return           // 압축/암호화된 프레임은 건너뜀
            if ((formatFlags and 0x02) != 0) body = deunsync(body)
            if ((formatFlags and 0x40) != 0) off += 1         // 그룹 식별 바이트
            if ((formatFlags and 0x01) != 0) off += 4         // 데이터 길이 표시
        } else if (major == 3) {
            if ((formatFlags and 0xC0) != 0) return           // 압축/암호화
            if ((formatFlags and 0x20) != 0) off += 1         // 그룹 식별 바이트
        }
        if (off >= body.size) return

        val enc = body[off].toInt() and 0xFF
        val strings = decodeStrings(enc, body, off + 1)
        if (strings.isEmpty()) return
        val first = strings[0]
        val joined = strings.joinToString("/")

        when (key) {
            "TIT2" -> if (b.title.isEmpty()) b.title = joined
            "TPE1" -> if (b.artist.isEmpty()) b.artist = joined
            "TALB" -> if (b.album.isEmpty()) b.album = joined
            "TPE2" -> if (b.albumArtist.isEmpty()) b.albumArtist = joined
            "TDRC" -> if (b.tdrc.isEmpty()) b.tdrc = first
            "TYER" -> if (b.tyer.isEmpty()) b.tyer = first
            "TDAT" -> if (b.tdat.isEmpty()) b.tdat = first
            "TRCK" -> if (b.trck.isEmpty()) b.trck = first
            "TPOS" -> if (b.tpos.isEmpty()) b.tpos = first
            "TCON" -> if (b.genre.isEmpty()) {
                b.genre = strings.map { parseGenre(it) }
                    .filter { it.isNotBlank() }
                    .joinToString("/")
            }
        }
    }

    private fun parseV1(t: ByteArray, b: Builder) {
        fun field(from: Int, to: Int): String {
            var e = to
            while (e > from && (t[e - 1].toInt() == 0 || t[e - 1].toInt() == 0x20)) e--
            return if (e <= from) "" else decodeLegacy(t, from, e).trim()
        }
        b.title = field(3, 33)
        b.artist = field(33, 63)
        b.album = field(63, 93)
        b.year = field(93, 97)
        if (t[125].toInt() == 0 && t[126].toInt() != 0) {
            b.trackNo1 = t[126].toInt() and 0xFF
        }
        val g = t[127].toInt() and 0xFF
        b.genre = GENRES.getOrNull(g) ?: ""
    }

    private fun build(b: Builder): Id3Tag {
        // 연도: v2.4 TDRC 우선, 없으면 v2.3 TYER(+TDAT), 없으면 v1
        val year = when {
            b.tdrc.isNotEmpty() -> normalizeDate(b.tdrc)
            b.tyer.isNotEmpty() -> {
                val y = normalizeDate(b.tyer)
                val d = b.tdat.trim()
                // TDAT 은 "DDMM" 4자리
                if (y.length == 4 && d.length == 4 && d.all { it.isDigit() }) {
                    val dd = d.substring(0, 2)
                    val mm = d.substring(2, 4)
                    if (mm.toInt() in 1..12 && dd.toInt() in 1..31) "$y.$mm.$dd" else y
                } else y
            }
            else -> normalizeDate(b.year)
        }

        var trackNo = 0
        var trackTotal = 0
        val m = Regex("""^\s*(\d+)(?:\s*/\s*(\d+))?""").find(b.trck)
        if (m != null) {
            trackNo = m.groupValues[1].toIntOrNull() ?: 0
            trackTotal = m.groupValues[2].toIntOrNull() ?: 0
        } else if (b.trackNo1 > 0) {
            trackNo = b.trackNo1
        }

        return Id3Tag(
            title = b.title,
            artist = b.artist,
            album = b.album,
            albumArtist = b.albumArtist,
            year = year,
            genre = b.genre,
            trackNo = trackNo,
            trackTotal = trackTotal,
            disc = b.tpos,
            hasArt = b.hasArt,
            artBytes = b.artBytes,
            format = b.format,
            hasTrackNo = m != null || trackNo > 0
        )
    }

    // ------------------------------------------------------------------
    // 문자열 처리
    // ------------------------------------------------------------------

    /** "2024-03-15", "2024.3.5", "20240315", "2024" 등을 "2024.03.15" / "2024" 로 통일 */
    fun normalizeDate(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        Regex("""^(\d{4})(\d{2})(\d{2})$""").find(s)?.let {
            return "${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}"
        }
        val m = Regex("""^(\d{4})(?:[-./](\d{1,2})(?:[-./](\d{1,2}))?)?""").find(s) ?: return s
        val y = m.groupValues[1]
        val mo = m.groupValues[2]
        val d = m.groupValues[3]
        return when {
            mo.isEmpty() -> y
            d.isEmpty() -> "$y.${mo.padStart(2, '0')}"
            else -> "$y.${mo.padStart(2, '0')}.${d.padStart(2, '0')}"
        }
    }

    /** "(13)Pop", "(13)", "13", "Pop" -> 장르 이름 */
    private fun parseGenre(raw: String): String {
        val s = raw.trim()
        val m = Regex("""^\((\d{1,3})\)(.*)$""").find(s)
        if (m != null) {
            val rest = m.groupValues[2].trim()
            if (rest.isNotEmpty()) return rest
            return GENRES.getOrNull(m.groupValues[1].toInt()) ?: ""
        }
        if (s.isNotEmpty() && s.all { it.isDigit() }) {
            val n = s.toIntOrNull()
            return (if (n != null) GENRES.getOrNull(n) else null) ?: s
        }
        if (s.startsWith("(") && s.endsWith(")")) return ""   // (RX), (CR) 같은 특수값
        return s
    }

    private fun decodeStrings(enc: Int, body: ByteArray, start: Int): List<String> {
        if (start >= body.size) return emptyList()
        val data = body.copyOfRange(start, body.size)
        val out = ArrayList<String>()

        if (enc == 1 || enc == 2) {
            // UTF-16: 끝 표시는 00 00 (2바이트 단위로 확인)
            var i = 0
            var segStart = 0
            while (i + 1 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                    out.add(utf16(data, segStart, i, enc))
                    segStart = i + 2
                }
                i += 2
            }
            if (segStart < data.size) out.add(utf16(data, segStart, data.size, enc))
        } else {
            var segStart = 0
            for (i in data.indices) {
                if (data[i].toInt() == 0) {
                    out.add(decodeBytes(enc, data, segStart, i))
                    segStart = i + 1
                }
            }
            if (segStart < data.size) out.add(decodeBytes(enc, data, segStart, data.size))
        }

        return out.map { it.replace("\uFEFF", "").trim() }.filter { it.isNotEmpty() }
    }

    private fun decodeBytes(enc: Int, data: ByteArray, from: Int, to: Int): String {
        if (to <= from) return ""
        return if (enc == 3) String(data, from, to - from, Charsets.UTF_8)
        else decodeLegacy(data, from, to)
    }

    private fun utf16(data: ByteArray, from: Int, to: Int, enc: Int): String {
        var s = from
        var le = (enc == 1)   // BOM 없는 enc=1 은 보통 윈도우 방식(LE)
        if (to - s >= 2) {
            val b0 = data[s].toInt() and 0xFF
            val b1 = data[s + 1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) {
                le = true
                s += 2
            } else if (b0 == 0xFE && b1 == 0xFF) {
                le = false
                s += 2
            } else if (enc == 2) {
                le = false
            }
        }
        if (to <= s) return ""
        return String(data, s, to - s, if (le) Charsets.UTF_16LE else Charsets.UTF_16BE)
    }

    /**
     * enc=0 (ISO-8859-1 표시) 이지만 실제로는 UTF-8 이거나 한글 CP949 인 경우가 많아서
     * 영어 전용이면 그대로, 아니면 UTF-8 -> CP949 -> ISO-8859-1 순으로 시도한다.
     */
    private fun decodeLegacy(bytes: ByteArray, from: Int, to: Int): String {
        var ascii = true
        for (i in from until to) {
            if (bytes[i].toInt() < 0) {
                ascii = false
                break
            }
        }
        if (ascii) return String(bytes, from, to - from, Charsets.ISO_8859_1)
        return strictDecode(Charsets.UTF_8, bytes, from, to)
            ?: cp949?.let { strictDecode(it, bytes, from, to) }
            ?: String(bytes, from, to - from, Charsets.ISO_8859_1)
    }

    private fun strictDecode(cs: Charset, bytes: ByteArray, from: Int, to: Int): String? {
        return try {
            cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, from, to - from))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    // ------------------------------------------------------------------
    // 바이트 도우미
    // ------------------------------------------------------------------

    private fun syncsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    /** unsynchronization 해제: FF 00 -> FF */
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

    private fun readFully(inp: InputStream, n: Int): ByteArray? {
        if (n < 0) return null
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = inp.read(buf, got, n - got)
            if (r < 0) return null
            got += r
        }
        return buf
    }

    private fun skipFully(inp: InputStream, n: Int): Boolean {
        var left = n.toLong()
        while (left > 0) {
            val s = inp.skip(left)
            if (s > 0) {
                left -= s
            } else {
                if (inp.read() < 0) return false
                left--
            }
        }
        return true
    }
}

// ----------------------------------------------------------------------
// 앨범 단위로 모아 보기
// ----------------------------------------------------------------------

/** 앨범 전체에서 한 항목의 값. mixed = 곡마다 값이 다르거나 일부 곡만 값이 있음 */
data class Agg(val value: String, val mixed: Boolean)

class AlbumTags(
    val perTrack: Map<Long, Id3Tag>,   // 트랙 id -> 그 트랙의 태그
    val album: Agg,
    val artist: Agg,
    val albumArtist: Agg,
    val year: Agg,
    val genre: Agg,
    val artCount: Int,                 // 앨범아트가 들어 있는 곡 수
    val total: Int,                    // 전체 곡 수
    val elapsedMs: Long                // 태그 읽는 데 걸린 시간
)

private fun aggregate(values: List<String>): Agg {
    val nonBlank = values.filter { it.isNotBlank() }
    val distinct = nonBlank.distinct()
    val mixed = distinct.size > 1 || (distinct.isNotEmpty() && nonBlank.size != values.size)
    val common = nonBlank.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""
    return Agg(common, mixed)
}

/** 앨범 상세/수정 화면에서 쓰는 태그 로더. 파일을 읽는 건 이 화면을 열 때뿐이다. */
object AlbumTagLoader {
    private val cache = HashMap<String, AlbumTags>()

    private fun cacheKey(a: Album): String =
        a.key + "|" + a.tracks.joinToString(",") { it.id.toString() }

    fun peek(album: Album): AlbumTags? = synchronized(cache) { cache[cacheKey(album)] }

    /** 태그를 수정한 뒤(6단계)에는 반드시 호출해서 옛날 값을 버린다 */
    fun invalidate() = synchronized(cache) { cache.clear() }

    suspend fun load(context: Context, album: Album): AlbumTags = withContext(Dispatchers.IO) {
        peek(album)?.let { return@withContext it }

        val t0 = System.nanoTime()
        val per = LinkedHashMap<Long, Id3Tag>()
        for (t in album.tracks) {
            per[t.id] = Id3Reader.read(context, t.uri)
        }
        val tags = per.values.toList()
        val result = AlbumTags(
            perTrack = per,
            album = aggregate(tags.map { it.album }),
            artist = aggregate(tags.map { it.artist }),
            albumArtist = aggregate(tags.map { it.albumArtist }),
            year = aggregate(tags.map { it.year }),
            genre = aggregate(tags.map { it.genre }),
            artCount = tags.count { it.hasArt },
            total = tags.size,
            elapsedMs = (System.nanoTime() - t0) / 1_000_000
        )
        synchronized(cache) {
            if (cache.size > 30) cache.clear()
            cache[cacheKey(album)] = result
        }
        result
    }
}
