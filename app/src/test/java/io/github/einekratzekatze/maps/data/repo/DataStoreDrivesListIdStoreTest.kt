package io.github.einekratzekatze.maps.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataStoreDrivesListIdStoreTest {
    @Test fun roundTrip() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("ds").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "t.preferences_pb") }
            val store = DataStoreDrivesListIdStore(ds)
            assertNull(store.get())
            store.set(42)
            assertEquals(42L, store.get())
        } finally { scope.cancel(); dir.deleteRecursively() }
    }
}
