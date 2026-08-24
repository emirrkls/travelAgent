package com.emirrkls.phokarta.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.emirrkls.phokarta.core.model.RepeatVisitCopy
import com.emirrkls.phokarta.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.ui.components.CollectionCard
import com.emirrkls.phokarta.ui.components.CompactPlaceCard
import com.emirrkls.phokarta.ui.components.OwnerVisitDetailSheet
import com.emirrkls.phokarta.ui.components.SectionHeader
import com.emirrkls.phokarta.ui.components.TravelImage
import com.emirrkls.phokarta.core.model.VisitStateLogic
import com.emirrkls.phokarta.ui.theme.Coral
import com.emirrkls.phokarta.ui.presentation.WantToGoCopy
import androidx.compose.material.icons.rounded.Settings
import com.emirrkls.phokarta.ui.localization.formatScoreLocalized
import com.emirrkls.phokarta.ui.localization.formatMediumDateLocalized
import com.emirrkls.phokarta.feature.rating.VisitVisibilityCopy

@Composable
fun ProfileScreen(
    onPlace: (String) -> Unit,
    onCollection: (String) -> Unit,
    onWantToGo: () -> Unit,
    onUserSearch: () -> Unit = {},
    onFollowers: () -> Unit = {},
    onFollowing: () -> Unit = {},
    onFriends: () -> Unit = {},
    onSettings: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshSocialCounts()
    }
    var tab by remember { mutableIntStateOf(0) }
    var selectedVisit by remember { mutableStateOf<com.emirrkls.phokarta.core.model.Visit?>(null) }
    var selectedPlaceName by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onUserSearch) { Icon(Icons.Rounded.PersonSearch, stringResource(R.string.find_people)) }
                IconButton(onClick = {}) { Icon(Icons.Rounded.IosShare, stringResource(R.string.share_profile)) }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.settings)) }
            }
            val identityContainer = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.secondaryContainer
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                color = identityContainer,
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = .28f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TravelImage(state.user.avatarUrl, state.user.displayName, Modifier.size(82.dp).clip(CircleShape))
                        Column(Modifier.weight(1f).padding(start = 15.dp)) {
                            Text(stringResource(R.string.travel_identity), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(state.user.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("@${state.user.username}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(state.user.bio, Modifier.fillMaxWidth().padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ProfileStat(state.user.countryCount.toString(), stringResource(R.string.countries), true)
                        ProfileStat(state.user.cityCount.toString(), stringResource(R.string.cities), true)
                        ProfileStat(state.visitedPlaces.size.toString(), stringResource(R.string.places), true)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceAround) {
                ProfileStat(compactCount(state.followerCount), stringResource(R.string.followers), onClick = onFollowers)
                Box(Modifier.size(1.dp, 34.dp).background(MaterialTheme.colorScheme.outlineVariant))
                ProfileStat(state.followingCount.toString(), stringResource(R.string.following), onClick = onFollowing)
                Box(Modifier.size(1.dp, 34.dp).background(MaterialTheme.colorScheme.outlineVariant))
                ProfileStat(state.friendCount.toString(), stringResource(R.string.friends), onClick = onFriends)
            }
            Spacer(Modifier.height(26.dp))
            Text(stringResource(R.string.travel_taste), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.user.travelTaste) { taste -> Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) { Text(taste, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge) } }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(4.dp)) {
                listOf(Icons.Rounded.GridView to stringResource(R.string.places), Icons.Rounded.FolderCopy to stringResource(R.string.lists), Icons.Rounded.Map to stringResource(R.string.map_tab), Icons.Rounded.FlightTakeoff to stringResource(R.string.trips)).forEachIndexed { index, item ->
                    Surface(Modifier.weight(1f).clickable { tab = index }, color = if (tab == index) MaterialTheme.colorScheme.surface else Color.Transparent, shape = RoundedCornerShape(13.dp)) {
                        Column(Modifier.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(item.first, null, Modifier.size(18.dp), tint = if (tab == index) Coral else MaterialTheme.colorScheme.onSurfaceVariant); Text(item.second, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
        }
        when (tab) {
            0 -> {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                            .padding(4.dp),
                    ) {
                        listOf(
                            ProfilePlacesSegment.VISITS to stringResource(R.string.visits),
                            ProfilePlacesSegment.SAVED to stringResource(R.string.saved),
                        ).forEach { (segment, label) ->
                            val selected = state.placesSegment == segment
                            Surface(
                                Modifier
                                    .weight(1f)
                                    .clickable { viewModel.setPlacesSegment(segment) },
                                color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                                shape = RoundedCornerShape(11.dp),
                            ) {
                                Text(
                                    label,
                                    Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) Coral else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                state.saveErrorMessage?.let { message ->
                    item {
                        Text(
                            stringResource(message),
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (state.placesSegment == ProfilePlacesSegment.VISITS) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ProfileSummaryChip(pluralStringResource(R.plurals.visits_count, state.visitSummary.totalVisits, state.visitSummary.totalVisits))
                            ProfileSummaryChip(pluralStringResource(R.plurals.places_count, state.visitSummary.placesVisited, state.visitSummary.placesVisited))
                            state.visitSummary.averageGivenScore?.let {
                                ProfileSummaryChip(stringResource(R.string.avg_score, formatScoreLocalized(it)))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    item {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            SectionHeader(stringResource(R.string.your_visits), stringResource(R.string.total_visits_label, state.visitSummary.totalVisits))
                        }
                    }
                    items(state.pendingVisits, key = { "pending-${it.pending.mutationId}" }) { pending ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                            CompactPlaceCard(
                                place = pending.place,
                                onClick = { onPlace(pending.place.id) },
                                saved = pending.place.id in state.savedPlaceIds,
                                visited = false,
                                onSave = { viewModel.toggleSaved(pending.place.id) },
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(
                                        R.string.sync_status_visibility,
                                        stringResource(if (pending.pending.failed) R.string.sync_failed else R.string.pending_sync),
                                        stringResource(VisitVisibilityCopy.labelRes(pending.pending.visit.visibility)),
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                if (pending.pending.failed) {
                                    TextButton(onClick = { viewModel.retryMutation(pending.pending.mutationId) }) {
                                        Text(stringResource(R.string.action_retry))
                                    }
                                }
                            }
                        }
                    }
                    items(state.visitedPlaces, key = { it.visit.id }) { visited ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                            CompactPlaceCard(
                                place = visited.place,
                                onClick = {
                                    selectedPlaceName = visited.place.name
                                    selectedVisit = visited.visit
                                },
                                saved = visited.place.id in state.savedPlaceIds,
                                visited = true,
                                onSave = { viewModel.toggleSaved(visited.place.id) },
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(
                                        formatMediumDateLocalized(visited.visit.visitedAt),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    when (val copy = VisitStateLogic.repeatVisitCopy(state.placeVisitCounts[visited.place.id] ?: 1)) {
                                        is RepeatVisitCopy.Twice -> Text(
                                            stringResource(R.string.visited_twice),
                                            color = MaterialTheme.colorScheme.secondary,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                        is RepeatVisitCopy.Times -> Text(
                                            pluralStringResource(R.plurals.visited_times, copy.count, copy.count),
                                            color = MaterialTheme.colorScheme.secondary,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                        null -> Unit
                                    }
                                }
                                Text(
                                    formatScoreLocalized(visited.visit.overallRating),
                                    color = Coral,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            SectionHeader(stringResource(WantToGoCopy.SURFACE), stringResource(R.string.action_see_all), onWantToGo)
                        }
                    }
                    if (state.savedPlaces.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.nothing_saved_yet),
                                Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(state.savedPlaces, key = { it.id }) { place ->
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                                CompactPlaceCard(
                                    place = place,
                                    onClick = { onPlace(place.id) },
                                    saved = true,
                                    onSave = { viewModel.toggleSaved(place.id) },
                                )
                            }
                        }
                    }
                }
            }
            1 -> item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.collections, key = { it.id }) { collection -> CollectionCard(collection, collection.placeIds.size, { onCollection(collection.id) }) }
                }
            }
            2 -> item {
                Box(Modifier.padding(16.dp).fillMaxWidth().height(280.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Rounded.Map, null, Modifier.size(58.dp), tint = MaterialTheme.colorScheme.secondary); Text(pluralStringResource(R.plurals.cities_explored, state.user.cityCount, state.user.cityCount), style = MaterialTheme.typography.titleLarge); Text(stringResource(R.string.travel_map_grows), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            else -> item {
                Surface(Modifier.padding(16.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.FlightTakeoff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.trips_coming_into_focus), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.trips_placeholder_body), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    selectedVisit?.let { visit ->
        OwnerVisitDetailSheet(
            placeName = selectedPlaceName,
            visit = visit,
            onDismiss = { selectedVisit = null },
        )
    }
}

@Composable
private fun ProfileSummaryChip(label: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(50)) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun ProfileStat(
    value: String,
    label: String,
    prominent: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .semantics { contentDescription = "$value $label" }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Text(
            value,
            fontWeight = FontWeight.Bold,
            style = if (prominent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
        )
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
private fun compactCount(count: Long): String =
    if (count >= 1000) String.format("%.1fk", count / 1000f) else count.toString()
private fun compactCount(count: Int): String = compactCount(count.toLong())
