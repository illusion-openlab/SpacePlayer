package tech.illusion.spaceplayer.library.fakes

import android.content.Context
import android.net.Uri
import tech.illusion.spaceplayer.library.MultiviewTrackProbe

class FakeMultiviewTrackProbe(private val result: Boolean) : MultiviewTrackProbe {
    override fun looksLikeMultiview(context: Context, uri: Uri): Boolean = result
}
