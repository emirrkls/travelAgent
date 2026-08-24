package com.emirrkls.phokarta.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.ui.components.CategoryChip
import com.emirrkls.phokarta.ui.components.CompactPlaceCard
import com.emirrkls.phokarta.ui.components.vectorIcon
import com.emirrkls.phokarta.ui.presentation.WantToGoCopy

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onPlace: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var sortMenuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Discover", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(
                onClick = { sortMenuOpen = true },
                modifier = Modifier.semantics { contentDescription = "Sort results" },
            ) {
                Icon(Icons.Rounded.Sort, contentDescription = null)
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Recommended") },
                    onClick = {
                        viewModel.setSort(SearchSort.DEFAULT)
                        sortMenuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Rating") },
                    onClick = {
                        viewModel.setSort(SearchSort.RATING)
                        sortMenuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Recently saved") },
                    onClick = {
                        viewModel.setSort(SearchSort.RECENTLY_SAVED)
                        sortMenuOpen = false
                    },
                )
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            singleLine = true,
            placeholder = { Text("Try Bodrum, beach, Kaş…") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = "Search places, cities or categories" },
        )
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                CategoryChip("All", !state.filters.hasActiveFilters && state.filters.category == null) {
                    viewModel.clearFilters()
                }
            }
            item {
                CategoryChip(WantToGoCopy.SURFACE, state.filters.savedOnly) {
                    viewModel.toggleSavedOnly()
                }
            }
            item {
                CategoryChip("Visited", state.filters.visitedOnly) {
                    viewModel.toggleVisitedOnly()
                }
            }
            item {
                CategoryChip("9+ Rated", state.filters.highlyRatedOnly) {
                    viewModel.toggleHighlyRated()
                }
            }
            items(PlaceCategory.entries) { category ->
                CategoryChip(
                    category.label,
                    state.filters.category == category,
                    category.vectorIcon,
                ) { viewModel.setCategory(if (state.filters.category == category) null else category) }
            }
        }
        if (state.filters.hasActiveFilters) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = viewModel::clearFilters) { Text("Clear filters") }
                Spacer(Modifier.weight(1f))
                Text(
                    sortLabel(state.filters.sort),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${state.totalElements} places",
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            if (state.isLoading) CircularProgressIndicator(Modifier.height(24.dp))
        }
        state.errorMessage?.let { message ->
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                Button(onClick = viewModel::retry) { Text("Retry") }
            }
        }
        state.saveErrorMessage?.let { message ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::dismissSaveError) { Text("Dismiss") }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.results, key = { it.id }) { place ->
                CompactPlaceCard(
                    place = place,
                    onClick = { onPlace(place.id) },
                    saved = place.id in state.savedPlaceIds,
                    visited = place.id in state.visitedPlaceIds,
                    onSave = { viewModel.toggleSaved(place.id) },
                )
            }
            state.emptyReason?.let { reason ->
                item {
                    SearchEmptyState(reason)
                }
            }
        }
    }
}

@Composable
private fun SearchEmptyState(reason: SearchEmptyReason) {
    val (title, body) = when (reason) {
        SearchEmptyReason.NO_RESULTS -> "No places found" to "Try another place, city or category."
        SearchEmptyReason.NOTHING_SAVED -> "Nothing saved yet" to
            "Save places you want to remember and they’ll appear here."
        SearchEmptyReason.NOTHING_VISITED -> "No visits yet" to
            "Places you’ve rated will show up when you filter by Visited."
    }
    Column(Modifier.padding(top = 40.dp, start = 8.dp, end = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sortLabel(sort: SearchSort): String = when (sort) {
    SearchSort.DEFAULT -> "Recommended"
    SearchSort.RATING -> "Rating"
    SearchSort.RECENTLY_SAVED -> "Recently saved"
    SearchSort.FRIENDS_SCORE -> "Friends score"
    SearchSort.MOST_FRIENDS_VISITED -> "Most friends visited"
}
