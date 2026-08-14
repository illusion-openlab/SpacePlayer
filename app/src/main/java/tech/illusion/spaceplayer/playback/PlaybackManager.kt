package tech.illusion.spaceplayer.playback

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.core.ecs.video.CypressMediaPlayer
import com.pico.spatial.core.ecs.video.CypressMediaPlayerCallback
import com.pico.spatial.core.ecs.video.CypressMediaPlayerErrorCode

private const val TAG = "PlaybackManager"

/** Also the volume the HUD's mute toggle restores to when unmuting. */
const val DEFAULT_VOLUME = 0.8f

class PlaybackManager(private val context: Context) {

    val player: CypressMediaPlayer = CypressMediaPlayer()

    var state by mutableStateOf(PlaybackState.INIT)
        private set
    var duration by mutableStateOf(1L)
        private set
    var hasFirstFrameRendered by mutableStateOf(false)
        private set

    var onFirstFrameRendered: (() -> Unit)? = null
    var onPlaybackCompleted: (() -> Unit)? = null

    // CypressMediaPlayer's native callbacks arrive on the decoder's own JNI thread (confirmed via
    // thread_debug.txt: a new Thread-N per prepare cycle, never main) - registerCypressMediaPlayerCallback()
    // only wraps the *registration* call in runOnScheduleThread, not the callback dispatch itself.
    // Mutating Compose state (and, downstream, driving SDK/window-container calls off
    // returnToMainWindowRequested) straight from that thread is exactly the pattern StoryPico's
    // VideoPlayableEntity avoids via its own mainHandler.post{} proxy - mirrored here for the same reason.
    private val mainHandler = Handler(Looper.getMainLooper())

    private val callback = object : CypressMediaPlayerCallback {
        override fun onPrepared() {
            mainHandler.post {
                state = PlaybackState.READY
                duration = player.getDuration()
                player.play()
                state = PlaybackState.PLAYING
            }
        }
        override fun onStarted() {
            mainHandler.post {
                state = PlaybackState.PLAYING
            }
        }
        override fun onCompleted() {
            mainHandler.post {
                onPlaybackCompleted?.invoke()
            }
        }
        override fun onSeekToCompleted() {}
        override fun onPaused() {
            mainHandler.post {
                state = PlaybackState.PAUSED
            }
        }
        override fun onStopped() {}
        override fun onVideoSizeChanged(width: Int, height: Int) {
            mainHandler.post {
                if (!hasFirstFrameRendered) {
                    hasFirstFrameRendered = true
                    onFirstFrameRendered?.invoke()
                }
            }
        }
        override fun onError(error: CypressMediaPlayerErrorCode) {
            mainHandler.post {
                Log.e(TAG, "onError code ${error.code}")
                state = PlaybackState.ERROR
            }
        }
    }

    fun setup(uri: Uri) {
        // CypressMediaPlayer.reset() KDoc lists "switching between different video sources" as its
        // own documented use case: setDataSource() on top of an already-configured player leaves the
        // previous source's decoder/surface state behind instead of replacing it. Guarded on state
        // rather than called unconditionally, since reset() is only meaningful once a source has
        // actually been set - the very first setup() call finds a fresh, still-idle player.
        if (state != PlaybackState.INIT) {
            player.reset()
        }
        state = PlaybackState.PREPARING
        duration = 1L
        hasFirstFrameRendered = false
        player.registerCypressMediaPlayerCallback(callback)
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Cannot open file descriptor for $uri")
        // AssetFileDescriptor.UNKNOWN_LENGTH (-1) makes CypressMediaPlayer's native decoder hang in
        // PREPARING forever instead of erroring out - pass the real length via ParcelFileDescriptor's
        // own fstat-backed size instead (content:// Uris from MediaStore/SAF are backed by real files).
        val afd = AssetFileDescriptor(pfd, 0, pfd.statSize)
        player.setDataSource(afd)
        afd.close()
        player.setVolume(DEFAULT_VOLUME)
        player.prepareAsync()
    }

    fun play() = player.play()
    fun pause() = player.pause()
    fun resume() = player.resume()
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun setVolume(volume: Float) = player.setVolume(volume)

    fun reset() {
        player.stop()
        player.unregisterCypressMediaPlayerCallback()
        player.close()
        state = PlaybackState.INIT
        duration = 1L
        hasFirstFrameRendered = false
    }
}
