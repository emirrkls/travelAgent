package com.emirrkls.travelagent.feature.place

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.travelagent.core.data.MockTravelRepository
import com.emirrkls.travelagent.ui.components.ScorePill
import com.emirrkls.travelagent.ui.components.CategoryIcon
import com.emirrkls.travelagent.ui.components.TravelImage
import com.emirrkls.travelagent.ui.components.UserAvatar
import com.emirrkls.travelagent.ui.theme.Coral

@Composable
fun PlaceDetailScreen(onBack: () -> Unit, onRate: () -> Unit, viewModel: PlaceDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val place = state.place
    if (place == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Scaffold(
        bottomBar = {
            Surface(shadowElevation = 12.dp) {
                Button(onClick = onRate, Modifier.fillMaxWidth().padding(16.dp).height(54.dp), shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Rounded.AddLocationAlt, null); Spacer(Modifier.width(8.dp)); Text("Been here · Rate this place")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()).verticalScroll(rememberScrollState())) {
            Box {
                TravelImage(place.coverImage, place.name, Modifier.fillMaxWidth().height(340.dp))
                if (isSystemInDarkTheme()) {
                    Box(
                        Modifier.fillMaxWidth().height(88.dp).background(
                            Brush.verticalGradient(listOf(Color.Black.copy(alpha = .38f), Color.Transparent))
                        )
                    )
                }
                IconButton(onClick = onBack, Modifier.align(Alignment.TopStart).padding(16.dp).background(Color.White.copy(.92f), CircleShape)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.Black) }
                IconButton(onClick = viewModel::toggleSaved, Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.White.copy(.92f), CircleShape)) { Icon(if (state.isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, "Save", tint = if (state.isSaved) Coral else Color.Black) }
            }
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CategoryIcon(place.category, size = 16.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(place.category.label.uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text("${"₺".repeat(place.priceLevel)} · ${place.city}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(8.dp))
                Text(place.name, style = MaterialTheme.typography.headlineLarge)
                Text(place.address, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(22.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(22.dp)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("MATCH FOR YOUR TASTE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.height(3.dp))
                            Text("Highly recommended for you", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Text(String.format("%.1f", place.similarUsersScore), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScorePill("Friends", place.friendsScore, modifier = Modifier.weight(1f))
                    ScorePill("Community", place.communityScore, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    DetailAction(Icons.Rounded.AddLocationAlt, "Been here", onRate, Modifier.weight(1f))
                    DetailAction(if (state.isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, "Want to go", viewModel::toggleSaved, Modifier.weight(1f), selected = state.isSaved)
                    DetailAction(Icons.Rounded.FolderCopy, "Add to list", {}, Modifier.weight(1f))
                    DetailAction(Icons.Rounded.IosShare, "Share", {}, Modifier.weight(1f))
                }
                Spacer(Modifier.height(28.dp))
                Text("Why people love it", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                Text(place.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(28.dp))
                Text("The scores", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                place.ratingBreakdown.forEach { (name, score) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Box(Modifier.weight(1.5f).height(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth((score / 10).toFloat()).height(7.dp).background(MaterialTheme.colorScheme.secondary))
                        }
                        Text(String.format("%.1f", score), Modifier.width(42.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text("Friends who visited", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(
                        MockTravelRepository.currentUser.avatarUrl,
                        "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=200",
                        "https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=200",
                    ).forEach { url -> Box(Modifier.padding(end = 6.dp)) { UserAvatar(url) } }
                    Text(place.friendSignal ?: "Friends recommend this", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(28.dp))
                Text("Traveler notes", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("“Come before noon, stay for the soft evening light. The quieter corner is to the left.”", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(12.dp)); Text("Ece Aksoy · 9.2", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text("Photos", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    place.photos.plus(place.coverImage).forEach { image -> TravelImage(image, null, Modifier.size(150.dp, 110.dp).clip(RoundedCornerShape(18.dp))) }
                }
                Spacer(Modifier.height(24.dp))
                Text(place.address, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DetailAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(48.dp).background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(22.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(7.dp)); Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}
