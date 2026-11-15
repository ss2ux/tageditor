package com.ss2ux.tageditor

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.getValue
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
class TagRuleEngine(rules: List<TagRule>, private val fixJosa: Boolean) {
    private val plainMap = HashMap<String, String>()
    private var plainRegex: Regex? = null
    private val regexRules = ArrayList<Pair<Regex, String>>()
    private val broken = HashSet<Int>()

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
        if (!hasRules || text.isEmpty()) return text
        var s = text
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

    /**
     * 저장할 값(트랙명/앨범/아티스트/앨범 아티스트/장르)에 규칙을 적용한다.
     * 돌려주는 것: (바뀐 요청, 바뀐 내용 설명 목록). 바뀐 게 없으면 목록이 비어 있다.
     */
    fun applyTo(ctx: Context, req: EditRequest): Pair<EditRequest, List<String>> {
        val engine = TagRuleEngine(load(ctx), fixJosa(ctx))
        if (!engine.hasRules) return req to emptyList()
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
            artist = conv("아티스트", req.artist),
            albumArtist = conv("앨범 아티스트", req.albumArtist),
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

    fun persist(list: List<TagRule>) {
        rules = list
        TagRules.save(ctx, list)
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
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(TagRules.toTsv(rules).toByteArray(Charsets.UTF_8)) }
                Toast.makeText(ctx, "${rules.size}개 규칙을 내보냈어요", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "내보내지 못했어요", Toast.LENGTH_SHORT).show()
            }
        }
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = { adding = true }) { Text("규칙 추가") }
            TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("가져오기") }
            TextButton(onClick = { exportLauncher.launch("tag_rules.tsv") }, enabled = rules.isNotEmpty()) { Text("내보내기") }
        }

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
            "규칙 ${rules.size}개 · 켜진 규칙 ${rules.count { it.enabled }}개",
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
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(rules.reversed(), key = { it.id }) { r ->
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
                        onCheckedChange = { on -> persist(rules.map { if (it.id == r.id) it.copy(enabled = on) else it }) }
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
