package com.emirrkls.phokarta.feature.place

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.ui.components.CommunityReviewCard
import com.emirrkls.phokarta.ui.components.CommunityReviewsEmptyState
import com.emirrkls.phokarta.ui.components.CommunityReviewsErrorState
import com.emirrkls.phokarta.ui.components.CommunityReviewsLoadingIndicator
import com.emirrkls.phokarta.ui.components.CommunityScoreSection
import com.emirrkls.phokarta.ui.components.FriendReviewsEmptyState
import com.emirrkls.phokarta.core.model.ActivityScope
import com.emirrkls.phokarta.feature.secondary.ActivityScopeSelector
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceReviewsScreen(
    onBack: () -> Unit,
    onAuthor: (String) -> Unit = {},
    viewModel: PlaceReviewsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= layoutInfo.totalItemsCount - 3
        }
    }

    LaunchedEffect(listState, state.hasNext, state.isLoadingMore, state.isLoadingInitial) {
        snapshotFlow { shouldLoadMore }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (nearEnd && state.hasNext && !state.isLoadingMore && !state.isLoadingInitial) {
                    viewModel.loadNextPage()
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.place?.name ?: "Reviews",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (state.scope == ActivityScope.FRIENDS) "Friend reviews" else "Community reviews",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoadingInitial && state.reviews.isEmpty() -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.errorMessage != null && state.reviews.isEmpty() -> {
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(20.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    CommunityReviewsErrorState(state.errorMessage.orEmpty(), onRetry = viewModel::retry)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onBack) { Text("Back") }
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        ActivityScopeSelector(
                            activeScope = state.scope,
                            onSelectScope = viewModel::selectScope,
                        )
                        Spacer(Modifier.height(8.dp))
                        state.place?.let { place ->
                            if (state.scope == ActivityScope.COMMUNITY) {
                                CommunityScoreSection(
                                    communityScore = place.communityScore,
                                    ratingCount = place.ratingCount,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (state.totalElements == 1L) "1 review" else "${state.totalElements} reviews",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    if (state.reviews.isEmpty()) {
                        item {
                            if (state.scope == ActivityScope.FRIENDS) {
                                FriendReviewsEmptyState()
                            } else {
                                CommunityReviewsEmptyState(hasVisited = false)
                            }
                        }
                    } else {
                        items(state.reviews, key = { it.id }) { review ->
                            CommunityReviewCard(
                                review = review,
                                currentUserId = state.currentUserId,
                                expanded = review.id in state.expandedReviewIds,
                                onToggleExpand = { viewModel.toggleReviewExpanded(review.id) },
                                onOpenAuthor = onAuthor,
                                previewMaxLines = Int.MAX_VALUE,
                            )
                        }
                    }
                    if (state.isLoadingMore) {
                        item { CommunityReviewsLoadingIndicator() }
                    }
                    state.loadMoreErrorMessage?.let { message ->
                        item {
                            CommunityReviewsErrorState(message, onRetry = viewModel::retryLoadMore)
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}
