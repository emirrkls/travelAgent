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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.emirrkls.phokarta.core.model.ActivityItem
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.feature.collections.CreateCollectionSheet
import com.emirrkls.phokarta.feature.collections.visibilityLabel
import com.emirrkls.phokarta.ui.components.CategoryIcon
import com.emirrkls.phokarta.ui.components.CollectionListCard
import com.emirrkls.phokarta.ui.components.CompactPlaceCard
import com.emirrkls.phokarta.ui.components.RatingBadge
import com.emirrkls.phokarta.ui.components.TravelImage
import com.emirrkls.phokarta.ui.components.UserAvatar
import com.emirrkls.phokarta.ui.presentation.WantToGoCopy
import com.emirrkls.phokarta.ui.theme.Coral

@Composable
fun ActivityScreen(onPlace: (String) -> Unit, onCollection: (String) -> Unit, viewModel: SecondaryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        item { Column(Modifier.padding(20.dp)) { Text("From your people", style = MaterialTheme.typography.headlineLarge); Text("Fresh reasons to discover somewhere new.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        items(state.activity, key = { it.id }) { item ->
            val place = state.places.firstOrNull { it.id == item.placeId }
            val collection = state.collections.firstOrNull { it.id == item.collectionId }
            when {
                place != null -> PlaceActivityCard(
                    item = item,
                    place = place,
                    saved = place.id in state.savedPlaceIds,
                    onOpen = { onPlace(place.id) },
                    onSave = { viewModel.toggleSaved(place.id) },
                )
                collection != null -> CollectionActivityCard(
                    item = item,
                    collection = collection,
                    previewPlaces = state.places.filter { it.id in collection.placeIds }.take(3),
                    onOpen = { onCollection(collection.id) },
                )
            }
        }
    }
}

@Composable
private fun ActivityHeader(item: ActivityItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        UserAvatar(item.user.avatarUrl, 42)
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(item.user.displayName, style = MaterialTheme.typography.labelLarge)
            Text(item.message, style = MaterialTheme.typography.bodyMedium)
        }
        Text(item.timeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PlaceActivityCard(item: ActivityItem, place: Place, saved: Boolean, onOpen: () -> Unit, onSave: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            ActivityHeader(item)
            Spacer(Modifier.height(12.dp))
            Surface(Modifier.fillMaxWidth().clickable(onClick = onOpen), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TravelImage(place.coverImage, place.name, Modifier.size(78.dp).clip(RoundedCornerShape(14.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            CategoryIcon(place.category, size = 15.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${place.category.label} · ${place.city}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        activityScore(item.message)?.let { score ->
                            Spacer(Modifier.height(7.dp))
                            RatingBadge(score)
                        }
                    }
                    IconButton(onClick = onSave) {
                        Icon(
                            if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            WantToGoCopy.saveContentDescription(saved),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionActivityCard(item: ActivityItem, collection: Collection, previewPlaces: List<Place>, onOpen: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            ActivityHeader(item)
            Spacer(Modifier.height(12.dp))
            Surface(Modifier.fillMaxWidth().clickable(onClick = onOpen), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        val previews = previewPlaces.ifEmpty { emptyList() }
                        if (previews.isEmpty()) {
                            TravelImage(collection.coverImage, collection.title, Modifier.fillMaxSize())
                        } else {
                            previews.forEach { place -> TravelImage(place.coverImage, place.name, Modifier.weight(1f).height(76.dp)) }
                        }
                    }
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(collection.title, style = MaterialTheme.typography.titleMedium)
                            Text("${collection.placeIds.size} places", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Open collection", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

private fun activityScore(message: String): Double? = Regex("""\b\d{1,2}\.\d\b""").find(message)?.value?.toDoubleOrNull()

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
