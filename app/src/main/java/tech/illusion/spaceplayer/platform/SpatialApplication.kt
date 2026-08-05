package tech.illusion.spaceplayer.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import tech.illusion.spaceplayer.di.PLAYBACK_SESSION_SCOPE_ID
import tech.illusion.spaceplayer.di.playbackModule
import tech.illusion.spaceplayer.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@SpatialApplication)
            modules(playbackModule)
        }
        GlobalContext.get().createScope(PLAYBACK_SESSION_SCOPE_ID, named(PLAYBACK_SESSION_SCOPE_ID))
        launch(::mainApp)
    }
}
