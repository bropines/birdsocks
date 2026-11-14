package io.github.bropines.birdsocks.admin.areas.streaming

import android.content.Context
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.api.ApiIntegration
import io.github.bropines.birdsocks.admin.api.ApiIntegrationRequest
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

/**
 * Event streaming (cloud): where the audit log is copied to. Configs carry
 * the destination's credentials, so the review shows which keys change,
 * never their values, and nothing here is cached.
 */
object EventStreamChanges {
    val CREATE = ChangeKind("event_stream.create", Capabilities.SETTINGS, Op.CREATE, ChangeClass.MEDIUM)
    val TOGGLE = ChangeKind("event_stream.toggle", Capabilities.SETTINGS, Op.UPDATE, ChangeClass.MEDIUM)
    val CONFIG = ChangeKind("event_stream.config", Capabilities.SETTINGS, Op.UPDATE, ChangeClass.MEDIUM)
    val DELETE = ChangeKind("event_stream.delete", Capabilities.SETTINGS, Op.DELETE, ChangeClass.HIGH)

    val PLATFORMS = listOf("datadog", "s3", "firehose", "generic_http")

    /** Keys a new integration of [platform] starts with; the user adds or removes any. */
    fun presetKeys(platform: String): List<String> = when (platform) {
        "s3" -> listOf("bucket_name", "region", "access_key_id", "secret_access_key")
        else -> emptyList()
    }

    private val SECRET = Regex("key|secret|token|password|pass|auth|credential|signature", RegexOption.IGNORE_CASE)

    /** Whether a config key holds something not to show. */
    fun isSecret(key: String): Boolean = SECRET.containsMatchIn(key)

    /** A config value as the screen shows it: secrets as dots, the rest as they are. */
    fun shown(key: String, value: String): String = if (isSecret(key) && value.isNotEmpty()) "••••••" else value

    fun name(i: ApiIntegration): String = "${i.platform} #${i.id}"

    fun target(i: ApiIntegration) = ChangeTarget(TargetType.EVENT_STREAM, i.id.toString(), name(i))

    fun create(ctx: Context, platform: String, config: Map<String, String>, enabled: Boolean): PlannedChange = PlannedChange(
        change = AdminChange(
            CREATE, ChangeTarget(TargetType.EVENT_STREAM, "", platform),
            title = ctx.getString(R.string.admin_stream_create_title, platform),
            effect = ctx.getString(R.string.admin_stream_create_effect, platform),
            diff = listOf(DiffLine(ctx.getString(R.string.admin_stream_platform), null, platform)) +
                config.keys.sorted().map { k -> DiffLine(k, null, shown(k, config.getValue(k))) },
        ),
        apply = { it.createIntegration(ApiIntegrationRequest(platform, config, enabled)) },
        refreshes = listOf(AdminKeys.EVENT_STREAMS),
    )

    /** Switching a stream off stops the copy of the audit log: HIGH. The config is not sent, so the server keeps it. */
    fun setEnabled(ctx: Context, i: ApiIntegration, on: Boolean, isUndo: Boolean = false): PlannedChange = PlannedChange(
        change = AdminChange(
            TOGGLE, target(i),
            title = ctx.getString(if (on) R.string.admin_stream_on_title else R.string.admin_stream_off_title, name(i)),
            effect = ctx.getString(if (on) R.string.admin_stream_on_effect else R.string.admin_stream_off_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin_stream_enabled), i.enabled.toString(), on.toString())),
            changeClass = ChangeClassifier.protection(i.enabled, on),
        ),
        apply = { it.updateIntegration(i.id, ApiIntegrationRequest(i.platform, emptyMap(), on)) },
        verify = { it.integration(i.id).enabled == on },
        undo = if (isUndo) null else setEnabled(ctx, i.copy(enabled = on), !on, isUndo = true),
        refreshes = listOf(AdminKeys.EVENT_STREAMS),
        isUndo = isUndo,
    )

    /** Only [changes] are sent: the server keeps every key not named. */
    fun updateConfig(ctx: Context, i: ApiIntegration, changes: Map<String, String>): PlannedChange = PlannedChange(
        change = AdminChange(
            CONFIG, target(i),
            title = ctx.getString(R.string.admin_stream_config_title, name(i)),
            effect = ctx.getString(R.string.admin_stream_config_effect, changes.keys.sorted().joinToString(", ")),
            diff = changes.keys.sorted().map { k -> DiffLine(k, i.config[k]?.let { shown(k, it) }, shown(k, changes.getValue(k))) },
        ),
        apply = { it.updateIntegration(i.id, ApiIntegrationRequest(i.platform, changes, i.enabled)) },
        refreshes = listOf(AdminKeys.EVENT_STREAMS),
    )

    fun delete(ctx: Context, i: ApiIntegration): PlannedChange = PlannedChange(
        change = AdminChange(
            DELETE, target(i),
            title = ctx.getString(R.string.admin_stream_delete_title, name(i)),
            effect = ctx.getString(R.string.admin_stream_delete_effect),
        ),
        apply = { it.deleteIntegration(i.id) },
        verify = { api -> api.integrations().items.none { it.id == i.id } },
        refreshes = listOf(AdminKeys.EVENT_STREAMS),
        done = ctx.getString(R.string.admin_done_deleted, name(i)),
    )
}
