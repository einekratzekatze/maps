package io.github.einekratzekatze.maps.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaceList::class, Place::class, ImportantLocation::class, Drive::class,
        ExternalList::class, ExternalSublist::class, RecentSearch::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun placeDao(): PlaceDao
    abstract fun importantLocationDao(): ImportantLocationDao
    abstract fun placeListDao(): PlaceListDao
    abstract fun driveDao(): DriveDao
    abstract fun recentSearchDao(): RecentSearchDao

    /** Seeds Home (id 1) and Work (id 2) once, when the file is created (schema.md 1.8). */
    object SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT INTO ImportantLocation(id, kind, label, lat, lon, address, sortOrder, updatedAt) VALUES (1, 'HOME', NULL, NULL, NULL, NULL, 0, 0)")
            db.execSQL("INSERT INTO ImportantLocation(id, kind, label, lat, lon, address, sortOrder, updatedAt) VALUES (2, 'WORK', NULL, NULL, NULL, NULL, 1, 0)")
        }
    }

    companion object {
        const val FILE_NAME = "maps.db"
    }
}
