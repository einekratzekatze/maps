package io.github.einekratzekatze.maps.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.einekratzekatze.maps.data.db.AppDatabase
import io.github.einekratzekatze.maps.data.db.DriveDao
import io.github.einekratzekatze.maps.data.db.ImportantLocationDao
import io.github.einekratzekatze.maps.data.db.PlaceDao
import io.github.einekratzekatze.maps.data.db.PlaceListDao
import io.github.einekratzekatze.maps.data.db.RecentSearchDao
import io.github.einekratzekatze.maps.data.repo.DataStoreDrivesListIdStore
import io.github.einekratzekatze.maps.data.repo.DrivesListIdStore
import io.github.einekratzekatze.maps.data.repo.PlacesQueries
import io.github.einekratzekatze.maps.data.repo.PlacesRepository
import io.github.einekratzekatze.maps.data.prefs.BubblePrefs
import io.github.einekratzekatze.maps.data.prefs.DataStoreBubblePrefs
import javax.inject.Singleton

/** Repositories have @Inject constructors and need no bindings here. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.configure(Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.FILE_NAME)).build()

    @Provides @Singleton fun drivesListIdStore(impl: DataStoreDrivesListIdStore): DrivesListIdStore = impl
    @Provides fun placesQueries(impl: PlacesRepository): PlacesQueries = impl
    @Provides fun bubblePrefs(impl: DataStoreBubblePrefs): BubblePrefs = impl

    /** The single app-wide preferences DataStore (one file; features add keys, never a second delegate). */
    @Provides @Singleton
    fun preferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // A corrupt file falls back to defaults instead of crashing (floating-places-bubble.md §4.6).
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { context.preferencesDataStoreFile("app_prefs") },
        )
    @Provides fun placeDao(db: AppDatabase): PlaceDao = db.placeDao()
    @Provides fun importantLocationDao(db: AppDatabase): ImportantLocationDao = db.importantLocationDao()
    @Provides fun placeListDao(db: AppDatabase): PlaceListDao = db.placeListDao()
    @Provides fun driveDao(db: AppDatabase): DriveDao = db.driveDao()
    @Provides fun recentSearchDao(db: AppDatabase): RecentSearchDao = db.recentSearchDao()
}
