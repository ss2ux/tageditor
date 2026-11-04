package com.ss2ux.tageditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

/** 1500x1500 으로 만들어진 새 앨범아트 (미리보기용 비트맵 + 저장용 JPEG) */
class NewArt(val bitmap: Bitmap, val jpeg: ByteArray)

object ArtProcessor {
    const val SIZE = 1500
    private const val MAX_PIXELS = 16_000_000L   // 편집용으로 메모리에 올릴 최대 크기 (약 64MB)

    /**
     * 이미지를 편집용으로 읽는다. 짧은 변이 1500 미만이 되지 않는 범위에서만 줄여 읽는다.
     * 시간이 걸리므로 반드시 백그라운드에서 호출. 실패하면 null
     */
    fun decodeSource(context: Context, uri: Uri): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val minSide = minOf(w, h)
                var s = 1
                while ((w / s).toLong() * (h / s) > MAX_PIXELS && minSide / (s + 1) >= SIZE) s++
                if (s > 1) decoder.setTargetSampleSize(s)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 위치나 확대를 조절할 여지가 있는가 (정사각형이고 확대할 만큼 크지 않으면 바로 사용) */
    fun canAdjust(src: Bitmap): Boolean =
        src.width != src.height || minOf(src.width, src.height) >= (SIZE * 1.01f).toInt()

    /** src 안에서 (cx, cy)를 중심으로 한 변이 side 인 정사각형을 잘라 1500x1500 JPEG 로 만든다 */
    fun render(src: Bitmap, cx: Float, cy: Float, side: Float): NewArt {
        val w = src.width
        val h = src.height
        val s = side.roundToInt().coerceIn(1, minOf(w, h))
        val left = (cx - s / 2f).roundToInt().coerceIn(0, w - s)
        val top = (cy - s / 2f).roundToInt().coerceIn(0, h - s)

        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)   // 투명 PNG는 흰 배경 위에
        canvas.drawBitmap(
            src,
            Rect(left, top, left + s, top + s),
            Rect(0, 0, SIZE, SIZE),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 92, bos)
        return NewArt(out, bos.toByteArray())
    }
}

/**
 * 앨범아트 위치 지정 화면.
 * - 한 손가락으로 끌면 위치 이동, 두 손가락으로 벌리면 확대 (원본 화질이 충분할 때만)
 * - 화면에 보이는 정사각형이 그대로 1500x1500 앨범아트가 된다.
 */
@Composable
fun ArtCropScreen(
    src: Bitmap,
    onCancel: () -> Unit,
    onDone: (cx: Float, cy: Float, side: Float) -> Unit
) {
    val w = src.width
    val h = src.height
    val minSide = minOf(w, h).toFloat()
    // 확대해도 선택 영역이 1500px 아래로 내려가지 않게(화질 유지) 제한
    val maxZoom = maxOf(1f, minSide / ArtProcessor.SIZE)

    var zoom by remember { mutableFloatStateOf(1f) }
    var cx by remember { mutableFloatStateOf(w / 2f) }
    var cy by remember { mutableFloatStateOf(h / 2f) }
    var viewPx by remember { mutableFloatStateOf(0f) }
    val image = remember(src) { src.asImageBitmap() }

    fun clampCenter() {
        val side = minSide / zoom
        cx = cx.coerceIn(side / 2f, w - side / 2f)
        cy = cy.coerceIn(side / 2f, h - side / 2f)
    }

    BackHandler { onCancel() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "취소")
                }
                Text(
                    "앨범아트 위치 지정",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onDone(cx, cy, minSide / zoom) }) {
                    Text("완료", fontSize = 16.sp)
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .aspectRatio(1f)
                    .onSizeChanged { viewPx = it.width.toFloat() }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, gestureZoom, _ ->
                            val v = viewPx
                            if (v > 0f) {
                                val r = (minSide / zoom) / v                  // 화면 1px 당 원본 몇 px
                                val newZoom = (zoom * gestureZoom).coerceIn(1f, maxZoom)
                                val r2 = (minSide / newZoom) / v
                                // 손가락이 가리키는 원본 지점이 그대로 손가락 아래에 있도록 중심을 옮긴다
                                val sx = cx + (centroid.x - v / 2f) * r
                                val sy = cy + (centroid.y - v / 2f) * r
                                cx = sx - (centroid.x - v / 2f) * r2 - pan.x * r2
                                cy = sy - (centroid.y - v / 2f) * r2 - pan.y * r2
                                zoom = newZoom
                                clampCenter()
                            }
                        }
                    }
            ) {
                val side = minSide / zoom
                val left = (cx - side / 2f).coerceIn(0f, w - side)
                val top = (cy - side / 2f).coerceIn(0f, h - side)
                drawImage(
                    image = image,
                    srcOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    srcSize = IntSize(side.roundToInt(), side.roundToInt()),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium
                )
            }

            val sideNow = minSide / zoom
            Text(
                buildString {
                    append("원본 ${w}×${h}")
                    append(" · 선택 영역 ${sideNow.roundToInt()}px → ${ArtProcessor.SIZE}px")
                    if (sideNow < ArtProcessor.SIZE) append(" (확대되어 화질이 떨어져요)")
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Text(
                if (maxZoom > 1.01f) "한 손가락: 위치 이동 · 두 손가락: 확대/축소 (최대 ${"%.1f".format(maxZoom)}배)"
                else "한 손가락: 위치 이동 (원본이 작아서 확대는 할 수 없어요)",
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
            TextButton(
                onClick = {
                    zoom = 1f
                    cx = w / 2f
                    cy = h / 2f
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("가운데로 초기화") }
        }
    }
}
