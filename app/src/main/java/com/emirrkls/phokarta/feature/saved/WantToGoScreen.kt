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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.feature.search.SearchSort
import com.emirrkls.phokarta.ui.components.CategoryChip
import com.emirrkls.phokarta.ui.components.CompactPlaceCard
import com.emirrkls.phokarta.ui.components.vectorIcon
import com.emirrkls.phokarta.ui.localization.labelRes
import com.emirrkls.phokarta.ui.presentation.WantToGoCopy

@Composable
fun WantToGoScreen(
    onBack: () -> Unit,
    onPlace: (String) -> Unit,
    viewModel: WantToGoViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var sortMenuOpen by remember { mutableStateOf(false) }
    val sortA11y = stringResource(R.string.a11y_sort_saved_places)
    val searchA11y = stringResource(R.string.a11y_search_within_want_to_go)

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(WantToGoCopy.SURFACE), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.saved_count, state.totalCount),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            IconButton(
                onClick = { sortMenuOpen = true },
                modifier = Modifier.semantics { contentDescription = sortA11y },
            ) {
                Icon(Icons.Rounded.Sort, contentDescription = null)
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_recently_saved)) },
                    onClick = {
                        viewModel.setSort(SearchSort.RECENTLY_SAVED)
                        sortMenuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_community_rating)) },
                    onClick = {
                        viewModel.setSort(SearchSort.RATING)
                        sortMenuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_friends_score)) },
                    onClick = {
                        viewModel.setSort(SearchSort.FRIENDS_SCORE)
                        sortMenuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_most_friends_visited)) },
                    onClick = {
                        viewModel.setSort(SearchSort.MOST_FRIENDS_VISITED)
                        sortMenuOpen = false
                    },
                )
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.want_to_go_search_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = searchA11y },
        )
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                CategoryChip(
                    stringResource(R.string.filter_all),
                    state.category == null &&
                        state.destination == null &&
                        !state.highlyRatedOnly &&
                        !state.friendsVisitedOnly,
                ) {
                    viewModel.clearFilters()
                }
            }
            item {
                CategoryChip(
                    stringResource(R.string.friends_visited),
                    state.friendsVisitedOnly,
                    onClick = viewModel::toggleFriendsVisited,
                )
            }
            item {
                CategoryChip(
                    stringResource(R.string.search_rated_9_plus),
                    state.highlyRatedOnly,
                    onClick = viewModel::toggleHighlyRated,
                )
            }
            items(PlaceCategory.entries) { category ->
                CategoryChip(
                    stringResource(category.labelRes()),
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
        if (
            state.category != null ||
            state.destination != null ||
            state.highlyRatedOnly ||
            state.friendsVisitedOnly ||
            state.query.isNotBlank()
        ) {
            TextButton(
                onClick = viewModel::clearFilters,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(stringResource(R.string.search_clear_filters)) }
        }
        state.saveErrorMessage?.let { message ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(message), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::dismissSaveError) { Text(stringResource(R.string.action_dismiss)) }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.places, key = { it.place.id }) { item ->
                CompactPlaceCard(
                    place = item.place,
                    onClick = { onPlace(item.place.id) },
                    saved = true,
                    friendAverageScore = item.friendAverageScore,
                    friendsVisitedCount = item.friendsVisitedCount,
                    onSave = { viewModel.toggleSaved(item.place.id) },
                )
            }
            if (state.places.isEmpty()) {
                item {
                    Column(Modifier.padding(top = 40.dp, start = 8.dp, end = 8.dp)) {
                        when {
                            state.totalCount == 0 -> {
                                Text(stringResource(R.string.want_to_go_nothing_saved), style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.want_to_go_nothing_saved_body),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            state.friendsVisitedOnly -> {
                                Text(
                                    stringResource(R.string.want_to_go_no_friend_activity),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(
                                        WantToGoLogic.friendsVisitedEmptyMessageRes(
                                            hasFriends = state.friendCount?.let { it > 0 },
                                        ),
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> {
                                Text(stringResource(R.string.want_to_go_no_matches), style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.want_to_go_no_matches_body),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
