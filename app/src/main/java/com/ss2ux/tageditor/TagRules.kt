package com.ss2ux.tageditor

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ======================================================================
// 치환 규칙 (자막 다듬기 앱의 규칙 기능을 태그 편집기용으로 옮긴 것)
//  - 찾을 말 -> 바꿀 말, 정규식 옵션, 켜기/끄기
//  - 일반 규칙은 긴 말 우선으로 한 번에 적용, 정규식 규칙은 그 뒤에 목록 순서대로 적용
//  - 결과가 안 바뀔 때까지 반복 (최대 10번)
//  - 바뀐 말 뒤의 조사를 받침에 맞게 보정 (설정으로 끌 수 있음)
//  - 제외 단어: 그 말이 들어 있는 부분은 규칙이 건드리지 않음
//  - 저장할 때 입력한 값(트랙명/앨범/아티스트/앨범 아티스트/장르)에 적용하고, 바뀐 내용을 알린다
// ======================================================================

data class TagRule(
    val id: Long,
    val find: String,
    val replace: String,
    val regex: Boolean = false,
    val enabled: Boolean = true
)

/** 규칙 목록을 실제 글에 적용하는 엔진 (화면과 무관한 순수 로직) */
class TagRuleEngine(
    rules: List<TagRule>,
    private val fixJosa: Boolean,
    protect: List<String> = emptyList()
) {
    private val plainMap = HashMap<String, String>()
    private var plainRegex: Regex? = null
    private val regexRules = ArrayList<Pair<Regex, String>>()
    private val broken = HashSet<Int>()

    // 제외 단어: 치환 전에 칸막이 글자로 바꿔 두었다가 마지막에 되돌린다
    private val protectRe: Regex? = protect
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }
        .takeIf { it.isNotEmpty() }
        ?.let { w -> Regex(w.joinToString("|") { Regex.escape(it) }) }
    private val PUA = 0xE000
    private val PUA_MAX = 6000

    val hasRules: Boolean

    init {
        for (r in rules) {
            if (!r.enabled || r.find.isEmpty()) continue
            if (r.regex) {
                try {
                    regexRules.add(Regex(HangulClass.expand(r.find)) to r.replace)
                } catch (e: Exception) {
                    // 잘못된 정규식은 건너뜀
                }
            } else if (r.find != r.replace) {
                plainMap[r.find] = r.replace   // 같은 찾을 말이 여러 번이면 아래 줄이 이긴다
            }
        }
        if (plainMap.isNotEmpty()) {
            val alt = plainMap.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
            plainRegex = Regex(alt)
        }
        hasRules = plainRegex != null || regexRules.isNotEmpty()
    }

    private fun replacePlain(s: String, re: Regex): String {
        val sb = StringBuilder(s.length + 16)
        var pos = 0
        while (true) {
            val m = re.find(s, pos) ?: break
            sb.append(s, pos, m.range.first)
            val repl = plainMap[m.value] ?: m.value
            sb.append(repl)
            var next = m.range.last + 1
            if (fixJosa && repl != m.value) {
                val j = Josa.fix(s, next, repl)
                if (j != null) {
                    sb.append(j.first)
                    next = j.second
                }
            }
            pos = next
        }
        if (pos < s.length) sb.append(s, pos, s.length)
        return sb.toString()
    }

    fun clean(text: String): String {
        if ((!hasRules && protectRe == null) || text.isEmpty()) return text
        var s = text
        val slots = ArrayList<String>()

        // 제외 단어 보호
        if (protectRe != null && s.none { it.code in PUA..(PUA + PUA_MAX) }) {
            s = protectRe.replace(s) { m ->
                if (slots.size >= PUA_MAX) m.value
                else {
                    val token = (PUA + slots.size).toChar().toString()
                    slots.add(m.value)
                    token
                }
            }
        }

        if (hasRules) {
            var passes = 0
            do {
                val prev = s
                plainRegex?.let { re -> s = replacePlain(s, re) }
                for ((i, pair) in regexRules.withIndex()) {
                    if (i in broken) continue
                    try {
                        s = pair.first.replace(s, pair.second)
                    } catch (e: Exception) {
                        broken.add(i)   // 바꿀 말의 $1 같은 참조가 잘못된 경우
                    }
                }
                passes++
            } while (s != prev && passes < MAX_PASSES)
        }

        // 제외 단어 복원
        if (slots.isNotEmpty()) {
            val sb = StringBuilder(s.length + 16)
            for (ch in s) {
                val code = ch.code
                if (code in PUA until (PUA + slots.size)) sb.append(slots[code - PUA])
                else sb.append(ch)
            }
            s = sb.toString()
        }
        return s
    }

    companion object {
        const val MAX_PASSES = 10
    }
}

/** 규칙 저장소 + 저장 시 적용 */
object TagRules {
    private fun file(ctx: Context) = File(ctx.filesDir, "tag_rules.json")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tagrules", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<TagRule> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                TagRule(
                    id = o.optLong("id", i.toLong() + 1),
                    find = o.optString("find"),
                    replace = o.optString("replace"),
                    regex = o.optBoolean("regex", false),
                    enabled = o.optBoolean("enabled", true)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, rules: List<TagRule>) {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject().put("id", r.id).put("find", r.find).put("replace", r.replace)
                    .put("regex", r.regex).put("enabled", r.enabled)
            )
        }
        val f = file(ctx)
        val tmp = File(f.parentFile, "tag_rules.json.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.writeText(arr.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun fixJosa(ctx: Context): Boolean = prefs(ctx).getBoolean("fixJosa", true)
    fun setFixJosa(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("fixJosa", v).apply()

    /** 제외 단어 (한 줄에 하나, 저장은 줄바꿈으로 이어 붙인 문자열) */
    fun protect(ctx: Context): List<String> =
        (prefs(ctx).getString("protect", "") ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun setProtect(ctx: Context, words: List<String>) {
        val clean = words.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        prefs(ctx).edit().putString("protect", clean.joinToString("\n")).apply()
    }

    /**
     * 똑같은 규칙(찾을 말·바꿀 말·정규식 여부)은 맨 앞 하나만 남긴다.
     * 하나라도 켜져 있었으면 켜진 채로 남긴다. 반환: (정리된 목록, 제거된 개수)
     */
    fun dedupe(list: List<TagRule>): Pair<List<TagRule>, Int> {
        data class Key(val f: String, val r: String, val x: Boolean)
        val firstIdx = LinkedHashMap<Key, Int>()
        val out = ArrayList<TagRule>(list.size)
        for (r in list) {
            val k = Key(r.find, r.replace, r.regex)
            val at = firstIdx[k]
            if (at == null) {
                firstIdx[k] = out.size
                out.add(r)
            } else if (r.enabled && !out[at].enabled) {
                out[at] = out[at].copy(enabled = true)
            }
        }
        return out to (list.size - out.size)
    }

    /**
     * 저장할 값(트랙명/앨범/아티스트/앨범 아티스트/장르)에 규칙을 적용한다.
     * 돌려주는 것: (바뀐 요청, 바뀐 내용 설명 목록). 바뀐 게 없으면 목록이 비어 있다.
     */
    fun applyTo(ctx: Context, req: EditRequest): Pair<EditRequest, List<String>> {
        val engine = TagRuleEngine(load(ctx), fixJosa(ctx), protect(ctx))
        if (!engine.hasRules && protect(ctx).isEmpty()) return req to emptyList()
        val changes = ArrayList<String>()
        fun conv(label: String, v: String?): String? {
            if (v == null || v.isEmpty()) return v
            val n = engine.clean(v)
            if (n != v) changes.add("$label: ${short(v)} → ${short(n)}")
            return n
        }
        val out = req.copy(
            title = conv("트랙명", req.title),
            album = conv("앨범", req.album),
            // 아티스트 계열은 치환 후에도 "성우" 역할 단어를 넣지 않는다
            artist = conv("아티스트", req.artist)?.let { ArtistCleaner.clean(it) },
            // 앨범 아티스트는 " · " 구분 — clean() 쓰면 공백·중점이 " / " 로 깨짐
            albumArtist = conv("앨범 아티스트", req.albumArtist)?.let { ArtistCleaner.cleanAlbumArtist(it) },
            genre = conv("장르", req.genre)
        )
        return out to changes
    }

    /** 바뀐 내용을 작은 알림(토스트)으로 보여 준다 */
    fun notifyChanges(ctx: Context, changes: List<String>) {
        if (changes.isEmpty()) return
        val shown = changes.take(5)
        val more = if (changes.size > 5) "\n…외 ${changes.size - 5}개" else ""
        Toast.makeText(ctx, "치환 규칙으로 바꿨어요\n" + shown.joinToString("\n") + more, Toast.LENGTH_LONG).show()
    }

    private fun short(s: String): String = if (s.length > 30) s.take(30) + "…" else s

    // ---------------- TSV (자막 다듬기 앱과 같은 형식) ----------------

    private fun esc(s: String): String = buildString {
        for (ch in s) when (ch) {
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(ch)
        }
    }

    private fun unesc(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> append('\\')
                    't' -> append('\t')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> {
                        append(c)
                        append(s[i + 1])
                    }
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    /** 찾을 말 <TAB> 바꿀 말 <TAB> 옵션(R = 정규식, - = 꺼짐) */
    fun toTsv(rules: List<TagRule>): String {
        val sb = StringBuilder()
        sb.append("# 태그 편집기 치환 규칙: 찾을 말<TAB>바꿀 말<TAB>옵션(R=정규식, -=꺼짐)\n")
        for (r in rules) {
            val flags = (if (r.regex) "R" else "") + (if (r.enabled) "" else "-")
            sb.append(esc(r.find)).append('\t').append(esc(r.replace))
            if (flags.isNotEmpty()) sb.append('\t').append(flags)
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * 자막 다듬기에서 내보낸 TSV 도 읽는다.
     * 말투(H1/H2/H/C)나 다듬기 전용(P) 표시가 붙은 규칙은 태그에 필요 없어서 건너뛴다.
     * 반환: (읽은 규칙, 건너뛴 개수)
     */
    fun parseTsv(text: String, firstId: Long): Pair<List<TagRule>, Int> {
        val out = ArrayList<TagRule>()
        var skipped = 0
        var id = firstId
        for (line in text.removePrefix("\uFEFF").split('\n')) {
            val l = line.trimEnd('\r')
            if (l.isBlank() || l.startsWith("#")) continue
            val cols = l.split('\t')
            val find = unesc(cols[0])
            if (find.isEmpty()) continue
            val repl = if (cols.size > 1) unesc(cols[1]) else ""
            val flags = if (cols.size > 2) cols[2].trim().uppercase() else ""
            if ('H' in flags || 'C' in flags || 'P' in flags) {
                skipped++
                continue
            }
            out.add(TagRule(id++, find, repl, regex = 'R' in flags, enabled = '-' !in flags))
        }
        return out to skipped
    }
}

// ----------------------------------------------------------------------
// 조사 보정 / 정규식 받침 약속어 (자막 다듬기 앱에서 그대로 옮김)
// ----------------------------------------------------------------------

/**
 * 한국어 조사 보정. 치환으로 낱말이 바뀌면 받침이 달라져서 뒤에 붙은 조사가 어색해진다 (철수가 -> 민철가).
 * 바뀐 낱말 바로 뒤에 조사가 붙어 있으면 새 낱말의 받침에 맞는 형태로 고친다 (민철이).
 * 조사 뒤가 한글이 아니거나 흔한 꼬리말일 때만 조사로 본다 (철수가방 같은 낱말은 건드리지 않음).
 */
object Josa {
    private class Particle(
        val withB: String,
        val noB: String,
        val rieulAsNo: Boolean = false,
        val tails: List<String> = emptyList()
    )

    private val pairs = listOf(
        Particle("이에요", "예요"),
        Particle("이랑", "랑"),
        Particle("이나", "나"),
        Particle("이란", "란"),
        Particle("이라", "라", tails = listOf("고", "면", "는", "서")),
        Particle("으로", "로", rieulAsNo = true, tails = listOf("부터", "서", "써", "는", "도", "만", "까지")),
        Particle("이", "가"),
        Particle("은", "는", tails = listOf("요")),
        Particle("을", "를", tails = listOf("요")),
        Particle("과", "와"),
        Particle("아", "야")
    )

    private val forms: List<kotlin.Pair<String, Particle>> =
        pairs.flatMap { listOf(it.withB to it, it.noB to it) }.sortedByDescending { it.first.length }

    private fun isSyllable(c: Char) = c in '\uAC00'..'\uD7A3'

    /** 0 = 받침 없음, 1 = ㄹ 받침, 2 = 그 밖의 받침, -1 = 한글 아님 */
    private fun batchim(c: Char): Int {
        if (!isSyllable(c)) return -1
        return when ((c - '\uAC00') % 28) {
            0 -> 0
            8 -> 1
            else -> 2
        }
    }

    fun fix(s: String, from: Int, newWord: String): kotlin.Pair<String, Int>? {
        if (newWord.isEmpty() || from >= s.length) return null
        val cls = batchim(newWord.last())
        if (cls < 0) return null
        for ((form, p) in forms) {
            if (!s.startsWith(form, from)) continue
            val end = from + form.length
            val ok = end >= s.length || !isSyllable(s[end]) || p.tails.any { s.startsWith(it, end) }
            if (!ok) continue
            val useB = if (p.rieulAsNo) cls == 2 else cls != 0
            return (if (useB) p.withB else p.noB) to end
        }
        return null
    }
}

/**
 * 정규식 찾을 말 안에서 쓰는 한글 받침 약속어.
 *   {받침} 받침 있는 글자   {무받침} 받침 없는 글자   {ㄹ받침} ㄹ 받침   {ㄴ받침} ㄴ 받침
 *   {ㅆ받침} ㅆ 받침   {양성받침} ㅏ/ㅗ 모음 + 받침 글자
 * 예: (?<={받침})이야
 */
object HangulClass {
    private fun build(pred: (Int, Int) -> Boolean): String {
        val sb = StringBuilder("[")
        var start = -1
        var prev = -1
        fun flush() {
            if (start < 0) return
            sb.append(start.toChar())
            if (prev - start >= 2) sb.append('-')
            if (prev > start) sb.append(prev.toChar())
            start = -1
        }
        for (code in 0xAC00..0xD7A3) {
            val idx = code - 0xAC00
            val jung = (idx / 28) % 21
            val jong = idx % 28
            if (pred(jung, jong)) {
                if (start < 0) start = code
                prev = code
            } else {
                flush()
            }
        }
        flush()
        sb.append(']')
        return sb.toString()
    }

    private val tokens: List<kotlin.Pair<String, String>> by lazy {
        listOf(
            "{받침}" to build { _, jong -> jong != 0 },
            "{무받침}" to build { _, jong -> jong == 0 },
            "{ㄹ받침}" to build { _, jong -> jong == 8 },
            "{ㄴ받침}" to build { _, jong -> jong == 4 },
            "{ㅆ받침}" to build { _, jong -> jong == 20 },
            "{양성받침}" to build { jung, jong -> jong != 0 && (jung == 0 || jung == 8) }
        )
    }

    fun expand(s: String): String {
        if (s.indexOf('{') < 0) return s
        var r = s
        for ((k, v) in tokens) {
            if (r.contains(k)) r = r.replace(k, v)
        }
        return r
    }
}

// ----------------------------------------------------------------------
// 규칙 충돌 점검 (자막 다듬기 RuleCheck 를 태그용으로 단순화)
// ----------------------------------------------------------------------

enum class TagSeverity(val label: String) {
    ERROR("오류"),
    WARN("경고"),
    INFO("참고")
}

data class TagFinding(
    val severity: TagSeverity,
    val kind: String,
    val ruleIds: List<Long>,
    val words: List<String> = emptyList(),
    val title: String,
    val detail: String
)

object TagRuleCheck {
    private fun short(s: String, max: Int = 28): String =
        if (s.length <= max) s else s.substring(0, max) + "…"

    private fun label(r: TagRule): String =
        "‘${short(r.find)}’ → ‘${if (r.replace.isEmpty()) "(삭제)" else short(r.replace)}’" +
            (if (r.regex) " (정규식)" else "")

    fun check(rules: List<TagRule>, protect: List<String>): List<TagFinding> {
        val scope = rules.filter { it.enabled && it.find.isNotEmpty() }
        val out = ArrayList<TagFinding>()
        val plain = scope.filter { !it.regex }
        val regexes = scope.filter { it.regex }

        // 1) 정규식 문법 / 바꿀 말 $ 표기
        for (r in regexes) {
            val re = try {
                Regex(HangulClass.expand(r.find))
            } catch (e: Exception) {
                val why = e.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
                out.add(
                    TagFinding(
                        TagSeverity.ERROR, "REGEX_SYNTAX", listOf(r.id), emptyList(),
                        "정규식 문법이 잘못됐어요: ${label(r)}",
                        "이 규칙은 정규식이 잘못돼서 아예 적용되지 않아요." +
                            (if (why.isNotEmpty()) " (원인: $why)" else "") +
                            " 규칙을 눌러 찾을 말을 고치거나, 일반 규칙이면 ‘정규식’을 꺼 주세요."
                    )
                )
                continue
            }
            // 간단한 $ 그룹 참조 검사
            val groups = re.toPattern().matcher("").groupCount()
            var i = 0
            val repl = r.replace
            while (i < repl.length) {
                val c = repl[i]
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '$' && i + 1 < repl.length && repl[i + 1].isDigit()) {
                    val d = repl[i + 1] - '0'
                    if (d > groups) {
                        out.add(
                            TagFinding(
                                TagSeverity.ERROR, "REGEX_REPLACE", listOf(r.id), emptyList(),
                                "바꿀 말의 \$ 표기가 잘못됐어요: ${label(r)}",
                                "\$${d} 는 없는 묶음이에요 (찾을 말의 괄호 묶음은 ${groups}개). " +
                                    "글자 \$ 를 그대로 쓰려면 \\\$ 라고 써 주세요."
                            )
                        )
                        break
                    }
                }
                i++
            }
            if (repl.isNotEmpty() && re.find("") != null &&
                !Regex("""\(\?<?[=!]|\$|\^|\\b|\\B""").containsMatchIn(r.find)
            ) {
                out.add(
                    TagFinding(
                        TagSeverity.WARN, "REGEX_EMPTY", listOf(r.id), emptyList(),
                        "빈 곳에도 걸리는 정규식: ${label(r)}",
                        "이 정규식은 글자가 하나도 없는 곳에도 맞아서, 바꿀 말이 모든 글자 사이에 끼어들 수 있어요."
                    )
                )
            }
        }

        // 2) 자기 증식 (바꾼 결과에 찾을 말이 다시 들어 있음)
        for (r in plain) {
            val f = r.find
            val t = r.replace
            if (f == t || t.isEmpty() || !t.contains(f)) continue
            out.add(
                TagFinding(
                    TagSeverity.WARN, "SELF_GROW", listOf(r.id), emptyList(),
                    "계속 불어나는 규칙: ${label(r)}",
                    "바꾼 결과 안에 찾을 말 ‘${short(r.find)}’ 이 또 들어 있어서, 결과가 안 바뀔 때까지 " +
                        "(최대 ${TagRuleEngine.MAX_PASSES}번) 되풀이돼요."
                )
            )
        }

        // 3) 같은 찾을 말에 바꿀 말이 다른 일반 규칙
        val byFind = plain.groupBy { it.find }
        for ((find, list) in byFind) {
            if (list.size < 2) continue
            val replaces = list.map { it.replace }.distinct()
            if (replaces.size < 2) continue
            out.add(
                TagFinding(
                    TagSeverity.WARN, "CONFLICT_FIND", list.map { it.id }, emptyList(),
                    "같은 찾을 말에 바꿀 말이 달라요: ‘${short(find)}’",
                    "일반 규칙은 긴 말 우선으로 한 번에 적용되고, 같은 찾을 말이 여러 개면 목록에서 더 아래(나중에 등록한) 쪽이 이깁니다. " +
                        "의도한 쪽만 남기고 나머지는 끄거나 지우세요."
                )
            )
        }

        // 4) 제외 단어가 규칙을 가리는 경우
        val words = protect.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (words.isNotEmpty()) {
            for (r in scope) {
                if (r.regex) {
                    // 글자 그대로 쓴 정규식만 대략 검사
                    val literal = r.find.removePrefix("^").removeSuffix("$")
                        .replace("(?:", "").replace("(", "").replace(")", "")
                    val meta = ".*+?[]{}|\\^$"
                    if (literal.isEmpty() || literal.any { it in meta }) continue
                    for (w in words) {
                        if (literal.contains(w) || w.contains(literal)) {
                            out.add(
                                TagFinding(
                                    TagSeverity.WARN, "PROTECT_BLOCK", listOf(r.id), listOf(w),
                                    "제외 단어 때문에 규칙이 안 먹어요: ‘${short(w)}’ ↔ ${label(r)}",
                                    "제외 단어는 규칙보다 먼저 지켜지므로 이 규칙은 거의 적용되지 않아요."
                                )
                            )
                        }
                    }
                } else {
                    for (w in words) {
                        if (r.find == w || r.find.contains(w) || w.contains(r.find)) {
                            out.add(
                                TagFinding(
                                    TagSeverity.WARN, "PROTECT_BLOCK", listOf(r.id), listOf(w),
                                    "제외 단어 때문에 규칙이 안 먹어요: ‘${short(w)}’ ↔ ${label(r)}",
                                    (if (r.find == w) "이 규칙의 찾을 말이 제외 단어와 똑같아요."
                                    else "이 규칙의 찾을 말 안에 제외 단어가 들어 있어요.") +
                                        " 제외 단어는 규칙보다 먼저 지켜지므로 이 규칙은 거의 적용되지 않아요."
                                )
                            )
                        }
                    }
                }
            }
        }

        // 5) 참고: 긴 말이 짧은 말을 덮는 경우
        val plains = plain.sortedByDescending { it.find.length }
        for (i in plains.indices) {
            val long = plains[i]
            for (j in i + 1 until plains.size) {
                val shortR = plains[j]
                if (long.find.contains(shortR.find) && long.find != shortR.find) {
                    out.add(
                        TagFinding(
                            TagSeverity.INFO, "OVERLAP", listOf(long.id, shortR.id), emptyList(),
                            "긴 말 규칙이 짧은 말 규칙을 덮어요",
                            "‘${short(long.find)}’ 규칙이 ‘${short(shortR.find)}’ 보다 길어서, " +
                                "겹치는 자리에서는 긴 쪽만 적용됩니다. 의도한 동작이면 괜찮아요."
                        )
                    )
                    break // 하나당 하나만
                }
            }
        }

        return out.sortedWith(compareBy({ it.severity.ordinal }, { it.kind }))
    }
}

// ----------------------------------------------------------------------
// 규칙 관리 화면 (설정 > 치환 규칙)
// ----------------------------------------------------------------------

@Composable
fun TagRulesDialog(onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            TagRulesScreen(onClose)
        }
    }
}

@Composable
private fun TagRulesScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var rules by remember { mutableStateOf(TagRules.load(ctx)) }
    var editing by remember { mutableStateOf<TagRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var fixJosa by remember { mutableStateOf(TagRules.fixJosa(ctx)) }
    var protect by remember { mutableStateOf(TagRules.protect(ctx)) }
    var query by remember { mutableStateOf("") }
    // 0 = 전체, 1 = 정규식만
    var filterIdx by remember { mutableIntStateOf(0) }
    var showCheck by remember { mutableStateOf(false) }
    var showProtect by remember { mutableStateOf(false) }
    var findings by remember { mutableStateOf<List<TagFinding>?>(null) }
    var checkStale by remember { mutableStateOf(true) }

    fun persist(list: List<TagRule>) {
        rules = list
        TagRules.save(ctx, list)
        checkStale = true
    }

    fun persistProtect(words: List<String>) {
        protect = words
        TagRules.setProtect(ctx, words)
        checkStale = true
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                val nextId = (rules.maxOfOrNull { it.id } ?: 0L) + 1
                val (parsed, skipped) = TagRules.parseTsv(text, nextId)
                val existing = rules.map { Triple(it.find, it.replace, it.regex) }.toHashSet()
                val fresh = parsed.filter { existing.add(Triple(it.find, it.replace, it.regex)) }
                persist(rules + fresh)
                val dup = parsed.size - fresh.size
                Toast.makeText(
                    ctx,
                    "${fresh.size}개 가져왔어요" +
                        (if (dup > 0) " · 같은 규칙 ${dup}개 건너뜀" else "") +
                        (if (skipped > 0) " · 말투/다듬기 전용 ${skipped}개 제외" else ""),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "파일을 읽지 못했어요", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/tab-separated-values")
    ) { uri ->
        if (uri != null) {
            try {
                val toExport = if (filterIdx == 1) rules.filter { it.regex } else rules
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(TagRules.toTsv(toExport).toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(ctx, "${toExport.size}개 규칙을 내보냈어요", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "내보내지 못했어요", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val q = query.trim()
    val listState = rememberLazyListState()
    var prevSize by remember { mutableIntStateOf(rules.size) }

    // 최신 추가가 위에 오도록 id 내림차순
    val shown = remember(rules, q, filterIdx) {
        val byFilter = when (filterIdx) {
            1 -> rules.filter { it.regex }
            else -> rules
        }
        val found = if (q.isEmpty()) byFilter
        else byFilter.filter {
            it.find.contains(q, ignoreCase = true) || it.replace.contains(q, ignoreCase = true)
        }
        found.sortedByDescending { it.id }
    }

    LaunchedEffect(rules.size) {
        if (rules.size > prevSize) {
            listState.scrollToItem(0)
        }
        prevSize = rules.size
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "닫기") }
            Text("치환 규칙", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }

        Text(
            "저장할 때 입력한 트랙명·앨범·아티스트·앨범 아티스트·장르에 적용돼요. " +
                "바뀐 내용은 저장할 때 작은 알림으로 알려 줘요.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        // 검색
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("규칙 검색") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // 버튼 줄
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { adding = true }) { Text("규칙 추가") }
            TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("가져오기") }
            TextButton(
                onClick = { exportLauncher.launch(if (filterIdx == 1) "정규식규칙.tsv" else "tag_rules.tsv") },
                enabled = rules.isNotEmpty()
            ) { Text(if (filterIdx == 1) "정규식 내보내기" else "내보내기") }
            TextButton(onClick = {
                val (deduped, removed) = TagRules.dedupe(rules)
                if (removed == 0) {
                    Toast.makeText(ctx, "중복 규칙이 없어요", Toast.LENGTH_SHORT).show()
                } else {
                    persist(deduped)
                    Toast.makeText(ctx, "중복 ${removed}개 지웠어요", Toast.LENGTH_SHORT).show()
                }
            }) { Text("중복 삭제") }
            TextButton(onClick = {
                findings = TagRuleCheck.check(rules, protect)
                checkStale = false
                showCheck = true
            }) { Text("충돌 검사") }
            TextButton(onClick = { showProtect = true }) {
                Text(if (protect.isEmpty()) "제외 단어" else "제외 단어 ${protect.size}")
            }
        }

        // 필터 탭 (전체 / 정규식만)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filterIdx == 0,
                onClick = { filterIdx = 0 },
                label = { Text("전체") }
            )
            FilterChip(
                selected = filterIdx == 1,
                onClick = { filterIdx = 1 },
                label = { Text("정규식만") }
            )
        }

        // 조사 보정
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("조사 자동 보정", fontSize = 15.sp)
                Text(
                    "바꾼 말 뒤의 조사를 받침에 맞춰요 (철수가 → 민철이)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = fixJosa, onCheckedChange = {
                fixJosa = it
                TagRules.setFixJosa(ctx, it)
            })
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

        Text(
            "규칙 ${rules.size}개 · 켜진 규칙 ${rules.count { it.enabled }}개" +
                (if (q.isNotEmpty() || filterIdx != 0) " · 표시 ${shown.size}개" else "") +
                (if (protect.isNotEmpty()) " · 제외 단어 ${protect.size}개" else ""),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        if (rules.isEmpty()) {
            Text(
                "아직 규칙이 없어요.\n‘규칙 추가’를 누르거나, 자막 다듬기 앱에서 내보낸 TSV 파일을 ‘가져오기’로 불러올 수 있어요.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else if (shown.isEmpty()) {
            Text(
                if (q.isNotEmpty()) "검색 결과가 없어요" else "이 탭에 규칙이 없어요",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }

        LazyColumn(modifier = Modifier.weight(1f), state = listState) {
            items(shown, key = { it.id }) { r ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = r }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (r.replace.isEmpty()) "${r.find} → (삭제)" else "${r.find} → ${r.replace}",
                            fontSize = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (r.enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (r.regex) {
                            Text("정규식", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Switch(
                        checked = r.enabled,
                        onCheckedChange = { on ->
                            persist(rules.map { if (it.id == r.id) it.copy(enabled = on) else it })
                        }
                    )
                }
                HorizontalDivider()
            }
        }
    }

    if (adding) {
        RuleEditDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = { find, repl, rx ->
                val nextId = (rules.maxOfOrNull { it.id } ?: 0L) + 1
                persist(rules + TagRule(nextId, find, repl, rx, true))
                adding = false
            },
            onDelete = null
        )
    }
    editing?.let { cur ->
        RuleEditDialog(
            initial = cur,
            onDismiss = { editing = null },
            onSave = { find, repl, rx ->
                persist(rules.map { if (it.id == cur.id) it.copy(find = find, replace = repl, regex = rx) else it })
                editing = null
            },
            onDelete = {
                persist(rules.filter { it.id != cur.id })
                editing = null
            }
        )
    }

    if (showCheck) {
        TagRuleCheckDialog(
            findings = findings ?: emptyList(),
            stale = checkStale,
            rules = rules,
            onClose = { showCheck = false },
            onRecheck = {
                findings = TagRuleCheck.check(rules, protect)
                checkStale = false
            },
            onEditRule = { r ->
                showCheck = false
                editing = r
            }
        )
    }

    if (showProtect) {
        ProtectWordsDialog(
            words = protect,
            onDismiss = { showProtect = false },
            onSave = { newWords ->
                persistProtect(newWords)
                showProtect = false
            }
        )
    }
}

@Composable
private fun RuleEditDialog(
    initial: TagRule?,
    onDismiss: () -> Unit,
    onSave: (find: String, replace: String, regex: Boolean) -> Unit,
    onDelete: (() -> Unit)?
) {
    var find by remember { mutableStateOf(initial?.find ?: "") }
    var repl by remember { mutableStateOf(initial?.replace ?: "") }
    var rx by remember { mutableStateOf(initial?.regex ?: false) }

    val regexError = if (rx && find.isNotEmpty()) {
        try {
            Regex(HangulClass.expand(find))
            false
        } catch (e: Exception) {
            true
        }
    } else false
    val canSave = find.isNotEmpty() && !regexError

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "규칙 추가" else "규칙 수정") },
        text = {
            Column {
                OutlinedTextField(
                    value = find,
                    onValueChange = { find = it },
                    label = { Text("찾을 말") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = regexError
                )
                OutlinedTextField(
                    value = repl,
                    onValueChange = { repl = it },
                    label = { Text("바꿀 말 (비우면 삭제)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { rx = !rx }.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = rx, onCheckedChange = { rx = it })
                    Text("정규식")
                }
                if (regexError) {
                    Text(
                        "정규식 문법이 올바르지 않아요",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { onSave(find, repl, rx) }) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("삭제", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        }
    )
}

@Composable
private fun TagRuleCheckDialog(
    findings: List<TagFinding>,
    stale: Boolean,
    rules: List<TagRule>,
    onClose: () -> Unit,
    onRecheck: () -> Unit,
    onEditRule: (TagRule) -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "닫기")
                    }
                    Text(
                        "규칙 충돌 검사",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRecheck) { Text("다시 검사") }
                }
                val e = findings.count { it.severity == TagSeverity.ERROR }
                val w = findings.count { it.severity == TagSeverity.WARN }
                val i = findings.count { it.severity == TagSeverity.INFO }
                val summary = when {
                    findings.isEmpty() -> "충돌 없음"
                    else -> "오류 $e · 경고 $w · 참고 $i"
                }
                Text(
                    if (stale) "$summary (규칙이 바뀌었어요. 다시 검사해 주세요)" else summary,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                if (findings.isEmpty()) {
                    Text(
                        "문제가 없어요. 규칙끼리·제외 단어와 잘 어울려요.",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        items(findings) { f ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = when (f.severity) {
                                        TagSeverity.ERROR -> MaterialTheme.colorScheme.errorContainer
                                        TagSeverity.WARN -> MaterialTheme.colorScheme.tertiaryContainer
                                        TagSeverity.INFO -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        "[${f.severity.label}] ${f.title}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(f.detail, fontSize = 13.sp)
                                    if (f.words.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "제외 단어: " + f.words.joinToString(", "),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    for (id in f.ruleIds.take(4)) {
                                        val r = rules.firstOrNull { it.id == id } ?: continue
                                        TextButton(onClick = { onEditRule(r) }) {
                                            Text(
                                                "규칙 고치기: ${r.find} → ${r.replace.ifEmpty { "(삭제)" }}",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtectWordsDialog(
    words: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var text by remember {
        mutableStateOf(words.joinToString("\n"))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("제외 단어") },
        text = {
            Column {
                Text(
                    "이 말이 들어 있는 부분은 치환 규칙이 건드리지 않아요. 한 줄에 하나씩 적으세요.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("제외 단어 (한 줄에 하나)") },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    minLines = 5
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val list = text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                onSave(list)
            }) { Text("저장") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}
