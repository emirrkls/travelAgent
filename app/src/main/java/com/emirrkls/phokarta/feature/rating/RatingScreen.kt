package com.emirrkls.phokarta.feature.rating

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.ui.components.RatingControl
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingScreen(onBack: () -> Unit, onPublished: () -> Unit, viewModel: RatingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.published) {
        if (state.published) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onPublished()
        }
    }
    val place = state.place
    if (place == null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (state.isLoading) {
                CircularProgressIndicator()
            } else {
                Text(if (state.isNotFound) "Place not found" else state.loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = viewModel::retryLoad) { Text("Retry") }
                Button(onClick = onBack) { Text("Back") }
            }
        }
        return
    }

    var showDatePicker by remember { mutableStateOf(false) }
    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.visitedAt.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                            viewModel.setVisitedAt(date)
                        }
                        showDatePicker = false
                    },
                ) { Text("Set date") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = {
            Surface(shadowElevation = 10.dp) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Button(
                        onClick = viewModel::publish,
                        enabled = state.canPublish,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        if (state.isPublishing) {
                            CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Publish visit")
                        }
                    }
                    state.publishError?.let { message ->
                        Text(message, Modifier.fillMaxWidth().padding(top = 7.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    state.dateError?.let { message ->
                        Text(message, Modifier.fillMaxWidth().padding(top = 7.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text(
                    if (state.hasExistingVisits) "Rate another visit" else "Record a visit",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            if (state.hasExistingVisits) {
                Text(
                    "You've visited ${place.name} ${state.existingVisitCount} ${if (state.existingVisitCount == 1) "time" else "times"}. Each visit stays in your history.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
            }
            Text("How was ${place.name}?", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Start with one score. Add detail only if it helps tell the story.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(26.dp))
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(26.dp)) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val animatedScore by animateFloatAsState(state.overall, label = "overallScore")
                    Text(
                        String.format("%.1f", animatedScore),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { contentDescription = "Overall score ${String.format("%.1f", animatedScore)}" },
                    )
                    AnimatedContent(VisitDraftLogic.scoreLabel(state.overall), label = "scoreLabel") { label ->
                        Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    RatingControl(
                        value = state.overall,
                        onValueChange = viewModel::setOverall,
                        onValueChangeFinished = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                        onThresholdCrossed = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Terrible", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Exceptional", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Surface(
                Modifier.fillMaxWidth().clickable(onClick = viewModel::toggleDimensionsExpanded),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Rate the details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Optional", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = if (state.dimensionsExpanded) "Collapse details" else "Expand details",
                    )
                }
            }
            AnimatedVisibility(state.dimensionsExpanded) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    place.category.ratingDimensions.forEach { dimension ->
                        val value = state.dimensions[dimension]
                        if (value == null) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { viewModel.enableDimension(dimension) },
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(dimension.label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                    Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary)
                                    Text("Add score", Modifier.padding(start = 5.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        } else {
                            Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(dimension.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                        Text(String.format("%.1f", value), fontWeight = FontWeight.Bold)
                                        IconButton(onClick = { viewModel.removeDimension(dimension) }) {
                                            Icon(Icons.Rounded.Close, "Remove ${dimension.label} score")
                                        }
                                    }
                                    RatingControl(
                                        value = value,
                                        onValueChange = { viewModel.setDimension(dimension, it) },
                                        compact = true,
                                        onValueChangeFinished = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("Public review", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Visible to others", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(9.dp))
            OutlinedTextField(
                state.review,
                viewModel::setReview,
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Public review input" },
                label = { Text("Public review") },
                placeholder = { Text("What should friends know?") },
                minLines = 3,
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text("Private memory", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Only you can see this", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(9.dp))
            OutlinedTextField(
                state.note,
                viewModel::setNote,
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Private memory input" },
                label = { Text("Private memory") },
                placeholder = { Text("A detail only you need") },
                leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                minLines = 2,
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(18.dp))
            Surface(
                Modifier.fillMaxWidth().clickable { showDatePicker = true },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CalendarMonth, null)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("Visit date", style = MaterialTheme.typography.labelLarge)
                        Text(
                            state.visitedAt.format(DateTimeFormatter.ofPattern("d MMMM yyyy")),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.visitedAt != LocalDate.now()) {
                        TextButton(onClick = viewModel::resetVisitedAtToToday) { Text("Today") }
                    } else {
                        Text("Today", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
        }
    }
}
