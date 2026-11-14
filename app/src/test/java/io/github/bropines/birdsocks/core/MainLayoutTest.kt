package io.github.bropines.birdsocks.core

import io.github.bropines.birdsocks.core.MainItem.*
import io.github.bropines.birdsocks.core.MainLayout.Placed
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The main screen's arrangement: what an unarranged screen is, and what editing does to it. */
class MainLayoutTest {
    private fun ids(layout: MainLayout) = layout.items.map { it.item }
    private val everything: (MainItem) -> Boolean = { true }

    // ─── The screen nobody arranged ──────────────────────────────────────────────────────────

    @Test
    fun aScreenNobodyTouchedIsTheScreenItWas() {
        val layout = MainLayout.DEFAULT
        assertEquals(listOf(STATUS, EXIT_NODE, PEERS, NETWORKS, DNS, PUBLISH, DIAGNOSTICS, SETTINGS), ids(layout))
        assertEquals(MainLayout.AUTO, layout.columns)
        // Two to a row, every tile one cell: the grid it always had.
        assertEquals(2, layout.effectiveColumns)
        assertTrue(layout.items.none { it.wide })
        assertEquals(
            listOf(listOf(STATUS), listOf(EXIT_NODE), listOf(PEERS, NETWORKS), listOf(DNS, PUBLISH), listOf(DIAGNOSTICS, SETTINGS)),
            MainLayout.rows(layout.items, layout.effectiveColumns).map { row -> row.map { it.item } }
        )
    }

    // ─── Storage ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun aLayoutComesBackAsItWasStored() {
        val layout = MainLayout.DEFAULT
            .move(STATUS, SETTINGS)
            .setWide(PEERS, true)
            .add(LOGS)
            .add(PROXY)
            .withColumns(3)
        assertEquals(layout, MainLayout.decode(layout.encode()))
    }

    @Test
    fun nothingStoredIsNoLayout() {
        assertNull(MainLayout.decode(null))
        assertNull(MainLayout.decode(""))
        assertNull(MainLayout.decode("   "))
        assertNull(MainLayout.decode("not json"))
        assertNull(MainLayout.decode("[1,2]"))
    }

    @Test
    fun anItemOfALaterVersionIsIgnored() {
        val json = """{"v":7,"columns":2,"glow":true,"items":[
            {"id":"status"},{"id":"tile_from_the_future","wide":true,"colour":"red"},{"id":"tile_peers"},{"id":"tile_settings"}]}"""
        val layout = MainLayout.decode(json)!!
        assertEquals(listOf(STATUS, PEERS, SETTINGS), ids(layout))
        assertEquals(2, layout.columns)
    }

    @Test
    fun whatALayoutDoesNotNameIsNotPutOnTheScreen() {
        val layout = MainLayout.decode("""{"items":[{"id":"tile_settings"},{"id":"status"}]}""")!!
        // Not in the middle, not at the end: only in the list of what can be added.
        assertEquals(listOf(SETTINGS, STATUS), ids(layout))
        assertTrue(layout.addable(everything).containsAll(listOf(PEERS, EXIT_NODE, PROXY, VPN)))
    }

    @Test
    fun aDamagedLayoutGetsBackWhatCannotBeTakenOff() {
        val layout = MainLayout.decode("""{"items":[{"id":"tile_peers"},{"id":"tile_logs"}]}""")!!
        assertEquals(listOf(STATUS, PEERS, LOGS, SETTINGS), ids(layout))
        assertEquals(listOf(STATUS, SETTINGS), ids(MainLayout.decode("""{"items":[]}""")!!))
    }

    @Test
    fun anItemNamedTwiceIsThereOnce() {
        val layout = MainLayout.decode("""{"items":[{"id":"status"},{"id":"tile_peers"},{"id":"tile_settings"},{"id":"tile_peers","wide":true}]}""")!!
        assertEquals(listOf(STATUS, PEERS, SETTINGS), ids(layout))
        assertFalse(layout.items[1].wide)
    }

    @Test
    fun aColumnCountOutOfRangeIsAutomatic() {
        for (columns in listOf(-1, 5, 99)) {
            val layout = MainLayout.decode("""{"columns":$columns,"items":[{"id":"status"},{"id":"tile_settings"}]}""")!!
            assertEquals(MainLayout.AUTO, layout.columns)
        }
        assertEquals(MainLayout.AUTO, MainLayout.DEFAULT.withColumns(7).columns)
        assertEquals(4, MainLayout.DEFAULT.withColumns(4).columns)
        assertEquals(1, MainLayout.DEFAULT.withColumns(1).effectiveColumns)
    }

    @Test
    fun aBlockIsNeverStoredWide() {
        val layout = MainLayout.decode("""{"items":[{"id":"status","wide":true},{"id":"tile_settings"}]}""")!!
        assertFalse(layout.items[0].wide)
        assertTrue(layout.items[0].full)
    }

    @Test
    fun idsAreUnique() {
        assertEquals(MainItem.entries.size, MainItem.entries.map { it.id }.toSet().size)
    }

    // ─── Guard rails ─────────────────────────────────────────────────────────────────────────

    @Test
    fun theCardAndSettingsCannotBeTakenOff() {
        val layout = MainLayout.DEFAULT
        assertSame(layout, layout.remove(STATUS))
        assertSame(layout, layout.remove(SETTINGS))
        assertEquals(listOf(STATUS, SETTINGS), MainItem.entries.filter { !it.removable })
    }

    @Test
    fun theCardAndSettingsCanBeMoved() {
        val layout = MainLayout.DEFAULT.move(STATUS, SETTINGS).move(SETTINGS, EXIT_NODE)
        assertEquals(SETTINGS, ids(layout).first())
        assertEquals(STATUS, ids(layout).last())
    }

    @Test
    fun whatTheBuildDoesNotOfferIsNeitherShownNorAddable() {
        val noTunnel: (MainItem) -> Boolean = { !it.needsTunnel }
        val layout = MainLayout.DEFAULT.add(VPN)
        assertFalse(layout.shown(noTunnel).any { it.item == VPN })
        assertFalse(TUNNEL in layout.addable(noTunnel) || APP_SPLIT in layout.addable(noTunnel))
        assertTrue(PROXIES in layout.addable(noTunnel))
        // It stays in the layout all the same.
        assertTrue(VPN in layout)
    }

    // ─── Editing ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun removingAndAddingBack() {
        val layout = MainLayout.DEFAULT.remove(DNS)
        assertFalse(DNS in layout)
        assertTrue(DNS in layout.addable(everything))
        val back = layout.add(DNS)
        // A tile comes back at the end, to be dragged where it is wanted.
        assertEquals(DNS, ids(back).last())
        assertSame(back, back.add(DNS))
    }

    @Test
    fun aNewBlockJoinsTheBlocks() {
        val layout = MainLayout.DEFAULT.add(PROXY)
        assertEquals(listOf(STATUS, EXIT_NODE, PROXY, PEERS), ids(layout).take(4))
        // With the card under the tiles, a block follows it there.
        val below = MainLayout.DEFAULT.remove(EXIT_NODE).move(STATUS, SETTINGS).add(PROXY)
        assertEquals(listOf(STATUS, PROXY), ids(below).takeLast(2))
    }

    @Test
    fun movingDownLandsAfterTheTargetAndUpBeforeIt() {
        val layout = MainLayout(listOf(STATUS, PEERS, NETWORKS, DNS, SETTINGS).map { Placed(it) })
        assertEquals(listOf(STATUS, NETWORKS, DNS, PEERS, SETTINGS), ids(layout.move(PEERS, DNS)))
        assertEquals(listOf(STATUS, DNS, PEERS, NETWORKS, SETTINGS), ids(layout.move(DNS, PEERS)))
        assertSame(layout, layout.move(DNS, DNS))
        assertSame(layout, layout.move(DNS, LOGS))
        assertSame(layout, layout.move(LOGS, DNS))
    }

    @Test
    fun aStepSkipsWhatTheBuildDoesNotShow() {
        val layout = MainLayout(listOf(STATUS, PEERS, VPN, DNS, SETTINGS).map { Placed(it) })
        val noVpn: (MainItem) -> Boolean = { it != VPN }
        // Past the tile it cannot see, to the other side of DNS.
        assertEquals(listOf(STATUS, VPN, DNS, PEERS, SETTINGS), ids(layout.step(PEERS, +1, noVpn)))
        assertEquals(listOf(PEERS, STATUS, VPN, DNS, SETTINGS), ids(layout.step(PEERS, -1, noVpn)))
        assertSame(layout, layout.step(STATUS, -1, noVpn))
        assertSame(layout, layout.step(SETTINGS, +1, noVpn))
    }

    @Test
    fun onlyATileHasASize() {
        val layout = MainLayout.DEFAULT
        assertTrue(layout.setWide(PEERS, true).items.first { it.item == PEERS }.wide)
        assertSame(layout, layout.setWide(STATUS, true))
        assertSame(layout, layout.setWide(EXIT_NODE, true))
    }

    // ─── Rows ────────────────────────────────────────────────────────────────────────────────

    private fun rows(columns: Int, vararg cells: Placed) = MainLayout.rows(cells.toList(), columns).map { row -> row.map { it.item } }

    @Test
    fun aBlockBetweenTilesSplitsThemInTwoGroups() {
        assertEquals(
            listOf(listOf(PEERS, NETWORKS, DNS), listOf(STATUS), listOf(PUBLISH, LOGS)),
            rows(3, Placed(PEERS), Placed(NETWORKS), Placed(DNS), Placed(STATUS), Placed(PUBLISH), Placed(LOGS))
        )
        // A wide tile in the middle of a row ends it short.
        assertEquals(
            listOf(listOf(PEERS), listOf(NETWORKS), listOf(DNS, PUBLISH)),
            rows(2, Placed(PEERS), Placed(NETWORKS, wide = true), Placed(DNS), Placed(PUBLISH))
        )
    }

    @Test
    fun oneColumnIsAList() {
        assertEquals(listOf(listOf(PEERS), listOf(DNS), listOf(LOGS)), rows(1, Placed(PEERS), Placed(DNS), Placed(LOGS)))
        assertEquals(listOf(listOf(PEERS), listOf(DNS)), rows(0, Placed(PEERS), Placed(DNS)))
    }

    // ─── Backups ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun aBackupCarriesTheLayout() {
        val arranged = MainLayout.DEFAULT.remove(EXIT_NODE).add(PROXY).withColumns(3).encode()
        for (scopes in listOf(setOf(BackupFormat.Scope.SETTING), BackupFormat.Scope.entries.toSet())) {
            val exported = BackupFormat.exportPrefs(mapOf(MainLayoutStore.KEY to arranged), scopes)
            val imported = BackupFormat.importPrefs(exported, scopes)
            assertEquals(mapOf(MainLayoutStore.KEY to arranged), imported.values)
            assertEquals(3, MainLayout.decode(imported.values[MainLayoutStore.KEY] as String)?.columns)
        }
        // A backup from before the editor leaves the screen as it is.
        val old = BackupFormat.importPrefs(buildJsonObject { put("auto_start", true) }, setOf(BackupFormat.Scope.SETTING))
        assertFalse(old.values.containsKey(MainLayoutStore.KEY))
    }

    // ─── What is off the screen (Settings → Appearance) ──────────────────────────────────────

    @Test
    fun whatWasTakenOffIsListedAndComesBackWhereThePaletteWouldPutIt() {
        val taken = MainLayout.DEFAULT.remove(PEERS).remove(EXIT_NODE)
        val hidden = taken.addable(everything)
        assertTrue(PEERS in hidden && EXIT_NODE in hidden)
        // Never the two that cannot be taken off, and nothing that is on the screen.
        assertFalse(STATUS in hidden || SETTINGS in hidden || DNS in hidden)
        val back = taken.add(PEERS).add(EXIT_NODE)
        assertFalse(PEERS in back.addable(everything) || EXIT_NODE in back.addable(everything))
        // A tile at the end of the tiles, a block under the last block.
        assertEquals(PEERS, ids(back).last())
        assertEquals(listOf(STATUS, EXIT_NODE), ids(back).take(2))
    }
}
