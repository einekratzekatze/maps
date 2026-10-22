package io.github.einekratzekatze.maps.di

import android.content.Context
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
import javax.inject.Singleton

/** Repositories have @Inject constructors and need no bindings here. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.FILE_NAME)
            .addCallback(AppDatabase.SeedCallback)
            .build()

    @Provides @Singleton fun drivesListIdStore(impl: DataStoreDrivesListIdStore): DrivesListIdStore = impl
    @Provides fun placeDao(db: AppDatabase): PlaceDao = db.placeDao()
    @Provides fun importantLocationDao(db: AppDatabase): ImportantLocationDao = db.importantLocationDao()
    @Provides fun placeListDao(db: AppDatabase): PlaceListDao = db.placeListDao()
    @Provides fun driveDao(db: AppDatabase): DriveDao = db.driveDao()
    @Provides fun recentSearchDao(db: AppDatabase): RecentSearchDao = db.recentSearchDao()
}
