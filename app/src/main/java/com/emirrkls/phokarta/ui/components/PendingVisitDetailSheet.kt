package com.emirrkls.phokarta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.database.entity.MutationStateValue
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.sync.PendingVisit
import com.emirrkls.phokarta.feature.rating.VisitDraftLogic
import com.emirrkls.phokarta.feature.rating.VisitVisibilityCopy
import com.emirrkls.phokarta.ui.localization.formatLongDateLocalized
import com.emirrkls.phokarta.ui.localization.formatScoreLocalized
import com.emirrkls.phokarta.ui.localization.labelRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingVisitDetailSheet(
    place: Place,
    pending: PendingVisit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onEditAndRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val statusLabel = when (pending.state) {
        MutationStateValue.FAILED_PERMANENT,
        MutationStateValue.FAILED_RETRYABLE,
        -> stringResource(R.string.sync_failed)
        else -> stringResource(R.string.pending_sync)
    }
    val actions = pending.actions
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("pending_visit_detail_sheet"),
        ) {
            Text(place.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                formatScoreLocalized(pending.visit.overallRating),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(VisitDraftLogic.scoreBand(pending.visit.overallRating.toFloat()).labelRes()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                formatLongDateLocalized(pending.visit.visitedAt),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            VisitPhotoStrip(pending.visit)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(
                    R.string.sync_status_visibility,
                    statusLabel,
                    stringResource(VisitVisibilityCopy.labelRes(pending.visit.visibility)),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("pending_visit_sync_status"),
            )
            pending.failureReason?.let { reason ->
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(reason.labelRes()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("pending_visit_failure_reason"),
                )
            }
            if (pending.visit.ratingDimensions.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                pending.visit.ratingDimensions.entries.sortedByDescending { it.value }.forEach { (dimension, score) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(dimension.labelRes()), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(formatScoreLocalized(score), fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (pending.visit.review.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.review), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(pending.visit.review, style = MaterialTheme.typography.bodyLarge)
            }
            if (pending.visit.personalNote.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.private_memory), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        Text(stringResource(R.string.only_you_can_see_this), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(pending.visit.personalNote, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (actions.showRemove) {
                    TextButton(
                        onClick = onRemove,
                        modifier = Modifier.semantics {
                            contentDescription = context.resources.getString(R.string.a11y_remove_failed_visit)
                        }.testTag("pending_visit_remove"),
                    ) {
                        Text(stringResource(R.string.action_remove_failed_visit))
                    }
                }
                if (actions.showRetry) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.semantics {
                            contentDescription = context.resources.getString(R.string.a11y_retry_sync)
                        }.testTag("pending_visit_retry"),
                    ) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                if (actions.showEditAndRetry) {
                    Button(
                        onClick = onEditAndRetry,
                        modifier = Modifier.semantics {
                            contentDescription = context.resources.getString(R.string.a11y_edit_and_retry)
                        }.testTag("pending_visit_edit_retry"),
                    ) {
                        Text(stringResource(R.string.action_edit_and_retry))
                    }
                }
            }
        }
    }
}

@Composable
fun ReplaceDraftDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.replace_existing_draft_title)) },
        text = { Text(stringResource(R.string.replace_existing_draft_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics {
                    contentDescription = context.resources.getString(R.string.a11y_replace_draft)
                },
            ) {
                Text(stringResource(R.string.action_replace))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        modifier = Modifier.testTag("replace_draft_dialog"),
    )
}

@Composable
fun RemoveFailedVisitDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_failed_visit_title)) },
        text = { Text(stringResource(R.string.remove_failed_visit_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics {
                    contentDescription = context.resources.getString(R.string.a11y_confirm_remove_failed_visit)
                },
            ) {
                Text(stringResource(R.string.action_remove_failed_visit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        modifier = Modifier.testTag("remove_failed_visit_dialog"),
    )
}
