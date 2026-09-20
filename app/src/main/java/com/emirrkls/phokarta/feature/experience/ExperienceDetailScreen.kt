package com.emirrkls.phokarta.feature.experience

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.RelationshipActionState
import com.emirrkls.phokarta.ui.components.UserAvatar
import com.emirrkls.phokarta.ui.localization.ExperienceLabels
import com.emirrkls.phokarta.ui.localization.appLocale
import com.emirrkls.phokarta.ui.localization.displayLanguage
import com.emirrkls.phokarta.ui.localization.formatLongDateLocalized
import com.emirrkls.phokarta.ui.localization.shouldShowExperienceTitle
import com.emirrkls.phokarta.feature.collections.ExperienceCollectionPickerSheet
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.material.icons.rounded.FolderCopy
import com.emirrkls.phokarta.ui.components.AcknowledgementControl

@Composable
fun ExperienceDetailScreen(
    onBack: () -> Unit,
    onPlace: (String) -> Unit,
    onAuthor: (String) -> Unit,
    viewModel: ExperienceDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val experience = state.experience
    val language = displayLanguage(appLocale())
    var showCollectionPicker by remember { mutableStateOf(false) }
    if (showCollectionPicker) {
        ExperienceCollectionPickerSheet(
            collections = state.collections,
            busy = state.collectionBusy,
            onDismiss = { showCollectionPicker = false },
            onAdd = { viewModel.addToCollection(it); showCollectionPicker = false },
        )
    }
    if (state.isLoading) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        return
    }
    if (experience == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.experience_failed_to_load))
            Button(onClick = viewModel::retry, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.action_retry))
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
                }
                Text(stringResource(R.string.experience_detail_title), style = MaterialTheme.typography.titleLarge)
            }
        }
        if (experience.media.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(experience.media, key = { "${it.kind}-${it.position}-${it.id}" }) { media ->
                        SubcomposeAsyncImage(
                            model = media.url,
                            contentDescription = experience.title,
                            modifier = Modifier.width(320.dp).height(260.dp),
                            contentScale = ContentScale.Crop,
                        ) {
                            when (painter.state) {
                                is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                                is AsyncImagePainter.State.Error -> Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Rounded.BrokenImage, contentDescription = null)
                                        Text(
                                            stringResource(R.string.experience_media_failed),
                                            Modifier.padding(top = 6.dp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                else -> Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        Modifier.size(28.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Surface(
                    Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraLarge,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.TravelExplore,
                            contentDescription = stringResource(R.string.experience_no_media),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = .45f),
                            modifier = Modifier.size(32.dp),
                        )
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(
                                stringResource(R.string.experience_no_media),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ExperienceLabels.primary(experience.primaryExperience.code, language)?.let {
                                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UserAvatar(experience.author.avatarUrl.orEmpty(), 44)
                    Column(
                        Modifier.weight(1f).padding(start = 10.dp).clickable { onAuthor(experience.author.id) },
                    ) {
                        Text(experience.author.displayName.ifBlank { experience.author.username }, fontWeight = FontWeight.SemiBold)
                        Text(formatLongDateLocalized(experience.experiencedAt), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val relation = experience.author.relationship
                    if (relation != null && relation.state != RelationshipActionState.UNAVAILABLE) {
                        if (relation.state == RelationshipActionState.FRIENDS) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)),
                                modifier = Modifier.height(36.dp),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.experience_friend_state),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = viewModel::toggleRelationship,
                                enabled = !state.relationshipBusy,
                            ) {
                                Text(when (relation.state) {
                                    RelationshipActionState.NONE -> stringResource(R.string.action_follow)
                                    RelationshipActionState.REQUEST_PENDING -> stringResource(R.string.experience_requested_state)
                                    RelationshipActionState.FOLLOWING -> stringResource(R.string.experience_following_state)
                                    else -> ""
                                })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                if (shouldShowExperienceTitle(experience.title, experience.place.name, experience.classification)) {
                    Text(experience.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                val primary = experience.primaryExperience.rawLabel?.takeIf(String::isNotBlank)
                    ?: ExperienceLabels.primary(experience.primaryExperience.code, language)
                primary?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) }
                AssistChip(
                    onClick = {},
                    label = { Text(ExperienceLabels.feeling(experience.feeling.code, language)) },
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = viewModel::togglePlan,
                        enabled = !state.planBusy,
                        modifier = Modifier.weight(1f).height(48.dp).semantics {
                            stateDescription = if (experience.plannedByViewer) "selected" else "not selected"
                        },
                    ) {
                        Icon(Icons.Rounded.Bookmark, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (experience.plannedByViewer) R.string.experience_in_plan else R.string.experience_add_to_plan))
                    }
                    if (experience.author.relationship != null) {
                        AcknowledgementControl(
                            acknowledged = experience.acknowledgedByViewer,
                            busy = state.acknowledgementBusy,
                            onAcknowledge = viewModel::acknowledge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (experience.acknowledgementCount > 0) {
                    Text(
                        pluralStringResource(R.plurals.experience_acknowledgement_count,
                            experience.acknowledgementCount.toInt(), experience.acknowledgementCount),
                        Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = { showCollectionPicker = true }, Modifier.padding(top = 2.dp)) {
                    Icon(Icons.Rounded.FolderCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_experience_to_collection))
                }
                if (experience.companion != null || experience.timeOfDay != null || experience.vibes.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        experience.companion?.let { item { AssistChip(onClick = {}, label = { Text(ExperienceLabels.companion(it, language)) }) } }
                        experience.timeOfDay?.let { item { AssistChip(onClick = {}, label = { Text(ExperienceLabels.time(it, language)) }) } }
                        items(experience.vibes, key = { it.name }) { vibe -> AssistChip(onClick = {}, label = { Text(ExperienceLabels.vibe(vibe, language)) }) }
                    }
                }
                if (experience.story.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.experience_story_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(experience.story, style = MaterialTheme.typography.bodyLarge)
                }
                experience.tip?.takeIf(String::isNotBlank)?.let { tipText ->
                    Surface(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                stringResource(R.string.experience_tip_title),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                tipText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                if (experience.practicalSignals.isNotEmpty()) {
                    Text(
                        stringResource(R.string.experience_practical),
                        Modifier.padding(top = 18.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    experience.practicalSignals.forEach { signal ->
                        Text("• ${ExperienceLabels.practical(signal, language)}", Modifier.padding(top = 4.dp))
                    }
                }
                if (experience.dimensions.isNotEmpty()) {
                    Text(
                        stringResource(R.string.experience_dimensions_title),
                        Modifier.padding(top = 18.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    experience.dimensions.forEach { dimension ->
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(ExperienceLabels.dimensionKey(dimension.key, language), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                dimension.semanticState?.let { ExperienceLabels.dimensionState(it, language) }
                                    ?: stringResource(R.string.past_ratings),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Surface(
                    Modifier.fillMaxWidth().padding(top = 22.dp).clickable { onPlace(experience.place.id) },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.experience_about_place), style = MaterialTheme.typography.labelMedium)
                        Text(experience.place.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            listOf(experience.place.city, experience.place.region, experience.place.country)
                                .filter(String::isNotBlank).joinToString(" · "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.errorMessage?.let {
                    Text(stringResource(it), Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
