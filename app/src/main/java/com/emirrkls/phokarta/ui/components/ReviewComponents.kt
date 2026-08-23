package com.emirrkls.phokarta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emirrkls.phokarta.core.model.PublicReview
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.feature.rating.VisitDraftLogic
import java.time.format.DateTimeFormatter

private val reviewDateFormatter = DateTimeFormatter.ofPattern("MMM yyyy")
private val visitDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

object CommunityScoreCopy {
    fun visitCountLabel(count: Int): String = when (count) {
        0 -> "No community ratings yet"
        1 -> "1 visit"
        else -> "$count visits"
    }
}

@Composable
fun CommunityScoreSection(
    communityScore: Double?,
    ratingCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Community",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (communityScore != null) {
                Text(
                    String.format("%.1f", communityScore),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "Community score ${String.format("%.1f", communityScore)}, ${VisitDraftLogic.scoreLabel(communityScore.toFloat())}"
                    },
                )
                Text(
                    VisitDraftLogic.scoreLabel(communityScore.toFloat()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else {
                Text(
                    "Not rated",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { contentDescription = "Community not rated" },
                )
            }
            if (ratingCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    CommunityScoreCopy.visitCountLabel(ratingCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun PersonalVisitScoreSection(
    latestVisit: Visit,
    visitCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Your latest visit",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                String.format("%.1f", latestVisit.overallRating),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics {
                    contentDescription =
                        "Your latest visit score ${String.format("%.1f", latestVisit.overallRating)}"
                },
            )
            Text(
                latestVisit.visitedAt.format(visitDateFormatter),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (visitCount > 1) {
                Text(
                    if (visitCount == 1) "1 visit" else "$visitCount visits",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
fun CommunityReviewCard(
    review: PublicReview,
    currentUserId: String?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenAuthor: ((String) -> Unit)? = null,
    previewMaxLines: Int = 3,
    modifier: Modifier = Modifier,
) {
    val isCurrentUser = currentUserId != null && review.author.userId == currentUserId
    val authorLabel = if (isCurrentUser) "${review.author.displayName} · You" else review.author.displayName
    val dateLabel = review.visitDate.format(reviewDateFormatter)
    val scoreText = String.format("%.1f", review.overallScore)
    val hasReviewText = review.publicReview.isNotBlank()
    val scoreDescriptor = VisitDraftLogic.scoreLabel(review.overallScore.toFloat())

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = buildString {
                    append("Review by $authorLabel")
                    append(", score $scoreText, $scoreDescriptor")
                    append(", visited $dateLabel")
                    if (hasReviewText) append(", ${review.publicReview}")
                }
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (onOpenAuthor != null) {
                    Modifier.clickable { onOpenAuthor(review.author.userId) }
                } else {
                    Modifier
                },
            ) {
                UserAvatar(review.author.avatarUrl.orEmpty(), size = 36)
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(authorLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "$scoreText · $dateLabel",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                RatingBadge(review.overallScore)
            }
            if (hasReviewText) {
                Spacer(Modifier.height(10.dp))
                val canExpand = review.publicReview.length > 120 || review.publicReview.lines().size > previewMaxLines
                Text(
                    text = if (expanded || !canExpand) review.publicReview else review.publicReview,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (expanded) Int.MAX_VALUE else previewMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (canExpand) {
                    TextButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.semantics {
                            contentDescription = if (expanded) "Show less review text" else "Read more review text"
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
fun CommunityReviewsEmptyState(
    hasVisited: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(vertical = 8.dp)) {
        Text("No community reviews yet", style = MaterialTheme.typography.titleMedium)
        Text(
            if (hasVisited) "Be the first to share your experience publicly." else "Be the first to share your experience.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun CommunityReviewsSectionHeader(
    totalElements: Long,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Community reviews", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (totalElements > 0) {
            Text(
                if (totalElements == 1L) "1 review" else "$totalElements reviews",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
fun CommunityReviewsLoadingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
fun CommunityReviewsErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text("Couldn't load community reviews", style = MaterialTheme.typography.titleSmall)
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}
