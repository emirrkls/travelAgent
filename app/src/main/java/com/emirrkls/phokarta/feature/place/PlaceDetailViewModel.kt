package com.emirrkls.phokarta.feature.place

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.RepositoryResult
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.data.VisitDraftRepository
import com.emirrkls.phokarta.core.model.ActivityScope
import com.emirrkls.phokarta.core.model.Collection
import com.emirrkls.phokarta.core.model.FriendPlaceSummary
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PublicReview
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.share.PhokartaShare
import com.emirrkls.phokarta.ui.presentation.toUserMessageRes
import com.emirrkls.phokarta.core.sync.NoOpOfflineMutationRepository
import com.emirrkls.phokarta.core.sync.OfflineMutationRepository
import com.emirrkls.phokarta.core.sync.PendingVisit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class CommunityReviewsUiState(
    val reviews: List<PublicReview> = emptyList(),
    val totalElements: Long = 0,
    val hasNext: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: Int? = null,
    val expandedReviewIds: Set<String> = emptySet(),
)

data class FriendSummaryUiState(
    val summary: FriendPlaceSummary? = null,
    val isLoading: Boolean = false,
    val errorMessage: Int? = null,
)

data class PlaceDetailUiState(
    val place: Place? = null,
    val isSaved: Boolean = false,
    val visits: List<Visit> = emptyList(),
    val pendingVisits: List<PendingVisit> = emptyList(),
    val collections: List<Collection> = emptyList(),
    val membershipBusyIds: Set<String> = emptySet(),
    val currentUserId: String,
    val currentUserAvatarUrl: String,
    val isLoading: Boolean = true,
    val errorMessage: Int? = null,
    val isNotFound: Boolean = false,
    val saveErrorMessage: Int? = null,
    val membershipErrorMessage: Int? = null,
    val isCreatingCollection: Boolean = false,
    val createCollectionError: Int? = null,
    val shareText: String? = null,
    val activeReviewScope: ActivityScope = ActivityScope.COMMUNITY,
    val communityReviews: CommunityReviewsUiState = CommunityReviewsUiState(),
    val friendReviews: CommunityReviewsUiState = CommunityReviewsUiState(),
    val friendSummary: FriendSummaryUiState = FriendSummaryUiState(),
    val hasUnfinishedDraft: Boolean = false,
) {
    val activeReviews: CommunityReviewsUiState
        get() = if (activeReviewScope == ActivityScope.FRIENDS) friendReviews else communityReviews
}

@HiltViewModel
class PlaceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TravelRepository,
    private val draftRepository: VisitDraftRepository,
    private val offlineMutations: OfflineMutationRepository = NoOpOfflineMutationRepository,
) : ViewModel() {
    private val placeId: String = checkNotNull(savedStateHandle["placeId"])
    private val place = MutableStateFlow<Place?>(null)
    private val status = MutableStateFlow(DetailStatus())
    private val communityReviews = MutableStateFlow(CommunityReviewsUiState())
    private val friendReviews = MutableStateFlow(CommunityReviewsUiState())
    private val friendSummary = MutableStateFlow(FriendSummaryUiState())
    private val activeReviewScope = MutableStateFlow(ActivityScope.COMMUNITY)
    private var friendReviewsRequested = false

    val uiState = combine(
        combine(
            place,
            repository.observeSavedPlaceIds(),
            repository.observeVisits(),
        ) { current, saved, visits ->
            Triple(current, saved, visits)
        },
        combine(
            repository.observeCollections(),
            status,
            communityReviews,
        ) { collections, detailStatus, reviewsState ->
            Triple(collections, detailStatus, reviewsState)
        },
        combine(
            friendReviews,
            friendSummary,
            activeReviewScope,
        ) { friendReviewsState, friendSummaryState, reviewScope ->
            Triple(friendReviewsState, friendSummaryState, reviewScope)
        },
        draftRepository.observeHasDraft(placeId),
        offlineMutations.observePendingVisits(),
    ) { (current, saved, visits), (collections, detailStatus, reviewsState), (friendReviewsState, friendSummaryState, reviewScope), hasDraft, pending ->
        PlaceDetailUiState(
            place = current,
            isSaved = placeId in saved,
            visits = visits.filter { it.placeId == placeId }.sortedByDescending { it.visitedAt },
            pendingVisits = pending.filter { it.visit.placeId == placeId }.sortedByDescending { it.visit.visitedAt },
            collections = collections,
            membershipBusyIds = detailStatus.membershipBusyIds,
            currentUserId = repository.currentUser.id,
            currentUserAvatarUrl = repository.currentUser.avatarUrl,
            isLoading = detailStatus.isLoading,
            errorMessage = detailStatus.errorMessage,
            isNotFound = detailStatus.isNotFound,
            saveErrorMessage = detailStatus.saveErrorMessage,
            membershipErrorMessage = detailStatus.membershipErrorMessage,
            isCreatingCollection = detailStatus.isCreatingCollection,
            createCollectionError = detailStatus.createCollectionError,
            shareText = detailStatus.shareText,
            activeReviewScope = reviewScope,
            communityReviews = reviewsState,
            friendReviews = friendReviewsState,
            friendSummary = friendSummaryState,
            hasUnfinishedDraft = hasDraft,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PlaceDetailUiState(
            currentUserId = repository.currentUser.id,
            currentUserAvatarUrl = repository.currentUser.avatarUrl,
        ),
    )

    init {
        load()
        loadCommunityReviews()
        loadFriendSummary()
        viewModelScope.launch { repository.refreshCollections() }
        viewModelScope.launch { repository.refreshOwnerVisits() }
    }

    fun retry() = load()

    fun retryMutation(mutationId: String) {
        viewModelScope.launch { offlineMutations.retry(mutationId) }
    }

    fun refreshCommunityReviews() = loadCommunityReviews(force = true)

    fun retryCommunityReviews() = loadCommunityReviews(force = true)

    fun selectReviewScope(scope: ActivityScope) {
        if (activeReviewScope.value == scope) return
        activeReviewScope.value = scope
        if (scope == ActivityScope.FRIENDS && !friendReviewsRequested) {
            friendReviewsRequested = true
            loadFriendReviews(force = true)
        }
    }

    fun refreshActiveReviews() {
        when (activeReviewScope.value) {
            ActivityScope.COMMUNITY -> loadCommunityReviews(force = true)
            ActivityScope.FRIENDS -> loadFriendReviews(force = true)
        }
    }

    fun retryActiveReviews() = refreshActiveReviews()

    fun refreshFriendSummary() = loadFriendSummary(force = true)

    fun retryFriendSummary() = loadFriendSummary(force = true)

    fun toggleReviewExpanded(reviewId: String) {
        val scope = activeReviewScope.value
        val target = if (scope == ActivityScope.FRIENDS) friendReviews else communityReviews
        target.update { state ->
            val expanded = reviewId in state.expandedReviewIds
            state.copy(
                expandedReviewIds = if (expanded) state.expandedReviewIds - reviewId else state.expandedReviewIds + reviewId,
            )
        }
    }

    fun toggleSaved() {
        viewModelScope.launch {
            status.update { it.copy(saveErrorMessage = null) }
            when (val result = repository.toggleSaved(placeId)) {
                is RepositoryResult.Success -> Unit
                is RepositoryResult.Failure -> status.update {
                    it.copy(saveErrorMessage = result.error.toUserMessageRes())
                }
            }
        }
    }

    fun toggleCollectionMembership(collectionId: String) {
        viewModelScope.launch {
            val collection = uiState.value.collections.firstOrNull { it.id == collectionId }
                ?: repository.observeCollections().first().firstOrNull { it.id == collectionId }
                ?: return@launch
            val removing = placeId in collection.placeIds
            status.update {
                it.copy(
                    membershipBusyIds = it.membershipBusyIds + collectionId,
                    membershipErrorMessage = null,
                )
            }
            val result = if (removing) {
                repository.removePlaceFromCollection(collectionId, placeId)
            } else {
                repository.addPlaceToCollection(collectionId, placeId)
            }
            when (result) {
                is RepositoryResult.Success -> {
                    if (removing) {
                        repository.refreshCollectionDetail(collectionId)
                    }
                }
                is RepositoryResult.Failure -> {
                    if (!removing && result.error is TravelError.Conflict) {
                        repository.refreshCollectionDetail(collectionId)
                    } else {
                        status.update {
                            it.copy(membershipErrorMessage = result.error.toUserMessageRes())
                        }
                    }
                }
            }
            status.update {
                it.copy(membershipBusyIds = it.membershipBusyIds - collectionId)
            }
        }
    }

    fun createCollection(
        title: String,
        description: String,
        visibility: Visibility,
        autoSelect: Boolean = true,
    ) {
        val trimmed = title.trim()
        if (trimmed.isBlank() || status.value.isCreatingCollection) return
        status.update {
            it.copy(isCreatingCollection = true, createCollectionError = null)
        }
        viewModelScope.launch {
            val cover = place.value?.coverImage?.takeIf { it.isNotBlank() }
                ?: DEFAULT_COLLECTION_COVER
            val draft = Collection(
                id = UUID.randomUUID().toString(),
                userId = repository.currentUser.id,
                title = trimmed,
                description = description.trim(),
                placeIds = emptyList(),
                visibility = visibility,
                coverImage = cover,
            )
            when (val result = repository.saveCollection(draft)) {
                is RepositoryResult.Success -> {
                    if (autoSelect) {
                        when (val add = repository.addPlaceToCollection(result.value.id, placeId)) {
                            is RepositoryResult.Success -> Unit
                            is RepositoryResult.Failure -> {
                                if (add.error is TravelError.Conflict) {
                                    repository.refreshCollectionDetail(result.value.id)
                                } else {
                                    status.update {
                                        it.copy(
                                            isCreatingCollection = false,
                                            createCollectionError = add.error.toUserMessageRes(),
                                        )
                                    }
                                    return@launch
                                }
                            }
                        }
                    }
                    status.update {
                        it.copy(isCreatingCollection = false, createCollectionError = null)
                    }
                }
                is RepositoryResult.Failure -> status.update {
                    it.copy(
                        isCreatingCollection = false,
                        createCollectionError = result.error.toUserMessageRes(),
                    )
                }
            }
        }
    }

    fun clearCreateCollectionError() {
        status.update { it.copy(createCollectionError = null) }
    }

    fun prepareShare(resources: android.content.res.Resources) {
        val current = place.value ?: return
        status.update { it.copy(shareText = PhokartaShare.placeText(resources, current)) }
    }

    fun consumeShareText(): String? {
        val text = status.value.shareText
        status.update { it.copy(shareText = null) }
        return text
    }

    private fun load() {
        viewModelScope.launch {
            status.update {
                it.copy(isLoading = true, errorMessage = null, isNotFound = false)
            }
            when (val result = repository.refreshPlaceDetail(placeId)) {
                is RepositoryResult.Success -> {
                    place.value = result.value
                    status.update { it.copy(isLoading = false) }
                }
                is RepositoryResult.Failure -> {
                    place.value = repository.observePlaces().first().firstOrNull { it.id == placeId }
                    status.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.error.toUserMessageRes(),
                            isNotFound = result.error is TravelError.NotFound && place.value == null,
                        )
                    }
                }
            }
        }
    }

    private fun loadCommunityReviews(force: Boolean = false) {
        if (!force && communityReviews.value.isLoading) return
        viewModelScope.launch {
            communityReviews.update { it.copy(isLoading = true, errorMessage = null) }
            when (
                val result = repository.refreshPublicReviews(
                    placeId,
                    scope = ActivityScope.COMMUNITY,
                    page = 0,
                    size = PREVIEW_PAGE_SIZE,
                )
            ) {
                is RepositoryResult.Success -> communityReviews.update {
                    it.copy(
                        reviews = result.value.reviews,
                        totalElements = result.value.totalElements,
                        hasNext = result.value.hasNext,
                        isLoading = false,
                        errorMessage = null,
                        expandedReviewIds = emptySet(),
                    )
                }
                is RepositoryResult.Failure -> communityReviews.update {
                    it.copy(isLoading = false, errorMessage = result.error.toUserMessageRes())
                }
            }
        }
    }

    private fun loadFriendReviews(force: Boolean = false) {
        if (!force && friendReviews.value.isLoading) return
        viewModelScope.launch {
            friendReviews.update { it.copy(isLoading = true, errorMessage = null) }
            when (
                val result = repository.refreshPublicReviews(
                    placeId,
                    scope = ActivityScope.FRIENDS,
                    page = 0,
                    size = PREVIEW_PAGE_SIZE,
                )
            ) {
                is RepositoryResult.Success -> friendReviews.update {
                    it.copy(
                        reviews = result.value.reviews,
                        totalElements = result.value.totalElements,
                        hasNext = result.value.hasNext,
                        isLoading = false,
                        errorMessage = null,
                        expandedReviewIds = emptySet(),
                    )
                }
                is RepositoryResult.Failure -> friendReviews.update {
                    it.copy(isLoading = false, errorMessage = result.error.toUserMessageRes())
                }
            }
        }
    }

    private fun loadFriendSummary(force: Boolean = false) {
        if (!force && friendSummary.value.isLoading) return
        viewModelScope.launch {
            friendSummary.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = repository.loadFriendPlaceSummary(placeId)) {
                is RepositoryResult.Success -> friendSummary.update {
                    FriendSummaryUiState(summary = result.value, isLoading = false)
                }
                is RepositoryResult.Failure -> friendSummary.update {
                    it.copy(isLoading = false, errorMessage = result.error.toUserMessageRes())
                }
            }
        }
    }

    private data class DetailStatus(
        val isLoading: Boolean = true,
        val errorMessage: Int? = null,
        val isNotFound: Boolean = false,
        val saveErrorMessage: Int? = null,
        val membershipBusyIds: Set<String> = emptySet(),
        val membershipErrorMessage: Int? = null,
        val isCreatingCollection: Boolean = false,
        val createCollectionError: Int? = null,
        val shareText: String? = null,
    )

    companion object {
        private const val DEFAULT_COLLECTION_COVER =
            "https://images.unsplash.com/photo-1488646953014-85cb44e25828?w=1200"
        const val PREVIEW_PAGE_SIZE = 3
    }
}
