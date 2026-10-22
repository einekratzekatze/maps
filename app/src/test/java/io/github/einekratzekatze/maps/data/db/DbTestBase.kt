package io.github.einekratzekatze.maps.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before

abstract class DbTestBase {
    protected lateinit var db: AppDatabase

    @Before fun openDb() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.configure(Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java))
            .allowMainThreadQueries()
            .build()
    }

    @After fun closeDb() { db.close() }

    protected fun exec(sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    protected fun <T> rawQuery(sql: String, read: (android.database.Cursor) -> T): List<T> =
        db.openHelper.readableDatabase.query(sql).use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }
}
