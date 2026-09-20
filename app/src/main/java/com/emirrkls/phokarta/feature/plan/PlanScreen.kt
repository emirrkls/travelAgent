package com.emirrkls.phokarta.feature.plan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.feature.saved.WantToGoScreen
import com.emirrkls.phokarta.feature.secondary.CollectionsScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.emirrkls.phokarta.ui.localization.ExperienceLabels
import com.emirrkls.phokarta.ui.localization.appLocale
import com.emirrkls.phokarta.ui.localization.displayLanguage

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PlanScreen(
    onPlace: (String) -> Unit,
    onCollection: (String) -> Unit,
    onExperience: (String) -> Unit,
    viewModel: PlanViewModel = hiltViewModel(),
) {
    var selected by remember { mutableIntStateOf(0) }
    // Preserve the existing Want-to-Go landing behavior while exposing Experiences as a peer tab.
    var wantToGoSelected by remember { mutableIntStateOf(1) }
    val planned by viewModel.planned.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.plan_title),
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
        )
        PrimaryTabRow(selectedTabIndex = selected) {
            Tab(
                selected = selected == 0,
                onClick = { selected = 0 },
                text = { Text(stringResource(R.string.plan_want_to_go)) },
            )
            Tab(
                selected = selected == 1,
                onClick = { selected = 1 },
                text = { Text(stringResource(R.string.plan_collections)) },
            )
        }
        Box(Modifier.weight(1f)) {
            if (selected == 0) {
                Column(Modifier.fillMaxSize()) {
                    PrimaryTabRow(selectedTabIndex = wantToGoSelected) {
                        Tab(selected = wantToGoSelected == 0, onClick = { wantToGoSelected = 0 },
                            text = { Text(stringResource(R.string.plan_experiences)) })
                        Tab(selected = wantToGoSelected == 1, onClick = { wantToGoSelected = 1 },
                            text = { Text(stringResource(R.string.plan_places)) })
                    }
                    if (wantToGoSelected == 0) {
                        if (planned.isEmpty()) {
                            Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.plan_experiences_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                                items(planned, key = { it.experienceId }) { item ->
                                    Surface(
                                        Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                            .clickable { onExperience(item.experienceId) },
                                        shape = MaterialTheme.shapes.large,
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    ) {
                                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            AsyncImage(item.imageUrl, item.title,
                                                Modifier.width(84.dp).height(72.dp), contentScale = ContentScale.Crop)
                                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                                Text(item.placeName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(ExperienceLabels.primary(item.primaryExperience,
                                                    displayLanguage(appLocale())).orEmpty(),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else WantToGoScreen(onBack = {}, onPlace = onPlace, showBack = false)
                }
            } else {
                CollectionsScreen(onBack = {}, onCollection = onCollection, showBack = false)
            }
        }
    }
}
