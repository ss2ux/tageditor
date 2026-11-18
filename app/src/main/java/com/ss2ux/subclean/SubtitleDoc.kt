package com.ss2ux.subclean

enum class Kind(val label: String) { SRT("SRT"), VTT("VTT"), LRC("LRC"), TXT("TXT") }

/**
 * 파일의 한 줄.
 *  - prefix   : 손대지 않는 앞부분 (LRC 의 [00:12.34] 시간 태그)
 *  - body     : 다듬기 대상 본문
 *  - eol      : 원래 줄바꿈 문자 (\r\n, \n, \r, 마지막 줄이면 "")
 *  - editable : false 면 번호/타임스탬프/빈 줄/헤더 등이라 절대 건드리지 않는다
 *  - startMs  : 이 줄(큐)의 시작 시각(ms). 오디오 시크용. 없으면 null
 */
class Line(
    val prefix: String,
    val body: String,
    val eol: String,
    val editable: Boolean,
    val startMs: Long? = null
)

/** 파일 전체를 "줄 단위 + 줄바꿈 그대로" 로 들고 있어서, 본문만 바꿔도 나머지는 한 글자도 달라지지 않는다 */
class Doc(val kind: Kind, val lines: List<Line>) {
    val editableCount: Int get() = lines.count { it.editable }

    /** newBodies[i] 는 lines[i] 의 새 본문 (같은 길이). 줄바꿈과 prefix 는 원본 그대로 */
    fun render(newBodies: List<String>): String {
        val sb = StringBuilder()
        for (i in lines.indices) {
            val ln = lines[i]
            sb.append(ln.prefix).append(newBodies[i]).append(ln.eol)
        }
        return sb.toString()
    }

    companion object {
        private val EOL = Regex("\r\n|\n|\r")
        private val LRC_LINE = Regex("""^((?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?\])+)(.*)$""")
        private val TIMING = Regex(
            """^\s*(?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3}\s*-->\s*(?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3}.*$"""
        )
        // SRT/VTT 시작 시각만 뽑기: 00:12.345 또는 1:00:12,345
        private val START_TIME = Regex(
            """^\s*(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})"""
        )
        private val LRC_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")

        fun detectKind(fileName: String, text: String): Kind {
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "srt" -> return Kind.SRT
                "vtt" -> return Kind.VTT
                "lrc" -> return Kind.LRC
            }
            // .txt 등은 내용을 보고 판단
            val head = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            if (head.startsWith("WEBVTT")) return Kind.VTT
            val rows = text.split(EOL)
            if (rows.any { TIMING.matches(it) }) return Kind.SRT
            if (rows.any { LRC_LINE.matches(it) }) return Kind.LRC
            return Kind.TXT
        }

        /** "1:23:45.678" / "00:12,340" 형태 → ms */
        fun parseClockToMs(raw: String): Long? {
            val m = START_TIME.find(raw) ?: return null
            val hours = m.groupValues[1].toLongOrNull() ?: 0L
            val mins = m.groupValues[2].toLongOrNull() ?: return null
            val secs = m.groupValues[3].toLongOrNull() ?: return null
            val frac = m.groupValues[4]
            val ms = when {
                frac.length >= 3 -> frac.take(3).toLongOrNull() ?: 0L
                frac.length == 2 -> (frac.toLongOrNull() ?: 0L) * 10
                frac.length == 1 -> (frac.toLongOrNull() ?: 0L) * 100
                else -> 0L
            }
            return ((hours * 60 + mins) * 60 + secs) * 1000 + ms
        }

        /** LRC prefix 에서 첫 태그 시각 */
        fun parseLrcStartMs(prefix: String): Long? {
            val m = LRC_TAG.find(prefix) ?: return null
            val mins = m.groupValues[1].toLongOrNull() ?: return null
            val secs = m.groupValues[2].toLongOrNull() ?: return null
            val frac = m.groupValues[3]
            val ms = when {
                frac.isEmpty() -> 0L
                frac.length >= 3 -> frac.take(3).toLongOrNull() ?: 0L
                frac.length == 2 -> (frac.toLongOrNull() ?: 0L) * 10
                else -> (frac.toLongOrNull() ?: 0L) * 100
            }
            return (mins * 60 + secs) * 1000 + ms
        }

        fun formatMs(ms: Long): String {
            val totalSec = ms / 1000
            val m = totalSec / 60
            val s = totalSec % 60
            val frac = (ms % 1000) / 10
            return "%d:%02d.%02d".format(m, s, frac)
        }

        fun parse(fileName: String, text: String): Doc {
            val clean = text.removePrefix("\uFEFF")
            val kind = detectKind(fileName, clean)

            // 줄과 줄바꿈 문자를 함께 쪼갠다
            val raw = ArrayList<Pair<String, String>>()
            var start = 0
            for (m in EOL.findAll(clean)) {
                raw.add(clean.substring(start, m.range.first) to m.value)
                start = m.range.last + 1
            }
            if (start < clean.length) raw.add(clean.substring(start) to "")

            val lines = ArrayList<Line>(raw.size)
            when (kind) {
                Kind.TXT -> for ((t, eol) in raw) lines.add(Line("", t, eol, t.isNotBlank(), null))
                Kind.LRC -> for ((t, eol) in raw) {
                    val m = LRC_LINE.matchEntire(t)
                    if (m != null && m.groupValues[2].isNotBlank()) {
                        val prefix = m.groupValues[1]
                        lines.add(Line(prefix, m.groupValues[2], eol, true, parseLrcStartMs(prefix)))
                    } else {
                        lines.add(Line("", t, eol, false, null))   // [ti:..] [ar:..] 같은 정보 줄, 빈 줄
                    }
                }
                Kind.SRT, Kind.VTT -> {
                    var inCue = false
                    var inNote = false
                    var cueStartMs: Long? = null
                    for ((t, eol) in raw) {
                        var editable = false
                        var startMs: Long? = null
                        if (t.isBlank()) {
                            inCue = false
                            inNote = false
                            cueStartMs = null
                        } else if (inNote) {
                            // VTT NOTE / STYLE / REGION 블록 안
                        } else if (inCue) {
                            editable = true
                            startMs = cueStartMs
                        } else if (TIMING.matches(t)) {
                            inCue = true
                            cueStartMs = parseClockToMs(t)
                        } else if (kind == Kind.VTT &&
                            (t.startsWith("NOTE") || t.startsWith("STYLE") || t.startsWith("REGION"))
                        ) {
                            inNote = true
                        }
                        lines.add(Line("", t, eol, editable, startMs))
                    }
                }
            }
            return Doc(kind, lines)
        }
    }
}
