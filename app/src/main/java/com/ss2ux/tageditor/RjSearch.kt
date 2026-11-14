package com.ss2ux.tageditor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 폴더명에서 RJ 번호를 뽑아, 사용자가 설정에 적어 둔 검색 주소로 외부 브라우저를 연다.
 * 검색 주소 안의 "RJ값" 이라는 글자가 RJ 번호(예: RJ123456)로 바뀐다.
 */
object RjSearch {
    const val PLACEHOLDER = "RJ값"

    private val RJ = Regex("RJ\\d{6,8}", RegexOption.IGNORE_CASE)

    /** 글에서 처음 나오는 RJ 번호 (없으면 null). 항상 대문자로 돌려준다. */
    fun extract(text: String): String? = RJ.find(text)?.value?.uppercase()

    fun hasPlaceholder(template: String): Boolean = template.contains(PLACEHOLDER)

    fun buildUrl(template: String, rj: String): String {
        var u = template.trim().replace(PLACEHOLDER, rj)
        if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) {
            u = "https://$u"
        }
        return u
    }

    fun open(context: Context, template: String, rj: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(buildUrl(template, rj))))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "브라우저를 열 수 없어요", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "주소를 열 수 없어요. 설정의 검색 주소를 확인해 주세요", Toast.LENGTH_LONG).show()
        }
    }
}

/** 수정 화면의 앨범아트 아래에 들어가는 RJ 검색 줄 */
@Composable
fun RjSearchRow(rj: String?, template: String) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            rj == null -> Text(
                "폴더명에서 RJ 번호를 찾지 못했어요",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
            template.isBlank() || !RjSearch.hasPlaceholder(template) -> Text(
                "$rj · 목록 화면의 설정(톱니)에서 검색 주소를 등록하면 바로 검색할 수 있어요",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
            else -> OutlinedButton(onClick = { RjSearch.open(ctx, template, rj) }) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("$rj 검색하기")
            }
        }
    }
}
