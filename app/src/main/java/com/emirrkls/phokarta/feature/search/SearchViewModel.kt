package com.emirrkls.phokarta.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emirrkls.phokarta.core.data.TravelRepository
import com.emirrkls.phokarta.core.model.Place
import com.emirrkls.phokarta.core.model.PlaceCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SearchUiState(val query: String = "", val category: PlaceCategory? = null, val results: List<Place> = emptyList())

@HiltViewModel
class SearchViewModel @Inject constructor(repository: TravelRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<PlaceCategory?>(null)
    val uiState = combine(repository.observePlaces(), query, category) { places, text, selected ->
        val terms = text.trim()
        SearchUiState(text, selected, places.filter { place ->
            val categoryMatches = selected == null || place.category == selected
            val textMatches = terms.isBlank() || listOf(place.name, place.city, place.region, place.country, place.category.label)
                .any { it.contains(terms, ignoreCase = true) }
            categoryMatches && textMatches
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())
    fun setQuery(value: String) { query.value = value }
    fun setCategory(value: PlaceCategory?) { category.value = value }
}
