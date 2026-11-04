package com.ss2ux.tageditor

import android.content.Context
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.YearMonth

/**
 * 수정 화면에서 "저장"을 눌렀을 때 만들어지는 요청.
 * null 인 항목 = 바꾸지 않음 (6단계의 태그 쓰기가 이걸 받아서 처리한다).
 * year: "" = 지우기, "2026" 또는 "2026.11.04" 형식
 * trackNo: 0 = 지우기
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
    val artJpeg: ByteArray? = null   // 1500x1500 JPEG
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
// 앨범아트: 고른 이미지를 중앙 기준 정사각형으로 잘라 1500x1500 JPEG로 만든다
// ----------------------------------------------------------------------

class NewArt(val bitmap: Bitmap, val jpeg: ByteArray)

object ArtProcessor {
    const val SIZE = 1500

    /** 시간이 걸리므로 반드시 백그라운드에서 호출. 실패하면 null */
    fun fromUri(context: Context, uri: Uri): NewArt? {
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // 짧은 변이 1500 이상으로 남도록, 너무 큰 사진은 미리 줄여서 읽음 (메모리 절약)
                val s = minOf(info.size.width, info.size.height) / SIZE
                if (s > 1) decoder.setTargetSampleSize(s)
            }
            val w = decoded.width
            val h = decoded.height
            val side = minOf(w, h)
            val left = (w - side) / 2
            val top = (h - side) / 2

            val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(android.graphics.Color.WHITE)   // 투명 PNG는 흰 배경 위에
            canvas.drawBitmap(
                decoded,
                Rect(left, top, left + side, top + side),
                Rect(0, 0, SIZE, SIZE),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            decoded.recycle()

            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 92, bos)
            NewArt(out, bos.toByteArray())
        } catch (e: Exception) {
            null
        }
    }
}

// ----------------------------------------------------------------------
// 수정 화면 (전체 화면)
// ----------------------------------------------------------------------

/**
 * track == null  -> 앨범 모드: 앨범 전체 곡에 일괄 적용
 * track != null  -> 트랙 모드: 그 곡에만 적용 (트랙명, 트랙번호 입력칸이 추가됨)
 *
 * 값이 곡마다 다른 항목(여러 값)은 빈칸으로 시작하고, 비워 두면 "변경 안 함"이다.
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
    val trackMode = track != null
    val tTag = track?.let { tags.perTrack[it.id] }

    // 시작값
    val iTitle = tTag?.title ?: ""
    val iTrackNo = if (tTag != null && tTag.trackNo > 0) tTag.trackNo.toString() else ""
    val iAlbum = if (trackMode) (tTag?.album ?: "") else if (tags.album.mixed) "" else tags.album.value
    val iArtist = if (trackMode) (tTag?.artist ?: "") else if (tags.artist.mixed) "" else tags.artist.value
    val iAlbumArtist =
        if (trackMode) (tTag?.albumArtist ?: "") else if (tags.albumArtist.mixed) "" else tags.albumArtist.value
    val iGenre = if (trackMode) (tTag?.genre ?: "") else if (tags.genre.mixed) "" else tags.genre.value
    val iYear = (if (trackMode) (tTag?.year ?: "") else if (tags.year.mixed) "" else tags.year.value)
        .filter { it.isDigit() }.take(8)

    val mAlbum = !trackMode && tags.album.mixed
    val mArtist = !trackMode && tags.artist.mixed
    val mAlbumArtist = !trackMode && tags.albumArtist.mixed
    val mGenre = !trackMode && tags.genre.mixed
    val mYear = !trackMode && tags.year.mixed

    // 입력 중인 값
    var title by remember { mutableStateOf(iTitle) }
    var trackNoText by remember { mutableStateOf(iTrackNo) }
    var albumText by remember { mutableStateOf(iAlbum) }
    var artistText by remember { mutableStateOf(iArtist) }
    var albumArtistText by remember { mutableStateOf(iAlbumArtist) }
    var genreText by remember { mutableStateOf(iGenre) }
    var yearDigits by remember { mutableStateOf(iYear) }

    var newArt by remember { mutableStateOf<NewArt?>(null) }
    var artBusy by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            artBusy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { ArtProcessor.fromUri(ctx, uri) }
                artBusy = false
                if (r != null) {
                    newArt = r
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
    val cAlbum = albumText.trim() != iAlbum.trim()
    val cArtist = artistText.trim() != iArtist.trim()
    val cAlbumArtist = albumArtistText.trim() != iAlbumArtist.trim()
    val cGenre = genreText.trim() != iGenre.trim()
    val cYear = yearDigits != iYear
    val anyChange = cTitle || cTrackNo || cAlbum || cArtist || cAlbumArtist || cGenre || cYear || newArt != null
    val canSave = anyChange && yearOk && !artBusy

    BackHandler { if (anyChange) confirmDiscard = true else onClose() }

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
                        onSave(
                            EditRequest(
                                trackIds = if (track != null) listOf(track.id) else album.tracks.map { it.id },
                                title = if (cTitle) title.trim() else null,
                                trackNo = if (cTrackNo) (trackNoText.toIntOrNull() ?: 0) else null,
                                album = if (cAlbum) albumText.trim() else null,
                                artist = if (cArtist) artistText.trim() else null,
                                albumArtist = if (cAlbumArtist) albumArtistText.trim() else null,
                                year = if (cYear) formatYear(yearDigits) else null,
                                genre = if (cGenre) genreText.trim() else null,
                                artJpeg = newArt?.jpeg
                            )
                        )
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
                Text(
                    if (na != null) "새 앨범아트 ${ArtProcessor.SIZE}×${ArtProcessor.SIZE} · ${na.jpeg.size / 1024}KB"
                    else "앨범아트는 바꾸지 않으면 그대로 유지돼요",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                if (na != null) {
                    TextButton(
                        onClick = { newArt = null },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) { Text("선택 취소") }
                }

                // 입력칸
                if (trackMode) {
                    EditField("트랙명", title, { title = it })
                    EditField(
                        "트랙번호", trackNoText,
                        { trackNoText = it.filter { c -> c.isDigit() }.take(3) },
                        keyboardType = KeyboardType.Number
                    )
                }
                EditField("앨범", albumText, { albumText = it }, mAlbum)
                EditField("아티스트", artistText, { artistText = it }, mArtist)
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

                EditField("장르", genreText, { genreText = it }, mGenre)

                Box(modifier = Modifier.padding(bottom = 96.dp))
            }
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
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (mixed) Text("여러 값 · 그대로 두면 변경 안 함") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    )
}
