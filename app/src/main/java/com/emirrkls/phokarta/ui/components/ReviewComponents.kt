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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.emirrkls.phokarta.ui.localization.formatMediumDateLocalized
import com.emirrkls.phokarta.ui.localization.formatMonthYearLocalized
import com.emirrkls.phokarta.ui.localization.formatScoreLocalized
import com.emirrkls.phokarta.ui.localization.labelRes
import com.emirrkls.phokarta.core.model.PublicReview
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.feature.rating.VisitDraftLogic
import com.emirrkls.phokarta.R
import coil.compose.AsyncImage

object CommunityScoreCopy {
    @androidx.annotation.StringRes
    fun visitCountLabelRes(count: Int): Int =
        if (count == 0) R.string.no_community_ratings_yet else 0

    @androidx.annotation.PluralsRes
    fun visitPluralRes(count: Int): Int = R.plurals.visits_count

    @Composable
    fun visitCountLabel(count: Int): String = when (count) {
        0 -> stringResource(R.string.no_community_ratings_yet)
        else -> pluralStringResource(R.plurals.visits_count, count, count)
    }
}

object FriendsScoreCopy {
    @androidx.annotation.StringRes
    fun visitedLabelRes(count: Int): Int =
        if (count == 0) R.string.no_friend_visits_yet else 0

    @androidx.annotation.PluralsRes
    fun visitedPluralRes(count: Int): Int = R.plurals.friends_visited_count

    @Composable
    fun visitedLabel(count: Int): String = when (count) {
        0 -> stringResource(R.string.no_friend_visits_yet)
        else -> pluralStringResource(R.plurals.friends_visited_count, count, count)
    }

    @Composable
    fun cardVisitedLabel(count: Int): String = when {
        count <= 0 -> ""
        else -> pluralStringResource(R.plurals.friends_visited_count, count, count)
    }

    @Composable
    fun cardSemantics(friendsVisitedCount: Int, friendAverageScore: Double?): String? {
        if (friendsVisitedCount <= 0) return null
        val visits = cardVisitedLabel(friendsVisitedCount)
        return if (friendAverageScore != null) {
            stringResource(
                R.string.friends_rating_with_visits_a11y,
                visits,
                formatScoreLocalized(friendAverageScore),
            )
        } else {
            visits
        }
    }

    @Composable
    fun mapSheetSemantics(friendsVisitedCount: Int, friendAverageScore: Double?): String? {
        if (friendsVisitedCount <= 0) return null
        val visits = cardVisitedLabel(friendsVisitedCount)
        return if (friendAverageScore != null) {
            stringResource(
                R.string.friends_rating_prefix_a11y,
                formatScoreLocalized(friendAverageScore),
                visits,
            )
        } else {
            visits
        }
    }
}

@Composable
fun CommunityScoreSection(
    communityScore: Double?,
    ratingCount: Int,
    modifier: Modifier = Modifier,
) {
    val scoreDescription = if (communityScore != null) {
        stringResource(
            R.string.community_score_a11y,
            formatScoreLocalized(communityScore),
            stringResource(VisitDraftLogic.scoreBand(communityScore.toFloat()).labelRes()),
        )
    } else {
        stringResource(R.string.community_not_rated)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = scoreDescription },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.community),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (communityScore != null) {
                Text(
                    formatScoreLocalized(communityScore),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(VisitDraftLogic.scoreBand(communityScore.toFloat()).labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else {
                Text(
                    stringResource(R.string.not_rated),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
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
fun FriendScoreSection(
    friendsScore: Double?,
    friendsVisitedCount: Int,
    modifier: Modifier = Modifier,
) {
    val scoreDescription = if (friendsScore != null) {
        stringResource(
            R.string.friends_score_a11y,
            formatScoreLocalized(friendsScore),
            stringResource(VisitDraftLogic.scoreBand(friendsScore.toFloat()).labelRes()),
        )
    } else {
        stringResource(R.string.friends_score_unavailable)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = scoreDescription },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.friends),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (friendsScore != null) {
                Text(
                    formatScoreLocalized(friendsScore),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(VisitDraftLogic.scoreBand(friendsScore.toFloat()).labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else {
                Text(
                    FriendsScoreCopy.visitedLabel(0),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (friendsVisitedCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    FriendsScoreCopy.visitedLabel(friendsVisitedCount),
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
                stringResource(R.string.you),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val scoreText = formatScoreLocalized(latestVisit.overallRating)
            val latestVisitA11y = stringResource(
                R.string.your_latest_visit_score_a11y,
                scoreText,
            )
            Text(
                scoreText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics {
                    contentDescription = latestVisitA11y
                },
            )
            Text(
                formatMediumDateLocalized(latestVisit.visitedAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (visitCount > 1) {
                Text(
                    pluralStringResource(R.plurals.visits_count, visitCount, visitCount),
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
    onRefreshMedia: (() -> Unit)? = null,
    onReport: ((visitId: String, authorUserId: String) -> Unit)? = null,
    previewMaxLines: Int = 3,
    modifier: Modifier = Modifier,
) {
    val isCurrentUser = currentUserId != null && review.author.userId == currentUserId
    val youLabel = stringResource(R.string.you)
    val authorLabel = if (isCurrentUser) "${review.author.displayName} · $youLabel" else review.author.displayName
    val dateLabel = formatMonthYearLocalized(review.visitDate)
    val scoreText = formatScoreLocalized(review.overallScore)
    val hasReviewText = review.publicReview.isNotBlank()
    val scoreDescriptor = stringResource(VisitDraftLogic.scoreBand(review.overallScore.toFloat()).labelRes())
    val reviewByA11y = stringResource(R.string.a11y_review_by, authorLabel)
    val scoreA11y = stringResource(R.string.a11y_score_with_band, scoreText, scoreDescriptor)
    val visitedA11y = stringResource(R.string.activity_visited_date, dateLabel)
    val now = System.currentTimeMillis()
    val orderedMedia = review.media.sortedBy { it.order }
    val mediaPhotos = orderedMedia.mapNotNull { media ->
        media.accessUrl?.takeIf {
            (media.accessUrlExpiresAtEpochMillis ?: Long.MAX_VALUE) > now + 30_000L
        }
    }
    val displayedPhotos = mediaPhotos.ifEmpty { review.photos }
    val needsMediaRefresh = orderedMedia.isNotEmpty() && mediaPhotos.size < orderedMedia.size

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = buildString {
                    append(reviewByA11y)
                    append(", ")
                    append(scoreA11y)
                    append(", ")
                    append(visitedA11y)
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
                if (onReport != null && !isCurrentUser) {
                    var menuExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.a11y_report_visit))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_report_visit)) },
                            onClick = {
                                menuExpanded = false
                                onReport(review.id, review.author.userId)
                            },
                        )
                    }
                }
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
                    val reviewExpandA11y = if (expanded) {
                        stringResource(R.string.a11y_show_less_review)
                    } else {
                        stringResource(R.string.a11y_read_more_review)
                    }
                    TextButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.semantics {
                            contentDescription = reviewExpandA11y
                        },
                    ) {
                        Text(if (expanded) stringResource(R.string.show_less) else stringResource(R.string.read_more))
                    }
                }
            }
            if (displayedPhotos.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(displayedPhotos) { index, url ->
                        AsyncImage(
                            model = url,
                            contentDescription = stringResource(R.string.visit_photo_a11y, index + 1),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(88.dp),
                        )
                    }
                }
            }
            if (needsMediaRefresh && onRefreshMedia != null) {
                TextButton(onClick = onRefreshMedia) {
                    Text(stringResource(R.string.action_retry))
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
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(stringResource(R.string.no_community_reviews_yet), style = MaterialTheme.typography.titleMedium)
        Text(
            if (hasVisited) stringResource(R.string.be_first_to_share_public) else stringResource(R.string.be_first_to_share),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun CommunityReviewsSectionHeader(
    totalElements: Long,
    title: String = stringResource(R.string.place_community_reviews),
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
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
fun FriendReviewsEmptyState(modifier: Modifier = Modifier) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(stringResource(R.string.no_friend_reviews_yet), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.friend_reviews_empty_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun CommunityReviewsLoadingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
fun CommunityReviewsErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.community_reviews_load_error), style = MaterialTheme.typography.titleSmall)
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}
