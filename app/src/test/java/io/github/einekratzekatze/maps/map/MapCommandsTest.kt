package io.github.einekratzekatze.maps.map

import app.cash.turbine.test
import io.github.einekratzekatze.maps.domain.LatLon
import io.github.einekratzekatze.maps.navigation.NavTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeMapController : MapController {
    val calls = mutableListOf<String>()
    var history = false
    override fun loadUrl(url: String) { calls += "load $url" }
    override fun navigateInPage(url: String) { calls += "inPage $url" }
    override fun evaluateJs(script: String, callback: ((String?) -> Unit)?) { calls += "js $script" }
    override fun postMessage(json: String) { calls += "post $json" }
    override fun canGoBack() = history
    override fun goBack() { calls += "back" }
}

class MapCommandsTest {
    @Test fun commandsSentBeforeCollectionWaitInOrder() = runTest {
        val commands = MapCommands()
        commands.send(MapCommand.LoadUrl("a"))
        commands.send(MapCommand.EvaluateJs("b"))
        commands.commands.test {
            assertEquals(MapCommand.LoadUrl("a"), awaitItem())
            assertEquals(MapCommand.EvaluateJs("b"), awaitItem())
        }
    }

    @Test fun dispatchRoutesEveryCommand() {
        val controller = FakeMapController()
        val navigated = mutableListOf<NavTarget>()
        val target = NavTarget(LatLon(1.0, 2.0), "x")
        listOf(
            MapCommand.LoadUrl("u1"),
            MapCommand.NavigateInPage("u2"),
            MapCommand.EvaluateJs("1+1"),
            MapCommand.PostMessage("{}"),
            MapCommand.Navigate(target),
        ).forEach { dispatchMapCommand(it, controller) { t -> navigated += t } }
        assertEquals(listOf("load u1", "inPage u2", "js 1+1", "post {}"), controller.calls)
        assertEquals(listOf(target), navigated)
    }
}
