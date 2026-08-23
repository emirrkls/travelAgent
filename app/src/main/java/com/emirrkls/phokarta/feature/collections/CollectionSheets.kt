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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.ui.theme.Coral

fun visibilityLabel(visibility: Visibility): String = when (visibility) {
    Visibility.PRIVATE -> "Private"
    Visibility.FRIENDS -> "Friends"
    Visibility.PUBLIC -> "Public"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("UNUSED_PARAMETER")
fun CreateCollectionSheet(
    onDismiss: () -> Unit,
    onSubmit: (title: String, description: String, visibility: Visibility) -> Unit,
    isSubmitting: Boolean = false,
    errorMessage: String? = null,
    coverImage: String? = null,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf(Visibility.PRIVATE) }
    val titleValid = title.trim().length in 1..120

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
            Text("New collection", style = MaterialTheme.typography.headlineMedium)
            Text(
                "A shortlist with a point of view.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 120) title = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Collection title" },
                label = { Text("Title") },
                placeholder = { Text("Bodrum Summer") },
                singleLine = true,
                enabled = !isSubmitting,
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { if (it.length <= 1000) description = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Description (optional)") },
                placeholder = { Text("What ties these places together?") },
                minLines = 2,
                enabled = !isSubmitting,
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("Visibility", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Visibility.entries.forEach { option ->
                    FilterChip(
                        selected = visibility == option,
                        onClick = { visibility = option },
                        enabled = !isSubmitting,
                        label = { Text(visibilityLabel(option)) },
                    )
                }
            }
            if (visibility == Visibility.FRIENDS) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Friends visibility is ready for when your circle is connected.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            errorMessage?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
                    Text("Create collection")
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Cancel")
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
    errorMessage: String? = null,
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
            Text("Add to list", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Choose shortlists for this place.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            collections.forEach { collection ->
                val selected = placeId in collection.placeIds
                val busy = collection.id in membershipBusyIds
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
                            "${collection.placeIds.size} ${if (collection.placeIds.size == 1) "place" else "places"} · ${visibilityLabel(collection.visibility)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    when {
                        busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Coral)
                        selected -> Icon(Icons.Rounded.Check, "In ${collection.title}", tint = Coral)
                    }
                }
            }
            if (collections.isEmpty()) {
                Text(
                    "No shortlists yet. Create one to start.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
            }
            errorMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCreateNew) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Create new collection")
            }
        }
    }
}
