package io.github.bropines.birdsocks.admin.areas.policies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.admin.AdminHost
import io.github.bropines.birdsocks.admin.describeError
import io.github.bropines.birdsocks.admin.ui.AdminDetailSheet
import io.github.bropines.birdsocks.admin.ui.DetailSheetBody
import io.github.bropines.birdsocks.admin.ui.TextDiffView
import io.github.bropines.birdsocks.ui.theme.BirdSocksTheme

/**
 * The pipeline on screen: each step with what it found, in order, and the
 * way on to the change's own gates when nothing stops it. A device about to
 * be cut off must be acknowledged with a tick before Continue is offered.
 */
@Composable
internal fun PolicyReviewSheet(host: AdminHost, state: PoliciesState, r: ReviewState) {
    val ctx = LocalContext.current
    var accept by remember(r.review) { mutableStateOf(false) }
    AdminDetailSheet(
        title = stringResource(R.string.admin_policy_review_title),
        subtitle = r.review?.title ?: (r.pending as? PendingChange.ApplySet)?.let { stringResource(R.string.admin_policy_set_target) },
        onDismiss = { state.dismissReview() },
    ) {
        when {
            r.error != null -> {
                Text(describeError(ctx, r.error), color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { state.dismissReview() }) { Text(stringResource(R.string.action_close)) }
                    TextButton(onClick = { state.check(r.pending) }) { Text(stringResource(R.string.admin_retry)) }
                }
            }
            r.review == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> ReviewBody(
                r.pending, r.review, accept, onAccept = { accept = it },
                onMerge = { state.takeMerge() },
                onCancel = { state.dismissReview() },
                onContinue = { state.propose(ctx, accept) },
                busy = host.changeBusy,
            )
        }
    }
}

@Composable
internal fun ReviewBody(
    pending: PendingChange,
    review: PolicyReview,
    accept: Boolean,
    onAccept: (Boolean) -> Unit,
    onMerge: () -> Unit,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
    busy: Boolean = false,
) {
    val ctx = LocalContext.current
    val stop = review.stoppedAt
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (pending is PendingChange.ApplySet) {
            val p = pending.plan
            Text(stringResource(R.string.admin_policy_set_effect, p.creates.size, p.updates.size, p.deletes.size), style = MaterialTheme.typography.bodyMedium)
            (p.creates.map { "+ ${it.name}" } + p.updates.map { "~ ${it.after.name}" } + p.deletes.map { "− ${it.name}" }).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        // LOCAL
        Step(stringResource(R.string.admin_policy_step_local), if (review.localOk) StepState.OK else StepState.FAIL) {
            review.local.forEach { NoteLine(PolicyText.problem(ctx, it), error = true) }
            review.setProblems.forEach { NoteLine(PolicyText.setProblem(ctx, it), error = true) }
        }
        if (!review.localOk) return@Column Buttons(onCancel, null, busy)

        // FRESH
        val c = review.conflict
        Step(stringResource(R.string.admin_policy_step_fresh), if (c == null) StepState.OK else StepState.FAIL) {
            when {
                c == null -> Unit
                c.theirs == null -> Text(stringResource(R.string.admin_policy_conflict_gone), style = MaterialTheme.typography.bodySmall)
                c.merged != null -> {
                    Text(stringResource(R.string.admin_policy_conflict_mergeable), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onMerge) { Text(stringResource(R.string.admin_policy_conflict_merge)) }
                }
                else -> Text(
                    stringResource(R.string.admin_policy_conflict_overlap, c.conflictLines.joinToString(", ") { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" }),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (c != null) return@Column Buttons(onCancel, null, busy)

        // DIFF
        Step(stringResource(R.string.admin_policy_step_diff), if (review.changed) StepState.OK else StepState.FAIL) {
            if (!review.changed) Text(stringResource(R.string.admin_policy_unchanged), style = MaterialTheme.typography.bodySmall)
            else TextDiffView(review.before, review.after, Modifier.heightIn(max = 320.dp), foldedLabel = { n -> ctx.resources.getQuantityString(R.plurals.admin_diff_folded, n, n) })
        }
        if (stop == ReviewStep.DIFF) return@Column Buttons(onCancel, null, busy)

        // RISKS
        Step(stringResource(R.string.admin_policy_step_risks), if (review.risks.isEmpty()) StepState.OK else StepState.WARN) {
            if (review.risks.isEmpty()) Text(stringResource(R.string.admin_policy_no_risks), style = MaterialTheme.typography.bodySmall)
            review.risks.forEach { NoteLine(PolicyText.risk(ctx, it), error = false) }
        }

        // ACCESS
        val a = review.access
        Step(stringResource(R.string.admin_policy_step_access), when { a.cutOff -> StepState.FAIL; a.lost.isNotEmpty() || a.skipped -> StepState.WARN; else -> StepState.OK }) {
            when {
                a.skipped -> Text(stringResource(R.string.admin_policy_access_skipped), style = MaterialTheme.typography.bodySmall)
                a.cutOff -> {
                    Text(stringResource(R.string.admin_policy_cutoff_warning), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                    a.lost.forEach { NoteLine(PolicyText.grant(ctx, it), error = true) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(accept, onAccept)
                        Text(stringResource(R.string.admin_policy_cutoff_accept), style = MaterialTheme.typography.bodySmall)
                    }
                }
                a.lost.isNotEmpty() -> {
                    Text(pluralStringResource(R.plurals.admin_policy_access_lost, a.lost.size, a.lost.size, a.after.size), style = MaterialTheme.typography.bodySmall)
                    a.lost.forEach { NoteLine(PolicyText.grant(ctx, it), error = false) }
                }
                else -> Text(pluralStringResource(R.plurals.admin_policy_access_kept, a.after.size, a.after.size), style = MaterialTheme.typography.bodySmall)
            }
        }
        Buttons(onCancel, if (review.passed(accept)) onContinue else null, busy)
    }
}

private enum class StepState { OK, WARN, FAIL }

@Composable
private fun Step(title: String, s: StepState, content: @Composable () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (s) { StepState.OK -> Icons.Default.CheckCircle; StepState.WARN -> Icons.Default.Warning; StepState.FAIL -> Icons.Default.Block },
                null, Modifier.size(18.dp),
                tint = when (s) { StepState.OK -> MaterialTheme.colorScheme.primary; StepState.WARN -> MaterialTheme.colorScheme.tertiary; StepState.FAIL -> MaterialTheme.colorScheme.error },
            )
            Spacer(Modifier.size(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        Column(Modifier.padding(start = 26.dp, top = 2.dp)) { content() }
    }
}

@Composable
private fun Buttons(onCancel: () -> Unit, onContinue: (() -> Unit)?, busy: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        if (onContinue != null) Button(onClick = onContinue, enabled = !busy) { Text(stringResource(R.string.admin_policy_review_continue)) }
        else Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
    }
}

@Preview
@Composable
private fun ReviewBodyPreview() = BirdSocksTheme {
    Surface {
        DetailSheetBody("Check before saving", "SSH") {
            ReviewBody(
                PendingChange.Save(PolicyForm(name = "SSH"), null),
                PolicyReview(
                    "SSH", before = "{\n  \"enabled\": true\n}", after = "{\n  \"enabled\": false\n}",
                    risks = listOf(RiskFinding(RiskKind.ALL_PROTOCOL, "SSH", "SSH", 0)),
                    access = AccessReport(listOf(Grant("p1", "Default", 0, "Default", true)), emptyList()),
                ),
                accept = false, onAccept = {}, onMerge = {}, onCancel = {}, onContinue = {},
            )
        }
    }
}
