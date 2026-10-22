package io.github.einekratzekatze.maps.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.einekratzekatze.maps.map.MapStateRepository
import kotlinx.coroutines.flow.combine

/**
 * Debug builds only (bubble B2b): a crosshair at the WebView centre and a `CAMERA` log line per
 * camera change, to check that the parsed URL centre matches the map centre and to calibrate the
 * `m` (metres) suffix. Not part of the release UI.
 */
@Composable
fun DebugCameraOverlay(mapState: MapStateRepository, modifier: Modifier = Modifier) {
    LaunchedEffect(mapState) {
        combine(mapState.urlState, mapState.viewportCssPx, mapState.bounds) { s, size, b -> Triple(s, size, b) }
            .collect { (s, size, b) -> Log.d("CAMERA", "camera=${s.camera} mode=${s.mode} viewportCss=$size bounds=$b") }
    }
    Canvas(modifier.fillMaxSize()) {
        val c = Offset(size.width / 2, size.height / 2)
        val arm = 12.dp.toPx()
        val stroke = 1.5.dp.toPx()
        drawLine(Color.Magenta, c - Offset(arm, 0f), c + Offset(arm, 0f), stroke)
        drawLine(Color.Magenta, c - Offset(0f, arm), c + Offset(0f, arm), stroke)
    }
}
