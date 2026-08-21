package com.emirrkls.travelagent.feature.secondary

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.emirrkls.travelagent.ui.components.CategoryChip
import com.emirrkls.travelagent.ui.components.CategoryIcon
import com.emirrkls.travelagent.ui.components.CollectionCard
import com.emirrkls.travelagent.ui.components.CompactPlaceCard
import com.emirrkls.travelagent.ui.components.RatingBadge
import com.emirrkls.travelagent.ui.components.TravelImage
import com.emirrkls.travelagent.ui.components.UserAvatar
import com.emirrkls.travelagent.core.model.ActivityItem
import com.emirrkls.travelagent.core.model.Collection
import com.emirrkls.travelagent.core.model.Place
import com.emirrkls.travelagent.ui.theme.Coral

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
                        Icon(if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, if (saved) "Remove saved place" else "Save place", tint = MaterialTheme.colorScheme.primary)
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
fun CollectionsScreen(onBack: () -> Unit, onCollection: (String) -> Unit, viewModel: SecondaryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }; Text("Curated collections", style = MaterialTheme.typography.titleLarge) }
        Text("Shortlists with a point of view", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.headlineLarge)
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(state.collections, key = { it.id }) { collection -> CollectionCard(collection, collection.placeIds.size, { onCollection(collection.id) }, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
fun CollectionDetailScreen(collectionId: String, onBack: () -> Unit, onPlace: (String) -> Unit, viewModel: SecondaryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val collection = state.collections.firstOrNull { it.id == collectionId }
    val places = state.places.filter { it.id in (collection?.placeIds ?: emptyList()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item {
            Box {
                AsyncImage(collection?.coverImage, collection?.title, Modifier.fillMaxWidth().height(285.dp), contentScale = ContentScale.Crop)
                IconButton(onClick = onBack, Modifier.padding(16.dp).background(Color.White.copy(.92f), CircleShape)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.Black) }
            }
            Column(Modifier.padding(20.dp)) { Text(collection?.title.orEmpty(), style = MaterialTheme.typography.headlineLarge); Text(collection?.description.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.height(6.dp)); Text("${places.size} places · ${collection?.visibility?.name?.lowercase()}", color = Coral, style = MaterialTheme.typography.labelLarge) }
        }
        items(places, key = { it.id }) { place -> CompactPlaceCard(place, { onPlace(place.id) }, Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) }
    }
}

@Composable
fun MapScreen(onPlace: (String) -> Unit, viewModel: SecondaryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxSize()) {
            Text("Explore the map", Modifier.padding(start = 20.dp, top = 18.dp), style = MaterialTheme.typography.headlineLarge)
            LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("Location", "Category", "Friends", "Rating", "Visited", "Want to Go")) { label -> CategoryChip(label, false) {} }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Icon(Icons.Rounded.Map, null, Modifier.size(180.dp).align(Alignment.Center), tint = MaterialTheme.colorScheme.secondary.copy(alpha = .12f))
                state.places.take(7).forEachIndexed { index, place ->
                    Surface(Modifier.align(mapAlignments[index]).padding(12.dp).clickable { onPlace(place.id) }, color = if (index == 0) Coral else MaterialTheme.colorScheme.surface, shape = CircleShape, shadowElevation = 5.dp) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.LocationOn, null, Modifier.size(17.dp), tint = if (index == 0) Color.White else Coral); Text(String.format("%.1f", place.communityScore), color = if (index == 0) Color.White else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), shadowElevation = 12.dp) {
                Column(Modifier.padding(vertical = 14.dp)) {
                    Box(Modifier.size(42.dp, 5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline).align(Alignment.CenterHorizontally))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Places in this area", style = MaterialTheme.typography.titleLarge); Text("${state.places.size} spots", color = Coral) }
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(state.places.take(5)) { place -> Surface(Modifier.clickable { onPlace(place.id) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Column(Modifier.padding(13.dp)) { Text(place.name, style = MaterialTheme.typography.labelLarge); Text("${place.city} · ${place.communityScore}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) } } } }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

private val mapAlignments = listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.Center, Alignment.CenterStart, Alignment.CenterEnd, Alignment.BottomStart, Alignment.BottomEnd)

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
