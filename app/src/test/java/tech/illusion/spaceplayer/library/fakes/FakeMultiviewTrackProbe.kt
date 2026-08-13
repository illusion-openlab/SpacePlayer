package tech.illusion.spaceplayer.library.fakes

import android.content.Context
import android.net.Uri
import tech.illusion.spaceplayer.library.ContainerProbeResult
import tech.illusion.spaceplayer.library.MultiviewTrackProbe

class FakeMultiviewTrackProbe(
    private val isMultiview: Boolean,
    private val videoWidth: Int? = null,
    private val videoHeight: Int? = null,
) : MultiviewTrackProbe {
    override fun probe(context: Context, uri: Uri): ContainerProbeResult =
        ContainerProbeResult(isMultiview, videoWidth, videoHeight)
}
