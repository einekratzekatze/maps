package io.github.einekratzekatze.maps.map

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Shows the host's WebView. Never creates, remembers or destroys it: the host owns its lifetime. */
@Composable
fun MapWebView(host: WebViewHost, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { host.webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
        modifier = modifier,
        onRelease = { },
    )
}
