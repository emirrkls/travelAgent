package com.emirrkls.phokarta.feature.rating

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import java.io.File
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.emirrkls.phokarta.ui.localization.formatLongDateLocalized
import com.emirrkls.phokarta.ui.localization.formatScoreLocalized
import com.emirrkls.phokarta.ui.localization.labelRes
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.ui.components.RatingControl
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.emirrkls.phokarta.feature.policy.PolicyAcceptanceSheet
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.CompanionCode
import com.emirrkls.phokarta.core.model.DimensionStateCode
import com.emirrkls.phokarta.core.model.ExperienceTitleSource
import com.emirrkls.phokarta.core.model.OverallFeelingCode
import com.emirrkls.phokarta.core.model.PracticalSignalCode
import com.emirrkls.phokarta.core.model.PrimaryExperienceCode
import com.emirrkls.phokarta.core.model.TimeOfDayCode
import com.emirrkls.phokarta.core.model.VibeCode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingScreen(onBack: () -> Unit, onPublished: () -> Unit, viewModel: RatingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDatePicker by remember { mutableStateOf(false) }
    var showVisibilitySheet by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(VisitDraftLogic.V2_MAX_MEDIA),
    ) { uris -> viewModel.addPhotos(uris) }
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.addPhotos(uris) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.flushDraft()
    }
    LaunchedEffect(state.published) {
        if (state.published) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onPublished()
        }
    }
    LaunchedEffect(state.queuedForSync) {
        if (state.queuedForSync) {
            snackbarHostState.showSnackbar(context.getString(R.string.visit_saved_for_sync))
            onBack()
        }
    }
    LaunchedEffect(state.discarded) {
        if (state.discarded) onBack()
    }
    LaunchedEffect(state.showDraftRestoredMessage) {
        if (state.showDraftRestoredMessage) {
            snackbarHostState.showSnackbar(context.getString(R.string.draft_restored))
            viewModel.consumeDraftRestoredMessage()
        }
    }
    LaunchedEffect(state.photoError) {
        state.photoError?.let {
            snackbarHostState.showSnackbar(context.getString(it))
            viewModel.consumePhotoError()
        }
    }

    val place = state.place
    if (place == null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (state.isLoading || state.isDraftInitializing) {
                CircularProgressIndicator()
            } else {
                Text(if (state.isNotFound) stringResource(R.string.place_not_found) else state.loadError?.let { stringResource(it) }.orEmpty(), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = viewModel::retryLoad) { Text(stringResource(R.string.action_retry)) }
                Button(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            }
        }
        return
    }

    if (showVisibilitySheet) {
        VisitVisibilitySheet(
            selectedVisibility = state.visibility,
            onSelect = { visibility ->
                viewModel.setVisibility(visibility)
                showVisibilitySheet = false
            },
            onDismiss = { showVisibilitySheet = false },
        )
    }
    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.discard_draft_title)) },
            text = { Text(stringResource(R.string.discard_draft_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        viewModel.discardDraft()
                    },
                ) { Text(stringResource(R.string.action_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    PolicyAcceptanceSheet(
        state = state.policy,
        onCheckedChange = viewModel::setPolicyChecked,
        onAccept = viewModel::acceptPolicy,
        onDismiss = viewModel::dismissPolicy,
    )
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
                ) { Text(stringResource(R.string.set_date)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    val draftEditsEnabled = !state.isDraftInitializing && !state.isPublishing
    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                            Text(stringResource(R.string.publish_visit))
                        }
                    }
                    state.publishError?.let { message ->
                        Text(stringResource(message), Modifier.fillMaxWidth().padding(top = 7.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    state.dateError?.let { message ->
                        Text(stringResource(message), Modifier.fillMaxWidth().padding(top = 7.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
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
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                Text(
                    if (state.hasExistingVisits) stringResource(R.string.rate_another_visit) else stringResource(R.string.record_a_visit),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (state.canDiscard) {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.semantics {
                            contentDescription = context.getString(R.string.a11y_draft_menu)
                        },
                    ) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.discard_draft)) },
                            onClick = {
                                menuExpanded = false
                                showDiscardConfirm = true
                            },
                            modifier = Modifier.semantics {
                                contentDescription = context.getString(R.string.a11y_discard_draft)
                            },
                        )
                    }
                }
            }
            if (state.hasExistingVisits) {
                Text(
                    stringResource(
                        R.string.visited_before_hint,
                        place.name,
                        pluralStringResource(R.plurals.visits_count, state.existingVisitCount, state.existingVisitCount),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
            }
            val reviewInputA11y = stringResource(R.string.a11y_review_input)
            val privateMemoryInputA11y = stringResource(R.string.a11y_private_memory_input)
            Text(stringResource(R.string.experience_at_place, place.name), style = MaterialTheme.typography.headlineLarge)
            Text(
                stringResource(R.string.experience_intro_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.primary_experience_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(
                    PrimaryExperienceCode.entries.filterNot {
                        it == PrimaryExperienceCode.UNKNOWN || it == PrimaryExperienceCode.UNKNOWN_LEGACY
                    },
                    key = { it.name },
                ) { code ->
                    FilterChip(
                        selected = state.draft.primaryExperience == code,
                        onClick = { if (draftEditsEnabled) viewModel.setPrimaryExperience(code) },
                        label = { Text(code.displayLabel()) },
                        enabled = draftEditsEnabled,
                    )
                }
            }
            if (state.draft.primaryExperience == PrimaryExperienceCode.OTHER) {
                OutlinedTextField(
                    value = state.draft.rawExperienceLabel.orEmpty(),
                    onValueChange = viewModel::setRawExperienceLabel,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = draftEditsEnabled,
                    label = { Text(stringResource(R.string.other_experience_label)) },
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.overall_feeling_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(OverallFeelingCode.entries.filterNot { it == OverallFeelingCode.UNKNOWN }, key = { it.name }) { code ->
                    FilterChip(
                        selected = state.draft.overallFeeling == code,
                        onClick = { if (draftEditsEnabled) viewModel.setOverallFeeling(code) },
                        label = { Text(code.displayLabel()) },
                        enabled = draftEditsEnabled,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.experience_context_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.companion_title), style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CompanionCode.entries.filterNot { it == CompanionCode.UNKNOWN }, key = { it.name }) { code ->
                    FilterChip(
                        selected = state.draft.companion == code,
                        onClick = { viewModel.setCompanion(if (state.draft.companion == code) null else code) },
                        label = { Text(code.displayLabel()) },
                        enabled = draftEditsEnabled,
                    )
                }
            }
            Text(stringResource(R.string.time_of_day_title), style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TimeOfDayCode.entries.filterNot { it == TimeOfDayCode.UNKNOWN }, key = { it.name }) { code ->
                    FilterChip(
                        selected = state.draft.timeOfDay == code,
                        onClick = { viewModel.setTimeOfDay(if (state.draft.timeOfDay == code) null else code) },
                        label = { Text(code.displayLabel()) },
                        enabled = draftEditsEnabled,
                    )
                }
            }
            Text(stringResource(R.string.vibes_title), style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(VibeCode.entries.filterNot { it == VibeCode.UNKNOWN }, key = { it.name }) { code ->
                    FilterChip(
                        selected = code in state.draft.vibes,
                        onClick = { viewModel.toggleVibe(code) },
                        label = { Text(code.displayLabel()) },
                        enabled = draftEditsEnabled && (code in state.draft.vibes || state.draft.vibes.size < 2),
                    )
                }
            }
            Text(stringResource(R.string.practical_signals_title), style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PracticalSignalCode.entries.filterNot { it == PracticalSignalCode.UNKNOWN }, key = { it.name }) { code ->
                    FilterChip(
                        selected = code in state.draft.practicalSignals,
                        onClick = { viewModel.togglePracticalSignal(code) },
                        label = { Text(code.displayLabel()) },
                        enabled = draftEditsEnabled,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Surface(
                Modifier.fillMaxWidth().clickable(enabled = draftEditsEnabled, onClick = viewModel::toggleDimensionsExpanded),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.experience_dimensions_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.optional), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = if (state.dimensionsExpanded) {
                            stringResource(R.string.a11y_collapse_details)
                        } else {
                            stringResource(R.string.a11y_expand_details)
                        },
                    )
                }
            }
            AnimatedVisibility(state.dimensionsExpanded) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    ExperienceDimensionCatalog.keysFor(state.draft.primaryExperience).forEach { dimensionKey ->
                        Text(dimensionKey.displayLabel(), fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(DimensionStateCode.entries.filterNot { it == DimensionStateCode.UNKNOWN }, key = { it.name }) { code ->
                                FilterChip(
                                    selected = state.draft.semanticDimensions[dimensionKey] == code,
                                    onClick = {
                                        viewModel.setSemanticDimension(
                                            dimensionKey,
                                            if (state.draft.semanticDimensions[dimensionKey] == code) null else code,
                                        )
                                    },
                                    label = { Text(code.displayLabel()) },
                                    enabled = draftEditsEnabled,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            OutlinedTextField(
                value = state.draft.title.orEmpty(),
                onValueChange = viewModel::setTitle,
                modifier = Modifier.fillMaxWidth(),
                enabled = draftEditsEnabled,
                label = { Text(stringResource(R.string.experience_title_label)) },
                supportingText = {
                    Text(if (state.draft.titleSource == ExperienceTitleSource.GENERATED) {
                        stringResource(R.string.experience_title_generated)
                    } else {
                        stringResource(R.string.experience_title_custom)
                    })
                },
            )
            if (state.draft.titleSource == ExperienceTitleSource.CUSTOM) {
                TextButton(onClick = viewModel::useGeneratedTitle, enabled = draftEditsEnabled) {
                    Text(stringResource(R.string.use_generated_title))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.experience_story_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                stringResource(VisitVisibilityCopy.reviewHelperRes(state.visibility)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(9.dp))
            OutlinedTextField(
                state.review,
                { if (draftEditsEnabled) viewModel.setReview(it) },
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = reviewInputA11y },
                enabled = draftEditsEnabled,
                label = { Text(stringResource(R.string.experience_story_title)) },
                placeholder = { Text(stringResource(R.string.review_placeholder)) },
                minLines = 3,
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                state.tip,
                { if (draftEditsEnabled) viewModel.setTip(it) },
                Modifier.fillMaxWidth(),
                enabled = draftEditsEnabled,
                label = { Text(stringResource(R.string.experience_tip_title)) },
                minLines = 2,
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.private_memory), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.only_you_can_see_this), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(9.dp))
            OutlinedTextField(
                state.note,
                { if (draftEditsEnabled) viewModel.setNote(it) },
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = privateMemoryInputA11y },
                enabled = draftEditsEnabled,
                label = { Text(stringResource(R.string.private_memory)) },
                placeholder = { Text(stringResource(R.string.private_memory_placeholder)) },
                leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                minLines = 2,
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.visit_photos_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.visit_photos_hint, state.draft.photos.size, VisitDraftLogic.V2_MAX_MEDIA),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(9.dp))
            if (state.draft.photos.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.draft.photos, key = { it }) { relativePath ->
                        Box(Modifier.size(84.dp)) {
                            AsyncImage(
                                model = if (relativePath.startsWith("https://")) {
                                    relativePath
                                } else {
                                    File(context.filesDir, relativePath)
                                },
                                contentDescription = stringResource(R.string.visit_photo_preview),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            IconButton(
                                onClick = { viewModel.removePhoto(relativePath) },
                                enabled = draftEditsEnabled,
                                modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
                            ) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.remove_photo))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(
                onClick = {
                    try {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } catch (_: ActivityNotFoundException) {
                        documentPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                    }
                },
                enabled = draftEditsEnabled && state.draft.photos.size < VisitDraftLogic.V2_MAX_MEDIA,
            ) {
                Icon(Icons.Rounded.Add, null)
                Text(stringResource(R.string.add_photos), Modifier.padding(start = 6.dp))
            }
            Spacer(Modifier.height(18.dp))
            Surface(
                Modifier.fillMaxWidth().clickable(enabled = draftEditsEnabled) { showDatePicker = true },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CalendarMonth, null)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(stringResource(R.string.visit_date), style = MaterialTheme.typography.labelLarge)
                        Text(
                            formatLongDateLocalized(state.visitedAt),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.visitedAt != LocalDate.now()) {
                        TextButton(onClick = viewModel::resetVisitedAtToToday, enabled = draftEditsEnabled) {
                            Text(stringResource(R.string.today))
                        }
                    } else {
                        Text(stringResource(R.string.today), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            VisitVisibilityRow(
                visibility = state.visibility,
                onClick = { if (draftEditsEnabled) showVisibilitySheet = true },
            )
            Spacer(Modifier.height(26.dp))
        }
    }
}

private fun Enum<*>.displayLabel(): String = name.lowercase()
    .split('_')
    .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }

private fun String.displayLabel(): String = lowercase()
    .split('_')
    .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }

