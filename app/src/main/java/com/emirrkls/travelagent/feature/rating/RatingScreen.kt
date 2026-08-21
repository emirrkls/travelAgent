package com.emirrkls.travelagent.feature.rating

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.travelagent.ui.components.RatingControl
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun RatingScreen(onBack: () -> Unit, onPublished: (String) -> Unit, viewModel: RatingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.published) { if (state.published) onPublished(state.place?.name.orEmpty()) }
    val place = state.place
    if (place == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val haptics = LocalHapticFeedback.current
    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = {
            Surface(shadowElevation = 10.dp) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Button(
                        onClick = viewModel::publish,
                        enabled = !state.isPublishing,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        if (state.isPublishing) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp) else Text("Publish visit")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Record a visit", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(12.dp))
            Text("How was ${place.name}?", style = MaterialTheme.typography.headlineLarge)
            Text("Start with one score. Add detail only if it helps tell the story.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(26.dp))
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(26.dp)) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val animatedScore by animateFloatAsState(state.overall, label = "overallScore")
                    Text(
                        String.format("%.1f", animatedScore),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    AnimatedContent(scoreLabel(state.overall), label = "scoreLabel") { label ->
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
                        Text("Not for me", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Unforgettable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text("What stood out?", style = MaterialTheme.typography.titleLarge)
            Text("Optional · add only the scores you care about", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            place.category.ratingDimensions.forEach { name ->
                val value = state.dimensions[name]
                if (value == null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { viewModel.enableDimension(name) },
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Add score", Modifier.padding(start = 5.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                } else {
                    Surface(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Text(String.format("%.1f", value), fontWeight = FontWeight.Bold)
                                IconButton(onClick = { viewModel.removeDimension(name) }) { Icon(Icons.Rounded.Close, "Remove $name score") }
                            }
                            RatingControl(
                                value = value,
                                onValueChange = { viewModel.setDimension(name, it) },
                                compact = true,
                                onValueChangeFinished = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("Share with others", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(9.dp))
            OutlinedTextField(
                state.review,
                viewModel::setReview,
                Modifier.fillMaxWidth(),
                label = { Text("Public review") },
                placeholder = { Text("What should friends know?") },
                minLines = 3,
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text("Keep for yourself", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(9.dp))
            OutlinedTextField(
                state.note,
                viewModel::setNote,
                Modifier.fillMaxWidth(),
                label = { Text("Private memory") },
                placeholder = { Text("A detail only you need") },
                leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                minLines = 2,
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(18.dp))
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CalendarMonth, null)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("Visited date", style = MaterialTheme.typography.labelLarge)
                        Text(state.visitedAt.format(DateTimeFormatter.ofPattern("d MMMM yyyy")), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (state.visitedAt == LocalDate.now()) "Today" else "Reset", Modifier.background(MaterialTheme.colorScheme.surface, CircleShape).padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PhotoCamera, null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("Add photos", fontWeight = FontWeight.Medium)
                        Text("Prototype placeholder", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
        }
    }
}

private fun scoreLabel(score: Float) = when {
    score >= 9f -> "Unforgettable"
    score >= 8f -> "Loved it"
    score >= 7f -> "Really good"
    score >= 5f -> "It was okay"
    else -> "Not for me"
}
