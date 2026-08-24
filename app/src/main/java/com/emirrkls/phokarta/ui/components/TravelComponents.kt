package com.emirrkls.phokarta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.ui.localization.appLocale
import com.emirrkls.phokarta.ui.localization.formatScoreLocalized
import com.emirrkls.phokarta.ui.localization.labelRes
import com.emirrkls.phokarta.ui.presentation.WantToGoCopy
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(24.dp)
private val ImageShape = RoundedCornerShape(20.dp)

@Composable
fun TravelImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val fallback = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    AsyncImage(
        model = ImageRequest.Builder(context).data(url).crossfade(250).build(),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        placeholder = fallback,
        error = fallback,
        fallback = fallback,
    )
}

@Composable
fun RatingBadge(score: Double, emphasized: Boolean = false) {
    val container = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val content = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(12.dp)) {
        Text(
            text = formatScoreLocalized(score),
            modifier = Modifier.padding(horizontal = if (emphasized) 11.dp else 9.dp, vertical = 6.dp),
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun ScorePill(label: String, score: Double, primary: Boolean = false, modifier: Modifier = Modifier) {
    val container = if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = container, contentColor = content) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(formatScoreLocalized(score), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
fun CategoryChip(label: String, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        label = "categoryColor",
    )
    val elevation by animateDpAsState(if (selected) 2.dp else 0.dp, label = "categoryElevation")
    val chipA11y = if (selected) {
        stringResource(R.string.a11y_chip_selected, label)
    } else {
        label
    }
    Surface(
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = chipA11y
            },
        color = color,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(50),
        shadowElevation = elevation,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(
                action,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = if (onAction != null) Modifier.clickable(onClick = onAction).padding(8.dp) else Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
fun FeaturedPlaceCard(place: Place, saved: Boolean, onClick: () -> Unit, onSave: () -> Unit, visited: Boolean = false) {
    Card(
        modifier = Modifier.width(316.dp).height(336.dp).clickable(onClick = onClick),
        shape = CardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            TravelImage(place.coverImage, place.name, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.80f)))
                )
            )
            SaveButton(saved, onSave, Modifier.align(Alignment.TopEnd).padding(10.dp), inverse = true)
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryIcon(place.category, size = 17.dp, tint = Color.White.copy(alpha = 0.86f))
                    Text(
                        stringResource(place.category.labelRes()).uppercase(appLocale()),
                        color = Color.White.copy(alpha = 0.86f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(place.name, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    place.friendsScore?.let { RatingBadge(it, emphasized = true) }
                    Text(if (place.friendsScore != null) stringResource(R.string.friends_city, place.city) else place.city, color = Color.White, style = MaterialTheme.typography.labelLarge)
                    if (visited) {
                        VisitedBadge()
                    }
                }
                place.friendSignal?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun PlaceCard(place: Place, saved: Boolean, onClick: () -> Unit, onSave: () -> Unit, visited: Boolean = false) {
    Card(
        modifier = Modifier.width(254.dp).clickable(onClick = onClick),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(7.dp)) {
            Box(Modifier.fillMaxWidth().height(166.dp)) {
                TravelImage(place.coverImage, place.name, Modifier.fillMaxSize().clip(ImageShape))
                SaveButton(saved, onSave, Modifier.align(Alignment.TopEnd).padding(6.dp), inverse = true)
            }
            Column(Modifier.padding(horizontal = 7.dp, vertical = 10.dp)) {
                Text(place.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    CategoryIcon(place.category, size = 15.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${stringResource(place.category.labelRes())} · ${place.city}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    place.friendsScore?.let {
                        RatingBadge(it)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.friends), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (visited) {
                        VisitedBadge()
                        Spacer(Modifier.width(8.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.ratings_count, place.ratingCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveButton(saved: Boolean, onSave: () -> Unit, modifier: Modifier = Modifier, inverse: Boolean = false) {
    val haptics = LocalHapticFeedback.current
    Surface(modifier = modifier, shape = CircleShape, color = if (inverse) Color.Black.copy(alpha = 0.42f) else MaterialTheme.colorScheme.surface) {
        IconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSave()
            },
            modifier = Modifier.size(42.dp),
        ) {
            AnimatedContent(saved, label = "saveIcon") { isSaved ->
                Icon(
                    if (isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = stringResource(WantToGoCopy.saveContentDescriptionRes(isSaved)),
                    tint = if (inverse) Color.White else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun CompactPlaceCard(
    place: Place,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    saved: Boolean = false,
    visited: Boolean = false,
    friendAverageScore: Double? = null,
    friendsVisitedCount: Int = 0,
    onSave: (() -> Unit)? = null,
) {
    val friendSemantics = FriendsScoreCopy.cardSemantics(friendsVisitedCount, friendAverageScore)
    val communitySemantics = if (place.communityScore != null) {
        stringResource(R.string.a11y_community_rating, formatScoreLocalized(place.communityScore))
    } else {
        stringResource(R.string.community_not_rated)
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(place.name)
                    append(". ")
                    append(communitySemantics)
                    if (friendSemantics != null) {
                        append(". ")
                        append(friendSemantics)
                    }
                }
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TravelImage(place.coverImage, place.name, Modifier.size(88.dp).clip(RoundedCornerShape(16.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(place.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    CategoryIcon(place.category, size = 15.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${stringResource(place.category.labelRes())} · ${place.city}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CommunityRatingLabel(place.communityScore)
                    if (visited) {
                        VisitedBadge()
                    }
                }
                if (friendsVisitedCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (friendAverageScore != null) {
                            Text(
                                stringResource(R.string.friends_score_value, formatScoreLocalized(friendAverageScore)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            FriendsScoreCopy.cardVisitedLabel(friendsVisitedCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (onSave != null) {
                SaveButton(saved, onSave)
            }
        }
    }
}

@Composable
fun CommunityRatingLabel(score: Double?, modifier: Modifier = Modifier) {
    if (score != null) {
        RatingBadge(score)
    } else {
        Text(
            stringResource(R.string.not_rated),
            modifier = modifier,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun CollectionCard(collection: Collection, placeCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.width(240.dp).clickable(onClick = onClick), shape = CardShape) {
        Column {
            TravelImage(collection.coverImage, collection.title, Modifier.fillMaxWidth().aspectRatio(1.55f))
            Column(Modifier.padding(15.dp)) {
                Text(collection.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("$placeCount ${if (placeCount == 1) "place" else "places"} · ${collection.visibility.name.lowercase().replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun CollectionListCard(
    collection: Collection,
    placeCount: Int,
    visibilityLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TravelImage(
                collection.coverImage,
                collection.title,
                Modifier.size(width = 100.dp, height = 96.dp).clip(RoundedCornerShape(16.dp)),
            )
            Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 4.dp)) {
                Text(
                    collection.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (collection.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        collection.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "$placeCount ${if (placeCount == 1) "place" else "places"} · $visibilityLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun UserAvatar(url: String, size: Int = 36) {
    TravelImage(url, stringResource(R.string.a11y_traveler_avatar), Modifier.size(size.dp).clip(CircleShape))
}
