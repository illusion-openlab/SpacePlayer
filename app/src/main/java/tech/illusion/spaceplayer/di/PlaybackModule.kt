package tech.illusion.spaceplayer.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import tech.illusion.spaceplayer.ui.PlaybackViewModel

const val PLAYBACK_SESSION_SCOPE_ID = "playback_session_scope"

val playbackModule = module {
    scope(named(PLAYBACK_SESSION_SCOPE_ID)) {
        scoped { PlaybackViewModel(get()) }
    }
}
