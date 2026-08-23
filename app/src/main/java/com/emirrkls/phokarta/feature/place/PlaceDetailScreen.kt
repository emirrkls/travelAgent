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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.ui.components.OwnerVisitDetailSheet
import com.emirrkls.phokarta.ui.components.VisitHistoryRow
import com.emirrkls.phokarta.core.model.ActivityScope
import com.emirrkls.phokarta.core.share.PhokartaShare
import com.emirrkls.phokarta.feature.collections.CollectionPickerSheet
import com.emirrkls.phokarta.feature.collections.CreateCollectionSheet
import com.emirrkls.phokarta.feature.secondary.ActivityScopeSelector
import com.emirrkls.phokarta.ui.components.CategoryIcon
import com.emirrkls.phokarta.ui.components.CommunityReviewCard
import com.emirrkls.phokarta.ui.components.CommunityReviewsEmptyState
import com.emirrkls.phokarta.ui.components.CommunityReviewsErrorState
import com.emirrkls.phokarta.ui.components.CommunityReviewsLoadingIndicator
import com.emirrkls.phokarta.ui.components.CommunityReviewsSectionHeader
import com.emirrkls.phokarta.ui.components.CommunityScoreSection
import com.emirrkls.phokarta.ui.components.FriendReviewsEmptyState
import com.emirrkls.phokarta.ui.components.FriendScoreSection
import com.emirrkls.phokarta.ui.components.PersonalVisitScoreSection
import com.emirrkls.phokarta.ui.components.RatingBadge
import com.emirrkls.phokarta.ui.components.TravelImage
import com.emirrkls.phokarta.ui.components.UserAvatar
import com.emirrkls.phokarta.ui.theme.Coral

@Composable
fun PlaceDetailScreen(
    onBack: () -> Unit,
    onRate: () -> Unit,
    onSeeAllReviews: (ActivityScope) -> Unit = {},
    onAuthor: (String) -> Unit = {},
    visitPublished: Boolean = false,
    onVisitPublishedConsumed: () -> Unit = {},
    viewModel: PlaceDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val place = state.place
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showPicker by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var awaitingCreateSuccess by remember { mutableStateOf(false) }
    var selectedVisit by remember { mutableStateOf<com.emirrkls.phokarta.core.model.Visit?>(null) }
    val hasVisited = state.visits.isNotEmpty()
    val visitCount = state.visits.size
    val latestVisit = state.visits.firstOrNull()
    val inAnyList = state.collections.any { place?.id in it.placeIds }
    val rateLabel = if (hasVisited) "Rate another visit" else "Been here"

    LaunchedEffect(visitPublished) {
        if (visitPublished) {
            snackbarHostState.showSnackbar("Visit added")
            viewModel.refreshCommunityReviews()
            onVisitPublishedConsumed()
        }
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    CommunityScoreSection(
                        communityScore = place.communityScore,
                        ratingCount = place.ratingCount,
                        modifier = Modifier.weight(1f),
                    )
                    FriendScoreSection(
                        friendsScore = state.friendSummary.summary?.averageScore,
                        friendsVisitedCount = state.friendSummary.summary?.friendsVisitedCount ?: 0,
                        modifier = Modifier.weight(1f),
                    )
                    latestVisit?.let { visit ->
                        PersonalVisitScoreSection(
                            latestVisit = visit,
                            visitCount = visitCount,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                state.friendSummary.errorMessage?.let { friendError ->
                    Spacer(Modifier.height(8.dp))
                    Text(friendError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::retryFriendSummary) { Text("Retry friends score") }
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Your visits", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        Text(
                            if (visitCount == 1) "1 visit" else "$visitCount visits",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    state.visits.forEach { visit ->
                        VisitHistoryRow(
                            visit = visit,
                            repeatLabel = if (visitCount > 1 && visit.id == latestVisit?.id) "Most recent" else null,
                            onClick = { selectedVisit = visit },
                        )
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
                val friendsPreview = state.friendSummary.summary?.friends.orEmpty()
                if (friendsPreview.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    Text("Friends who visited", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(14.dp))
                    friendsPreview.take(5).forEach { friend ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onAuthor(friend.userId) }
                                .padding(vertical = 6.dp)
                                .semantics {
                                    contentDescription =
                                        "Friend who visited ${friend.displayName}, latest score ${String.format("%.1f", friend.latestScore)}"
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UserAvatar(friend.avatarUrl.orEmpty(), size = 40)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(
                                    friend.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    String.format("%.1f", friend.latestScore),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            RatingBadge(friend.latestScore)
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
                val activeReviews = state.activeReviews
                CommunityReviewsSectionHeader(
                    totalElements = activeReviews.totalElements,
                    title = if (state.activeReviewScope == ActivityScope.FRIENDS) {
                        "Friend reviews"
                    } else {
                        "Community reviews"
                    },
                )
                Spacer(Modifier.height(10.dp))
                ActivityScopeSelector(
                    activeScope = state.activeReviewScope,
                    onSelectScope = viewModel::selectReviewScope,
                )
                Spacer(Modifier.height(12.dp))
                when {
                    activeReviews.isLoading && activeReviews.reviews.isEmpty() ->
                        CommunityReviewsLoadingIndicator()
                    activeReviews.errorMessage != null && activeReviews.reviews.isEmpty() ->
                        CommunityReviewsErrorState(
                            message = activeReviews.errorMessage.orEmpty(),
                            onRetry = viewModel::retryActiveReviews,
                        )
                    activeReviews.reviews.isEmpty() -> {
                        if (state.activeReviewScope == ActivityScope.FRIENDS) {
                            FriendReviewsEmptyState()
                        } else {
                            CommunityReviewsEmptyState(hasVisited = hasVisited)
                        }
                    }
                    else -> {
                        activeReviews.reviews.forEach { review ->
                            CommunityReviewCard(
                                review = review,
                                currentUserId = state.currentUserId,
                                expanded = review.id in activeReviews.expandedReviewIds,
                                onToggleExpand = { viewModel.toggleReviewExpanded(review.id) },
                                onOpenAuthor = onAuthor,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        if (activeReviews.totalElements > activeReviews.reviews.size ||
                            activeReviews.hasNext
                        ) {
                            TextButton(
                                onClick = { onSeeAllReviews(state.activeReviewScope) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("See all reviews")
                            }
                        }
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
        OwnerVisitDetailSheet(
            placeName = place.name,
            visit = visit,
            onDismiss = { selectedVisit = null },
        )
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
