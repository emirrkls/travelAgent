package com.emirrkls.phokarta.feature.explore

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.ExperienceFeedLens
import com.emirrkls.phokarta.ui.components.ExperienceCard
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

@Composable
fun ExploreScreen(
    onExperience: (String) -> Unit,
    onPlace: (String) -> Unit,
    onAuthor: (String) -> Unit,
    onMap: () -> Unit,
    viewModel: ExploreViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) requestApproximateLocation(context, viewModel::setNearbyLocation)
        else viewModel.clearNearbyLocation()
    }
    val requestLocation = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            requestApproximateLocation(context, viewModel::setNearbyLocation)
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.experience_discover_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.experience_discover_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text(stringResource(R.string.experience_search_hint)) },
            )
            Spacer(Modifier.height(7.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ExperienceFeedLens.entries, key = { it.name }) { lens ->
                    val selected = state.lens == lens
                    Surface(
                        onClick = { viewModel.selectLens(lens) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(
                            stringResource(lens.labelRes()),
                            Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ExperienceDiscoveryFilter.entries, key = { it.name }) { filter ->
                    FilterChip(
                        selected = state.discoveryFilter == filter,
                        onClick = { viewModel.selectDiscoveryFilter(filter) },
                        label = { Text(stringResource(filter.labelRes()), style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }

        if (state.needsNearbyLocation) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.experience_nearby_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.experience_nearby_body),
                        Modifier.padding(top = 7.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = requestLocation) {
                            Text(stringResource(R.string.experience_use_approximate_location))
                        }
                        OutlinedButton(onClick = onMap) {
                            Text(stringResource(R.string.experience_open_map))
                        }
                    }
                }
            }
        } else if (state.isLoading) {
            item {
                Row(Modifier.fillMaxWidth().padding(40.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        } else {
            state.errorMessage?.let { message ->
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                        Button(onClick = viewModel::retry, modifier = Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }
            if (state.items.isEmpty() && state.errorMessage == null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(if (state.lens == ExperienceFeedLens.FOLLOWING) R.string.experience_following_empty_title else R.string.experience_empty_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            stringResource(if (state.lens == ExperienceFeedLens.FOLLOWING) R.string.experience_following_empty_body else R.string.experience_empty_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(state.items, key = { it.id }) { experience ->
                ExperienceCard(
                    experience = experience,
                    onOpen = { onExperience(experience.id) },
                    onAuthor = { onAuthor(experience.author.id) },
                    onPlace = { onPlace(experience.place.id) },
                    onRelationship = { viewModel.toggleRelationship(experience.author.id) },
                    relationshipBusy = experience.author.id in state.relationshipInFlight,
                    onPlan = { viewModel.togglePlan(experience.id) },
                    onAcknowledge = { viewModel.acknowledge(experience.id) },
                )
            }
            if (state.hasMore) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Button(onClick = viewModel::loadMore, enabled = !state.isLoadingMore) {
                            if (state.isLoadingMore) CircularProgressIndicator(Modifier.height(20.dp))
                            else Text(stringResource(R.string.experience_load_more))
                        }
                    }
                }
            }
        }
    }
}

private fun ExperienceFeedLens.labelRes(): Int = when (this) {
    ExperienceFeedLens.FOR_YOU -> R.string.experience_lens_for_you
    ExperienceFeedLens.FOLLOWING -> R.string.experience_lens_following
    ExperienceFeedLens.NEARBY -> R.string.experience_lens_nearby
    ExperienceFeedLens.POPULAR -> R.string.experience_lens_popular
}

private fun ExperienceDiscoveryFilter.labelRes(): Int = when (this) {
    ExperienceDiscoveryFilter.CALM -> R.string.experience_filter_calm
    ExperienceDiscoveryFilter.SUNSET -> R.string.experience_filter_sunset
    ExperienceDiscoveryFilter.FOOD -> R.string.experience_filter_food
    ExperienceDiscoveryFilter.SEA -> R.string.experience_filter_sea
    ExperienceDiscoveryFilter.NATURE -> R.string.experience_filter_nature
}

@SuppressLint("MissingPermission")
private fun requestApproximateLocation(context: Context, onFound: (Double, Double) -> Unit) {
    LocationServices.getFusedLocationProviderClient(context)
        .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token)
        .addOnSuccessListener { location ->
            if (location != null) onFound(location.latitude, location.longitude)
        }
}
