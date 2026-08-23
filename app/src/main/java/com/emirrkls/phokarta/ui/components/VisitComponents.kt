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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.feature.rating.VisitDraftLogic
import com.emirrkls.phokarta.feature.rating.VisitVisibilityCopy
import com.emirrkls.phokarta.feature.rating.visibilityIcon
import java.time.format.DateTimeFormatter

@Composable
fun VisitedBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics { contentDescription = "Visited" },
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
            Text("Visited", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerVisitDetailSheet(
    placeName: String,
    visit: Visit,
    onDismiss: () -> Unit,
) {
    val dateLabel = visit.visitedAt.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))
    val scoreLabel = VisitDraftLogic.scoreLabel(visit.overallRating.toFloat())
    val visibilityLabel = VisitVisibilityCopy.label(visit.visibility)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(placeName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                String.format("%.1f", visit.overallRating),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(scoreLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(dateLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("owner_visit_visibility"),
            ) {
                Icon(
                    visibilityIcon(visit.visibility),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                    Text("Visibility", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        visibilityLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics {
                            contentDescription = VisitVisibilityCopy.contentDescription(visit.visibility)
                        },
                    )
                }
            }
            if (visit.ratingDimensions.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                visit.ratingDimensions.entries.sortedByDescending { it.value }.forEach { (dimension, score) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dimension.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(String.format("%.1f", score), fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (visit.review.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Text("Review", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    VisitVisibilityCopy.reviewHelper(visit.visibility),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(visit.review, style = MaterialTheme.typography.bodyLarge)
            }
            if (visit.personalNote.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Private memory", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        Text("Only you can see this", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(visit.personalNote, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
fun VisitHistoryRow(
    visit: Visit,
    repeatLabel: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateLabel = visit.visitedAt.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    val scoreText = String.format("%.1f", visit.overallRating)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("owner_visit_row"),
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .semantics {
                    contentDescription = "Your visit $scoreText on $dateLabel"
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    scoreText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(dateLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
            repeatLabel?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
            }
            if (visit.review.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    visit.review,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
