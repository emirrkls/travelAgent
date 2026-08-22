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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.ui.components.CategoryChip
import com.emirrkls.phokarta.ui.components.FeaturedPlaceCard
import com.emirrkls.phokarta.ui.components.PlaceCard
import com.emirrkls.phokarta.ui.components.SectionHeader
import com.emirrkls.phokarta.ui.components.UserAvatar
import com.emirrkls.phokarta.ui.components.vectorIcon
import com.emirrkls.phokarta.ui.theme.TravelSpacing

@Composable
fun ExploreScreen(
    onSearch: () -> Unit,
    onPlace: (String) -> Unit,
    onCollections: () -> Unit,
    viewModel: ExploreViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedCategory = state.selectedCategory
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(bottom = 110.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = TravelSpacing.md, vertical = TravelSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                UserAvatar("https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=300", 46)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("GOOD EVENING", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    Text("Where to next, Emircan?", style = MaterialTheme.typography.titleLarge)
                }
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    IconButton(onClick = {}) { Icon(Icons.Outlined.Notifications, "Notifications") }
                }
            }
            SearchEntry(onSearch)
            Spacer(Modifier.height(18.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { CategoryChip("All", state.selectedCategory == null) { viewModel.selectCategory(null) } }
                items(listOf(PlaceCategory.BEACH, PlaceCategory.RESTAURANT, PlaceCategory.CAFE, PlaceCategory.HOTEL, PlaceCategory.NIGHTLIFE, PlaceCategory.NATURE)) { category ->
                    CategoryChip(category.label, state.selectedCategory == category, category.vectorIcon) { viewModel.selectCategory(category) }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
        if (selectedCategory != null) {
            item { PlaceSection("${selectedCategory.label} picks", state.filteredPlaces, state.savedPlaceIds, onPlace, viewModel::toggleSaved) }
        } else {
            item { FeaturedSection("Picked by people you trust", state.places.take(5), state.savedPlaceIds, onPlace, viewModel::toggleSaved) }
            item { Spacer(Modifier.height(34.dp)); PlaceSection("Hidden gems", state.places.drop(5).take(4), state.savedPlaceIds, onPlace, viewModel::toggleSaved) }
            item { Spacer(Modifier.height(34.dp)); PlaceSection("Aegean summer", state.places.filter { it.category == PlaceCategory.BEACH }, state.savedPlaceIds, onPlace, viewModel::toggleSaved) }
            item {
                Spacer(Modifier.height(30.dp))
                Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = onCollections), color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(22.dp)) {
                        Text("Travel better with a shortlist", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text("Explore trusted collections →", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text("Search places, cities or categories", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlaceSection(title: String, places: List<Place>, saved: Set<String>, onPlace: (String) -> Unit, onSave: (String) -> Unit) {
    Column {
        Box(Modifier.padding(horizontal = 16.dp)) { SectionHeader(title, "See all") }
        Spacer(Modifier.height(14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(places, key = { it.id }) { place -> PlaceCard(place, place.id in saved, { onPlace(place.id) }, { onSave(place.id) }) }
        }
    }
}

@Composable
private fun FeaturedSection(title: String, places: List<Place>, saved: Set<String>, onPlace: (String) -> Unit, onSave: (String) -> Unit) {
    Column {
        Box(Modifier.padding(horizontal = 16.dp)) { SectionHeader(title, "See all") }
        Spacer(Modifier.height(14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(places, key = { it.id }) { place -> FeaturedPlaceCard(place, place.id in saved, { onPlace(place.id) }, { onSave(place.id) }) }
        }
    }
}
