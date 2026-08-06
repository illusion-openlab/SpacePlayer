package tech.illusion.spaceplayer.ui.library

// User-facing label lives in ui/Labels.kt (LibraryCategory.label()) - a stored String field here
// would be a fixed Chinese literal baked into the enum, not locale-aware.
enum class LibraryCategory {
    LIBRARY,
    DOWNLOADS,
    HISTORY,
    IMPORT,
}
