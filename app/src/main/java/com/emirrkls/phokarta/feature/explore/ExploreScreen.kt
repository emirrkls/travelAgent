package com.emirrkls.phokarta.feature.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.ui.components.CategoryChip
import com.emirrkls.phokarta.ui.components.FeaturedPlaceCard
import com.emirrkls.phokarta.ui.components.PlaceCard
import com.emirrkls.phokarta.ui.components.SectionHeader
import com.emirrkls.phokarta.ui.components.UserAvatar
import com.emirrkls.phokarta.ui.components.vectorIcon
import com.emirrkls.phokarta.ui.localization.labelRes
import com.emirrkls.phokarta.ui.presentation.WantToGoCopy
import com.emirrkls.phokarta.ui.theme.TravelSpacing

@Composable
fun ExploreScreen(
    onSearch: () -> Unit,
    onPlace: (String) -> Unit,
    onCollections: () -> Unit,
    onWantToGo: () -> Unit,
    viewModel: ExploreViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedCategory = state.selectedCategory
    val firstName = state.currentUser?.displayName?.substringBefore(' ').orEmpty()
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(bottom = 110.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = TravelSpacing.md, vertical = TravelSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(state.currentUser?.avatarUrl.orEmpty(), 46)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.explore_greeting), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    Text(
                        stringResource(R.string.explore_where_to_next, firstName),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    IconButton(onClick = {}) {
                        Icon(Icons.Outlined.Notifications, stringResource(R.string.a11y_notifications))
                    }
                }
            }
            SearchEntry(onSearch)
            Spacer(Modifier.height(18.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { CategoryChip(stringResource(R.string.filter_all), state.selectedCategory == null) { viewModel.selectCategory(null) } }
                items(listOf(PlaceCategory.BEACH, PlaceCategory.RESTAURANT, PlaceCategory.CAFE, PlaceCategory.HOTEL, PlaceCategory.NIGHTLIFE, PlaceCategory.NATURE)) { category ->
                    CategoryChip(stringResource(category.labelRes()), state.selectedCategory == category, category.vectorIcon) { viewModel.selectCategory(category) }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
        if (state.isLoading) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        state.errorMessage?.let { message ->
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(message), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::retry) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
        if (selectedCategory == null && state.savedPlaces.isNotEmpty()) {
            item {
                PlaceSection(
                    title = stringResource(WantToGoCopy.SURFACE),
                    places = state.savedPlaces.take(8),
                    saved = state.savedPlaceIds,
                    onPlace = onPlace,
                    onSave = viewModel::toggleSaved,
                    onSeeAll = onWantToGo,
                    visited = state.visitedPlaceIds,
                )
                Spacer(Modifier.height(34.dp))
            }
        }
        if (selectedCategory != null) {
            item {
                PlaceSection(
                    stringResource(R.string.explore_category_picks, stringResource(selectedCategory.labelRes())),
                    state.filteredPlaces,
                    state.savedPlaceIds,
                    state.visitedPlaceIds,
                    onPlace,
                    viewModel::toggleSaved,
                )
            }
        } else {
            item {
                FeaturedSection(
                    stringResource(R.string.explore_picked_by_people),
                    state.places.take(5),
                    state.savedPlaceIds,
                    state.visitedPlaceIds,
                    onPlace,
                    viewModel::toggleSaved,
                )
            }
            item {
                Spacer(Modifier.height(34.dp))
                PlaceSection(
                    stringResource(R.string.explore_hidden_gems),
                    state.places.drop(5).take(4),
                    state.savedPlaceIds,
                    state.visitedPlaceIds,
                    onPlace,
                    viewModel::toggleSaved,
                )
            }
            item {
                Spacer(Modifier.height(34.dp))
                PlaceSection(
                    stringResource(R.string.explore_aegean_summer),
                    state.places.filter { it.category == PlaceCategory.BEACH },
                    state.savedPlaceIds,
                    state.visitedPlaceIds,
                    onPlace,
                    viewModel::toggleSaved,
                )
            }
            item {
                Spacer(Modifier.height(30.dp))
                Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = onCollections), color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(22.dp)) {
                        Text(stringResource(R.string.explore_shortlist_title), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.explore_shortlist_cta), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchEntry(onClick: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(58.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlaceSection(
    title: String,
    places: List<Place>,
    saved: Set<String>,
    visited: Set<String>,
    onPlace: (String) -> Unit,
    onSave: (String) -> Unit,
    onSeeAll: (() -> Unit)? = null,
) {
    Column {
        Box(Modifier.padding(horizontal = 16.dp)) {
            SectionHeader(title, stringResource(R.string.action_see_all), onAction = onSeeAll)
        }
        Spacer(Modifier.height(14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(places, key = { it.id }) { place ->
                PlaceCard(place, place.id in saved, { onPlace(place.id) }, { onSave(place.id) }, place.id in visited)
            }
        }
    }
}

@Composable
private fun FeaturedSection(title: String, places: List<Place>, saved: Set<String>, visited: Set<String>, onPlace: (String) -> Unit, onSave: (String) -> Unit) {
    Column {
        Box(Modifier.padding(horizontal = 16.dp)) { SectionHeader(title, stringResource(R.string.action_see_all)) }
        Spacer(Modifier.height(14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(places, key = { it.id }) { place ->
                FeaturedPlaceCard(place, place.id in saved, { onPlace(place.id) }, { onSave(place.id) }, place.id in visited)
            }
        }
    }
}
