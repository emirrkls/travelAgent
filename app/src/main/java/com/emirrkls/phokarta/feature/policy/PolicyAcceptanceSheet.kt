package com.emirrkls.phokarta.feature.policy

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.emirrkls.phokarta.BuildConfig
import com.emirrkls.phokarta.R

@Composable
fun PolicyAcceptanceSheet(
    state: PolicyAcceptanceUi,
    onCheckedChange: (Boolean) -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.visible) return
    val context = LocalContext.current
    val termsUrl = BuildConfig.PHOKARTA_TERMS_URL
    val guidelinesUrl = BuildConfig.PHOKARTA_COMMUNITY_GUIDELINES_URL
    AlertDialog(
        onDismissRequest = { if (!state.accepting) onDismiss() },
        title = { Text(stringResource(R.string.policy_accept_title)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag("policy_acceptance_sheet"),
            ) {
                Text(
                    stringResource(R.string.policy_accept_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.policy_guidelines_draft_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.policy_guidelines_draft_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (termsUrl.isNotBlank()) {
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(termsUrl))) }) {
                        Text(stringResource(R.string.policy_open_terms))
                    }
                }
                if (guidelinesUrl.isNotBlank()) {
                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(guidelinesUrl))) },
                    ) {
                        Text(stringResource(R.string.policy_open_guidelines))
                    }
                }
                if (termsUrl.isBlank() && guidelinesUrl.isBlank()) {
                    Text(
                        stringResource(R.string.policy_urls_unpublished),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.checked,
                        onCheckedChange = onCheckedChange,
                        enabled = !state.accepting,
                        modifier = Modifier.testTag("policy_acceptance_checkbox"),
                    )
                    Text(
                        stringResource(R.string.policy_accept_checkbox),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                state.error?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(message),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAccept,
                enabled = state.checked && !state.accepting,
                modifier = Modifier.testTag("policy_acceptance_accept"),
            ) {
                if (state.accepting) {
                    CircularProgressIndicator(Modifier.height(16.dp))
                } else {
                    Text(stringResource(R.string.policy_accept_action))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.accepting,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
