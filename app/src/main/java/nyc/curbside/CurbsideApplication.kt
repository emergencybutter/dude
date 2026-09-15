package nyc.curbside

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nyc.curbside.asp.AspDatasetInstaller
import nyc.curbside.detect.DetectionRegistrar
import nyc.curbside.di.ApplicationScope
import nyc.curbside.notify.Notifications

@HiltAndroidApp
class CurbsideApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var detectionRegistrar: DetectionRegistrar

    @Inject lateinit var datasetInstaller: AspDatasetInstaller

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)

        // Re-arm activity transitions if they were dropped (a Play Services update, a force stop,
        // a permission newly granted). Cheap and idempotent; the registrar no-ops when nothing has
        // changed. Detection itself does not depend on the app being launched.
        scope.launch { detectionRegistrar.ensureRegistered() }

        // Load the bundled curb dataset the first time the app runs, so the map is useful before
        // any network is available. A count query when one is already installed, which is every
        // launch but the first.
        scope.launch {
            datasetInstaller.installSeedIfEmpty()
            // Separately from the curb data: the calendar expires in weeks, the streets do not.
            datasetInstaller.installSeedSuspensions()
        }
    }
}
