package io.github.bropines.birdsocks.core

import android.content.Context
import kotlinx.serialization.Serializable

/**
 * Everything the main screen can hold: a block across its width, or a tile that opens something.
 *
 * [id] is what a stored layout names the item by, so it never changes. Two of them cannot be
 * taken off — the status card, which is what the app is for, and Settings: a screen that can
 * lose the way back to its own editor is a trap.
 *
 * [needsTunnel]: offered only while the build has Tunnel mode (ui/TunnelSection.kt); a layout
 * that names it keeps it all the same.
 */
enum class MainItem(
    val id: String,
    val group: Group,
    val removable: Boolean = true,
    /** A tile that starts across the row; the person can make it one cell, and any other the row. */
    val wideByDefault: Boolean = false,
    val needsTunnel: Boolean = false,
) {
    STATUS("status", Group.BLOCK, removable = false),
    EXIT_NODE("exit_node", Group.BLOCK),
    /** The SOCKS5 proxy's address, to copy into an app. */
    PROXY("proxy_address", Group.BLOCK),

    PEERS("tile_peers", Group.SCREEN),
    NETWORKS("tile_networks", Group.SCREEN),
    DNS("tile_dns", Group.SCREEN),
    PUBLISH("tile_publish", Group.SCREEN),
    DIAGNOSTICS("tile_diagnostics", Group.SCREEN),
    SETTINGS("tile_settings", Group.SCREEN, removable = false),
    LOGS("tile_logs", Group.SCREEN),

    ACCOUNTS("tile_accounts", Group.SHORTCUT),
    /** VPN mode on or off, as Settings → Tunnel mode turns it. */
    VPN("tile_vpn", Group.SHORTCUT, needsTunnel = true),

    TUNNEL("tile_tunnel", Group.SETTING, needsTunnel = true),
    APP_SPLIT("tile_app_split", Group.SETTING, needsTunnel = true),
    PROXIES("tile_proxies", Group.SETTING),
    DNS_SETTINGS("tile_dns_settings", Group.SETTING),
    PERMISSIONS("tile_permissions", Group.SETTING);

    /** How the list of what can be added is divided. */
    enum class Group { BLOCK, SCREEN, SHORTCUT, SETTING }

    val isTile: Boolean get() = group != Group.BLOCK

    companion object {
        fun byId(id: String): MainItem? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The main screen as the person arranged it: what is on it, in order, and how many columns
 * its tiles stand in.
 *
 * One flow, top to bottom. A block takes the width; tiles that follow one another form a grid
 * of [columns], and a [Placed.wide] tile takes a row of that grid to itself. So the status card
 * can stand above the tiles, under them or between two groups of them.
 *
 * Stored as one JSON string under [MainLayoutStore.KEY]:
 *
 *     {"v":1,"columns":0,"items":[{"id":"status","wide":false},{"id":"tile_peers","wide":true}]}
 *
 * An id this build does not know — a later version's — is dropped on reading. An item this
 * build knows and the string does not name is simply not on the screen: it waits in the list of
 * what can be added, and never turns up in the middle of an arrangement by itself.
 */
data class MainLayout(val items: List<Placed>, val columns: Int = AUTO) {

    data class Placed(val item: MainItem, val wide: Boolean = item.wideByDefault) {
        /** Across the width: every block, and a tile set to. */
        val full: Boolean get() = !item.isTile || wide
    }

    operator fun contains(item: MainItem): Boolean = items.any { it.item == item }

    /** What of it [offered] leaves this build, in order. */
    fun shown(offered: (MainItem) -> Boolean): List<Placed> = items.filter { offered(it.item) }

    /** What is not on the screen and could be, in the order the items are declared. */
    fun addable(offered: (MainItem) -> Boolean): List<MainItem> = MainItem.entries.filter { it !in this && offered(it) }

    /**
     * [item] at the end of the tiles, or — a block — under the last block there is: beside the
     * status, where the blocks start out, wherever the person has put that.
     */
    fun add(item: MainItem): MainLayout {
        if (item in this) return this
        val placed = Placed(item)
        if (item.isTile) return copy(items = items + placed)
        val after = items.indexOfLast { !it.item.isTile }
        return copy(items = items.toMutableList().apply { add(after + 1, placed) })
    }

    /** Without [item]; the two that cannot be taken off stay. */
    fun remove(item: MainItem): MainLayout =
        if (!item.removable) this else copy(items = items.filter { it.item != item })

    /**
     * [item] where [target] is, the rest closing up behind it: dragged down it lands after
     * [target], dragged up before it — what a finger crossing [target] means.
     */
    fun move(item: MainItem, target: MainItem): MainLayout {
        val from = items.indexOfFirst { it.item == item }
        val to = items.indexOfFirst { it.item == target }
        if (from < 0 || to < 0 || from == to) return this
        return copy(items = items.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** [item] one place earlier ([by] -1) or later (+1) among what [offered] leaves on screen. */
    fun step(item: MainItem, by: Int, offered: (MainItem) -> Boolean): MainLayout {
        val visible = shown(offered)
        val target = visible.getOrNull(visible.indexOfFirst { it.item == item } + by) ?: return this
        return move(item, target.item)
    }

    /** A tile across the row, or back to one cell; a block has no size to set. */
    fun setWide(item: MainItem, wide: Boolean): MainLayout =
        if (!item.isTile) this else copy(items = items.map { if (it.item == item) it.copy(wide = wide) else it })

    fun withColumns(columns: Int): MainLayout = copy(columns = columns.takeIf { it in 1..MAX_COLUMNS } ?: AUTO)

    /** The columns the tiles stand in: [AUTO] is two, the grid the screen always had. */
    val effectiveColumns: Int get() = if (columns == AUTO) AUTO_COLUMNS else columns

    fun encode(): String = AppJson.encodeToString(
        Stored.serializer(),
        Stored(v = VERSION, columns = columns, items = items.map { StoredItem(it.item.id, it.wide) })
    )

    companion object {
        /** [columns]: the screen's own choice. */
        const val AUTO = 0
        const val MAX_COLUMNS = 4
        /** Two to a row, whatever the width: the author's call for the fixed screen. */
        const val AUTO_COLUMNS = 2
        private const val VERSION = 1

        /**
         * The layout [json] holds, or null when it holds none: an empty string, something
         * that is not a layout. What it names twice is taken once, and the two items that
         * cannot be taken off are put back if a hand-edited or damaged string lost them.
         */
        fun decode(json: String?): MainLayout? {
            if (json.isNullOrBlank()) return null
            val stored = runCatching { AppJson.decodeFromString(Stored.serializer(), json) }.getOrNull() ?: return null
            val items = stored.items
                .mapNotNull { s -> MainItem.byId(s.id)?.let { Placed(it, s.wide && it.isTile) } }
                .distinctBy { it.item }
                .toMutableList()
            if (items.none { it.item == MainItem.STATUS }) items.add(0, Placed(MainItem.STATUS))
            if (items.none { it.item == MainItem.SETTINGS }) items.add(Placed(MainItem.SETTINGS))
            return MainLayout(items, stored.columns.takeIf { it in 1..MAX_COLUMNS } ?: AUTO)
        }

        /**
         * What a fresh install shows, and whoever never arranged anything: the screen as it
         * was before it could be arranged — the status, the exit node, six tiles two to a row.
         */
        val DEFAULT = MainLayout(
            listOf(
                MainItem.STATUS, MainItem.EXIT_NODE,
                MainItem.PEERS, MainItem.NETWORKS, MainItem.DNS, MainItem.PUBLISH, MainItem.DIAGNOSTICS, MainItem.SETTINGS,
            ).map { Placed(it) }
        )

        /**
         * The rows [cells] make in [columns]: tiles side by side until a row is full or
         * something that takes the width comes, which is a row by itself.
         */
        fun rows(cells: List<Placed>, columns: Int): List<List<Placed>> {
            val rows = mutableListOf<List<Placed>>()
            var row = mutableListOf<Placed>()
            for (cell in cells) {
                if (cell.full) {
                    if (row.isNotEmpty()) rows += row
                    rows += listOf(cell)
                    row = mutableListOf()
                } else {
                    row += cell
                    if (row.size == columns.coerceAtLeast(1)) {
                        rows += row
                        row = mutableListOf()
                    }
                }
            }
            if (row.isNotEmpty()) rows += row
            return rows
        }
    }

    @Serializable
    private data class Stored(val v: Int = VERSION, val columns: Int = AUTO, val items: List<StoredItem> = emptyList())

    @Serializable
    private data class StoredItem(val id: String = "", val wide: Boolean = false)
}

/** Where the layout is kept: one global preference, carried by backups (BackupFormat's KEYS). */
object MainLayoutStore {
    const val KEY = "main_layout"

    /** Whether the person has arranged the screen. */
    fun isCustom(context: Context): Boolean = MainLayout.decode(GlobalSettings.getString(context, KEY, "")) != null

    fun load(context: Context): MainLayout =
        MainLayout.decode(GlobalSettings.getString(context, KEY, "")) ?: MainLayout.DEFAULT

    fun save(context: Context, layout: MainLayout) = GlobalSettings.setString(context, KEY, layout.encode())

    /** Back to what a fresh install shows. */
    fun reset(context: Context) = GlobalSettings.setString(context, KEY, "")

    /** [item] back on the screen, where the editor's palette puts it ([MainLayout.add]). */
    fun restore(context: Context, item: MainItem) = save(context, load(context).add(item))
}
