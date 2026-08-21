package com.emirrkls.travelagent.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Park
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Sailing
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.emirrkls.travelagent.core.model.PlaceCategory

val PlaceCategory.vectorIcon: ImageVector
    get() = when (this) {
        PlaceCategory.BEACH -> Icons.Rounded.BeachAccess
        PlaceCategory.RESTAURANT -> Icons.Rounded.Restaurant
        PlaceCategory.CAFE -> Icons.Rounded.LocalCafe
        PlaceCategory.HOTEL -> Icons.Rounded.Hotel
        PlaceCategory.BAR -> Icons.Rounded.LocalBar
        PlaceCategory.NIGHTLIFE -> Icons.Rounded.MusicNote
        PlaceCategory.ATTRACTION -> Icons.Rounded.AccountBalance
        PlaceCategory.ACTIVITY -> Icons.Rounded.Sailing
        PlaceCategory.NATURE -> Icons.Rounded.Park
    }

@Composable
fun CategoryIcon(
    category: PlaceCategory,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    tint: Color? = null,
) {
    Icon(
        imageVector = category.vectorIcon,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint ?: LocalContentColor.current,
    )
}
