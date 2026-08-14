package tech.illusion.spaceplayer.ui.library

/**
 * Survives the main WindowContainer being torn down and recreated across an immersive-playback
 * round-trip - LibraryViewModel itself does not (see its own KDoc), because it's a plain `remember`
 * in MainLibraryScreen's composition, which gets fully re-initialized every time that container
 * reopens (ImmersiveScene.kt closes/reopens MAIN_WINDOW_ID around each playback session).
 *
 * Registered as a Koin `single` in playbackModule (di/PlaybackModule.kt) - same category as
 * VideoPreferencesStore/PlaybackHistoryStore there: process-lifetime state, reset only on a full
 * app restart, not on every container round-trip.
 *
 * Deliberately holds only this one field, not the rest of LibraryViewModel's state (selectedItem,
 * format filter, ...) - see the design spec's "取舍" note for why the scope is kept this narrow.
 */
class LibrarySessionState {
    var selectedCategory: LibraryCategory = LibraryCategory.LIBRARY
}
