package com.ss2ux.tageditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** 원본 이미지 위에서 잘라낼 정사각형 영역 (원본 픽셀 기준) */
data class CropRect(val left: Float, val top: Float, val side: Float)

class NewArt(val bitmap: Bitmap, val jpeg: ByteArray)

object ArtProcessor {
    const val SIZE = 1500
    private const val MAX_SHORT = 3000   // 짧은 변이 이보다 크면 절반씩 줄여서 읽음 (메모리 절약)

    /** 시간이 걸리므로 반드시 백그라운드에서 호출. 실패하면 null */
    fun decode(context: Context, uri: Uri): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = minOf(info.size.width, info.size.height) / MAX_SHORT
                if (s > 1) decoder.setTargetSampleSize(s)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 처음 위치: 중앙 기준 가장 큰 정사각형 */
    fun initialCrop(b: Bitmap): CropRect {
        val side = minOf(b.width, b.height).toFloat()
        return CropRect((b.width - side) / 2f, (b.height - side) / 2f, side)
    }

    /** 지정한 영역을 1500x1500 JPEG로 만든다. 백그라운드에서 호출. 실패하면 null */
    fun render(src: Bitmap, crop: CropRect): NewArt? {
        return try {
            val l = crop.left.roundToInt().coerceIn(0, src.width - 1)
            val t = crop.top.roundToInt().coerceIn(0, src.height - 1)
            val s = crop.side.roundToInt().coerceAtLeast(1).coerceAtMost(minOf(src.width - l, src.height - t))

            val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(android.graphics.Color.WHITE)   // 투명 PNG는 흰 배경 위에
            canvas.drawBitmap(src, Rect(l, t, l + s, t + s), Rect(0, 0, SIZE, SIZE), Paint(Paint.FILTER_BITMAP_FLAG))

            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 92, bos)
            NewArt(out, bos.toByteArray())
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 앨범아트 위치/확대 조정 화면.
 * - 한 손가락으로 끌면 위치 이동, 두 손가락(또는 슬라이더)으로 확대/축소
 * - 확대는 "잘라낸 영역이 1500픽셀 이상일 때"까지만 가능 (화질이 충분히 클 때만 확대)
 */
@Composable
fun ArtCropScreen(
    source: Bitmap,
    initial: CropRect,
    onCancel: () -> Unit,
    onConfirm: (CropRect) -> Unit
) {
    val w = source.width
    val h = source.height
    val short = minOf(w, h).toFloat()
    val target = ArtProcessor.SIZE.toFloat()
    val minSide = if (short > target) target else short       // 이보다 작게(=더 확대) 못 함
    val maxZoom = short / minSide
    val canZoom = maxZoom > 1.02f

    var side by remember { mutableFloatStateOf(initial.side.coerceIn(minSide, short)) }
    var left by remember { mutableFloatStateOf(initial.left.coerceIn(0f, w - initial.side.coerceIn(minSide, short))) }
    var top by remember { mutableFloatStateOf(initial.top.coerceIn(0f, h - initial.side.coerceIn(minSide, short))) }
    var viewPx by remember { mutableFloatStateOf(0f) }

    BackHandler { onCancel() }

    val img = remember(source) { source.asImageBitmap() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancel) { Text("취소", fontSize = 16.sp) }
                Text(
                    "앨범아트 위치 조정",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onConfirm(CropRect(left, top, side)) }) { Text("완료", fontSize = 16.sp) }
            }

            // 정사각형 미리보기 (이 안에 보이는 부분이 그대로 앨범아트가 됨)
            ComposeCanvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .onSizeChanged { viewPx = it.width.toFloat() }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            if (viewPx <= 0f) return@detectTransformGestures
                            val oldSide = side
                            val newSide = (oldSide / zoom).coerceIn(minSide, short)
                            val k = oldSide / viewPx
                            val nk = newSide / viewPx
                            // 손가락 아래에 있던 그림 지점이 계속 손가락 아래에 있도록 계산
                            val fx = left + centroid.x * k
                            val fy = top + centroid.y * k
                            side = newSide
                            left = (fx - (centroid.x + pan.x) * nk).coerceIn(0f, w - newSide)
                            top = (fy - (centroid.y + pan.y) * nk).coerceIn(0f, h - newSide)
                        }
                    }
            ) {
                val sl = left.roundToInt().coerceIn(0, w - 1)
                val st = top.roundToInt().coerceIn(0, h - 1)
                val ss = side.roundToInt().coerceAtLeast(1).coerceAtMost(minOf(w - sl, h - st))
                drawImage(
                    image = img,
                    srcOffset = IntOffset(sl, st),
                    srcSize = IntSize(ss, ss),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium
                )
            }

            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                val zoom = short / side
                if (canZoom) {
                    Text(
                        "확대 ${"%.1f".format(zoom)}×  (최대 ${"%.1f".format(maxZoom)}×)",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = zoom.coerceIn(1f, maxZoom),
                        onValueChange = { z ->
                            val newSide = (short / z).coerceIn(minSide, short)
                            val cx = left + side / 2f
                            val cy = top + side / 2f
                            side = newSide
                            left = (cx - newSide / 2f).coerceIn(0f, w - newSide)
                            top = (cy - newSide / 2f).coerceIn(0f, h - newSide)
                        },
                        valueRange = 1f..maxZoom
                    )
                } else {
                    Text(
                        "이미지가 1500픽셀 정도라 확대는 할 수 없어요. 끌어서 위치만 옮길 수 있어요.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (side < target - 0.5f) "선택 영역 ${side.roundToInt()}px → 1500px로 확대 저장 (흐려질 수 있어요)"
                    else "선택 영역 ${side.roundToInt()}px → 1500px",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                TextButton(
                    onClick = {
                        val c = ArtProcessor.initialCrop(source)
                        side = c.side; left = c.left; top = c.top
                    },
                    modifier = Modifier.align(Alignment.End)
                ) { Text("처음 위치로") }
            }
        }
    }
}
