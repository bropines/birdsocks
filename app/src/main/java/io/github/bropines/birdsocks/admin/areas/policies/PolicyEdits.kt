package io.github.bropines.birdsocks.admin.areas.policies

/*
 * The visual editor's edits as pure functions: each takes a form and gives
 * the next one. Screens hold one PolicyForm and call these; nothing here
 * knows Compose, so every edit is tested on the JVM.
 */

/** Ports a chip stands for. [ports] combine: Web is 80 and 443. */
enum class PortPreset(val ports: List<Int>) {
    SSH(listOf(22)),
    HTTP(listOf(80)),
    HTTPS(listOf(443)),
    WEB(listOf(80, 443)),
    DNS(listOf(53)),
    RDP(listOf(3389)),
    MYSQL(listOf(3306)),
    POSTGRES(listOf(5432)),
}

object PortWords {

    /**
     * "22, 80-90 443" → spans; null with the first entry that is not a port
     * or a range of ports (1–65535, start ≤ end).
     */
    fun parse(text: String): Result {
        val spans = mutableListOf<PortSpan>()
        for (raw in text.split(',', ' ', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            val parts = raw.split('-', '–', ':').map { it.trim() }
            val span = when (parts.size) {
                1 -> parts[0].toIntOrNull()?.let { PortSpan(it) }
                2 -> {
                    val a = parts[0].toIntOrNull()
                    val b = parts[1].toIntOrNull()
                    if (a != null && b != null) PortSpan(a, b) else null
                }
                else -> null
            }
            if (span == null || !valid(span)) return Result(null, raw)
            if (span !in spans) spans += span
        }
        return Result(spans, null)
    }

    data class Result(val spans: List<PortSpan>?, val bad: String?)

    fun valid(s: PortSpan): Boolean = s.start in 1..65535 && s.end in 1..65535 && s.start <= s.end

    /** Spans as a person writes them: "22, 80, 8000-8100". */
    fun text(spans: List<PortSpan>): String = spans.joinToString(", ")

    /** Whether [port] is one of [spans] or inside one of their ranges. */
    fun covers(spans: List<PortSpan>, port: Int): Boolean = spans.any { port in it.start..it.end }

    /** The chip of [preset] is on: all of its ports are there as single ports. */
    fun isOn(spans: List<PortSpan>, preset: PortPreset): Boolean = preset.ports.all { PortSpan(it) in spans }

    /** [spans] with [preset] switched: its ports added after the others, or removed. */
    fun toggle(spans: List<PortSpan>, preset: PortPreset): List<PortSpan> {
        val mine = preset.ports.map { PortSpan(it) }
        return if (isOn(spans, preset)) spans - mine.toSet() else spans + mine.filter { it !in spans }
    }

    /** The preset whose ports are exactly [spans], for words like "SSH" on a list row. */
    fun preset(spans: List<PortSpan>): PortPreset? =
        PortPreset.entries.firstOrNull { p -> p.ports.size == spans.size && p.ports.map { PortSpan(it) }.toSet() == spans.toSet() }
}

object PolicyEdits {

    /** A new rule as the editor starts it: accept, both ways, all protocols, empty sides. */
    fun newRule(name: String = ""): RuleForm = RuleForm(name = name)

    /** A new policy with one empty rule. */
    fun newPolicy(): PolicyForm = PolicyForm(rules = listOf(newRule()))

    fun rename(p: PolicyForm, name: String): PolicyForm = p.copy(name = name)

    fun describe(p: PolicyForm, text: String): PolicyForm = p.copy(description = text)

    fun setEnabled(p: PolicyForm, on: Boolean): PolicyForm = p.copy(enabled = on)

    fun setPostureChecks(p: PolicyForm, ids: List<String>): PolicyForm = p.copy(postureChecks = ids.distinct())

    fun addRule(p: PolicyForm, rule: RuleForm = newRule()): PolicyForm = p.copy(rules = p.rules + rule)

    fun removeRule(p: PolicyForm, index: Int): PolicyForm = p.copy(rules = p.rules.filterIndexed { i, _ -> i != index })

    fun updateRule(p: PolicyForm, index: Int, change: (RuleForm) -> RuleForm): PolicyForm =
        p.copy(rules = p.rules.mapIndexed { i, r -> if (i == index) change(r) else r })

    /** A copy of the rule after it, without its id (the server gives the copy one). */
    fun duplicateRule(p: PolicyForm, index: Int): PolicyForm {
        val r = p.rules.getOrNull(index) ?: return p
        return p.copy(rules = p.rules.take(index + 1) + r.copy(id = null) + p.rules.drop(index + 1))
    }

    fun moveRule(p: PolicyForm, from: Int, to: Int): PolicyForm {
        if (from !in p.rules.indices || to !in p.rules.indices || from == to) return p
        val list = p.rules.toMutableList()
        list.add(to, list.removeAt(from))
        return p.copy(rules = list)
    }

    /**
     * Rules without a name get the policy's: the server needs one, and the
     * dashboard names a one-rule policy's rule after the policy.
     */
    fun fillRuleNames(p: PolicyForm): PolicyForm =
        p.copy(rules = p.rules.mapIndexed { i, r -> if (r.name.isBlank()) r.copy(name = if (p.rules.size == 1) p.name else "${p.name} ${i + 1}") else r })

    // ---------------------------------------------------------------- one rule

    /**
     * The protocol, with what goes with it: ALL and ICMP take no ports (the
     * server refuses them), NetBird SSH keeps its authorized users and the
     * others drop them.
     */
    fun setProtocol(r: RuleForm, protocol: String): RuleForm = r.copy(
        protocol = protocol,
        ports = if (protocol == RuleForm.TCP || protocol == RuleForm.UDP) r.ports else emptyList(),
        badPorts = if (protocol == RuleForm.TCP || protocol == RuleForm.UDP) r.badPorts else emptyList(),
        portsAsRanges = if (protocol == RuleForm.TCP || protocol == RuleForm.UDP) r.portsAsRanges else false,
        authorizedGroups = if (protocol == RuleForm.SSH) r.authorizedGroups else emptyMap(),
    )

    fun setAction(r: RuleForm, action: String): RuleForm = r.copy(action = action)

    fun setPorts(r: RuleForm, spans: List<PortSpan>): RuleForm = r.copy(ports = spans, badPorts = emptyList())

    fun togglePreset(r: RuleForm, preset: PortPreset): RuleForm = setPorts(r, PortWords.toggle(r.ports, preset))

    /** Groups as the sources; a resource there is replaced. */
    fun setSources(r: RuleForm, groups: List<String>): RuleForm = r.copy(sources = groups.distinct(), sourceResource = null).let(::trimAuthorized)

    fun setDestinations(r: RuleForm, groups: List<String>): RuleForm = r.copy(destinations = groups.distinct(), destinationResource = null)

    /** One resource (a network resource or a peer) as the source; the groups there are dropped. */
    fun setSourceResource(r: RuleForm, res: ResourceRef?): RuleForm = r.copy(sourceResource = res, sources = if (res != null) emptyList() else r.sources).let(::trimAuthorized)

    fun setDestinationResource(r: RuleForm, res: ResourceRef?): RuleForm = r.copy(destinationResource = res, destinations = if (res != null) emptyList() else r.destinations)

    /** Sources and destinations change places. */
    fun swap(r: RuleForm): RuleForm = r.copy(
        sources = r.destinations, sourceResource = r.destinationResource,
        destinations = r.sources, destinationResource = r.sourceResource,
    ).let(::trimAuthorized)

    /** NetBird SSH: the local users a source group may log in as; an empty list removes the group. */
    fun setAuthorizedUsers(r: RuleForm, group: String, users: List<String>): RuleForm {
        val clean = users.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return r.copy(authorizedGroups = if (clean.isEmpty()) r.authorizedGroups - group else r.authorizedGroups + (group to clean))
    }

    /** Authorized users only for groups that are still sources. */
    private fun trimAuthorized(r: RuleForm): RuleForm =
        if (r.authorizedGroups.isEmpty()) r else r.copy(authorizedGroups = r.authorizedGroups.filterKeys { it in r.sources })
}
