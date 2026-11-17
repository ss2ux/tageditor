package com.ss2ux.tageditor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.YearMonth

/**
 * 수정 화면에서 "저장"을 눌렀을 때 만들어지는 요청.
 * null 인 항목 = 바꾸지 않음 (6단계의 태그 쓰기가 이걸 받아서 처리한다).
 * year: "" = 지우기, "2026" 또는 "2026.11.04" 형식
 * trackNo: 0 = 0번 트랙으로 저장, 음수(-1) = 지우기
 */
data class EditRequest(
    val trackIds: List<Long>,
    val title: String? = null,
    val trackNo: Int? = null,
    val album: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val artJpeg: ByteArray? = null,  // 1500x1500 JPEG
    val renameFolder: String? = null // 저장 후 앨범 폴더를 이 이름으로 바꿈 (null = 안 바꿈)
) {
    fun changedNames(): List<String> = buildList {
        if (title != null) add("트랙명")
        if (trackNo != null) add("트랙번호")
        if (album != null) add("앨범")
        if (artist != null) add("아티스트")
        if (albumArtist != null) add("앨범 아티스트")
        if (year != null) add("연도")
        if (genre != null) add("장르")
        if (artJpeg != null) add("앨범아트")
    }
}

// 폴더명 끝의 RJ번호(RJ123456, [RJ123456], (RJ01234567), _RJ123456 등)는 앨범명 기본값에서 뺀다.
private val rjTail = Regex("""[\s_\-]*[\[(（【]?\s*RJ\d{6,}\s*[\])）】]?\s*$""", RegexOption.IGNORE_CASE)

private fun stripRj(name: String): String {
    var r = name.trim()
    while (true) {
        val n = rjTail.replace(r, "").trim()
        if (n == r) break
        r = n
    }
    return r.ifBlank { name }   // 폴더명이 RJ번호뿐이면 그대로 둔다
}

// ----------------------------------------------------------------------
// 연도 입력: 숫자만 입력하면 점이 자동으로 들어간다 (20261104 -> 2026.11.04)
// 내부에는 숫자만 저장하고, 화면에만 점을 끼워서 보여준다.
// ----------------------------------------------------------------------

private class YearVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val d = text.text
        val sb = StringBuilder()
        for (i in d.indices) {
            if (i == 4 || i == 6) sb.append('.')
            sb.append(d[i])
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                if (offset <= 4) offset else if (offset <= 6) offset + 1 else offset + 2

            override fun transformedToOriginal(offset: Int): Int {
                val o = if (offset <= 4) offset else if (offset <= 7) offset - 1 else offset - 2
                return o.coerceIn(0, d.length)
            }
        }
        return TransformedText(AnnotatedString(sb.toString()), mapping)
    }
}

/** 숫자 4자리(yyyy) 또는 8자리(yyyymmdd, 실제 있는 날짜)만 허용. 비어 있는 것도 허용(지우기) */
private fun isValidYear(d: String): Boolean {
    if (d.isEmpty()) return true
    if (d.length != 4 && d.length != 8) return false
    val y = d.substring(0, 4).toInt()
    if (y < 1000) return false
    if (d.length == 4) return true
    val m = d.substring(4, 6).toInt()
    val day = d.substring(6, 8).toInt()
    if (m !in 1..12) return false
    return day in 1..YearMonth.of(y, m).lengthOfMonth()
}

private fun formatYear(d: String): String =
    if (d.length == 8) "${d.substring(0, 4)}.${d.substring(4, 6)}.${d.substring(6, 8)}" else d

// ----------------------------------------------------------------------
// 수정 화면 (전체 화면)
// ----------------------------------------------------------------------

/**
 * track == null  -> 앨범 모드: 앨범 전체 곡에 일괄 적용
 * track != null  -> 트랙 모드: 그 곡에만 적용 (트랙명, 트랙번호 입력칸이 추가됨)
 *
 * 값이 곡마다 다른 항목(여러 값)은 빈칸으로 시작하고, 비워 두면 "변경 안 함"이다.
 * 단 장르는 예외: 항상 빈칸으로 시작하고, 비운 채 저장하면 모든 곡의 장르를 지운다.
 * 앨범명은 RJ번호를 뺀 값(또는 폴더명)을 보여 주고, 실제 태그와 다르면 저장할 때 전 곡에 써 넣는다.
 */
@Composable
fun EditScreen(
    album: Album,
    tags: AlbumTags,
    track: Track?,
    currentArt: Bitmap?,
    onClose: () -> Unit,
    onSave: (EditRequest) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 이 기기의 다른 앨범들에 적힌 "앨범 아티스트" 목록 (추천용). 수정 화면을 열 때 한 번 읽는다.
    var deviceAlbumArtists by remember { mutableStateOf(AlbumArtistIndex.names) }
    LaunchedEffect(Unit) {
        deviceAlbumArtists = withContext(Dispatchers.IO) { AlbumArtistIndex.load(ctx) }
    }
    val trackMode = track != null
    val tTag = track?.let { tags.perTrack[it.id] }

    // 시작값
    // 트랙명: 태그에 제목이 있으면 그 값, 없으면 파일명(삭제문구 .mp3 제외)을 그대로 보여준다.
    // 비교 기준(iTitle)은 처음 보여준 값이라, 건드리지 않으면 트랙명 태그는 바뀌지 않는다.
    val fileTitle = NameCleaner.clean(track?.displayName ?: "")
    val rawTitle = tTag?.title ?: ""
    val iTitle = if (rawTitle.isBlank()) NameCleaner.clean(track?.displayName ?: "") else rawTitle
    val startTitle = if (rawTitle.isBlank()) iTitle else NameCleaner.clean(rawTitle)
    val iTrackNo = if (tTag != null && tTag.hasTrackNo) tTag.trackNo.toString() else ""
    // 앨범명 기본값: 태그가 비어 있거나 곡마다 다르면 폴더명을 넣어 둔다
    val folderName = remember(album) {
        val p = album.path.trimEnd('/')
        if ('/' !in p) "" else stripRj(p.substringAfterLast('/'))   // Download/Music 바로 아래 낱개 곡은 폴더명 없음
    }
    // 실제 태그에 들어 있는 앨범명 (앨범 모드에서 곡마다 다르면 ""). 바뀌었는지는 이 값과 비교한다.
    val tagAlbum = if (trackMode) (tTag?.album ?: "") else if (tags.album.mixed) "" else tags.album.value
    // 화면에 처음 보여 줄 값: 앨범 모드에서는 RJ번호를 뺀 값, 비어 있거나 곡마다 다르면 폴더명
    // (보여 주는 값이 실제 태그와 다르면 "바뀐 것"으로 쳐서 저장할 때 모든 곡에 실제로 써 넣는다)
    // 폴더명 바꾸기(앨범 모드, RJ 폴더일 때만): 폴더의 RJ번호와 "폴더에 한 앨범만 있는지"를 본다
    val rawFolderName = remember(album) {
        val p = album.path.trimEnd('/')
        if ('/' !in p) "" else p.substringAfterLast('/')
    }
    val rjCode = remember(rawFolderName) { RjSearch.extract(rawFolderName) }
    val canRenameFolder = !trackMode && rjCode != null && rawFolderName.isNotBlank()
    var renameChecked by remember { mutableStateOf(FolderPrefs.renameOnSave(ctx)) }
    val iAlbum = NameCleaner.clean(if (trackMode) tagAlbum else stripRj(tagAlbum).ifBlank { folderName })
    val iArtist = if (trackMode) (tTag?.artist ?: "") else if (tags.artist.mixed) "" else tags.artist.value
    val iAlbumArtist =
        if (trackMode) (tTag?.albumArtist ?: "") else if (tags.albumArtist.mixed) "" else tags.albumArtist.value
    // 장르: 항상 빈칸으로 시작하고, 빈칸인 채로 저장하면 장르를 모두 지운다 (RJ번호를 빼고 넣는 것처럼 장르도 날림)
    val iGenre = ""
    val genreHas = if (trackMode) !(tTag?.genre).isNullOrBlank() else (tags.genre.value.isNotBlank() || tags.genre.mixed)
    val iYear = (if (trackMode) (tTag?.year ?: "") else if (tags.year.mixed) "" else tags.year.value)
        .filter { it.isDigit() }.take(8)

    // 새로 추가한 곡처럼 이 곡의 값이 비어 있으면, 앨범에서 가장 많은 값(다수값)을 자동으로 채운다.
    // (Agg.value 는 곡마다 값이 달라도 "가장 많은 값"이 들어 있다)
    val albumArtistMajor = if (trackMode) tags.albumArtist.value.trim() else ""
    val artistMajor = if (trackMode) tags.artist.value.trim() else ""
    val yearMajor = if (trackMode) tags.year.value.filter { it.isDigit() }.take(8) else ""
    val autoArtist = if (trackMode && iArtist.isBlank()) artistMajor else ""
    val autoAlbumArtist = if (trackMode && iAlbumArtist.isBlank()) albumArtistMajor else ""
    val autoYear = if (trackMode && iYear.isBlank()) yearMajor else ""
    val autoFilledNames = buildList {
        if (autoArtist.isNotEmpty()) add("아티스트")
        if (autoAlbumArtist.isNotEmpty()) add("앨범 아티스트")
        if (autoYear.isNotEmpty()) add("연도")
    }

    val mAlbum = !trackMode && tags.album.mixed && iAlbum.isBlank()
    val mArtist = !trackMode && tags.artist.mixed
    val mAlbumArtist = !trackMode && tags.albumArtist.mixed
    val mYear = !trackMode && tags.year.mixed

    // 입력 중인 값
    // 처음부터 전체가 선택된 상태로 열어서, 바로 타이핑하면 통째로 바뀌고 방향키/탭으로 일부만 고칠 수도 있다
    var titleTv by remember { mutableStateOf(TextFieldValue(startTitle, TextRange(0, startTitle.length))) }
    val title = titleTv.text
    val titleFocus = remember { FocusRequester() }
    var titleRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val view = LocalView.current
    val toolbar = remember(view) { TranslateTextToolbar(view) }
    val clipboard = LocalClipboardManager.current

    // ---- DeepL 번역 ----
    // 이미 깔려 있는 DeepL 앱의 "텍스트 처리(PROCESS_TEXT)" 창을 띄운다.
    // DeepL 창에서 "바꾸기"처럼 결과를 돌려주면 선택했던 부분을 번역문으로 바꿔 넣는다.
    var translateRange by remember { mutableStateOf<TextRange?>(null) }
    val translateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val out = result.data?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        val r = translateRange
        if (result.resultCode == Activity.RESULT_OK && !out.isNullOrBlank() && r != null) {
            val t = titleTv
            val a = r.min.coerceIn(0, t.text.length)
            val b = r.max.coerceIn(a, t.text.length)
            val rep = out.replace('\n', ' ')
            titleTv = TextFieldValue(t.text.substring(0, a) + rep + t.text.substring(b), TextRange(a + rep.length))
        }
        translateRange = null
    }
    fun startTranslate() {
        val t = titleTv
        // 선택한 글자가 없으면 트랙명 전체를 번역
        val sel = if (t.selection.collapsed) TextRange(0, t.text.length) else t.selection
        val src = t.text.substring(sel.min, sel.max)
        if (src.isBlank()) {
            Toast.makeText(ctx, "번역할 글자가 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        val pm = ctx.packageManager
        fun isDeepl(ri: android.content.pm.ResolveInfo) =
            ri.activityInfo.packageName.contains("deepl", ignoreCase = true) ||
                ri.loadLabel(pm).toString().contains("deepl", ignoreCase = true)
        try {
            // 1순위: 텍스트 선택 메뉴용 창 (결과를 돌려받아 바로 바꿔 넣을 수 있음)
            val pt = pm.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)
                .firstOrNull { isDeepl(it) }
            if (pt != null) {
                translateRange = sel
                translateLauncher.launch(
                    Intent(Intent.ACTION_PROCESS_TEXT)
                        .setClassName(pt.activityInfo.packageName, pt.activityInfo.name)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_PROCESS_TEXT, src)
                        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
                )
                return
            }
            // 2순위: 공유 창 (번역문은 DeepL 화면에서 복사해서 붙여넣어야 함)
            val sd = pm.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("text/plain"), 0)
                .firstOrNull { isDeepl(it) }
            if (sd != null) {
                ctx.startActivity(
                    Intent(Intent.ACTION_SEND)
                        .setClassName(sd.activityInfo.packageName, sd.activityInfo.name)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, src)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Toast.makeText(ctx, "번역문은 DeepL에서 복사한 뒤 붙여넣어 주세요", Toast.LENGTH_LONG).show()
                return
            }
            Toast.makeText(ctx, "DeepL 앱을 찾지 못했어요. 설치되어 있는지 확인해 주세요", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            translateRange = null
            Toast.makeText(ctx, "DeepL을 열지 못했어요", Toast.LENGTH_SHORT).show()
        }
    }
    // 메뉴에서 번역을 눌렀을 때 항상 최신 트랙명으로 실행되도록 매번 연결해 둔다
    SideEffect { toolbar.onTranslate = { startTranslate() } }
    LaunchedEffect(Unit) {
        if (trackMode) {
            try {
                titleFocus.requestFocus()
            } catch (e: Exception) {
                // 포커스를 못 줘도 직접 눌러서 편집 가능
            }
            // 열리자마자 잘라내기/복사/붙여넣기 메뉴도 같이 띄운다
            delay(350)
            val r = titleRect
            if (r != null) {
                toolbar.showMenu(
                    rect = r,
                    onCopyRequested = {
                        val t = titleTv
                        if (!t.selection.collapsed) {
                            clipboard.setText(AnnotatedString(t.text.substring(t.selection.min, t.selection.max)))
                        }
                        toolbar.hide()
                    },
                    onPasteRequested = {
                        val t = titleTv
                        val clip = (clipboard.getText()?.text ?: "").replace('\n', ' ')
                        val nt = t.text.substring(0, t.selection.min) + clip + t.text.substring(t.selection.max)
                        titleTv = TextFieldValue(nt, TextRange(t.selection.min + clip.length))
                        toolbar.hide()
                    },
                    onCutRequested = {
                        val t = titleTv
                        if (!t.selection.collapsed) {
                            clipboard.setText(AnnotatedString(t.text.substring(t.selection.min, t.selection.max)))
                            titleTv = TextFieldValue(t.text.removeRange(t.selection.min, t.selection.max), TextRange(t.selection.min))
                        }
                        toolbar.hide()
                    },
                    onSelectAllRequested = {
                        titleTv = titleTv.copy(selection = TextRange(0, titleTv.text.length))
                        toolbar.hide()
                    }
                )
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { toolbar.hide() }
    }
    var trackNoText by remember { mutableStateOf(iTrackNo) }
    var albumText by remember { mutableStateOf(iAlbum) }
    var artistText by remember { mutableStateOf(iArtist.ifBlank { autoArtist }) }
    var albumArtistText by remember { mutableStateOf(iAlbumArtist.ifBlank { autoAlbumArtist }) }
    var genreText by remember { mutableStateOf(iGenre) }
    var yearDigits by remember { mutableStateOf(iYear.ifBlank { autoYear }) }

    var newArt by remember { mutableStateOf<NewArt?>(null) }
    var artBusy by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // 확정된 원본/영역 (다시 조정할 때 사용) 과, 지금 조정 화면에 올라가 있는 것
    var srcBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var srcCrop by remember { mutableStateOf<CropRect?>(null) }
    var pendingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingCrop by remember { mutableStateOf<CropRect?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            artBusy = true
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { ArtProcessor.decode(ctx, uri) }
                artBusy = false
                if (bmp != null) {
                    pendingBitmap = bmp
                    pendingCrop = ArtProcessor.initialCrop(bmp)
                } else {
                    Toast.makeText(ctx, "이미지를 읽지 못했어요", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 무엇이 바뀌었나
    val yearOk = yearDigits == iYear || isValidYear(yearDigits)
    val cTitle = trackMode && title.trim() != iTitle.trim()
    val cTrackNo = trackMode && trackNoText != iTrackNo
    val cAlbum = albumText.trim() != tagAlbum.trim()
    val artistFinal = artistText.trim().trimEnd('/').trim()   // 입력 중 끝에 남은 " / " 는 저장할 때 뺀다
    val cArtist = artistFinal != iArtist.trim()
    val cAlbumArtist = albumArtistText.trim() != iAlbumArtist.trim()
    val cGenre = genreHas || genreText.trim().isNotEmpty()   // 지울 장르가 있거나, 새로 입력했으면 바뀐 것
    val cYear = yearDigits != iYear
    val anyChange = cTitle || cTrackNo || cAlbum || cArtist || cAlbumArtist || cGenre || cYear || newArt != null
    // 바꿀 폴더 이름 = 앨범 입력칸의 제목(치환 규칙 적용) + 공백 + RJ번호
    // 앨범 태그는 저장할 때 치환 규칙이 적용되므로, 폴더명도 같은 규칙을 거쳐서 둘이 같게 맞춘다
    // 규칙·제외 단어·조사 보정 설정이 바뀌면 다음 재구성 때 엔진이 새로 만들어진다
    val ruleEngine = TagRuleEngine(TagRules.load(ctx), TagRules.fixJosa(ctx), TagRules.protect(ctx))
    val folderTarget = if (canRenameFolder && rjCode != null) {
        FolderRename.buildName(NameCleaner.clean(ruleEngine.clean(albumText.trim())), rjCode)
    } else ""
    val folderRenameOn = canRenameFolder && renameChecked && folderTarget.isNotBlank() && folderTarget != rawFolderName
    val canSave = (anyChange || folderRenameOn) && yearOk && !artBusy

    BackHandler(enabled = pendingBitmap == null) { if (anyChange) confirmDiscard = true else onClose() }

    Box(modifier = Modifier.fillMaxSize()) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            // 상단 바
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (anyChange) confirmDiscard = true else onClose() }) {
                    Icon(Icons.Default.Close, contentDescription = "닫기")
                }
                Text(
                    if (trackMode) "트랙 수정" else "앨범 수정",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = canSave,
                    onClick = {
                        val built = EditRequest(
                                trackIds = if (track != null) listOf(track.id) else album.tracks.map { it.id },
                                title = if (cTitle) NameCleaner.clean(title) else null,
                                trackNo = if (cTrackNo) (trackNoText.toIntOrNull() ?: -1) else null,
                                album = if (cAlbum) NameCleaner.clean(albumText) else null,
                                artist = if (cArtist) artistFinal else null,
                                albumArtist = if (cAlbumArtist) albumArtistText.trim() else null,
                                year = if (cYear) formatYear(yearDigits) else null,
                                // 빈칸이면 "" -> 장르 프레임 삭제. 화면에 안 보이는 이상한 값도 같이 지워진다
                                genre = genreText.trim(),
                                artJpeg = newArt?.jpeg,
                                renameFolder = if (folderRenameOn) folderTarget else null
                        )
                        // 치환 규칙 적용: 바뀐 게 있으면 작은 알림으로 알려 준다
                        val (converted, ruleChanges) = TagRules.applyTo(ctx, built)
                        TagRules.notifyChanges(ctx, ruleChanges)
                        onSave(converted)
                    }
                ) { Text("저장", fontSize = 16.sp) }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                // 적용 범위 안내
                Text(
                    if (track == null) "앨범 전체 ${album.tracks.size}곡에 적용돼요"
                    else "‘${track.title}’ 트랙에만 적용돼요",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                // 앨범아트 (눌러서 이미지 선택)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { picker.launch("image/*") }
                ) {
                    val shown = newArt?.bitmap ?: currentArt
                    if (shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.Center),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (artBusy) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    Text(
                        "눌러서 이미지 선택",
                        color = Color.White,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color(0x99000000))
                            .padding(vertical = 6.dp)
                    )
                }
                val na = newArt
                if (na != null) {
                    Text(
                        "새 앨범아트 ${ArtProcessor.SIZE}×${ArtProcessor.SIZE} · ${na.jpeg.size / 1024}KB",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                } else {
                    // 앨범아트를 아직 안 골랐을 때: 폴더명의 RJ 번호로 검색하는 줄
                    val rj = remember(album) {
                        val p = album.path.trimEnd('/')
                        if ('/' !in p) null else RjSearch.extract(p.substringAfterLast('/'))
                    }
                    val rjUrl = remember { Prefs.rjUrl(ctx) }
                    RjSearchRow(rj, rjUrl)
                }
                if (na != null) {
                    Row(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        TextButton(onClick = {
                            val sb = srcBitmap
                            val sc = srcCrop
                            if (sb != null && sc != null) {
                                pendingBitmap = sb
                                pendingCrop = sc
                            }
                        }) { Text("위치·확대 조정") }
                        TextButton(onClick = {
                            newArt = null
                            srcBitmap = null
                            srcCrop = null
                        }) { Text("선택 취소") }
                    }
                }

                // 입력칸
                if (trackMode) {
                    // 트랙명 지우기: 키보드가 내려가 있어도(DeepL을 쓰고 돌아온 뒤 등) 누르면 바로 비워진다.
                    // 비운 뒤에는 바로 붙여넣을 수 있게 커서를 트랙명 칸으로 돌려놓고 키보드를 다시 올린다.
                    TextButton(
                        onClick = {
                            toolbar.hide()
                            titleTv = TextFieldValue("", TextRange(0))
                            scope.launch {
                                try {
                                    titleFocus.requestFocus()
                                } catch (e: Exception) {
                                    // 포커스를 못 줘도 이미 비워졌으니 괜찮다
                                }
                                delay(80)
                                try {
                                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                                } catch (e: Exception) {
                                    // 키보드는 직접 눌러서 올려도 된다
                                }
                            }
                        },
                        enabled = title.isNotEmpty(),
                        modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                    ) { Text("트랙명 지우기") }
                    JpHint(title)
                    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                    OutlinedTextField(
                        value = titleTv,
                        onValueChange = {
                            if (it.text != titleTv.text) toolbar.hide()
                            titleTv = it
                        },
                        label = { Text("트랙명") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .onGloballyPositioned { titleRect = it.boundsInRoot() }
                            .focusRequester(titleFocus)
                    )
                    }
                    if (fileTitle.isNotBlank() && title.trim() != fileTitle) {
                        TextButton(
                            onClick = {
                                toolbar.hide()
                                titleTv = TextFieldValue(fileTitle, TextRange(fileTitle.length))
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) { Text("파일명으로: $fileTitle", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    EditField(
                        "트랙번호", trackNoText,
                        { trackNoText = it.filter { c -> c.isDigit() }.take(3) },
                        keyboardType = KeyboardType.Number
                    )
                }
                JpHint(albumText)
                EditField("앨범", albumText, { albumText = it }, mAlbum)
                if (!trackMode && folderName.isNotBlank() && albumText != folderName) {
                    TextButton(
                        onClick = { albumText = folderName },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("폴더명으로: $folderName", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (canRenameFolder && rjCode != null) {
                    // 폴더명 바꾸기: RJ 검색 -> 제목 복사 -> 돌아와서 붙여넣기 -> 저장하면 "제목 RJ번호" 폴더로 변경
                    TextButton(
                        onClick = {
                            val clip = clipboard.getText()?.text?.replace('\n', ' ')?.trim() ?: ""
                            if (clip.isEmpty()) {
                                Toast.makeText(ctx, "클립보드가 비어 있어요", Toast.LENGTH_SHORT).show()
                            } else {
                                albumText = clip
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("클립보드 내용을 앨범명으로") }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                renameChecked = !renameChecked
                                FolderPrefs.setRenameOnSave(ctx, renameChecked)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = renameChecked,
                            onCheckedChange = {
                                renameChecked = it
                                FolderPrefs.setRenameOnSave(ctx, it)
                            }
                        )
                        Text("저장할 때 폴더명도 바꾸기", fontSize = 14.sp)
                    }
                    if (renameChecked) {
                        Text(
                            when {
                                folderTarget.isBlank() -> "앨범명을 입력하면 폴더명이 정해져요"
                                folderTarget == rawFolderName -> "폴더명이 이미 같아요 · 바뀌지 않아요"
                                else -> "$rawFolderName\n→ $folderTarget"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                        )
                    }
                }
                if (trackMode && (artistMajor.isNotEmpty() || albumArtistMajor.isNotEmpty() || yearMajor.isNotEmpty())) {
                    Text(
                        if (autoFilledNames.isNotEmpty())
                            "비어 있던 ${autoFilledNames.joinToString(", ")}을(를) 앨범의 다수값으로 채웠어요"
                        else "아티스트·앨범 아티스트·연도를 앨범의 다수값으로 바꿀 수 있어요",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    TextButton(
                        onClick = {
                            if (artistMajor.isNotEmpty()) artistText = artistMajor
                            if (albumArtistMajor.isNotEmpty()) albumArtistText = albumArtistMajor
                            if (yearMajor.isNotEmpty()) yearDigits = yearMajor
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("앨범 다수값 불러오기") }
                }
                JpHint(artistText)
                ArtistField("아티스트", artistText, { artistText = it }, mArtist)
                JpHint(albumArtistText)
                // 한 글자 이상 적으면, 이미 있는 아티스트 이름 중 비슷한 것을 작게 추천한다
                val aaQuery = albumArtistText.trim()
                if (aaQuery.isNotEmpty()) {
                    val sugg = suggestAlbumArtists(aaQuery, artistText, tags, deviceAlbumArtists)
                    if (sugg.isNotEmpty()) {
                        SuggestionRow(sugg) { albumArtistText = it }
                    }
                }
                EditField("앨범 아티스트", albumArtistText, { albumArtistText = it }, mAlbumArtist)

                // 연도: 숫자만 입력, 점은 자동
                OutlinedTextField(
                    value = yearDigits,
                    onValueChange = { v -> yearDigits = v.filter { c -> c.isDigit() }.take(8) },
                    label = { Text("연도") },
                    placeholder = { Text(if (mYear) "여러 값 · 그대로 두면 변경 안 함" else "yyyy.mm.dd") },
                    supportingText = {
                        Text(
                            if (!yearOk) "2026 또는 20261104 처럼 숫자만 입력해 주세요"
                            else "숫자만 입력하면 점(.)이 자동으로 들어가요"
                        )
                    },
                    isError = !yearOk,
                    singleLine = true,
                    visualTransformation = YearVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )

                EditField("장르", genreText, { genreText = it }, hint = "비워 두고 저장하면 장르가 모두 지워져요")

                Box(modifier = Modifier.padding(bottom = 96.dp))
            }
        }
    }


    val pb = pendingBitmap
    val pc = pendingCrop
    if (pb != null && pc != null) {
        ArtCropScreen(
            source = pb,
            initial = pc,
            onCancel = { pendingBitmap = null; pendingCrop = null },
            onConfirm = { crop ->
                pendingBitmap = null
                pendingCrop = null
                artBusy = true
                scope.launch {
                    val r = withContext(Dispatchers.Default) { ArtProcessor.render(pb, crop) }
                    artBusy = false
                    if (r != null) {
                        newArt = r
                        srcBitmap = pb
                        srcCrop = crop
                    } else {
                        Toast.makeText(ctx, "앨범아트를 만들지 못했어요", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("변경 내용을 버릴까요?") },
            text = { Text("저장하지 않은 변경 내용이 사라져요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text("버리기") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("계속 수정") }
            }
        )
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    mixed: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    hint: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = {
            if (mixed) Text("여러 값 · 그대로 두면 변경 안 함") else if (hint != null) Text(hint)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    )
}

// ----------------------------------------------------------------------
// 아티스트 입력칸: 이름 사이를 " / " 로 자동 정리
//  - 띄어쓰기(또는 /)를 치면 " / " 가 들어간다.   A + 스페이스 -> "A / "
//  - 한 번 붙여넣고 또 붙여넣으면 앞뒤가 자동으로 나뉜다.   "A" 에 "B" 붙여넣기 -> "A / B"
//  - 지울 때: "A / " 에서 백스페이스를 한 번 누르면 "A" 가 된다.
// 아티스트 이름 안에 띄어쓰기가 없다는 전제이므로, 이 칸에서 띄어쓰기는 이름 구분으로 취급한다.
// 처음부터 들어 있던 값은 사용자가 건드리기 전에는 그대로 둔다.
// ----------------------------------------------------------------------

private fun isSepChar(c: Char): Boolean = c.isWhitespace() || c == '/'

/** 바뀐 입력을 정리한다. 반환: (새 글, 커서 위치). null 이면 손대지 않고 그대로 통과. */
private fun reformatArtist(old: String, new: String, cursor: Int, composing: Boolean, clip: () -> String?): Pair<String, Int>? {
    // 이전 글과 새 글을 비교해 "지워진 부분"과 "들어온 부분"을 찾는다
    var p = 0
    val maxP = minOf(old.length, new.length)
    while (p < maxP && old[p] == new[p]) p++
    var s = 0
    val maxS = minOf(old.length, new.length) - p
    while (s < maxS && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
    val removed = old.substring(p, old.length - s)
    val inserted = new.substring(p, new.length - s)
    val deleting = new.length < old.length

    // 붙여넣기 판단:
    //  (1) 글자 조합 중이 아닐 때 2글자 이상이 한꺼번에 들어왔거나(= 붙여넣기),
    //  (2) 선택한 글자를 바꿔 넣는 경우엔 클립보드 내용과 같을 때
    val ins = inserted.trim()
    val bigInsert = !composing && removed.isEmpty() && ins.length >= 2
    val isPaste = bigInsert || (ins.length >= 2 && ins == (clip()?.trim() ?: ""))

    // 띄어쓰기/슬래시가 오가지도 않고 붙여넣기도 아니면 (일반 글자 입력) 손대지 않는다 (한글 조합 보호)
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

    // 이름 조각으로 나눠서 " / " 로 다시 잇는다
    val tokens = ArrayList<String>()
    val sb = StringBuilder()
    for (ch in raw) {
        if (isSepChar(ch)) {
            if (sb.isNotEmpty()) {
                tokens.add(sb.toString())
                sb.setLength(0)
            }
        } else {
            sb.append(ch)
        }
    }
    if (sb.isNotEmpty()) tokens.add(sb.toString())
    var text = tokens.joinToString(" / ")
    // 끝에서 띄어쓰기를 쳤으면 다음 이름을 위해 " / " 를 붙여 둔다 (지우는 중에는 붙이지 않음)
    if (!deleting && tokens.isNotEmpty() && isSepChar(raw.last())) text += " / "

    // 커서: "이름 글자 몇 개 뒤"였는지를 새 글에서 찾는다
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
    if (cur >= raw.length) {
        pos = text.length
    } else if (k > 0 && cur > 0 && isSepChar(raw[cur - 1]) && text.startsWith(" / ", pos)) {
        pos += 3
    }
    return text to pos
}

@Composable
private fun ArtistField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    mixed: Boolean = false
) {
    val clipboard = LocalClipboardManager.current
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }

    // 바깥에서 값이 바뀌면(예: 버튼으로 채움) 따라간다
    LaunchedEffect(value) {
        if (tfv.text != value) tfv = TextFieldValue(value, TextRange(value.length))
    }

    OutlinedTextField(
        value = tfv,
        onValueChange = { nv ->
            val r = if (nv.text == tfv.text) null
            else reformatArtist(tfv.text, nv.text, nv.selection.end, nv.composition != null) { clipboard.getText()?.text }
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
                // 칸을 벗어날 때 끝에 남은 " / " 는 지운다
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

// ----------------------------------------------------------------------
// 앨범 아티스트 추천
// ----------------------------------------------------------------------

/**
 * 이 기기의 모든 음악 파일에 적힌 "앨범 아티스트" 태그 모음 (안드로이드 미디어 DB에서 읽음 · 파일을 열지 않아 빠름).
 * 많이 쓰인 것이 앞에 온다. 안드로이드 11 미만에서는 미디어 DB에 이 정보가 없어 빈 목록이다.
 */
object AlbumArtistIndex {
    @Volatile
    var names: List<String> = emptyList()

    fun load(context: android.content.Context): List<String> {
        if (android.os.Build.VERSION.SDK_INT < 30) return names
        val counts = HashMap<String, Int>()
        try {
            val col = android.provider.MediaStore.Audio.AudioColumns.ALBUM_ARTIST
            context.contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(col),
                "$col IS NOT NULL AND $col != ''",
                null,
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val v = (c.getString(0) ?: "").trim()
                    if (v.isEmpty() || v == "<unknown>") continue
                    counts[v] = (counts[v] ?: 0) + 1
                }
            }
        } catch (e: Exception) {
            return names
        }
        // "A / B" 처럼 여러 명이면 한 명씩도 후보로 (한 명 쪽에 개수를 더해 준다)
        val merged = HashMap<String, Int>(counts)
        for ((full, n) in counts) {
            if (" / " in full) {
                for (part in full.split(" / ")) {
                    val t = part.trim()
                    if (t.isNotEmpty()) merged[t] = (merged[t] ?: 0) + n
                }
            }
        }
        names = merged.entries.sortedByDescending { it.value }.map { it.key }
        return names
    }
}

/** 목록에 있는 모든 앨범의 아티스트 이름 모음 (목록 화면이 갱신할 때마다 채운다) */
object ArtistIndex {
    @Volatile
    var names: List<String> = emptyList()

    fun update(albums: List<Album>) {
        val set = LinkedHashSet<String>()
        for (a in albums) {
            val full = a.artist.trim()
            if (full.isEmpty() || full == "알 수 없는 아티스트") continue
            set.add(full)
            for (n in full.split(" / ")) {
                val t = n.trim()
                if (t.isNotEmpty()) set.add(t)
            }
        }
        names = set.toList()
    }
}

/**
 * 추천 후보를 모은다. 우선순위:
 *  1) 지금 이 화면의 아티스트 칸  2) 이 앨범 곡들의 태그(아티스트/앨범 아티스트)  3) 목록 전체의 아티스트
 * 적은 글자로 시작하는 것을 먼저, 글자가 들어 있는 것을 그다음에 보여준다.
 */
private fun suggestAlbumArtists(query: String, artistText: String, tags: AlbumTags, deviceAlbumArtists: List<String>): List<String> {
    val pool = ArrayList<String>()
    pool.addAll(deviceAlbumArtists)   // 이 기기의 다른 앨범들에 적힌 앨범 아티스트가 가장 먼저
    fun addWithParts(v: String) {
        val t = v.trim()
        if (t.isEmpty()) return
        pool.add(t)
        for (n in t.split(" / ")) {
            val x = n.trim()
            if (x.isNotEmpty()) pool.add(x)
        }
    }
    addWithParts(artistText)
    addWithParts(tags.albumArtist.value)
    addWithParts(tags.artist.value)
    for (t in tags.perTrack.values) {
        addWithParts(t.artist)
        addWithParts(t.albumArtist)
    }
    pool.addAll(ArtistIndex.names)

    val seen = HashSet<String>()
    val starts = ArrayList<String>()
    val contains = ArrayList<String>()
    for (c in pool) {
        if (c.equals(query, ignoreCase = true)) continue
        if (!seen.add(c.lowercase())) continue
        if (c.startsWith(query, ignoreCase = true)) starts.add(c)
        else if (c.contains(query, ignoreCase = true)) contains.add(c)
    }
    return (starts + contains).take(6)
}

/** 입력칸 위에 작게 보여 주는 추천 줄. 누르면 그 값으로 입력된다. */
@Composable
private fun SuggestionRow(items: List<String>, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "추천 · 누르면 입력돼요",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        for (item in items) {
            Text(
                item,
                fontSize = 12.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable { onPick(item) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
