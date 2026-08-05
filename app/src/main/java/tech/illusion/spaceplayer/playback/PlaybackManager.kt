package tech.illusion.spaceplayer.playback

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.core.ecs.video.CypressMediaPlayer
import com.pico.spatial.core.ecs.video.CypressMediaPlayerCallback
import com.pico.spatial.core.ecs.video.CypressMediaPlayerErrorCode

private const val TAG = "PlaybackManager"
private const val INIT_VOLUME = 0.8f

class PlaybackManager(private val context: Context) {

    val player: CypressMediaPlayer = CypressMediaPlayer()

    var state by mutableStateOf(PlaybackState.INIT)
        private set
    var duration by mutableStateOf(1L)
        private set
    var hasFirstFrameRendered by mutableStateOf(false)
        private set

    private val callback = object : CypressMediaPlayerCallback {
        override fun onPrepared() {
            state = PlaybackState.READY
            duration = player.getDuration()
            player.play()
            state = PlaybackState.PLAYING
        }
        override fun onStarted() {
            state = PlaybackState.PLAYING
        }
        override fun onCompleted() {
            player.seekTo(0)
        }
        override fun onSeekToCompleted() {}
        override fun onPaused() {
            state = PlaybackState.PAUSED
        }
        override fun onStopped() {}
        override fun onVideoSizeChanged(width: Int, height: Int) {
            hasFirstFrameRendered = true
        }
        override fun onError(error: CypressMediaPlayerErrorCode) {
            Log.e(TAG, "onError code ${error.code}")
            state = PlaybackState.ERROR
        }
    }

    fun setup(assetPath: String) {
        state = PlaybackState.PREPARING
        duration = 1L
        hasFirstFrameRendered = false
        player.registerCypressMediaPlayerCallback(callback)
        val afd = context.assets.openFd(assetPath)
        player.setDataSource(afd)
        afd.close()
        player.setVolume(INIT_VOLUME)
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
