package com.emirrkls.phokarta.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.ExperienceRepository
import com.emirrkls.phokarta.core.model.PlannedExperienceItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PlanViewModel @Inject constructor(private val experiences: ExperienceRepository) : ViewModel() {
    val planned: StateFlow<List<PlannedExperienceItem>> = experiences.plannedExperiences()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    init { viewModelScope.launch { experiences.refreshMilestoneState() } }
}
