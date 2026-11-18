package com.ss2ux.subclean

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 미니플레이어용 MediaPlayer 래퍼.
 * 폴더에서 고른 mp3 등을 열어 두고, 자막 줄의 시작 시각으로 시크한 뒤 재생한다.
 */
class AudioPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var currentUri: Uri? = null
    private val main = Handler(Looper.getMainLooper())
    private var progressTick: Runnable? = null

    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** 지금 재생 중인 줄 번호 (없으면 null) */
    var playingLineIndex by mutableStateOf<Int?>(null)
        private set

    fun load(uri: Uri?) {
        if (uri == null) {
            release()
            return
        }
        if (uri == currentUri && player != null) return
        release()
        currentUri = uri
        error = null
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setDataSource(context, uri)
            p.setOnPreparedListener {
                durationMs = it.duration.toLong().coerceAtLeast(0)
                positionMs = 0
            }
            p.setOnCompletionListener {
                isPlaying = false
                playingLineIndex = null
                stopTick()
                positionMs = durationMs
            }
            p.setOnErrorListener { _, what, extra ->
                error = "재생 오류 ($what/$extra)"
                isPlaying = false
                playingLineIndex = null
                stopTick()
                true
            }
            p.prepareAsync()
            player = p
        } catch (e: Exception) {
            error = "오디오를 열 수 없어요: ${e.message ?: e}"
            player = null
            currentUri = null
        }
    }

    /** 지정 시각(ms)부터 재생. 줄 번호는 UI 하이라이트용 */
    fun playFrom(ms: Long, lineIndex: Int? = null) {
        val p = player
        if (p == null) {
            error = "짝이 되는 오디오 파일이 없어요"
            return
        }
        try {
            val target = ms.coerceIn(0, (durationMs - 1).coerceAtLeast(0))
            if (p.isPlaying) p.pause()
            p.seekTo(target.toInt())
            p.start()
            isPlaying = true
            playingLineIndex = lineIndex
            positionMs = target
            startTick()
            error = null
        } catch (e: Exception) {
            error = "재생 실패: ${e.message ?: e}"
            isPlaying = false
        }
    }

    fun togglePlayPause() {
        val p = player ?: return
        try {
            if (p.isPlaying) {
                p.pause()
                isPlaying = false
                stopTick()
            } else {
                p.start()
                isPlaying = true
                startTick()
            }
        } catch (e: Exception) {
            error = "재생 실패: ${e.message ?: e}"
        }
    }

    fun pause() {
        try {
            player?.pause()
        } catch (_: Exception) {
        }
        isPlaying = false
        stopTick()
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        try {
            val target = ms.coerceIn(0, durationMs.coerceAtLeast(0))
            p.seekTo(target.toInt())
            positionMs = target
        } catch (_: Exception) {
        }
    }

    fun release() {
        stopTick()
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        currentUri = null
        isPlaying = false
        playingLineIndex = null
        positionMs = 0
        durationMs = 0
    }

    private fun startTick() {
        stopTick()
        val tick = object : Runnable {
            override fun run() {
                val p = player
                if (p != null && isPlaying) {
                    try {
                        positionMs = p.currentPosition.toLong()
                    } catch (_: Exception) {
                    }
                    main.postDelayed(this, 200)
                }
            }
        }
        progressTick = tick
        main.post(tick)
    }

    private fun stopTick() {
        progressTick?.let { main.removeCallbacks(it) }
        progressTick = null
    }
}
