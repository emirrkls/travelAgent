package com.emirrkls.phokarta.feature.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.model.SocialListKind
import com.emirrkls.phokarta.ui.components.TravelImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicProfileScreen(
    onBack: () -> Unit,
    onSocialList: (SocialListKind) -> Unit,
    viewModel: PublicProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.profile?.displayName ?: stringResource(R.string.profile),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading && state.profile == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            state.notFound -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.user_not_found))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onBack) { Text(stringResource(R.string.action_go_back)) }
                }
            }
            state.profile == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.errorMessage?.let { stringResource(it) } ?: stringResource(R.string.error_generic))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = viewModel::refresh) { Text(stringResource(R.string.action_retry)) }
                }
            }
            else -> {
                val profile = state.profile!!
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TravelImage(profile.avatarUrl, profile.displayName, Modifier.size(84.dp).clip(CircleShape))
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(profile.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("@${profile.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (profile.bio.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Text(profile.bio, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(18.dp))
                    if (state.isOwnProfile) {
                        SocialCountersRow(
                            followerCount = profile.followerCount,
                            followingCount = profile.followingCount,
                            friendCount = profile.friendCount,
                            onFollowers = { onSocialList(SocialListKind.FOLLOWERS) },
                            onFollowing = { onSocialList(SocialListKind.FOLLOWING) },
                            onFriends = { onSocialList(SocialListKind.FRIENDS) },
                        )
                    } else {
                        SocialCountersRow(
                            followerCount = profile.followerCount,
                            followingCount = profile.followingCount,
                            friendCount = profile.friendCount,
                            onFollowers = {},
                            onFollowing = {},
                            onFriends = {},
                        )
                    }
                    if (!state.isOwnProfile) {
                        Spacer(Modifier.height(16.dp))
                        FollowsYouHint(profile.relationship)
                        FollowActionButton(
                            relationship = profile.relationship,
                            enabled = !state.isMutating,
                            onClick = viewModel::toggleFollow,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        state.actionErrorMessage?.let { msg ->
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(msg), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (profile.cityCount > 0 || profile.countryCount > 0) {
                        Spacer(Modifier.height(24.dp))
                        Text(stringResource(R.string.travel_summary), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.countries_cities_summary, profile.countryCount, profile.cityCount),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
