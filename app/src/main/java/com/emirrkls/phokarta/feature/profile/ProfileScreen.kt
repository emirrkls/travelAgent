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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.ui.components.CollectionCard
import com.emirrkls.phokarta.ui.components.CompactPlaceCard
import com.emirrkls.phokarta.ui.components.SectionHeader
import com.emirrkls.phokarta.ui.components.TravelImage
import com.emirrkls.phokarta.ui.theme.Coral

@Composable
fun ProfileScreen(onPlace: (String) -> Unit, onCollection: (String) -> Unit, viewModel: ProfileViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = {}) { Icon(Icons.Rounded.IosShare, "Share profile") }
                IconButton(onClick = {}) { Icon(Icons.Rounded.MoreHoriz, "More") }
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
                            Text("TRAVEL IDENTITY", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(state.user.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("@${state.user.username}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(state.user.bio, Modifier.fillMaxWidth().padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ProfileStat(state.user.countryCount.toString(), "Countries", true)
                        ProfileStat(state.user.cityCount.toString(), "Cities", true)
                        ProfileStat(state.visitedPlaces.size.toString(), "Places", true)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 52.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceAround) {
                ProfileStat(compactCount(state.user.followersCount), "Followers")
                Box(Modifier.size(1.dp, 34.dp).background(MaterialTheme.colorScheme.outlineVariant))
                ProfileStat(state.user.followingCount.toString(), "Following")
            }
            Spacer(Modifier.height(26.dp))
            Text("Travel taste", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.user.travelTaste) { taste -> Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) { Text(taste, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge) } }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(4.dp)) {
                listOf(Icons.Rounded.GridView to "Places", Icons.Rounded.FolderCopy to "Lists", Icons.Rounded.Map to "Map", Icons.Rounded.FlightTakeoff to "Trips").forEachIndexed { index, item ->
                    Surface(Modifier.weight(1f).clickable { tab = index }, color = if (tab == index) MaterialTheme.colorScheme.surface else Color.Transparent, shape = RoundedCornerShape(13.dp)) {
                        Column(Modifier.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(item.first, null, Modifier.size(18.dp), tint = if (tab == index) Coral else MaterialTheme.colorScheme.onSurfaceVariant); Text(item.second, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
        }
        when (tab) {
            0 -> {
                item { Box(Modifier.padding(horizontal = 16.dp)) { SectionHeader("Your visits", "${state.visitedPlaces.size} total") } }
                items(state.visitedPlaces, key = { it.visit.id }) { visited ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                        CompactPlaceCard(visited.place, { onPlace(visited.place.id) })
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(visited.visit.visitedAt.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            Text(String.format("Your score %.1f", visited.visit.overallRating), color = Coral, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Rounded.Map, null, Modifier.size(58.dp), tint = MaterialTheme.colorScheme.secondary); Text("${state.user.cityCount} cities explored", style = MaterialTheme.typography.titleLarge); Text("Your travel map will grow with every visit", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            else -> item {
                Surface(Modifier.padding(16.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.FlightTakeoff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("Trips, coming into focus", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text("Your saved places and visits will become trip stories here.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable private fun ProfileStat(value: String, label: String, prominent: Boolean = false) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, fontWeight = FontWeight.Bold, style = if (prominent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) } }
private fun compactCount(count: Int): String = if (count >= 1000) String.format("%.1fk", count / 1000f) else count.toString()
