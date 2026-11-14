package io.github.bropines.birdsocks.admin.api

/**
 * What the credential may do, per area, read from `GET /api/users/current`
 * (`permissions.modules`) so the panel hides what it cannot do instead of
 * failing on a tap. A 403 narrows it further ([denied]).
 */
data class Capabilities(
    private val modules: Map<String, Map<String, Boolean>>,
    /** False when the server sent no permissions (older than the model): [role] decides. */
    val fromServer: Boolean,
    val role: String,
    /** A regular user's peer view: only their own peers. */
    val restricted: Boolean = false,
) {
    enum class Op(val key: String) { READ("read"), CREATE("create"), UPDATE("update"), DELETE("delete") }

    fun can(module: String, op: Op): Boolean {
        if (fromServer) return modules[module]?.get(op.key) == true
        // An old server: an owner or admin may do everything, anyone else only read.
        return if (role in ADMIN_ROLES) true else op == Op.READ
    }

    /** After a 403 on [op] in [module]: it is not allowed, whatever was thought. */
    fun denied(module: String, op: Op): Capabilities {
        // Without server data the role's answers are written out first, so they are kept for the rest.
        val base = if (fromServer) modules else MODULES.associateWith { m -> Op.entries.associate { it.key to can(m, it) } }
        val ops = (base[module] ?: emptyMap()) + (op.key to false)
        return copy(modules = base + (module to ops), fromServer = true)
    }

    /** Nothing at all can be written: the panel is read-only whatever the switch says. */
    val readOnly: Boolean get() = MODULES.none { m -> Op.entries.any { it != Op.READ && can(m, it) } }

    companion object {
        const val PEERS = "peers"
        const val SETUP_KEYS = "setup_keys"
        const val GROUPS = "groups"
        const val ACCOUNTS = "accounts"
        const val USERS = "users"
        val MODULES = listOf(PEERS, SETUP_KEYS, GROUPS, ACCOUNTS, USERS)
        val ADMIN_ROLES = setOf("owner", "admin")

        fun of(user: ApiUser): Capabilities {
            val p = user.permissions
            return if (p != null && p.modules.isNotEmpty()) Capabilities(p.modules, true, user.role, p.isRestricted)
            else Capabilities(emptyMap(), false, user.role, p?.isRestricted ?: false)
        }
    }
}
