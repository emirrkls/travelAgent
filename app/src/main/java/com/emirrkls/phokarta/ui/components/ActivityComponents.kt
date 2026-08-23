package com.emirrkls.phokarta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emirrkls.phokarta.core.model.ActivityEvent
import com.emirrkls.phokarta.feature.rating.VisitDraftLogic
import java.time.format.DateTimeFormatter

private val activityDateFormatter = DateTimeFormatter.ofPattern("MMM d")

@Composable
fun ActivityEventCard(
    event: ActivityEvent,
    currentUserId: String?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenPlace: () -> Unit,
    onOpenAuthor: ((String) -> Unit)? = null,
    previewMaxLines: Int = 3,
    modifier: Modifier = Modifier,
) {
    val isCurrentUser = currentUserId != null && event.author.userId == currentUserId
    val authorName = event.author.displayName
    val authorLabel = if (isCurrentUser) "$authorName · You" else authorName
    val scoreText = String.format("%.1f", event.overallScore)
    val scoreDescriptor = VisitDraftLogic.scoreLabel(event.overallScore.toFloat())
    val dateLabel = event.visitDate.format(activityDateFormatter)
    val hasReviewText = event.publicReview.isNotBlank()
    val placeMeta = listOfNotNull(
        event.place.city.takeIf { it.isNotBlank() },
        event.place.category.label,
    ).joinToString(" · ")

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("activity_event_${event.visitId}")
            .clickable(onClick = onOpenPlace)
            .semantics {
                contentDescription = buildString {
                    append("$authorLabel visited ${event.place.name}")
                    append(", score $scoreText, $scoreDescriptor")
                    append(", visited $dateLabel")
                    if (hasReviewText) {
                        append(", ${event.publicReview}")
                    } else {
                        append(", rating only")
                    }
                    append(". Open place")
                }
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = if (onOpenAuthor != null) {
                        Modifier.clickable { onOpenAuthor(event.author.userId) }
                    } else {
                        Modifier
                    },
                ) {
                    UserAvatar(event.author.avatarUrl.orEmpty(), size = 42)
                }
                Column(
                    modifier = Modifier
                        .padding(start = 11.dp)
                        .weight(1f)
                        .then(
                            if (onOpenAuthor != null) {
                                Modifier.clickable { onOpenAuthor(event.author.userId) }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Text(
                        text = "$authorLabel visited",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = event.place.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (placeMeta.isNotBlank()) {
                        Text(
                            text = placeMeta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (event.place.coverImage.isNotBlank()) {
                    TravelImage(
                        event.place.coverImage,
                        event.place.name,
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RatingBadge(event.overallScore)
                Text(
                    text = "$scoreText · $scoreDescriptor",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.semantics {
                        contentDescription = "Score $scoreText, $scoreDescriptor"
                    },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Visited $dateLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (hasReviewText) {
                Spacer(Modifier.height(10.dp))
                val canExpand = event.publicReview.length > 120 ||
                    event.publicReview.lines().size > previewMaxLines
                Text(
                    text = event.publicReview,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (expanded) Int.MAX_VALUE else previewMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (canExpand) {
                    TextButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.semantics {
                            contentDescription = if (expanded) {
                                "Show less review text"
                            } else {
                                "Read more review text"
                            }
                        },
                    ) {
                        Text(if (expanded) "Show less" else "Read more")
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityEmptyState(
    modifier: Modifier = Modifier,
    title: String = "No activity yet",
    subtitle: String = "Community visits will appear here.",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ActivityErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Couldn't load activity", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun ActivityLoadingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(28.dp))
    }
}
