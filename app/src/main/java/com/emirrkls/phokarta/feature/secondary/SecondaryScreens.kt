package com.emirrkls.phokarta.feature.secondary

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.feature.activity.ActivityViewModel
import com.emirrkls.phokarta.feature.collections.CreateCollectionSheet
import com.emirrkls.phokarta.feature.collections.visibilityLabel
import com.emirrkls.phokarta.ui.components.ActivityEmptyState
import com.emirrkls.phokarta.ui.components.ActivityErrorState
import com.emirrkls.phokarta.ui.components.ActivityEventCard
import com.emirrkls.phokarta.ui.components.ActivityLoadingIndicator
import com.emirrkls.phokarta.ui.components.CollectionListCard
import com.emirrkls.phokarta.ui.components.CompactPlaceCard
import com.emirrkls.phokarta.ui.theme.Coral
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    onPlace: (String) -> Unit,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onScreenResumed()
    }

    LaunchedEffect(listState, state.hasNext, state.isLoadingMore, state.isLoadingInitial) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            lastVisible >= total - 3 && total > 0
        }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (nearEnd && state.hasNext && !state.isLoadingMore && !state.isLoadingInitial) {
                    viewModel.loadNextPage()
                }
            }
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.isLoadingInitial && state.items.isEmpty() -> {
                Column(Modifier.fillMaxSize()) {
                    ActivityHeader()
                    ActivityLoadingIndicator()
                }
            }
            state.errorMessage != null && state.items.isEmpty() -> {
                Column(Modifier.fillMaxSize()) {
                    ActivityHeader()
                    ActivityErrorState(
                        message = state.errorMessage.orEmpty(),
                        onRetry = viewModel::retry,
                    )
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 110.dp),
                ) {
                    item { ActivityHeader() }
                    if (state.items.isEmpty()) {
                        item { ActivityEmptyState() }
                    } else {
                        items(state.items, key = { it.visitId }) { event ->
                            ActivityEventCard(
                                event = event,
                                currentUserId = state.currentUserId,
                                expanded = event.visitId in state.expandedReviewIds,
                                onToggleExpand = { viewModel.toggleReviewExpanded(event.visitId) },
                                onOpenPlace = { onPlace(event.place.id) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                            )
                        }
                    }
                    state.loadMoreErrorMessage?.let { error ->
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = viewModel::retryLoadMore) { Text("Retry") }
                            }
                        }
                    }
                    if (state.isLoadingMore) {
                        item { ActivityLoadingIndicator() }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityHeader() {
    Column(Modifier.padding(20.dp)) {
        Text("Community activity", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Recent public visits from travelers.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun CollectionsScreen(
    onBack: () -> Unit,
    onCollection: (String) -> Unit,
    onCreateCollection: (() -> Unit)? = null,
    viewModel: SecondaryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var awaitingCreateSuccess by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Curated collections", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(
                onClick = {
                    if (onCreateCollection != null) onCreateCollection() else showCreate = true
                },
            ) {
                Text("+ New")
            }
        }
        Text(
            "Shortlists with a point of view",
            Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.headlineLarge,
        )
        state.collectionsError?.let { error ->
            Text(
                error,
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.collections.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Start your first shortlist", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Save places you want to remember together.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                TextButton(
                    onClick = {
                        if (onCreateCollection != null) onCreateCollection() else showCreate = true
                    },
                ) {
                    Text("+ New collection")
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.collections, key = { it.id }) { collection ->
                    CollectionListCard(
                        collection = collection,
                        placeCount = collection.placeIds.size,
                        visibilityLabel = visibilityLabel(collection.visibility),
                        onClick = { onCollection(collection.id) },
                    )
                }
            }
        }
    }

    if (showCreate && onCreateCollection == null) {
        CreateCollectionSheet(
            onDismiss = {
                showCreate = false
                awaitingCreateSuccess = false
                viewModel.clearCreateCollectionError()
            },
            isSubmitting = state.isCreatingCollection,
            errorMessage = state.createCollectionError,
            onSubmit = { title, description, visibility ->
                awaitingCreateSuccess = true
                viewModel.createCollection(title, description, visibility)
            },
        )
        LaunchedEffect(state.isCreatingCollection, state.createCollectionError, state.createdCollectionId) {
            val createdId = state.createdCollectionId
            if (awaitingCreateSuccess && !state.isCreatingCollection && state.createCollectionError == null && createdId != null) {
                awaitingCreateSuccess = false
                showCreate = false
                viewModel.clearCreatedCollectionId()
                onCollection(createdId)
            }
        }
    }
}

@Composable
fun CollectionDetailScreen(collectionId: String, onBack: () -> Unit, onPlace: (String) -> Unit, viewModel: SecondaryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(collectionId) { viewModel.refreshCollectionDetail(collectionId) }
    val collection = state.collections.firstOrNull { it.id == collectionId }
    val places = state.places.filter { it.id in (collection?.placeIds ?: emptyList()) }

    if (state.detailNotFound && collection == null) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Collection not found", style = MaterialTheme.typography.headlineSmall)
            state.detailError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item {
            Box {
                AsyncImage(collection?.coverImage, collection?.title, Modifier.fillMaxWidth().height(285.dp), contentScale = ContentScale.Crop)
                IconButton(onClick = onBack, Modifier.padding(16.dp).background(Color.White.copy(.92f), CircleShape)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.Black)
                }
            }
            Column(Modifier.padding(20.dp)) {
                Text(collection?.title.orEmpty(), style = MaterialTheme.typography.headlineLarge)
                val description = collection?.description.orEmpty()
                if (description.isNotBlank()) {
                    Text(
                        description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${places.size} ${if (places.size == 1) "place" else "places"} · ${collection?.visibility?.let { visibilityLabel(it) }.orEmpty()}",
                    color = Coral,
                    style = MaterialTheme.typography.labelLarge,
                )
                state.detailError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                state.membershipError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (places.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No places here yet", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Add places from any Place page.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            items(places, key = { it.id }) { place ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactPlaceCard(place, { onPlace(place.id) }, Modifier.weight(1f))
                    IconButton(onClick = { viewModel.removePlaceFromCollection(collectionId, place.id) }) {
                        Icon(Icons.Rounded.Clear, "Remove ${place.name} from collection")
                    }
                }
            }
        }
    }
}

@Composable
fun SuccessScreen(placeName: String, onProfile: () -> Unit, onExplore: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(112.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Star, null, Modifier.size(60.dp), tint = Coral) }
        Spacer(Modifier.height(28.dp)); Text("Visit published", style = MaterialTheme.typography.headlineLarge)
        Text("$placeName is now part of your travel history.", Modifier.padding(vertical = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        Surface(Modifier.fillMaxWidth().clickable(onClick = onProfile), color = Coral, shape = RoundedCornerShape(18.dp)) { Text("See it on your profile", Modifier.padding(18.dp), color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.height(10.dp)); Surface(Modifier.fillMaxWidth().clickable(onClick = onExplore), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp)) { Text("Back to Explore", Modifier.padding(18.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelLarge) }
    }
}
