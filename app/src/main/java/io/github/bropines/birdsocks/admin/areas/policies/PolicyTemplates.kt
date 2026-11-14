package io.github.bropines.birdsocks.admin.areas.policies

/*
 * Policy templates as data: a form of a few fields, and a function from the
 * filled form to the policy it creates. The screen draws the fields, shows
 * the policy as a preview, and saves it through the usual pipeline. Words
 * (titles, field labels) live in the string resources, keyed by [id].
 */

/** What a template field takes. */
enum class FieldKind {
    /** One or more groups. */
    GROUPS,
    /** Ports and ranges as text: "80, 443". */
    PORTS,
    /** The policy's name. */
    NAME,
}

/**
 * A field of a template's form. [default] is the starting value: for GROUPS,
 * [ALL_GROUP] stands for the All group's id, filled in by the screen.
 */
data class TemplateField(val key: String, val kind: FieldKind, val required: Boolean = true, val default: List<String> = emptyList())

/** A template: its form and how the filled form becomes a policy. */
class PolicyTemplate(
    val id: String,
    val fields: List<TemplateField>,
    /** Drop rules only; it says so on screen, since such a policy only takes something away. */
    val denies: Boolean = false,
    /** Works as deny-by-default only once the Default (All → All) policy is off: the preview says so. */
    val needsDefaultOff: Boolean = false,
    private val make: (TemplateValues) -> PolicyForm,
) {
    /** The policy for [values], or the keys of the required fields still empty and of ports that do not read. */
    fun build(values: TemplateValues): TemplateResult {
        val missing = fields.filter { f ->
            when (f.kind) {
                FieldKind.PORTS -> PortWords.parse(values.text(f.key)).spans.let { it == null || (f.required && it.isEmpty()) }
                else -> f.required && values.list(f.key).none { it.isNotBlank() }
            }
        }.map { it.key }
        if (missing.isNotEmpty()) return TemplateResult(null, missing)
        return TemplateResult(PolicyEdits.fillRuleNames(make(values)), emptyList())
    }
}

data class TemplateResult(val policy: PolicyForm?, val missing: List<String>)

/** A filled template form: each field's values (one string for NAME and PORTS). */
class TemplateValues(private val values: Map<String, List<String>>) {
    fun list(key: String): List<String> = values[key].orEmpty().filter { it.isNotBlank() }
    fun text(key: String): String = values[key].orEmpty().joinToString(", ")
    fun ports(key: String): List<PortSpan> = PortWords.parse(text(key)).spans.orEmpty()
}

object PolicyTemplates {

    const val ALL_GROUP = "\$all"

    private fun name(default: String) = TemplateField("name", FieldKind.NAME, default = listOf(default))

    private fun rule(
        sources: List<String>, destinations: List<String>, protocol: String, ports: List<PortSpan> = emptyList(),
        bidirectional: Boolean = false, action: String = RuleForm.ACCEPT, name: String = "",
    ) = RuleForm(name = name, action = action, bidirectional = bidirectional, protocol = protocol, ports = ports, sources = sources, destinations = destinations)

    private fun policy(v: TemplateValues, description: String, vararg rules: RuleForm) =
        PolicyForm(name = v.text("name").trim(), description = description, enabled = true, rules = rules.toList())

    val SSH_ADMINS = PolicyTemplate(
        "ssh_admins",
        listOf(name("SSH for admins"), TemplateField("admins", FieldKind.GROUPS), TemplateField("servers", FieldKind.GROUPS), TemplateField("ports", FieldKind.PORTS, default = listOf("22"))),
    ) { v -> policy(v, "SSH from the admins to the servers", rule(v.list("admins"), v.list("servers"), RuleForm.TCP, v.ports("ports"))) }

    val WEB_SERVER = PolicyTemplate(
        "web_server",
        listOf(name("Web server"), TemplateField("clients", FieldKind.GROUPS, default = listOf(ALL_GROUP)), TemplateField("servers", FieldKind.GROUPS), TemplateField("ports", FieldKind.PORTS, default = listOf("80, 443"))),
    ) { v -> policy(v, "Web traffic to the servers", rule(v.list("clients"), v.list("servers"), RuleForm.TCP, v.ports("ports"))) }

    val DNS_SERVER = PolicyTemplate(
        "dns_server",
        listOf(name("DNS server"), TemplateField("clients", FieldKind.GROUPS, default = listOf(ALL_GROUP)), TemplateField("servers", FieldKind.GROUPS)),
    ) { v ->
        policy(
            v, "DNS queries to the resolvers",
            rule(v.list("clients"), v.list("servers"), RuleForm.UDP, listOf(PortSpan(53)), name = "${v.text("name").trim()} UDP"),
            rule(v.list("clients"), v.list("servers"), RuleForm.TCP, listOf(PortSpan(53)), name = "${v.text("name").trim()} TCP"),
        )
    }

    val MONITORING = PolicyTemplate(
        "monitoring",
        listOf(name("Monitoring"), TemplateField("monitors", FieldKind.GROUPS), TemplateField("targets", FieldKind.GROUPS), TemplateField("ports", FieldKind.PORTS, default = listOf("9100"))),
    ) { v ->
        policy(
            v, "Metrics scraping and ping from the monitoring hosts",
            rule(v.list("monitors"), v.list("targets"), RuleForm.TCP, v.ports("ports"), name = "${v.text("name").trim()} metrics"),
            rule(v.list("monitors"), v.list("targets"), RuleForm.ICMP, name = "${v.text("name").trim()} ping"),
        )
    }

    /** Peers of one group talk only to each other: deny-by-default for everyone else once Default is off. */
    val GROUP_ONLY = PolicyTemplate(
        "group_only",
        listOf(name("Team only"), TemplateField("group", FieldKind.GROUPS)),
        needsDefaultOff = true,
    ) { v -> policy(v, "Members reach each other and nothing else is granted", rule(v.list("group"), v.list("group"), RuleForm.ALL, bidirectional = true)) }

    /** Blocks traffic between two sets of groups, both ways. */
    val ISOLATE = PolicyTemplate(
        "isolate",
        listOf(name("Isolate"), TemplateField("from", FieldKind.GROUPS), TemplateField("to", FieldKind.GROUPS)),
        denies = true,
    ) { v -> policy(v, "Blocks traffic between the groups", rule(v.list("from"), v.list("to"), RuleForm.ALL, bidirectional = true, action = RuleForm.DROP)) }

    val all: List<PolicyTemplate> = listOf(SSH_ADMINS, WEB_SERVER, DNS_SERVER, MONITORING, GROUP_ONLY, ISOLATE)

    fun byId(id: String): PolicyTemplate? = all.firstOrNull { it.id == id }

    /** The starting values of [t]'s form, with [ALL_GROUP] replaced by [allGroupId] (or dropped without one). */
    fun defaults(t: PolicyTemplate, allGroupId: String?): Map<String, List<String>> =
        t.fields.associate { f -> f.key to f.default.mapNotNull { if (it == ALL_GROUP) allGroupId else it } }
}
