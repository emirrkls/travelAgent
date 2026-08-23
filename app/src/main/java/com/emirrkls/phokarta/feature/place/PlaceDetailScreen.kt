package com.emirrkls.phokarta.feature.place

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.share.PhokartaShare
import com.emirrkls.phokarta.feature.collections.CollectionPickerSheet
import com.emirrkls.phokarta.feature.collections.CreateCollectionSheet
import com.emirrkls.phokarta.ui.components.CategoryIcon
import com.emirrkls.phokarta.ui.components.ScorePill
import com.emirrkls.phokarta.ui.components.TravelImage
import com.emirrkls.phokarta.ui.components.UserAvatar
import com.emirrkls.phokarta.ui.theme.Coral
import java.time.format.DateTimeFormatter

@Composable
fun PlaceDetailScreen(
    onBack: () -> Unit,
    onRate: () -> Unit,
    viewModel: PlaceDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val place = state.place
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var showPicker by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var awaitingCreateSuccess by remember { mutableStateOf(false) }
    var selectedVisit by remember { mutableStateOf<Visit?>(null) }
    val hasVisited = state.visits.isNotEmpty()
    val inAnyList = state.collections.any { place?.id in it.placeIds }
    val rateLabel = if (hasVisited) {
        "Been here · Rate another visit"
    } else {
        "Been here · Rate this place"
    }

    LaunchedEffect(state.shareText) {
        val text = state.shareText ?: return@LaunchedEffect
        PhokartaShare.shareText(context, text)
        viewModel.consumeShareText()
    }

    if (place == null) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (state.isLoading) {
                CircularProgressIndicator()
            } else {
                Text(
                    if (state.isNotFound) "Place not found" else state.errorMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = viewModel::retry) { Text("Retry") }
                Button(onClick = onBack) { Text("Back") }
            }
        }
        return
    }

    Scaffold(
        bottomBar = {
            Surface(shadowElevation = 12.dp) {
                Button(
                    onClick = onRate,
                    Modifier.fillMaxWidth().padding(16.dp).height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Rounded.AddLocationAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(rateLabel)
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
                .verticalScroll(rememberScrollState()),
        ) {
            Box {
                TravelImage(place.coverImage, place.name, Modifier.fillMaxWidth().height(340.dp))
                if (isSystemInDarkTheme()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(88.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = .38f), Color.Transparent),
                                ),
                            ),
                    )
                }
                IconButton(
                    onClick = onBack,
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .background(Color.White.copy(.92f), CircleShape),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.Black)
                }
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.toggleSaved()
                    },
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(Color.White.copy(.92f), CircleShape),
                ) {
                    AnimatedContent(state.isSaved, label = "heroBookmark") { saved ->
                        Icon(
                            if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            if (saved) "Saved" else "Want to go",
                            tint = if (saved) Coral else Color.Black,
                        )
                    }
                }
            }
            Column(Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CategoryIcon(place.category, size = 16.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(
                                place.category.label.uppercase(),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    Text(
                        "${"₺".repeat(place.priceLevel)} · ${place.city}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(place.name, style = MaterialTheme.typography.headlineLarge)
                Text(place.address, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                state.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                state.saveErrorMessage?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = viewModel::toggleSaved) { Text("Retry") }
                    }
                }
                Spacer(Modifier.height(22.dp))
                place.similarUsersScore?.let { personalizedScore ->
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(22.dp)) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "MATCH FOR YOUR TASTE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    "Highly recommended for you",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Text(
                                String.format("%.1f", personalizedScore),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    place.friendsScore?.let { ScorePill("Friends", it, modifier = Modifier.weight(1f)) }
                    place.communityScore?.let {
                        ScorePill("Community", it, modifier = Modifier.weight(1f))
                    } ?: Text("Community · Not rated yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    DetailAction(
                        icon = Icons.Rounded.AddLocationAlt,
                        label = "Been here",
                        onClick = onRate,
                        modifier = Modifier.weight(1f),
                        selected = hasVisited,
                        contentDescription = if (hasVisited) {
                            "Been here. Rate another visit"
                        } else {
                            "Been here. Rate this place"
                        },
                    )
                    DetailAction(
                        icon = if (state.isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        label = if (state.isSaved) "Saved" else "Want to go",
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.toggleSaved()
                        },
                        modifier = Modifier.weight(1f),
                        selected = state.isSaved,
                        contentDescription = if (state.isSaved) "Saved" else "Want to go",
                        animateIcon = true,
                        iconTarget = state.isSaved,
                    )
                    DetailAction(
                        icon = Icons.Rounded.FolderCopy,
                        label = "Add to list",
                        onClick = { showPicker = true },
                        modifier = Modifier.weight(1f),
                        selected = inAnyList,
                        contentDescription = "Add to list",
                    )
                    DetailAction(
                        icon = Icons.Rounded.IosShare,
                        label = "Share",
                        onClick = viewModel::prepareShare,
                        modifier = Modifier.weight(1f),
                        contentDescription = "Share place",
                    )
                }
                state.membershipErrorMessage?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.visits.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    Text("Your visits", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    state.visits.forEach { visit ->
                        VisitHistoryRow(visit = visit, onClick = { selectedVisit = visit })
                        Spacer(Modifier.height(10.dp))
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text("Why people love it", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                Text(place.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(28.dp))
                Text("The scores", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                place.ratingBreakdown.forEach { (dimension, score) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dimension.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Box(
                            Modifier
                                .weight(1.5f)
                                .height(7.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth((score / 10).toFloat())
                                    .height(7.dp)
                                    .background(MaterialTheme.colorScheme.secondary),
                            )
                        }
                        Text(
                            String.format("%.1f", score),
                            Modifier.width(42.dp),
                            textAlign = TextAlign.End,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                place.friendSignal?.let { friendSignal ->
                    Spacer(Modifier.height(28.dp))
                    Text("Friends who visited", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(
                            state.currentUserAvatarUrl,
                            "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=200",
                            "https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=200",
                        ).forEach { url ->
                            Box(Modifier.padding(end = 6.dp)) { UserAvatar(url) }
                        }
                        Text(friendSignal, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text("Traveler notes", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            "“Come before noon, stay for the soft evening light. The quieter corner is to the left.”",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Ece Aksoy · 9.2", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text("Photos", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    place.photos.plus(place.coverImage).forEach { image ->
                        TravelImage(image, null, Modifier.size(150.dp, 110.dp).clip(RoundedCornerShape(18.dp)))
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(place.address, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showPicker && !showCreate) {
        CollectionPickerSheet(
            collections = state.collections,
            placeId = place.id,
            membershipBusyIds = state.membershipBusyIds,
            errorMessage = state.membershipErrorMessage,
            onDismiss = { showPicker = false },
            onToggle = { collectionId ->
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.toggleCollectionMembership(collectionId)
            },
            onCreateNew = {
                showPicker = false
                showCreate = true
            },
        )
    }

    if (showCreate) {
        CreateCollectionSheet(
            onDismiss = {
                showCreate = false
                awaitingCreateSuccess = false
                viewModel.clearCreateCollectionError()
                showPicker = true
            },
            isSubmitting = state.isCreatingCollection,
            errorMessage = state.createCollectionError,
            coverImage = place.coverImage,
            onSubmit = { title, description, visibility ->
                awaitingCreateSuccess = true
                viewModel.createCollection(title, description, visibility, autoSelect = true)
            },
        )
        var wasCreating by remember { mutableStateOf(false) }
        LaunchedEffect(state.isCreatingCollection, state.createCollectionError) {
            if (state.isCreatingCollection) {
                wasCreating = true
            }
            if (
                awaitingCreateSuccess &&
                wasCreating &&
                !state.isCreatingCollection &&
                state.createCollectionError == null
            ) {
                awaitingCreateSuccess = false
                wasCreating = false
                showCreate = false
                showPicker = true
            }
            if (!state.isCreatingCollection && state.createCollectionError != null) {
                wasCreating = false
            }
        }
    }

    selectedVisit?.let { visit ->
        VisitDetailSheet(
            placeName = place.name,
            visit = visit,
            onDismiss = { selectedVisit = null },
        )
    }
}

@Composable
private fun VisitHistoryRow(visit: Visit, onClick: () -> Unit) {
    val dateLabel = visit.visitedAt.format(DateTimeFormatter.ofPattern("MMM yyyy"))
    val highlights = visit.ratingDimensions.entries
        .sortedByDescending { it.value }
        .take(3)
        .joinToString(" · ") { "${it.key.label} ${String.format("%.1f", it.value)}" }
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$dateLabel — ${String.format("%.1f", visit.overallRating)}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (highlights.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    highlights,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (visit.review.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    visit.review,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VisitDetailSheet(placeName: String, visit: Visit, onDismiss: () -> Unit) {
    val dateLabel = visit.visitedAt.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(placeName, style = MaterialTheme.typography.headlineMedium)
            Text(
                "$dateLabel · ${String.format("%.1f", visit.overallRating)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (visit.review.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text("Review", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(visit.review, style = MaterialTheme.typography.bodyLarge)
            }
            if (visit.personalNote.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                Text("Private memory", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    visit.personalNote,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                Spacer(Modifier.height(18.dp))
                Text(
                    "No private memory for this visit.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun DetailAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    contentDescription: String = label,
    animateIcon: Boolean = false,
    iconTarget: Boolean = selected,
) {
    Column(
        modifier
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (animateIcon) {
                AnimatedContent(iconTarget, label = "detailActionIcon") { saved ->
                    Icon(
                        if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = contentDescription,
                        Modifier.size(22.dp),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Icon(
                    icon,
                    contentDescription = contentDescription,
                    Modifier.size(22.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}
