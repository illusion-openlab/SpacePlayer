package tech.illusion.spaceplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tech.illusion.spaceplayer.R
import tech.illusion.spaceplayer.library.FormatSource
import tech.illusion.spaceplayer.playback.Environment
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode
import tech.illusion.spaceplayer.ui.library.LibraryCategory

// Shared locale-aware labels for the enums used across the library/playback screens - previously
// duplicated as separate private, hardcoded-Chinese extension functions in
// MainLibraryScreen.kt/VideoGridCard.kt/FormatCorrectionPopup.kt (three copies of the same
// Projection mapping that could drift out of sync). Centralized here during i18n so there's one
// source of truth per enum, resolved through stringResource() rather than baked into the enum
// constructors (Environment/LibraryCategory used to carry a stored `label: String` - a fixed
// Chinese literal can't be locale-aware, so that field is gone; see their own files).

@Composable
fun Projection.label(): String = stringResource(
    when (this) {
        Projection.FLAT -> R.string.projection_flat
        Projection.HEMISPHERE_180 -> R.string.projection_180
        Projection.SPHERE_360 -> R.string.projection_360
    },
)

/**
 * Compact single-line label for the bottom bar's stereo-mode SegmentControl - reuses the same
 * short strings as [badgeLabel] (plus a non-null MONO case) rather than a descriptive phrase like
 * "Side-by-side 3D", since a SegmentItem's Text wraps at the first space once its container is
 * sized to intrinsic-max width, and a wrapped two-line item looks broken next to the single-line
 * items around it.
 */
@Composable
fun StereoMode.shortLabel(): String = stringResource(
    when (this) {
        StereoMode.MONO -> R.string.stereo_mono
        StereoMode.SIDE_BY_SIDE -> R.string.stereo_sbs_badge
        StereoMode.TOP_AND_DOWN -> R.string.stereo_tb_badge
        StereoMode.MULTIVIEW_MVHEVC -> R.string.stereo_mvhevc
    },
)

/**
 * Compact abbreviation for a stereo mode; null for MONO (nothing to show). Deferred dead code as of
 * 2026-08-17's badge removal - no remaining callers (grep confirmed), left in place per the same
 * "not part of this task's scope" reasoning as `Projection.badgeColor()` in `SpacePlayerPalette.kt`.
 */
@Composable
fun StereoMode.badgeLabel(): String? = when (this) {
    StereoMode.MONO -> null
    StereoMode.SIDE_BY_SIDE -> stringResource(R.string.stereo_sbs_badge)
    StereoMode.TOP_AND_DOWN -> stringResource(R.string.stereo_tb_badge)
    StereoMode.MULTIVIEW_MVHEVC -> stringResource(R.string.stereo_mvhevc)
}

@Composable
fun FormatSource.label(): String = stringResource(
    when (this) {
        FormatSource.DETECTED_CONTAINER -> R.string.format_source_container
        FormatSource.DETECTED_METADATA -> R.string.format_source_metadata
        FormatSource.MANUAL_OVERRIDE -> R.string.format_source_manual
        FormatSource.DEFAULT -> R.string.format_source_default
    },
)

@Composable
fun Environment.label(): String = stringResource(
    when (this) {
        Environment.CINEMA -> R.string.environment_cinema
        Environment.STARRY_SKY -> R.string.environment_starry_sky
        Environment.SEASIDE -> R.string.environment_seaside
    },
)

@Composable
fun LibraryCategory.label(): String = stringResource(
    when (this) {
        LibraryCategory.LIBRARY -> R.string.library_category_library
        LibraryCategory.DOWNLOADS -> R.string.library_category_downloads
        LibraryCategory.HISTORY -> R.string.library_category_history
        LibraryCategory.IMPORT -> R.string.library_category_import
    },
)
