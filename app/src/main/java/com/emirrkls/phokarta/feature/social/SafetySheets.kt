package com.emirrkls.phokarta.feature.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.ReportReason
import com.emirrkls.phokarta.ui.localization.labelRes

@Composable
fun SafetyActionHost(
    viewModel: SafetyActionViewModel = hiltViewModel(),
    onUserBlocked: () -> Unit = {},
    onReportSubmitted: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.event) {
        when (state.event) {
            SafetyEvent.UserBlocked -> {
                onUserBlocked()
                viewModel.consumeEvent()
            }
            SafetyEvent.ReportSubmitted -> {
                onReportSubmitted()
                viewModel.consumeEvent()
            }
            null -> Unit
        }
    }
    state.blockUserId?.let {
        BlockConfirmDialog(
            submitting = state.submitting,
            error = state.error,
            onConfirm = viewModel::confirmBlock,
            onDismiss = viewModel::dismissBlock,
        )
    }
    if (state.reportTargetType != null && state.reportTargetId != null) {
        ReportSheet(
            state = state,
            onSelectReason = viewModel::selectReason,
            onDetailsChange = viewModel::updateDetails,
            onSubmit = viewModel::submitReport,
            onDismiss = viewModel::dismissReport,
        )
    }
    state.offerBlockUserId?.let { userId ->
        if (state.blockUserId == null && state.reportTargetType == null) {
            OfferBlockDialog(
                onBlock = { viewModel.openBlock(userId) },
                onDismiss = viewModel::dismissOfferBlock,
            )
        }
    }
}

@Composable
private fun BlockConfirmDialog(
    submitting: Boolean,
    error: Int?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.block_confirm_title)) },
        text = {
            Column {
                Text(stringResource(R.string.block_confirm_body))
                error?.let {
                    Text(
                        stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !submitting,
                modifier = Modifier.testTag("block_confirm"),
            ) {
                if (submitting) {
                    CircularProgressIndicator(Modifier.size(18.dp).padding(end = 8.dp))
                }
                Text(stringResource(R.string.action_block_user))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun OfferBlockDialog(
    onBlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_thanks)) },
        text = { Text(stringResource(R.string.report_offer_block)) },
        confirmButton = {
            TextButton(onClick = onBlock) {
                Text(stringResource(R.string.action_block_user))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportSheet(
    state: SafetyActionUiState,
    onSelectReason: (ReportReason) -> Unit,
    onDetailsChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = { if (!state.submitting) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.report_select_reason),
                style = MaterialTheme.typography.titleMedium,
            )
            ReportReason.entries.forEach { reason ->
                val label = stringResource(reason.labelRes())
                val selected = state.selectedReason == reason
                Row(
                    Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {
                            contentDescription = label
                            role = Role.RadioButton
                            this.selected = selected
                        }
                        .clickable(enabled = !state.submitting) { onSelectReason(reason) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(label, modifier = Modifier.padding(start = 8.dp))
                }
            }
            OutlinedTextField(
                value = state.details,
                onValueChange = onDetailsChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("report_details"),
                label = { Text(stringResource(R.string.report_details_optional)) },
                enabled = !state.submitting,
                minLines = 3,
                maxLines = 6,
            )
            state.error?.let {
                Text(
                    stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            TextButton(
                onClick = onSubmit,
                enabled = !state.submitting && state.selectedReason != null,
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag("report_submit"),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(Modifier.size(18.dp).padding(end = 8.dp))
                }
                Text(stringResource(R.string.report_submit))
            }
        }
    }
}
