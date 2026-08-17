package tech.illusion.spaceplayer.library.fakes

import android.content.Context
import android.net.Uri
import tech.illusion.spaceplayer.library.SphericalMetadataHint
import tech.illusion.spaceplayer.library.SphericalMetadataProbe
import tech.illusion.spaceplayer.playback.Projection
import tech.illusion.spaceplayer.playback.StereoMode

class FakeSphericalMetadataProbe(
    private val projection: Projection? = null,
    private val stereoMode: StereoMode? = null,
) : SphericalMetadataProbe {
    override fun probe(context: Context, uri: Uri): SphericalMetadataHint =
        SphericalMetadataHint(projection, stereoMode)
}
