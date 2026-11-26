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

// ---------------------------------------------------------------------------
// 아티스트 칸: 공백·/ → " / " (기존)
// 앨범 아티스트 칸: 띄어쓰기로만 추가, '/' 는 이름에 포함, 칩 UI, 태그는 " · "
// ---------------------------------------------------------------------------

private fun isSepChar(c: Char): Boolean = c.isWhitespace() || c == '/'

/** 반환: (새 글, 커서). null 이면 그대로 통과. */
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

/** 앨범 아티스트 여러 명 구분자 (이름에 '/' 가 있어도 됨) */
const val MULTI_ARTIST_SEP = " · "

fun splitMultiArtist(s: String): List<String> =
    if (s.isBlank()) emptyList()
    else s.split(MULTI_ARTIST_SEP).map { it.trim() }.filter { it.isNotEmpty() }

fun joinMultiArtist(parts: List<String>): String =
    parts.map { ArtistCleaner.cleanToken(it) }.filter { it.isNotEmpty() }.joinToString(MULTI_ARTIST_SEP)

fun normalizeMultiArtist(s: String): String = joinMultiArtist(splitMultiArtist(s))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlbumArtistField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    mixed: Boolean = false
) {
    val chips = splitMultiArtist(value)
    var draft by remember { mutableStateOf("") }
    fun emit(next: List<String>) = onChange(joinMultiArtist(next))

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
                                    emit(chips.toMutableList().also { it.removeAt(idx) })
                                }
                            )
                        }
                    )
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { nv ->
                if (nv.contains('\n')) {
                    val t = ArtistCleaner.cleanToken(nv.replace("\n", " ").trim())
                    if (t.isNotEmpty()) emit(chips + t)
                    draft = ""
                    return@OutlinedTextField
                }
                if (nv.isNotEmpty() && nv.last().isWhitespace()) {
                    val token = ArtistCleaner.cleanToken(nv.dropLast(1).trim())
                    if (token.isNotEmpty()) {
                        emit(chips + token)
                        draft = ""
                    } else draft = nv.trim()
                } else draft = nv
            },
            label = { Text(if (chips.isEmpty()) "이름 입력" else "이름 추가") },
            placeholder = { Text("띄어쓰기로 다음 이름 추가 (/ 는 이름에 포함)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { f ->
                    if (!f.isFocused) {
                        val t = ArtistCleaner.cleanToken(draft.trim())
                        if (t.isNotEmpty()) {
                            emit(chips + t)
                            draft = ""
                        }
                    }
                }
        )
    }
}
