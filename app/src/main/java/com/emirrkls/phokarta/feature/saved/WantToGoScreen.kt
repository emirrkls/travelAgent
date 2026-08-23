package com.emirrkls.phokarta.feature.saved

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
import com.emirrkls.phokarta.feature.search.SearchSort
import com.emirrkls.phokarta.ui.components.CategoryChip
import com.emirrkls.phokarta.ui.components.CompactPlaceCard
import com.emirrkls.phokarta.ui.components.vectorIcon
import com.emirrkls.phokarta.ui.presentation.WantToGoCopy

@Composable
fun WantToGoScreen(
    onBack: () -> Unit,
    onPlace: (String) -> Unit,
    viewModel: WantToGoViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var sortMenuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(WantToGoCopy.SURFACE, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${state.totalCount} saved",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            IconButton(
                onClick = { sortMenuOpen = true },
                modifier = Modifier.semantics { contentDescription = "Sort saved places" },
            ) {
                Icon(Icons.Rounded.Sort, contentDescription = null)
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Recently saved") },
                    onClick = {
                        viewModel.setSort(SearchSort.RECENTLY_SAVED)
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
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            singleLine = true,
            placeholder = { Text("Search saved places") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = "Search within Want to Go" },
        )
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                CategoryChip("All", state.category == null && state.destination == null && !state.highlyRatedOnly) {
                    viewModel.clearFilters()
                }
            }
            item {
                CategoryChip("9+ Rated", state.highlyRatedOnly, onClick = viewModel::toggleHighlyRated)
            }
            items(PlaceCategory.entries) { category ->
                CategoryChip(
                    category.label,
                    state.category == category,
                    category.vectorIcon,
                ) { viewModel.setCategory(if (state.category == category) null else category) }
            }
            items(state.destinations) { city ->
                CategoryChip(city, state.destination == city) {
                    viewModel.setDestination(if (state.destination == city) null else city)
                }
            }
        }
        if (state.category != null || state.destination != null || state.highlyRatedOnly || state.query.isNotBlank()) {
            TextButton(
                onClick = viewModel::clearFilters,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text("Clear filters") }
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
            items(state.places, key = { it.id }) { place ->
                CompactPlaceCard(
                    place = place,
                    onClick = { onPlace(place.id) },
                    saved = true,
                    onSave = { viewModel.toggleSaved(place.id) },
                )
            }
            if (state.places.isEmpty()) {
                item {
                    Column(Modifier.padding(top = 40.dp, start = 8.dp, end = 8.dp)) {
                        if (state.totalCount == 0) {
                            Text("Nothing saved yet", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Save places you want to remember and they’ll appear here.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text("No matches", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Try another filter or clear your search.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
