package tech.illusion.spaceplayer.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import tech.illusion.spaceplayer.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
