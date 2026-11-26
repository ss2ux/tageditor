package com.ss2ux.tageditor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 아티스트: 공백·/ → " / "
// 앨범 아티스트: 띄어쓰기로 다음 사람, 저장은 " · "

private fun isSepChar(c: Char): Boolean = c.isWhitespace() || c == '/'

private fun reformatArtist(
    old: String,
    new: String,
    cursor: Int,
    composing: Boolean,
    clip: () -> String?
): Pair<String, Int>? {
    var p = 0
    val maxP = minOf(old.length, new.length)
    while (p < maxP && old[p] == new[p]) p++
    var s = 0
    val maxS = minOf(old.length, new.length) - p
    while (s < maxS && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
    val removed = old.substring(p, old.length - s)
    val inserted = new.substring(p, new.length - s)
    val deleting = new.length < old.length

    val ins = inserted.trim()
    val bigInsert = !composing && removed.isEmpty() && ins.length >= 2
    val isPaste = bigInsert || (ins.length >= 2 && ins == (clip()?.trim() ?: ""))
    if (!isPaste && !inserted.any { isSepChar(it) } && !removed.any { isSepChar(it) }) return null

    var raw = new
    var cur = cursor.coerceIn(0, new.length)
    if (isPaste) {
        val l = new.substring(0, p)
        val r = new.substring(new.length - s)
        val pre = if (l.isNotEmpty() && !isSepChar(l.last())) " " else ""
        val post = if (r.isNotEmpty() && !isSepChar(r.first())) " " else ""
        raw = l + pre + inserted + post + r
        cur = l.length + pre.length + inserted.length
    }

    val tokens = ArrayList<String>()
    val sb = StringBuilder()
    for (ch in raw) {
        if (isSepChar(ch)) {
            if (sb.isNotEmpty()) {
                val t = ArtistCleaner.cleanToken(sb.toString())
                if (t.isNotEmpty()) tokens.add(t)
                sb.setLength(0)
            }
        } else sb.append(ch)
    }
    if (sb.isNotEmpty()) {
        val t = ArtistCleaner.cleanToken(sb.toString())
        if (t.isNotEmpty()) tokens.add(t)
    }
    var text = tokens.joinToString(" / ")
    if (!deleting && tokens.isNotEmpty() && isSepChar(raw.last())) text += " / "

    var k = 0
    for (i in 0 until cur) if (!isSepChar(raw[i])) k++
    var pos = 0
    if (k > 0) {
        var cnt = 0
        pos = text.length
        for (i in text.indices) {
            if (!isSepChar(text[i])) {
                cnt++
                if (cnt == k) {
                    pos = i + 1
                    break
                }
            }
        }
    }
    if (cur >= raw.length) pos = text.length
    else if (k > 0 && cur > 0 && isSepChar(raw[cur - 1]) && text.startsWith(" / ", pos)) pos += 3
    return text to pos
}

@Composable
fun ArtistField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    mixed: Boolean = false
) {
    val clipboard = LocalClipboardManager.current
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        if (tfv.text != value) tfv = TextFieldValue(value, TextRange(value.length))
    }
    OutlinedTextField(
        value = tfv,
        onValueChange = { nv ->
            val r = if (nv.text == tfv.text) null
            else reformatArtist(tfv.text, nv.text, nv.selection.end, nv.composition != null) {
                clipboard.getText()?.text
            }
            if (r == null) {
                tfv = nv
                onChange(nv.text)
            } else {
                tfv = TextFieldValue(r.first, TextRange(r.second))
                onChange(r.first)
            }
        },
        label = { Text(label) },
        placeholder = { if (mixed) Text("여러 값 · 그대로 두면 변경 안 함") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .onFocusChanged { f ->
                if (!f.isFocused) {
                    val t = tfv.text
                    val c = t.trimEnd { isSepChar(it) }
                    if (c != t) {
                        tfv = TextFieldValue(c, TextRange(c.length))
                        onChange(c)
                    }
                }
            }
    )
}

const val MULTI_ARTIST_SEP = " · "

fun splitMultiArtist(s: String): List<String> =
    if (s.isBlank()) emptyList()
    else s.split(MULTI_ARTIST_SEP).map { it.trim() }.filter { it.isNotEmpty() }

fun joinMultiArtist(parts: List<String>): String =
    parts.map { ArtistCleaner.cleanToken(it) }.filter { it.isNotEmpty() }.joinToString(MULTI_ARTIST_SEP)

fun normalizeMultiArtist(s: String): String = joinMultiArtist(splitMultiArtist(s))

/**
 * 앨범 아티스트.
 * - 입력 중(띄어쓰기 전)에는 일반 글자로만 두고 칩으로 올리지 않음 → 한글 조합 중 ㅂ 칩 되는 문제 방지
 * - 띄어쓰기 한 번 = 그 사람 확정(칩) 후 다음 이름 입력
 * - value: 칩이 하나여도 "이름"만, 둘 이상이면 "A · B"
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlbumArtistField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    mixed: Boolean = false,
    suggest: (query: String) -> List<String> = { emptyList() }
) {
    // 이미 확정된 사람들 (value 에 · 가 있을 때만 여러 명으로 봄)
    // 한 명만 있을 때는 value 전체가 입력 중 글이라 칩으로 안 쪼갬
    val multi = MULTI_ARTIST_SEP in value
    var chips by remember {
        mutableStateOf(if (multi) splitMultiArtist(value) else emptyList())
    }
    // 입력 중 글: 여러 명 모드면 다음 이름, 아니면 value 전체
    var draft by remember {
        mutableStateOf(if (multi) "" else value)
    }

    // 바깥 value 가 바뀌면(불러오기 등) 맞추기
    LaunchedEffect(value) {
        if (MULTI_ARTIST_SEP in value) {
            val parts = splitMultiArtist(value)
            if (parts != chips) {
                chips = parts
                draft = ""
            }
        } else if (chips.isEmpty() && value != draft) {
            draft = value
        }
    }

    fun publish(nextChips: List<String>, nextDraft: String) {
        chips = nextChips
        draft = nextDraft
        onChange(
            when {
                nextChips.isEmpty() -> nextDraft
                nextDraft.isBlank() -> joinMultiArtist(nextChips)
                else -> joinMultiArtist(nextChips) // draft 는 아직 미확정, 저장 값은 확정 칩만
            }
        )
    }

    fun commitDraft() {
        val t = ArtistCleaner.cleanToken(draft.trim())
        if (t.isEmpty()) return
        if (chips.any { it.equals(t, ignoreCase = true) }) {
            draft = ""
            return
        }
        publish(chips + t, "")
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        if (mixed && chips.isEmpty() && draft.isEmpty()) {
            Text(
                "여러 값 · 그대로 두면 변경 안 함",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
        }
        if (chips.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            ) {
                chips.forEachIndexed { idx, name ->
                    InputChip(
                        selected = false,
                        onClick = { },
                        label = { Text(name) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "삭제",
                                modifier = Modifier.size(16.dp).clickable {
                                    val next = chips.toMutableList().also { it.removeAt(idx) }
                                    // 칩 하나 남으면 다시 일반 입력 모드로
                                    if (next.size <= 1) {
                                        val left = next.firstOrNull().orEmpty()
                                        chips = emptyList()
                                        draft = left
                                        onChange(left)
                                    } else {
                                        publish(next, draft)
                                    }
                                }
                            )
                        }
                    )
                }
            }
        }

        val q = draft.trim()
        if (q.isNotEmpty()) {
            val sugg = remember(q, chips) { suggest(q) }
            if (sugg.isNotEmpty()) {
                SuggestionRow(sugg) { picked ->
                    val t = ArtistCleaner.cleanToken(picked.trim())
                    if (t.isEmpty()) return@SuggestionRow
                    if (chips.isEmpty()) {
                        // 추천으로 한 명 확정(아직 칩 모드 아님) — 글만 채움
                        draft = t
                        onChange(t)
                    } else if (!chips.any { it.equals(t, ignoreCase = true) }) {
                        publish(chips + t, "")
                    } else {
                        draft = ""
                    }
                }
            }
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { nv ->
                // 한글 조합 중에는 composition 이 있을 수 있음 — 공백으로만 확정
                if (nv.isNotEmpty() && nv.last().isWhitespace()) {
                    val token = nv.dropLast(1).trim()
                    if (token.isNotEmpty()) {
                        val t = ArtistCleaner.cleanToken(token)
                        if (t.isNotEmpty() && !chips.any { it.equals(t, ignoreCase = true) }) {
                            publish(chips + t, "")
                        } else {
                            draft = ""
                        }
                    }
                    return@OutlinedTextField
                }
                draft = nv
                // 칩이 아직 없으면 저장 대상 value 도 같이 갱신 (한 명만 넣고 저장해도 빈칸 방지)
                if (chips.isEmpty()) onChange(nv)
            },
            label = { Text(if (chips.isEmpty()) "이름 입력" else "이름 추가") },
            placeholder = { Text("띄어쓰기로 다음 사람 추가") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { f ->
                    if (!f.isFocused && chips.isNotEmpty() && draft.isNotBlank()) {
                        commitDraft()
                    }
                }
        )
    }
}
