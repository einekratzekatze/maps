package io.github.einekratzekatze.maps.map

import android.util.Log
import dagger.hilt.android.scopes.ActivityRetainedScoped
import io.github.einekratzekatze.maps.navigation.NavTarget
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** What ViewModels ask of the WebView. ViewModels never hold a [MapController] (compose-host.md §4.2). */
sealed interface MapCommand {
    data class LoadUrl(val url: String) : MapCommand
    data class NavigateInPage(val url: String) : MapCommand
    data class EvaluateJs(val script: String) : MapCommand
    data class PostMessage(val json: String) : MapCommand
    data class Navigate(val target: NavTarget) : MapCommand
    data object OpenGmapsMenu : MapCommand
}

/**
 * Command channel from ViewModels to the map screen. `MapScreen` collects [commands] exactly once
 * while STARTED; commands sent while stopped wait in the buffer.
 */
@ActivityRetainedScoped
class MapCommands @Inject constructor() {
    private val channel = Channel<MapCommand>(Channel.BUFFERED)
    val commands: Flow<MapCommand> = channel.receiveAsFlow()

    /** Returns false (and logs) when the buffer is full and [cmd] was dropped. */
    fun send(cmd: MapCommand): Boolean {
        val sent = channel.trySend(cmd).isSuccess
        if (!sent) Log.w("MapCommands", "Command buffer full, dropped $cmd")
        return sent
    }
}

/** Routes one command. Kept out of composition so it can be tested with a fake controller. */
fun dispatchMapCommand(cmd: MapCommand, controller: MapController, navigate: (NavTarget) -> Unit) {
    when (cmd) {
        is MapCommand.LoadUrl -> controller.loadUrl(cmd.url)
        is MapCommand.NavigateInPage -> controller.navigateInPage(cmd.url)
        is MapCommand.EvaluateJs -> controller.evaluateJs(cmd.script)
        is MapCommand.PostMessage -> controller.postMessage(cmd.json)
        is MapCommand.Navigate -> navigate(cmd.target)
        // TODO(custom-search-field.md §5): click GMaps' burger button once the selector is settled (S1).
        MapCommand.OpenGmapsMenu -> Log.w("MapCommands", "OpenGmapsMenu not implemented yet")
    }
}
