package io.github.bropines.birdsocks.admin.areas.keys

import android.content.Context
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.ApiCreateSetupKey
import io.github.bropines.birdsocks.admin.api.ApiSetupKey
import io.github.bropines.birdsocks.admin.api.Capabilities
import io.github.bropines.birdsocks.admin.api.Capabilities.Op
import io.github.bropines.birdsocks.admin.safety.AdminChange
import io.github.bropines.birdsocks.admin.safety.ChangeClass
import io.github.bropines.birdsocks.admin.safety.ChangeClassifier
import io.github.bropines.birdsocks.admin.safety.ChangeKind
import io.github.bropines.birdsocks.admin.safety.ChangeTarget
import io.github.bropines.birdsocks.admin.safety.DiffLine
import io.github.bropines.birdsocks.admin.safety.PlannedChange
import io.github.bropines.birdsocks.admin.safety.TargetType
import io.github.bropines.birdsocks.admin.state.AdminKeys

/** The Setup keys area's writes as [PlannedChange]s. */
object KeyChanges {
    val CREATE = ChangeKind("setup_key.create", Capabilities.SETUP_KEYS, Op.CREATE, ChangeClass.MEDIUM)
    val REVOKE = ChangeKind("setup_key.revoke", Capabilities.SETUP_KEYS, Op.UPDATE, ChangeClass.HIGH)

    fun target(key: ApiSetupKey) = ChangeTarget(TargetType.SETUP_KEY, key.id, key.name.ifEmpty { key.id })

    /**
     * A new key; [onCreated] gets it once, whole — the server keeps only a
     * hash, and the panel never stores it. A reusable key without a limit is HIGH.
     */
    fun create(ctx: Context, request: ApiCreateSetupKey, groupNames: List<String>, onCreated: (ApiSetupKey) -> Unit): PlannedChange {
        val reusable = request.type == ApiSetupKey.TYPE_REUSABLE
        val days = request.expiresIn / 86_400
        return PlannedChange(
            change = AdminChange(
                CREATE, ChangeTarget(TargetType.SETUP_KEY, "", request.name),
                title = ctx.getString(R.string.admin_key_create_unlock, request.name),
                effect = ctx.getString(if (reusable) R.string.admin_key_create_effect_reusable else R.string.admin_key_create_effect_one_off, days.toInt()),
                diff = listOfNotNull(
                    DiffLine(ctx.getString(R.string.admin_key_name), null, request.name),
                    DiffLine(ctx.getString(R.string.admin_key_days), null, days.toString()),
                    if (reusable) DiffLine(ctx.getString(R.string.admin_key_limit), null, if (request.usageLimit == 0) "∞" else request.usageLimit.toString()) else null,
                    groupNames.takeIf { it.isNotEmpty() }?.let { DiffLine(ctx.getString(R.string.admin_key_groups), null, it.joinToString(", ")) },
                ),
                warnings = listOfNotNull(if (reusable && request.usageLimit == 0) ctx.getString(R.string.admin_key_unlimited_warning) else null),
                changeClass = ChangeClassifier.setupKeyCreate(reusable, request.usageLimit),
                confirmPhrase = null,
            ),
            apply = { onCreated(it.createSetupKey(request)) },
            refreshes = listOf(AdminKeys.SETUP_KEYS),
        )
    }

    /** The key stops admitting new machines; machines it already admitted stay. No way back. */
    fun revoke(ctx: Context, key: ApiSetupKey): PlannedChange = PlannedChange(
        change = AdminChange(
            REVOKE, target(key),
            title = ctx.getString(R.string.admin_key_revoke_title, key.name),
            effect = ctx.getString(R.string.admin_key_revoke_text),
        ),
        apply = { it.revokeSetupKey(key) },
        verify = { it.setupKey(key.id).revoked },
        refreshes = listOf(AdminKeys.SETUP_KEYS),
        done = ctx.getString(R.string.admin_done_revoked, key.name),
    )
}
