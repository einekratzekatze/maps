package io.github.einekratzekatze.maps.ui.bubble

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.einekratzekatze.maps.R

/**
 * The collapsed 56 dp button with its badge (§3.3). Stateless: gestures, semantics and the
 * click come in through [modifier] from the layout, which owns the position.
 * [badgeOnLeft] puts the badge at the top-start so it points inward when docked right.
 */
@Composable
fun PlacesBubble(
    badge: BadgeState,
    badgeOnLeft: Boolean,
    dragging: Boolean,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(if (dragging) 1.08f else 1f, tween(100), label = "bubbleScale")
    val shadow by animateDpAsState(if (dragging) 12.dp else 6.dp, tween(100), label = "bubbleShadow")
    Box(modifier.testTag(BubbleTestTags.Bubble)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            tonalElevation = 3.dp,
            shadowElevation = shadow,
            modifier = Modifier.size(BubbleDefaults.Size).graphicsLayer { scaleX = scale; scaleY = scale },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_bookmark_border), contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
        if (badge is BadgeState.Count) {
            Badge(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                modifier = Modifier
                    .align(if (badgeOnLeft) Alignment.TopStart else Alignment.TopEnd)
                    .offset(x = if (badgeOnLeft) (-2).dp else 2.dp, y = (-2).dp)
                    .testTag(BubbleTestTags.Badge),
            ) { Text(badge.label, style = MaterialTheme.typography.labelSmall) }
        }
    }
}
