package com.ss2ux.tageditor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atilika.kuromoji.ipadic.Tokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * 일본어 -> 한글 음차.
 *  1) 형태소 분석기(kuromoji)로 한자를 일본어 발음(가타카나)으로 바꾸고
 *  2) 가타카나를 외래어 표기법(일본어) 방식으로 한글로 적는다.
 * 사전을 읽는 데 몇 초 걸리므로, 일본어가 처음 나올 때 한 번만 백그라운드에서 읽는다.
 */
object JpReading {
    /** 0 = 아직 안 읽음, 1 = 읽는 중, 2 = 준비됨, 3 = 실패 */
    @Volatile
    var state: Int = 0
        private set

    @Volatile
    private var tokenizer: Tokenizer? = null
    private val lock = Any()

    /** 일본어가 들어 있는 글인지: 가나가 있거나, 한글 없이 한자만 있을 때 */
    fun looksJapanese(s: String): Boolean {
        var kana = false
        var han = false
        var hangul = false
        for (c in s) {
            when {
                c in '\u3041'..'\u3096' || c in '\u30A1'..'\u30FA' || c in '\uFF66'..'\uFF9D' -> kana = true
                c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' -> han = true
                c in '\uAC00'..'\uD7A3' || c in '\u1100'..'\u11FF' || c in '\u3131'..'\u318E' -> hangul = true
            }
        }
        return kana || (han && !hangul)
    }

    private fun getTokenizer(): Tokenizer? {
        tokenizer?.let { return it }
        synchronized(lock) {
            tokenizer?.let { return it }
            if (state == 3) return null
            state = 1
            return try {
                val t = Tokenizer()
                tokenizer = t
                state = 2
                t
            } catch (e: Throwable) {
                state = 3
                null
            }
        }
    }

    /** 시간이 걸릴 수 있으니 백그라운드에서 호출. 못 만들면 null */
    fun transliterate(text: String): String? {
        val tk = getTokenizer() ?: return null
        return try {
            val src = Normalizer.normalize(text, Normalizer.Form.NFKC)
            val sb = StringBuilder()
            for (t in tk.tokenize(src)) {
                val p = t.pronunciation
                val r = t.reading
                val piece = when {
                    p != null && p != "*" -> p
                    r != null && r != "*" -> r
                    else -> t.surface
                }
                // 낱말 사이는 띄어 쓴다. 조사/조동사/접미사 등은 앞말에 붙인다.
                val pos1 = t.partOfSpeechLevel1
                val pos2 = t.partOfSpeechLevel2
                val attach = pos1 == "助詞" || pos1 == "助動詞" || pos1 == "記号" ||
                    pos2 == "接尾" || pos2 == "非自立" || t.surface.isBlank()
                if (sb.isNotEmpty() && !attach && !sb.last().isWhitespace() && sb.last() != '/') sb.append(' ')
                sb.append(piece)
            }
            KanaToHangul.convert(sb.toString())
        } catch (e: Throwable) {
            null
        }
    }
}

/** 가타카나/히라가나 -> 한글 (외래어 표기법 일본어 기준) */
object KanaToHangul {
    // 값: [낱말 첫머리일 때, 낱말 가운데일 때]
    private val T = HashMap<String, Array<String>>()

    private fun put(k: String, head: String, mid: String = head) {
        T[k] = arrayOf(head, mid)
    }

    private fun addRow(vararg pairs: kotlin.Pair<String, String>) {
        for ((k, v) in pairs) put(k, v)
    }

    init {
        addRow("ア" to "아", "イ" to "이", "ウ" to "우", "エ" to "에", "オ" to "오")
        // 어두 평음 / 어중 격음: か행, た행
        put("カ", "가", "카"); put("キ", "기", "키"); put("ク", "구", "쿠"); put("ケ", "게", "케"); put("コ", "고", "코")
        put("タ", "다", "타"); put("チ", "지", "치"); put("ツ", "쓰", "츠"); put("テ", "데", "테"); put("ト", "도", "토")
        addRow("ガ" to "가", "ギ" to "기", "グ" to "구", "ゲ" to "게", "ゴ" to "고")
        addRow("サ" to "사", "シ" to "시", "ス" to "스", "セ" to "세", "ソ" to "소")
        addRow("ザ" to "자", "ジ" to "지", "ズ" to "즈", "ゼ" to "제", "ゾ" to "조")
        addRow("ダ" to "다", "ヂ" to "지", "ヅ" to "즈", "デ" to "데", "ド" to "도")
        addRow("ナ" to "나", "ニ" to "니", "ヌ" to "누", "ネ" to "네", "ノ" to "노")
        addRow("ハ" to "하", "ヒ" to "히", "フ" to "후", "ヘ" to "헤", "ホ" to "호")
        addRow("バ" to "바", "ビ" to "비", "ブ" to "부", "ベ" to "베", "ボ" to "보")
        addRow("パ" to "파", "ピ" to "피", "プ" to "푸", "ペ" to "페", "ポ" to "포")
        addRow("マ" to "마", "ミ" to "미", "ム" to "무", "メ" to "메", "モ" to "모")
        addRow("ヤ" to "야", "ユ" to "유", "ヨ" to "요")
        addRow("ラ" to "라", "リ" to "리", "ル" to "루", "レ" to "레", "ロ" to "로")
        addRow("ワ" to "와", "ヲ" to "오", "ヰ" to "이", "ヱ" to "에", "ヴ" to "부", "ヮ" to "와")
        addRow("ァ" to "아", "ィ" to "이", "ゥ" to "우", "ェ" to "에", "ォ" to "오")
        put("ヵ", "카"); put("ヶ", "케")

        // 요음 (イ단 + ャュョ)
        put("キャ", "갸", "캬"); put("キュ", "규", "큐"); put("キョ", "교", "쿄")
        addRow("ギャ" to "갸", "ギュ" to "규", "ギョ" to "교")
        addRow("シャ" to "샤", "シュ" to "슈", "ショ" to "쇼")
        addRow("ジャ" to "자", "ジュ" to "주", "ジョ" to "조", "ヂャ" to "자", "ヂュ" to "주", "ヂョ" to "조")
        put("チャ", "차", "차"); put("チュ", "추", "추"); put("チョ", "초", "초")
        addRow("ニャ" to "냐", "ニュ" to "뉴", "ニョ" to "뇨")
        addRow("ヒャ" to "햐", "ヒュ" to "휴", "ヒョ" to "효")
        addRow("ビャ" to "뱌", "ビュ" to "뷰", "ビョ" to "뵤")
        addRow("ピャ" to "퍄", "ピュ" to "퓨", "ピョ" to "표")
        addRow("ミャ" to "먀", "ミュ" to "뮤", "ミョ" to "묘")
        addRow("リャ" to "랴", "リュ" to "류", "リョ" to "료")

        // 외래음
        addRow(
            "ファ" to "파", "フィ" to "피", "フェ" to "페", "フォ" to "포", "フュ" to "퓨",
            "ヴァ" to "바", "ヴィ" to "비", "ヴェ" to "베", "ヴォ" to "보", "ヴュ" to "뷰",
            "ウィ" to "위", "ウェ" to "웨", "ウォ" to "워",
            "ティ" to "티", "トゥ" to "투", "ディ" to "디", "ドゥ" to "두", "デュ" to "듀", "テュ" to "튜",
            "シェ" to "셰", "ジェ" to "제", "チェ" to "체",
            "ツァ" to "차", "ツィ" to "치", "ツェ" to "체", "ツォ" to "초",
            "イェ" to "예", "クァ" to "콰", "クィ" to "퀴", "クェ" to "퀘", "クォ" to "쿼", "グァ" to "과",
            "スィ" to "시", "ズィ" to "지", "ヒェ" to "혜"
        )
    }

    private fun toKatakana(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c in '\u3041'..'\u3096') sb.append((c.code + 0x60).toChar()) else sb.append(c)
        }
        return sb.toString()
    }

    /** 앞 글자에 받침을 붙인다 (ㅅ = 19, ㄴ = 4). 붙일 수 없으면 그냥 둔다 */
    private fun addBatchim(out: StringBuilder, jong: Int) {
        if (out.isEmpty()) return
        val last = out[out.length - 1]
        if (last in '\uAC00'..'\uD7A3' && (last - '\uAC00') % 28 == 0) {
            out.setCharAt(out.length - 1, (last.code + jong).toChar())
        }
    }

    fun convert(src: String): String {
        val s = toKatakana(src)
        val out = StringBuilder()
        var head = true
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == 'ー' -> i++                                   // 장음은 적지 않는다
                c == 'ッ' -> { addBatchim(out, 19); i++ }          // 촉음 -> 받침 ㅅ
                c == 'ン' -> { addBatchim(out, 4); head = false; i++ }   // ん -> 받침 ㄴ
                else -> {
                    val two = if (i + 1 < s.length) T[s.substring(i, i + 2)] else null
                    if (two != null) {
                        out.append(if (head) two[0] else two[1])
                        head = false
                        i += 2
                    } else {
                        val one = T[c.toString()]
                        if (one != null) {
                            out.append(if (head) one[0] else one[1])
                            head = false
                        } else {
                            out.append(c)
                            head = !c.isLetterOrDigit()
                        }
                        i++
                    }
                }
            }
        }
        return out.toString()
    }
}

/** 입력칸 위에 작게 보여 주는 일본어 음차. 일본어가 없으면 아무것도 안 보인다 */
@Composable
fun JpHint(text: String) {
    if (!JpReading.looksJapanese(text)) return
    var out by remember(text) { mutableStateOf<String?>(null) }
    var failed by remember(text) { mutableStateOf(false) }
    LaunchedEffect(text) {
        delay(250)   // 타자 치는 동안은 기다렸다가 계산
        val r = withContext(Dispatchers.Default) { JpReading.transliterate(text) }
        out = r
        failed = r == null
    }
    val o = out
    val msg = when {
        failed -> "음차 사전을 불러오지 못했어요"
        o == null -> "음차 불러오는 중…"
        o.isNotBlank() && o != text -> "음차 · $o"
        else -> null
    }
    if (msg != null) {
        Text(
            msg,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        )
    }
}
