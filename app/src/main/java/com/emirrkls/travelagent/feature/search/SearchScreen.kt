package com.emirrkls.travelagent.feature.search

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.travelagent.core.model.PlaceCategory
import com.emirrkls.travelagent.ui.components.CategoryChip
import com.emirrkls.travelagent.ui.components.vectorIcon
import com.emirrkls.travelagent.ui.components.CompactPlaceCard

@Composable
fun SearchScreen(onBack: () -> Unit, onPlace: (String) -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Discover", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = state.query, onValueChange = viewModel::setQuery, singleLine = true,
            placeholder = { Text("Try Bodrum, beach, Kaş…") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { CategoryChip("All", state.category == null) { viewModel.setCategory(null) } }
            items(PlaceCategory.entries) { category -> CategoryChip(category.label, state.category == category, category.vectorIcon) { viewModel.setCategory(category) } }
        }
        Spacer(Modifier.height(20.dp))
        Text("${state.results.size} places", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.results, key = { it.id }) { place -> CompactPlaceCard(place, { onPlace(place.id) }) }
            if (state.results.isEmpty()) item { Text("No places found. Try a nearby city or a broader category.", Modifier.padding(top = 40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
