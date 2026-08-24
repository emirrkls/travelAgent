package com.emirrkls.phokarta.feature.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LocationSearching
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.emirrkls.phokarta.ui.localization.appLocale
import com.emirrkls.phokarta.ui.localization.formatScore
import com.emirrkls.phokarta.ui.localization.formatScoreLocalized
import com.emirrkls.phokarta.ui.localization.labelRes
import com.emirrkls.phokarta.R
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.core.model.MapMarkerLogic
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import com.emirrkls.phokarta.ui.components.CategoryIcon
import com.emirrkls.phokarta.ui.components.FriendsScoreCopy
import com.emirrkls.phokarta.ui.components.RatingBadge
import com.emirrkls.phokarta.ui.components.TravelImage
import com.emirrkls.phokarta.ui.components.vectorIcon
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.compose.ui.res.pluralStringResource
import com.emirrkls.phokarta.ui.presentation.WantToGoCopy

private val DefaultMapCenter = LatLng(37.085, 27.53)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onPlace: (String) -> Unit,
    viewModel: MapViewModel = hiltViewModel(
        viewModelStoreOwner = checkNotNull(LocalActivity.current) as ComponentActivity,
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale = appLocale()
    val notRatedLabel = stringResource(R.string.not_rated)
    val darkTheme = isSystemInDarkTheme()
    val scaffoldState = rememberBottomSheetScaffoldState()
    val initialViewport = state.cameraViewport
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            initialViewport?.let { LatLng(it.centerLatitude, it.centerLongitude) } ?: DefaultMapCenter,
            initialViewport?.zoom ?: 11.2f,
        )
    }
    val coroutineScope = rememberCoroutineScope()
    var categoryMenuOpen by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            requestSingleLocation(context, viewModel)
        } else {
            viewModel.onLocationUnavailable(R.string.map_location_denied)
        }
    }

    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.isMoving }
            .distinctUntilChanged()
            .filter { moving -> !moving }
            .collect {
                cameraPositionState.projection?.visibleRegion?.latLngBounds?.let { bounds ->
                    val camera = cameraPositionState.position
                    viewModel.onCameraIdle(bounds.toViewport(camera.target, camera.zoom))
                }
            }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(400)
        val current = viewModel.uiState.value
        if (current.allPlaces.isEmpty() && current.appliedViewport == null && !current.isLoading) {
            viewModel.bootstrapDefaultAreaIfNeeded(
                MapViewport(
                    north = DefaultMapCenter.latitude + 0.35,
                    east = DefaultMapCenter.longitude + 0.45,
                    south = DefaultMapCenter.latitude - 0.35,
                    west = DefaultMapCenter.longitude - 0.45,
                    centerLatitude = DefaultMapCenter.latitude,
                    centerLongitude = DefaultMapCenter.longitude,
                    zoom = 11.2f,
                ),
            )
        }
    }

    LaunchedEffect(state.cameraRequest?.id) {
        state.cameraRequest?.let { request ->
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(request.latitude, request.longitude), request.zoom),
                650,
            )
            viewModel.consumeCameraRequest(request.id)
        }
    }

    state.locationMessage?.let { message ->
        val text = stringResource(message)
        LaunchedEffect(message) {
            scaffoldState.snackbarHostState.showSnackbar(text)
            viewModel.dismissLocationMessage()
        }
    }
    state.boundsErrorMessage?.let { message ->
        val text = stringResource(message)
            val retryLabel = stringResource(R.string.action_retry)
            LaunchedEffect(message) {
                if (scaffoldState.snackbarHostState.showSnackbar(text, actionLabel = retryLabel) == SnackbarResult.ActionPerformed) {
                    viewModel.retryBounds()
                }
            }
    }
    state.saveErrorMessage?.let { message ->
        val text = stringResource(message)
        LaunchedEffect(message) {
            scaffoldState.snackbarHostState.showSnackbar(text)
            viewModel.dismissSaveError()
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 186.dp,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContent = {
            MapPlaceSheet(
                state = state,
                onSelect = { place ->
                    viewModel.selectPlace(place.id)
                    val position = LatLng(place.latitude, place.longitude)
                    if (cameraPositionState.projection?.visibleRegion?.latLngBounds?.contains(position) == false) {
                        coroutineScope.launch {
                            cameraPositionState.animate(
                                CameraUpdateFactory.newLatLngZoom(position, cameraPositionState.position.zoom),
                                450,
                            )
                        }
                    }
                },
                onOpen = onPlace,
                onSave = viewModel::toggleSaved,
                onRetryFriends = viewModel::retryFriendMetrics,
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                contentDescription = stringResource(R.string.a11y_travel_discovery_map),
                properties = remember(darkTheme) {
                    MapProperties(
                        mapStyleOptions = if (darkTheme) {
                            com.google.android.gms.maps.model.MapStyleOptions.loadRawResourceStyle(
                                context,
                                com.emirrkls.phokarta.R.raw.map_style_dark,
                            )
                        } else null,
                    )
                },
                uiSettings = remember {
                    MapUiSettings(
                        compassEnabled = true,
                        mapToolbarEnabled = false,
                        myLocationButtonEnabled = false,
                        zoomControlsEnabled = false,
                    )
                },
                contentPadding = PaddingValues(bottom = 176.dp),
                onMapClick = { viewModel.clearSelection() },
            ) {
                state.visiblePlaces.forEach { place ->
                    key(place.id) {
                        val selected = state.selectedPlaceId == place.id
                        val saved = place.id in state.savedPlaceIds
                        val visited = place.id in state.visitedPlaceIds
                        val friendsVisited = mapFriendSignal(state.friendMetricsByPlaceId[place.id]).hasSignal
                        val flags = MapMarkerLogic.flags(saved, visited, friendsVisited)
                        MarkerComposable(
                            place.id,
                            selected,
                            saved,
                            visited,
                            friendsVisited,
                            state = remember(place.id) { MarkerState(LatLng(place.latitude, place.longitude)) },
                            contentDescription = MapMarkerLogic.contentDescription(
                                place.name,
                                formatMapScore(place.communityScore, locale, notRatedLabel),
                                flags,
                                context.resources,
                            ),
                            title = place.name,
                            zIndex = 1f,
                            onClick = {
                                viewModel.selectPlace(place.id)
                                true
                            },
                        ) {
                            TravelMapMarker(place, selected, saved, visited, friendsVisited)
                        }
                    }
                }
                state.userLocation?.let { (latitude, longitude) ->
                    MarkerComposable(
                        "user-location",
                        state = remember(latitude, longitude) { MarkerState(LatLng(latitude, longitude)) },
                        contentDescription = stringResource(R.string.a11y_current_location),
                        zIndex = 3f,
                    ) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .background(Color.White, CircleShape)
                                .padding(4.dp)
                                .background(MaterialTheme.colorScheme.secondary, CircleShape),
                        )
                    }
                }
            }

            MapControls(
                filters = state.filters,
                hasUserLocation = state.userLocation != null,
                categoryMenuOpen = categoryMenuOpen,
                onCategoryMenuChange = { categoryMenuOpen = it },
                onCategory = viewModel::selectCategory,
                onHighlyRated = viewModel::toggleHighlyRated,
                onFriendsVisited = viewModel::toggleFriendsVisited,
                onVisited = viewModel::toggleVisited,
                onWantToGo = viewModel::toggleWantToGo,
                onClear = viewModel::clearFilters,
                onLocation = {
                    if (context.hasLocationPermission()) requestSingleLocation(context, viewModel)
                    else locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )

            if (state.showSearchThisArea) {
                val searchAreaA11y = stringResource(R.string.a11y_search_this_map_area)
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 118.dp)
                        .clickable(onClick = viewModel::searchThisArea)
                        .semantics { contentDescription = searchAreaA11y },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shadowElevation = 8.dp,
                ) {
                    Text(
                        stringResource(R.string.map_search_this_area),
                        Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(top = 122.dp, end = 18.dp))
            }
        }
    }
}

@Composable
private fun TravelMapMarker(
    place: Place,
    selected: Boolean,
    saved: Boolean,
    visited: Boolean,
    friendsVisited: Boolean,
) {
    val container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = if (selected) 9.dp else 5.dp,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.surface) else null,
    ) {
        Row(
            Modifier.padding(
                horizontal = if (selected) 11.dp else 8.dp,
                vertical = if (selected) 8.dp else 6.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            CategoryIcon(place.category, size = if (selected) 17.dp else 14.dp, tint = content)
            Text(formatMapScore(place.communityScore, appLocale(), stringResource(R.string.not_rated)), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            if (saved || visited || friendsVisited) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (saved) {
                        Icon(
                            Icons.Outlined.BookmarkBorder,
                            contentDescription = null,
                            modifier = Modifier.size(if (selected) 11.dp else 9.dp),
                            tint = if (selected) content else MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (visited) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(if (selected) 11.dp else 9.dp),
                            tint = if (selected) content else MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (friendsVisited) {
                        Icon(
                            Icons.Rounded.People,
                            contentDescription = null,
                            modifier = Modifier.size(if (selected) 11.dp else 9.dp),
                            tint = if (selected) content else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MapControls(
    filters: MapFilters,
    hasUserLocation: Boolean,
    categoryMenuOpen: Boolean,
    onCategoryMenuChange: (Boolean) -> Unit,
    onCategory: (PlaceCategory?) -> Unit,
    onHighlyRated: () -> Unit,
    onFriendsVisited: () -> Unit,
    onVisited: () -> Unit,
    onWantToGo: () -> Unit,
    onClear: () -> Unit,
    onLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(top = 10.dp, bottom = 9.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.map_discovery), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.map_places_through_people), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (filters.activeCount > 0) {
                    IconButton(onClick = onClear) { Icon(Icons.Rounded.ClearAll, stringResource(R.string.a11y_clear_map_filters)) }
                }
                IconButton(onClick = onLocation) {
                    Icon(
                        Icons.Rounded.LocationSearching,
                        if (hasUserLocation) stringResource(R.string.a11y_center_on_location) else stringResource(R.string.a11y_use_my_location),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    FilterChip(
                        selected = filters.category != null,
                        onClick = { onCategoryMenuChange(true) },
                        label = { Text(filters.category?.let { stringResource(it.labelRes()) } ?: stringResource(R.string.category)) },
                        leadingIcon = filters.category?.let { category ->
                            { CategoryIcon(category, size = 17.dp) }
                        },
                        trailingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp)) },
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = categoryMenuOpen,
                        onDismissRequest = { onCategoryMenuChange(false) },
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.all_categories)) },
                            onClick = { onCategory(null); onCategoryMenuChange(false) },
                        )
                        PlaceCategory.entries.forEach { category ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(category.labelRes())) },
                                leadingIcon = { Icon(category.vectorIcon, null) },
                                onClick = { onCategory(category); onCategoryMenuChange(false) },
                            )
                        }
                    }
                }
                MapFilterChip(stringResource(R.string.search_rated_9_plus), filters.highlyRatedOnly, onHighlyRated)
                MapFilterChip(
                    stringResource(R.string.friends_visited),
                    filters.friendsVisitedOnly,
                    onFriendsVisited,
                    contentDescription = if (filters.friendsVisitedOnly) {
                        stringResource(R.string.a11y_friends_visited_filter_selected)
                    } else {
                        stringResource(R.string.a11y_friends_visited_filter)
                    },
                )
                MapFilterChip(stringResource(R.string.visited), filters.visitedOnly, onVisited)
                MapFilterChip(
                    stringResource(WantToGoCopy.SURFACE),
                    filters.wantToGoOnly,
                    onWantToGo,
                    contentDescription = if (filters.wantToGoOnly) {
                        stringResource(R.string.a11y_want_to_go_filter_selected)
                    } else {
                        stringResource(R.string.a11y_want_to_go_filter)
                    },
                )
            }
        }
    }
}

@Composable
private fun MapFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = if (contentDescription != null) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@Composable
private fun MapPlaceSheet(
    state: MapUiState,
    onSelect: (Place) -> Unit,
    onOpen: (String) -> Unit,
    onSave: (String) -> Unit,
    onRetryFriends: () -> Unit,
) {
    val listState = rememberLazyListState()
    val selectedIndex = state.visiblePlaces.indexOfFirst { it.id == state.selectedPlaceId }
    val friendsFilterPending = state.filters.friendsVisitedOnly &&
        (state.friendMetricsLoading || state.friendMetricsErrorMessage != null)
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
    }
    Column(Modifier.fillMaxWidth().height(470.dp)) {
        Box(
            Modifier
                .padding(top = 10.dp, bottom = 8.dp)
                .size(42.dp, 5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant)
                .align(Alignment.CenterHorizontally),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.places_in_this_area), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (state.filters.activeCount == 0) stringResource(R.string.map_move_or_choose) else pluralStringResource(R.plurals.active_filters_count, state.filters.activeCount, state.filters.activeCount),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("${state.visiblePlaces.size} spots", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        if (state.friendMetricsErrorMessage != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
                Text(
                    if (state.filters.friendsVisitedOnly) {
                        stringResource(R.string.map_friend_visits_load_failed)
                    } else {
                        stringResource(R.string.map_friend_signals_unavailable)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onRetryFriends) { Text(stringResource(R.string.retry_friends)) }
            }
        }
        if (state.visiblePlaces.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    friendsFilterPending && state.friendMetricsLoading -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.loading_friend_visits), style = MaterialTheme.typography.titleMedium)
                    }
                    state.filters.friendsVisitedOnly && state.friendMetricsErrorMessage != null -> {
                        Text(stringResource(R.string.friends_visited_unavailable), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(5.dp))
                        Text(stringResource(R.string.friends_visited_unavailable_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        Text(stringResource(R.string.map_no_places_match), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(5.dp))
                        Text(stringResource(R.string.map_pan_or_adjust), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(state.visiblePlaces, key = { it.id }) { place ->
                    val signal = mapFriendSignal(state.friendMetricsByPlaceId[place.id])
                    MapPlaceRow(
                        place = place,
                        selected = state.selectedPlaceId == place.id,
                        saved = place.id in state.savedPlaceIds,
                        visited = place.id in state.visitedPlaceIds,
                        friendsVisitedCount = signal.friendsVisitedCount,
                        friendAverageScore = signal.friendAverageScore,
                        onClick = {
                            if (state.selectedPlaceId == place.id) onOpen(place.id) else onSelect(place)
                        },
                        onSave = { onSave(place.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MapPlaceRow(
    place: Place,
    selected: Boolean,
    saved: Boolean,
    visited: Boolean,
    friendsVisitedCount: Int,
    friendAverageScore: Double?,
    onClick: () -> Unit,
    onSave: () -> Unit,
) {
    val friendSemantics = FriendsScoreCopy.mapSheetSemantics(friendsVisitedCount, friendAverageScore)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(place.name)
                    if (saved) append(", saved")
                    if (visited) append(", visited")
                    if (friendSemantics != null) {
                        append(", ")
                        append(friendSemantics)
                    }
                }
            },
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TravelImage(place.coverImage, place.name, Modifier.size(76.dp).clip(RoundedCornerShape(15.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(place.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    CategoryIcon(place.category, size = 15.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${stringResource(place.category.labelRes())} · ${place.city}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (visited) {
                    Text(stringResource(R.string.visited), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
                } else if (saved) {
                    Text(stringResource(R.string.saved), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                } else if (selected) {
                    Text(stringResource(R.string.map_tap_again_to_open), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
                if (friendsVisitedCount > 0) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        if (friendAverageScore != null) {
                            Text(
                                stringResource(R.string.friends_score_value, formatScoreLocalized(friendAverageScore)),
                                color = MaterialTheme.colorScheme.tertiary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            FriendsScoreCopy.cardVisitedLabel(friendsVisitedCount),
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            place.communityScore?.let { RatingBadge(it) }
            IconButton(onClick = onSave) {
                Icon(
                    if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    if (saved) stringResource(R.string.a11y_remove_place_want_to_go, place.name) else stringResource(R.string.a11y_save_place_want_to_go, place.name),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun LatLngBounds.toViewport(center: LatLng, zoom: Float) = MapViewport(
    north = northeast.latitude,
    east = northeast.longitude,
    south = southwest.latitude,
    west = southwest.longitude,
    centerLatitude = center.latitude,
    centerLongitude = center.longitude,
    zoom = zoom,
)

private fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private fun requestSingleLocation(context: Context, viewModel: MapViewModel) {
    if (!context.hasLocationPermission()) {
        viewModel.onLocationUnavailable(R.string.map_location_permission_rationale)
        return
    }
    val request = CurrentLocationRequest.Builder()
        .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
        .setMaxUpdateAgeMillis(30_000L)
        .setDurationMillis(12_000L)
        .build()
    val client = LocationServices.getFusedLocationProviderClient(context)
    fun useLastKnownLocation() {
        client.lastLocation
            .addOnSuccessListener { location ->
                val ageMillis = location?.let {
                    (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L
                }
                if (location != null && ageMillis != null && ageMillis <= 120_000L) {
                    viewModel.onLocationFound(location.latitude, location.longitude)
                } else {
                    viewModel.onLocationUnavailable(R.string.map_location_unavailable)
                }
            }
            .addOnFailureListener {
                viewModel.onLocationUnavailable(R.string.map_location_unavailable)
            }
    }
    client
        .getCurrentLocation(request, CancellationTokenSource().token)
        .addOnSuccessListener { location ->
            if (location != null) viewModel.onLocationFound(location.latitude, location.longitude)
            else useLastKnownLocation()
        }
        .addOnFailureListener {
            useLastKnownLocation()
        }
}

private fun formatMapScore(score: Double?, locale: Locale, notRated: String): String =
    score?.let { formatScore(it, locale) } ?: notRated
