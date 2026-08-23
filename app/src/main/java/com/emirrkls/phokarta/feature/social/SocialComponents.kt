package com.emirrkls.phokarta.feature.social

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emirrkls.phokarta.core.model.RelationshipState
import com.emirrkls.phokarta.core.model.UserSummary
import com.emirrkls.phokarta.ui.components.TravelImage
import com.emirrkls.phokarta.ui.theme.Coral

@Composable
fun FollowActionButton(
    relationship: RelationshipState?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val label = when {
        relationship?.isFriend == true -> "Friends"
        relationship?.isFollowing == true -> "Following"
        else -> "Follow"
    }
    val outlined = relationship?.isFollowing == true || relationship?.isFriend == true
    val buttonModifier = modifier
        .widthIn(min = 112.dp)
        .semantics { contentDescription = label }
    val click = {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        onClick()
    }
    if (outlined) {
        OutlinedButton(
            onClick = click,
            enabled = enabled,
            modifier = buttonModifier,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(label, maxLines = 1)
        }
    } else {
        Button(
            onClick = click,
            enabled = enabled,
            modifier = buttonModifier,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Coral),
        ) {
            Text(label, maxLines = 1)
        }
    }
}

@Composable
fun SocialUserRow(
    user: UserSummary,
    showFollowAction: Boolean,
    followEnabled: Boolean,
    onOpenProfile: () -> Unit,
    onToggleFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenProfile)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics {
                contentDescription = buildString {
                    append(user.displayName)
                    append(", @")
                    append(user.username)
                    user.relationship?.let { rel ->
                        append(", ")
                        append(
                            when {
                                rel.isFriend -> "Friends"
                                rel.isFollowing -> "Following"
                                rel.followsYou -> "Follows you"
                                else -> "Not following"
                            },
                        )
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TravelImage(user.avatarUrl, user.displayName, Modifier.size(48.dp).clip(CircleShape))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                user.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "@${user.username}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (user.relationship?.followsYou == true && user.relationship?.isFollowing != true) {
                Text(
                    "Follows you",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (showFollowAction) {
            FollowActionButton(
                relationship = user.relationship,
                enabled = followEnabled,
                onClick = onToggleFollow,
            )
        }
    }
}

@Composable
fun FollowsYouHint(relationship: RelationshipState?, modifier: Modifier = Modifier) {
    if (relationship?.followsYou == true && relationship.isFollowing != true) {
        Text(
            "Follows you",
            modifier = modifier,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun SocialCountersRow(
    followerCount: Long,
    followingCount: Long,
    friendCount: Long,
    onFollowers: () -> Unit,
    onFollowing: () -> Unit,
    onFriends: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Counter(followerCount.toString(), "Followers", onFollowers)
        Counter(followingCount.toString(), "Following", onFollowing)
        Counter(friendCount.toString(), "Friends", onFriends)
    }
}

@Composable
private fun Counter(value: String, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
