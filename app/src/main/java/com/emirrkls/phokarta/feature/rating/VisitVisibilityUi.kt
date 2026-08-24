package com.emirrkls.phokarta.feature.rating

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.Visibility

fun visibilityIcon(visibility: Visibility): ImageVector = when (visibility) {
    Visibility.PUBLIC -> Icons.Rounded.Public
    Visibility.FRIENDS -> Icons.Rounded.People
    Visibility.PRIVATE -> Icons.Rounded.Lock
}

@Composable
fun VisitVisibilityRow(
    visibility: Visibility,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibilityLabel = stringResource(VisitVisibilityCopy.labelRes(visibility))
    val visibilityA11y = stringResource(
        R.string.visibility_content_description,
        visibilityLabel,
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = visibilityA11y
            },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(visibilityIcon(visibility), contentDescription = null)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.visibility), style = MaterialTheme.typography.labelLarge)
                    Text(
                        visibilityLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(VisitVisibilityCopy.impactHintRes(visibility)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisitVisibilitySheet(
    selectedVisibility: Visibility,
    onSelect: (Visibility) -> Unit,
    onDismiss: () -> Unit,
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
                .padding(bottom = 28.dp),
        ) {
            Text(
                stringResource(R.string.visibility_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            VisitVisibilityCopy.selectionOrder.forEach { option ->
                val isSelected = option == selectedVisibility
                val optionLabel = stringResource(VisitVisibilityCopy.labelRes(option))
                val optionDescription = stringResource(VisitVisibilityCopy.sheetDescriptionRes(option))
                val optionA11y = stringResource(
                    R.string.visibility_option_content_description,
                    optionLabel,
                    optionDescription,
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = optionA11y
                            this.selected = isSelected
                            role = Role.RadioButton
                        }
                        .clickable { onSelect(option) },
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            visibilityIcon(option),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                optionLabel,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                optionDescription,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (isSelected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.action_selected),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.visibility_friends_definition),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
