package com.emirrkls.phokarta.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.ExperienceClassification
import com.emirrkls.phokarta.core.model.ExperienceSummary
import com.emirrkls.phokarta.core.model.RelationshipActionState
import com.emirrkls.phokarta.ui.localization.formatMediumDateLocalized

@Composable
fun ExperienceCard(
    experience: ExperienceSummary,
    onOpen: () -> Unit,
    onAuthor: () -> Unit,
    onPlace: () -> Unit,
    onRelationship: () -> Unit,
    relationshipBusy: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val motionEnabled = ValueAnimator.areAnimatorsEnabled()
    val cardScale by animateFloatAsState(
        targetValue = if (motionEnabled && isPressed) 1.015f else 1f,
        animationSpec = tween(durationMillis = if (motionEnabled) 120 else 0),
        label = "experienceCardScale",
    )
    val cardElevation by animateDpAsState(
        targetValue = if (motionEnabled && isPressed) 10.dp else 2.dp,
        animationSpec = tween(durationMillis = if (motionEnabled) 120 else 0),
        label = "experienceCardElevation",
    )
    val openLabel = stringResource(R.string.experience_open_card, experience.title)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = openLabel,
                onClick = onOpen,
            ),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = cardElevation,
    ) {
        Column {
            if (experience.mediaPreview != null) {
                Box {
                    AsyncImage(
                        model = experience.mediaPreview.url,
                        contentDescription = experience.title,
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        contentScale = ContentScale.Crop,
                    )
                    if (experience.mediaCount > 1) {
                        Text(
                            stringResource(R.string.experience_media_count, experience.mediaCount),
                            Modifier.align(Alignment.BottomEnd).padding(12.dp)
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = .7f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            } else {
                Box(
                    Modifier.fillMaxWidth().height(92.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(primaryLabel(experience), style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clickable(onClick = onAuthor)) {
                        UserAvatar(experience.author.avatarUrl.orEmpty(), 38)
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(
                            experience.author.displayName.ifBlank { experience.author.username },
                            Modifier.clickable(onClick = onAuthor),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            formatMediumDateLocalized(experience.experiencedAt),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    RelationshipButton(experience.author.relationship?.state, relationshipBusy, onRelationship)
                }
                Spacer(Modifier.height(13.dp))
                Text(experience.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(experience.place.name)
                        if (experience.place.city.isNotBlank()) append(" · ${experience.place.city}")
                    },
                    Modifier.clickable(onClick = onPlace),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
                experience.place.distanceMeters?.let { meters ->
                    Text(
                        if (meters < 1000) stringResource(R.string.experience_distance_m, meters.toInt())
                        else stringResource(R.string.experience_distance_km, meters / 1000),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                experience.storyPreview?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                experience.tipPreview?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.experience_tip, it),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(onClick = {}, label = { Text(feelingLabel(experience.feeling.name)) })
                    experience.vibes.firstOrNull()?.let { vibe ->
                        AssistChip(onClick = {}, label = { Text(humanize(vibe.name)) })
                    }
                }
                if (experience.classification == ExperienceClassification.LEGACY_COMPATIBILITY) {
                    Text(
                        stringResource(R.string.experience_legacy_badge),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun RelationshipButton(
    state: RelationshipActionState?,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val label = when (state) {
        RelationshipActionState.NONE -> stringResource(R.string.action_follow)
        RelationshipActionState.REQUEST_PENDING -> stringResource(R.string.experience_requested_state)
        RelationshipActionState.FOLLOWING -> stringResource(R.string.experience_following_state)
        RelationshipActionState.FRIENDS -> stringResource(R.string.experience_friend_state)
        else -> null
    }
    if (label != null) {
        Button(
            onClick = onClick,
            enabled = !busy && state != RelationshipActionState.FRIENDS,
            contentPadding = ButtonDefaults.ContentPadding,
        ) { Text(label) }
    }
}

private fun primaryLabel(experience: ExperienceSummary): String =
    experience.primaryExperience.rawLabel?.takeIf(String::isNotBlank)
        ?: humanize(experience.primaryExperience.code.name)

fun humanize(code: String): String = code.lowercase()
    .split('_')
    .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

private fun feelingLabel(code: String): String = when (code) {
    "BAYILDIM" -> "😍"
    "GUZELDI" -> "😊"
    "EH_ISTE" -> "😐"
    "BEKLENTIMI_KARSILAMADI" -> "🙁"
    "BIR_DAHA_TERCIH_ETMEM" -> "😞"
    else -> "•"
}
