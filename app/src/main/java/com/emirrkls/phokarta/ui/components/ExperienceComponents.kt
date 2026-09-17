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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.Icon
import coil.compose.AsyncImage
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.ExperienceClassification
import com.emirrkls.phokarta.core.model.ExperienceSummary
import com.emirrkls.phokarta.core.model.PrimaryExperienceCode
import com.emirrkls.phokarta.core.model.RelationshipActionState
import com.emirrkls.phokarta.ui.localization.ExperienceLabels
import com.emirrkls.phokarta.ui.localization.appLocale
import com.emirrkls.phokarta.ui.localization.displayLanguage
import com.emirrkls.phokarta.ui.localization.formatMediumDateLocalized
import com.emirrkls.phokarta.ui.localization.shouldShowExperienceTitle

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
    val language = displayLanguage(appLocale())
    val showTitle = shouldShowExperienceTitle(experience.title, experience.place.name, experience.classification)
    val primaryLabel = experience.primaryExperience.rawLabel?.takeIf(String::isNotBlank)
        ?: ExperienceLabels.primary(experience.primaryExperience.code, language)
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
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = cardElevation,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                    Modifier.fillMaxWidth().height(150.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(
                        Icons.Rounded.TravelExplore,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.TopEnd).padding(18.dp).size(36.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                        primaryLabel?.let {
                            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        if (showTitle) {
                            Text(experience.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        Text(experience.place.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
                if (experience.mediaPreview != null && showTitle) {
                    Spacer(Modifier.height(13.dp))
                    Text(experience.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                    AssistChip(onClick = {}, label = { Text(ExperienceLabels.feeling(experience.feeling, language)) })
                    val contextLabels = buildList {
                        experience.companion?.let { add(ExperienceLabels.companion(it, language)) }
                        experience.timeOfDay?.let { add(ExperienceLabels.time(it, language)) }
                        experience.vibes.forEach { add(ExperienceLabels.vibe(it, language)) }
                    }.take(2)
                    contextLabels.forEach { label ->
                        AssistChip(onClick = {}, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onPlace).padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Place, contentDescription = null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        buildString {
                            append(experience.place.name)
                            if (experience.place.city.isNotBlank()) append(" · ${experience.place.city}")
                        },
                        Modifier.padding(start = 5.dp).weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                }
                if (experience.classification == ExperienceClassification.LEGACY_COMPATIBILITY &&
                    experience.primaryExperience.code != PrimaryExperienceCode.UNKNOWN_LEGACY
                ) {
                    Text(stringResource(R.string.experience_legacy_badge), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider(Modifier.padding(top = 11.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(top = 11.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.experience_view_details), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 5.dp).size(17.dp))
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
    if (state == RelationshipActionState.FRIENDS) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)),
            modifier = Modifier.height(36.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 14.dp),
            ) {
                Text(
                    text = stringResource(R.string.experience_friend_state),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    } else {
        val label = when (state) {
            RelationshipActionState.NONE -> stringResource(R.string.action_follow)
            RelationshipActionState.REQUEST_PENDING -> stringResource(R.string.experience_requested_state)
            RelationshipActionState.FOLLOWING -> stringResource(R.string.experience_following_state)
            else -> null
        }
        if (label != null) {
            OutlinedButton(
                onClick = onClick,
                enabled = !busy,
                contentPadding = ButtonDefaults.TextButtonContentPadding,
                modifier = Modifier.height(40.dp),
            ) { Text(label) }
        }
    }
}
