package com.emirrkls.phokarta.feature.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.ui.localization.labelRes
import com.emirrkls.phokarta.ui.theme.Coral

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("UNUSED_PARAMETER")
fun CreateCollectionSheet(
    onDismiss: () -> Unit,
    onSubmit: (title: String, description: String, visibility: Visibility) -> Unit,
    isSubmitting: Boolean = false,
    errorMessage: Int? = null,
    coverImage: String? = null,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf(Visibility.PRIVATE) }
    val titleValid = title.trim().length in 1..120
    val collectionTitleA11y = stringResource(R.string.a11y_collection_title)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(stringResource(R.string.new_collection), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.collection_shortlist_pov),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 120) title = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = collectionTitleA11y },
                label = { Text(stringResource(R.string.collection_title)) },
                placeholder = { Text(stringResource(R.string.collection_title_hint)) },
                singleLine = true,
                enabled = !isSubmitting,
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { if (it.length <= 1000) description = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.collection_description_optional)) },
                placeholder = { Text(stringResource(R.string.collection_description_placeholder)) },
                minLines = 2,
                enabled = !isSubmitting,
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.visibility), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Visibility.entries.forEach { option ->
                    FilterChip(
                        selected = visibility == option,
                        onClick = { visibility = option },
                        enabled = !isSubmitting,
                        label = { Text(stringResource(option.labelRes())) },
                    )
                }
            }
            if (visibility == Visibility.FRIENDS) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.friends_visibility_ready),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            errorMessage?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(stringResource(msg), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onSubmit(title.trim(), description.trim(), visibility) },
                enabled = titleValid && !isSubmitting,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.create_collection))
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionPickerSheet(
    collections: List<Collection>,
    placeId: String,
    membershipBusyIds: Set<String> = emptySet(),
    errorMessage: Int? = null,
    onDismiss: () -> Unit,
    onToggle: (collectionId: String) -> Unit,
    onCreateNew: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(stringResource(R.string.add_to_list), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.choose_shortlists_for_place),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            collections.forEach { collection ->
                val selected = placeId in collection.placeIds
                val busy = collection.id in membershipBusyIds
                val placesLabel = pluralStringResource(
                    R.plurals.places_count,
                    collection.placeIds.size,
                    collection.placeIds.size,
                )
                val visibilityLabel = stringResource(collection.visibility.labelRes())
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { onToggle(collection.id) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            collection.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.places_count_with_visibility, placesLabel, visibilityLabel),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    when {
                        busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Coral)
                        selected -> Icon(
                            Icons.Rounded.Check,
                            stringResource(R.string.a11y_in_collection, collection.title),
                            tint = Coral,
                        )
                    }
                }
            }
            if (collections.isEmpty()) {
                Text(
                    stringResource(R.string.no_shortlists_yet),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
            }
            errorMessage?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(stringResource(msg), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCreateNew) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.create_new_collection))
            }
        }
    }
}
