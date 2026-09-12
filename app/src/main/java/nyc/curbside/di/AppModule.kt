package nyc.curbside.di

import android.content.Context
import androidx.room.Room
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nyc.curbside.data.db.CurbSegmentDao
import nyc.curbside.data.db.CurbsideDatabase
import nyc.curbside.data.db.ParkingEventDao
import okhttp3.OkHttpClient

/**
 * A scope that outlives any one component, for work started from a broadcast receiver that must
 * finish even though the receiver has returned.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CurbsideDatabase =
        Room.databaseBuilder(context, CurbsideDatabase::class.java, CurbsideDatabase.NAME)
            // The curb table is a cache of a public dataset; it can always be re-downloaded, and a
            // destructive migration is far preferable to shipping a migration for 150k rows of data
            // the pipeline can regenerate. Parking history is the part that matters, and it is
            // small enough to migrate properly when the schema changes.
            .build()

    @Provides fun provideParkingDao(db: CurbsideDatabase): ParkingEventDao = db.parkingEvents()

    @Provides fun provideCurbDao(db: CurbsideDatabase): CurbSegmentDao = db.curbSegments()

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton fun provideFirestore(): FirebaseFirestore = Firebase.firestore

    @Provides @Singleton fun provideAuth(): FirebaseAuth = Firebase.auth
}
