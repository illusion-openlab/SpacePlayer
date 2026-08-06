package tech.illusion.spaceplayer.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import tech.illusion.spaceplayer.library.PlaybackHistoryStore
import tech.illusion.spaceplayer.library.VideoPreferencesStore
import tech.illusion.spaceplayer.library.storage.SharedPreferencesKeyValueStore
import tech.illusion.spaceplayer.ui.PlaybackViewModel

const val PLAYBACK_SESSION_SCOPE_ID = "playback_session_scope"

val playbackModule = module {
    single { VideoPreferencesStore(SharedPreferencesKeyValueStore(get(), "video_preferences")) }
    single { PlaybackHistoryStore(SharedPreferencesKeyValueStore(get(), "playback_history")) }
    scope(named(PLAYBACK_SESSION_SCOPE_ID)) {
        scoped { PlaybackViewModel(get(), get(), get()) }
    }
}
