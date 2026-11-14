package io.github.bropines.birdsocks.ui

import io.github.bropines.birdsocks.core.NetbirdState
import io.github.bropines.birdsocks.models.NbNetwork
import io.github.bropines.birdsocks.models.NbPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetModelsTest {
    @Test
    fun statusFollowsTheDaemonThenTheConnection() {
        assertEquals(WidgetStatus.STOPPED, widgetStatus(NetbirdState.Daemon.Stopped, "Connected"))
        assertEquals(WidgetStatus.STARTING, widgetStatus(NetbirdState.Daemon.Starting, null))
        assertEquals(WidgetStatus.STOPPING, widgetStatus(NetbirdState.Daemon.Stopping, "Connected"))
        assertEquals(WidgetStatus.CONNECTED, widgetStatus(NetbirdState.Daemon.Running, "Connected"))
        assertEquals(WidgetStatus.NEEDS_LOGIN, widgetStatus(NetbirdState.Daemon.Running, "SessionExpired"))
        assertEquals(WidgetStatus.CONNECTING, widgetStatus(NetbirdState.Daemon.Running, null))
    }

    @Test
    fun exitRowsListExitNodesOnlySelectedThenOnline() {
        val networks = listOf(
            NbNetwork(id = "office", range = "10.0.0.0/8"),
            NbNetwork(id = "b-exit", range = "0.0.0.0/0"),
            NbNetwork(id = "a-exit", range = "0.0.0.0/0"),
            NbNetwork(id = "z-exit", range = "0.0.0.0/0,::/0", selected = true),
        )
        val peers = listOf(
            NbPeer(fqdn = "gw-b.netbird.cloud", connStatus = "Connected", networks = listOf("0.0.0.0/0")),
        )
        val rows = exitRows(networks, peers)
        assertEquals(listOf("z-exit", "a-exit", "b-exit").sorted(), rows.map { it.id }.sorted())
        assertEquals("z-exit", rows.first().id)
        // Every 0.0.0.0/0 is routed by the one peer here, so all count as online; ties go by name.
        assertEquals(listOf("z-exit", "a-exit", "b-exit"), rows.map { it.id })
        assertEquals("gw-b", rows[1].via)
    }

    @Test
    fun anExitNodeWithoutAPeerHasNoOnlineState() {
        val rows = exitRows(listOf(NbNetwork(id = "x", range = "::/0")), emptyList())
        assertNull(rows.single().online)
        assertEquals("", rows.single().via)
    }

    @Test
    fun modelsRoundTripAndAnotherSchemaIsRebuilt() {
        val model = ServiceWidgetModel(on = true, status = WidgetStatus.CONNECTED, statusText = "Active")
        assertEquals(model, decodeServiceModel(encodeModel(model)))
        assertNull(decodeServiceModel(encodeModel(model.copy(schema = WIDGET_SCHEMA + 1))))
        val exit = ExitWidgetModel(rows = listOf(ExitRow("x", "gw", selected = true, online = true)))
        assertEquals(exit, decodeExitModel(encodeModel(exit)))
        assertNull(decodeExitModel("not json"))
    }
}
