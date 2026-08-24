package com.emirrkls.phokarta.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.ui.theme.Coral
import com.emirrkls.phokarta.ui.theme.TravelSpacing
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) { delay(1_100); onFinished() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(90.dp).clip(RoundedCornerShape(28.dp)).background(Coral), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Explore, null, Modifier.size(52.dp), tint = Color.White)
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.splash_tagline), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private data class OnboardingPage(
    val icon: ImageVector,
    @StringRes val eyebrow: Int,
    @StringRes val title: Int,
    @StringRes val description: Int,
)

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val pages = listOf(
        OnboardingPage(
            Icons.Rounded.Explore,
            R.string.onboarding_discover_eyebrow,
            R.string.onboarding_discover_title,
            R.string.onboarding_discover_body,
        ),
        OnboardingPage(
            Icons.Rounded.BookmarkAdded,
            R.string.onboarding_remember_eyebrow,
            R.string.onboarding_remember_title,
            R.string.onboarding_remember_body,
        ),
        OnboardingPage(
            Icons.Rounded.Favorite,
            R.string.onboarding_rate_eyebrow,
            R.string.onboarding_rate_title,
            R.string.onboarding_rate_body,
        ),
        OnboardingPage(
            Icons.Rounded.AutoAwesome,
            R.string.onboarding_together_eyebrow,
            R.string.onboarding_together_title,
            R.string.onboarding_together_body,
        ),
    )
    var pageIndex by remember { mutableIntStateOf(0) }
    val page = pages[pageIndex]

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = TravelSpacing.lg, vertical = TravelSpacing.lg)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onComplete) { Text(stringResource(R.string.onboarding_skip)) }
        }
        Spacer(Modifier.weight(.4f))
        Box(Modifier.size(128.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(40.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(page.icon, null, Modifier.size(68.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(42.dp))
        Text(stringResource(page.eyebrow), color = Coral, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(page.title), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Text(stringResource(page.description), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pages.indices.forEach { index ->
                Box(Modifier.size(if (index == pageIndex) 22.dp else 8.dp, 8.dp).clip(CircleShape).background(if (index == pageIndex) Coral else MaterialTheme.colorScheme.outline))
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { if (pageIndex == pages.lastIndex) onComplete() else pageIndex++ },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground),
        ) {
            Text(
                stringResource(
                    if (pageIndex == pages.lastIndex) R.string.onboarding_get_started else R.string.onboarding_continue,
                ),
            )
        }
    }
}
