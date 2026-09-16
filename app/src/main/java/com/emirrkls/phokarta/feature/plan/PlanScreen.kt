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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PlanScreen(
    onPlace: (String) -> Unit,
    onCollection: (String) -> Unit,
) {
    var selected by remember { mutableIntStateOf(0) }
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
                WantToGoScreen(onBack = {}, onPlace = onPlace, showBack = false)
            } else {
                CollectionsScreen(onBack = {}, onCollection = onCollection, showBack = false)
            }
        }
    }
}
