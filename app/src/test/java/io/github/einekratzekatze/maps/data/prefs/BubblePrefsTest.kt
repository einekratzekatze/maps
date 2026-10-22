package io.github.einekratzekatze.maps.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.einekratzekatze.maps.ui.bubble.BubbleAnchor
import io.github.einekratzekatze.maps.ui.bubble.EdgeSide
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePrefsTest {
    private fun withPrefs(block: suspend (DataStoreBubblePrefs, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit) = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("ds").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "t.preferences_pb") }
            block(DataStoreBubblePrefs(ds), ds)
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun defaults() = withPrefs { prefs, _ ->
        assertEquals(BubbleAnchor.Default, prefs.anchor.first())
        assertTrue(prefs.enabled.first())
    }

    @Test fun roundTrip() = withPrefs { prefs, _ ->
        prefs.setAnchor(BubbleAnchor(EdgeSide.Start, 0.2f))
        prefs.setEnabled(false)
        assertEquals(BubbleAnchor(EdgeSide.Start, 0.2f), prefs.anchor.first())
        assertFalse(prefs.enabled.first())
    }

    @Test fun unknownSideFallsBackToDefault() = withPrefs { prefs, ds ->
        ds.edit { it[stringPreferencesKey("bubble_side")] = "Middle" }
        assertEquals(BubbleAnchor.Default.side, prefs.anchor.first().side)
    }
}
