package tech.illusion.spaceplayer

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.Stage
import tech.illusion.spaceplayer.ui.ImmersiveScene
import tech.illusion.spaceplayer.ui.library.MainLibraryScreen

const val IMMERSIVE_STAGE_ID = "ImmersiveStage"

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            MainLibraryScreen(modifier = Modifier.windowConstraints(minWidth = 1400.dp, minHeight = 860.dp))
        }

        Stage(id = IMMERSIVE_STAGE_ID) {
            ImmersiveScene()
        }
    }
