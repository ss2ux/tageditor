package com.ss2ux.tageditor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 곡 제목(또는 파일 이름) 맨 앞의 트랙번호를 읽는다.
 *
 * 접두어(tr, track, 트랙, t)가 있으면 뒤에 숫자만 와도 인식:  tr1, track 1, 트랙02, t3
 * 접두어가 없으면 숫자 뒤에 구분 기호(_ - . ) ])가 있거나 숫자만 있어야 인식:  01_, 1-, 3., 7
 * 0으로 시작하는 숫자 뒤에 공백이 와도 인식:  "01 제목"
 * "1999", "2 Unlimited" 처럼 곡 제목이 숫자로 시작하는 경우는 인식하지 않는다.
 */
object TrackNumberParser {
    class Parsed(val number: Int, val rest: String)

    private val prefixed = Regex(
        """^(?:tr(?:ack)?|트랙|t)[\s_.\-]*(\d{1,3})(?=[\s_.\-)\]]|$)""",
        RegexOption.IGNORE_CASE
    )
    private val plain = Regex("""^(\d{1,3})(?=\s*[_.\-)\]]|$)""")
    private val padded = Regex("""^(0\d{1,2})(?=\s)""")
    private val leadingSeparators = Regex("""^[\s_.\-)\]]+""")

    /** number = 읽은 트랙번호, rest = 번호 표기를 뺀 나머지 제목. 못 읽으면 null */
    fun parse(text: String): Parsed? {
        val s = text.trim()
        val m = prefixed.find(s) ?: plain.find(s) ?: padded.find(s) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        if (n <= 0) return null
        val rest = s.substring(m.range.last + 1).replace(leadingSeparators, "").trim()
        return Parsed(n, rest)
    }
}

class TrackPlanItem(
    val track: Track,
    val curTitle: String,    // 지금 트랙명
    val newTitle: String,    // 번호 표기를 지운 트랙명 (지우기를 껐으면 curTitle 과 같음)
    val newNo: Int,          // 0 = 변경 안 함
    val recognized: Boolean  // true = 제목/파일명에서 읽음, false = 순서대로 부여
)

/**
 * 앨범 전체의 트랙번호 배분 계획을 만든다.
 * - 번호를 읽은 곡: 그 번호 사용 (제목에서 못 읽으면 파일명에서 읽음)
 * - 못 읽은 곡: fillOrder 가 켜져 있으면, 쓰이지 않은 가장 작은 번호부터 목록 순서대로 부여
 */
fun buildTrackPlan(
    tracks: List<Track>,
    curTitles: Map<Long, String>,
    stripTitle: Boolean,
    fillOrder: Boolean
): List<TrackPlanItem> {
    class Tmp(val t: Track, val cur: String, val no: Int?, val title: String)

    val tmps = tracks.map { t ->
        val cur = (curTitles[t.id] ?: "").ifBlank { t.title }
        val fromTitle = TrackNumberParser.parse(cur)
        val fromFile =
            if (fromTitle == null) TrackNumberParser.parse(t.displayName.substringBeforeLast('.')) else null
        val no = fromTitle?.number ?: fromFile?.number
        val title =
            if (stripTitle && fromTitle != null && fromTitle.rest.isNotEmpty()) fromTitle.rest else cur
        Tmp(t, cur, no, title)
    }

    val used = HashSet<Int>()
    for (x in tmps) {
        val n = x.no
        if (n != null) used.add(n)
    }

    var next = 1
    val result = ArrayList<TrackPlanItem>()
    for (x in tmps) {
        val n = x.no
        if (n != null) {
            result.add(TrackPlanItem(x.t, x.cur, x.title, n, true))
        } else if (fillOrder) {
            while (next in used) next++
            used.add(next)
            result.add(TrackPlanItem(x.t, x.cur, x.title, next, false))
            next++
        } else {
            result.add(TrackPlanItem(x.t, x.cur, x.title, 0, false))
        }
    }
    return result
}

/** 자동 배분 미리보기 창. "적용"을 누르면 계획을 돌려준다 (파일에 쓰는 건 수정 화면의 저장 때) */
@Composable
fun TrackPlanDialog(
    tracks: List<Track>,
    curTitles: Map<Long, String>,
    onApply: (List<TrackPlanItem>) -> Unit,
    onDismiss: () -> Unit
) {
    var strip by remember { mutableStateOf(true) }
    var fill by remember { mutableStateOf(true) }
    val plan = remember(strip, fill) { buildTrackPlan(tracks, curTitles, strip, fill) }
    val numbers = plan.map { it.newNo }.filter { it > 0 }
    val hasDuplicates = numbers.size != numbers.toSet().size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("트랙번호 자동 배분") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("트랙명 앞의 번호 표기 지우기 (01_, track1 …)", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = strip, onCheckedChange = { strip = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("번호를 못 읽은 곡은 순서대로 부여", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = fill, onCheckedChange = { fill = it })
                }
                if (hasDuplicates) {
                    Text(
                        "번호가 겹치는 곡이 있어요. 확인해 주세요.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp).padding(top = 8.dp)) {
                    items(plan, key = { it.track.id }) { p ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                if (p.newNo > 0) "%02d".format(p.newNo) else "-",
                                modifier = Modifier.width(32.dp),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(p.newTitle, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (p.newTitle != p.curTitle) {
                                    Text(
                                        "원래: ${p.curTitle}",
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (!p.recognized && p.newNo > 0) {
                                    Text(
                                        "순서대로 부여",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(plan) }) { Text("적용") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}
