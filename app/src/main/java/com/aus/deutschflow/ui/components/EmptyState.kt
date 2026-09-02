package com.aus.deutschflow.ui.components

import com.aus.deutschflow.ui.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.aus.deutschflow.ui.theme.EmptyStateIconSize
import com.aus.deutschflow.ui.theme.EmptyStateMedallionSize
import com.aus.deutschflow.ui.theme.Spacing

/**
 * The one empty state, used by every screen that can have nothing to show.
 *
 * [action] is the difference between a dead end and an invitation. A screen with
 * nothing on it is the moment the user most needs to know what to do next, and
 * "No transcripts found" on its own answers that with silence. Optional, because a
 * few empty states genuinely have no action to offer - the word-detail pane has
 * nothing to add until a word is picked in the list beside it.
 *
 * Sizes come from the theme and the type styles carry their own metrics: the
 * hand-set line height and letter spacing that used to sit here were overriding
 * the ramp in Type.kt, which is the same problem as a hardcoded font size, one
 * level further in.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    message: String,
    description: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(EmptyStateMedallionSize)
                // Solid, from the elevation ramp. At 0.4f alpha over a near-black
                // ground the disc behind the icon was all but invisible, so the icon
                // floated with nothing holding it.
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                // The message below says the same thing, so announcing the icon too
                // makes a screen reader repeat itself.
                contentDescription = null,
                modifier = Modifier.size(EmptyStateIconSize),
                // Flat, not alpha-dimmed, for the same reason AppTheme.colors.onSurfaceMuted exists:
                // alpha over a dark ground loses contrast faster than it looks like
                // it should.
                tint = AppTheme.colors.onSurfaceMuted
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        if (description != null) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        if (action != null) {
            // Further from the body than the body is from the title: the copy is one
            // block, and the thing you can do about it is another.
            Spacer(modifier = Modifier.height(Spacing.lg))
            action()
        }
    }
}
