package tech.illusion.spaceplayer

import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.Stage
import tech.illusion.spaceplayer.ui.ImmersiveScene
import tech.illusion.spaceplayer.ui.PlaceholderMainScreen

const val IMMERSIVE_STAGE_ID = "ImmersiveStage"

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PlaceholderMainScreen()
        }

        Stage(id = IMMERSIVE_STAGE_ID) {
            ImmersiveScene()
        }
    }
