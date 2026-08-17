package com.aus.deutschflow.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The one way this app tells the user something went wrong in place.
 *
 * Transcript and Practice each carried a byte-identical copy of this, which is why
 * neither of them had anywhere to put a text-to-speech failure. Study and the
 * library now render it too.
 */
@Composable
fun ErrorBanner(message: String?, modifier: Modifier = Modifier) {
    // The last message outlives the null that starts the exit animation, so the
    // banner shrinks away with its text intact instead of blanking first.
    var lastMessage by remember { mutableStateOf("") }
    if (message != null) lastMessage = message

    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Row(
            modifier = modifier
                .padding(bottom = 16.dp)
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.shapes.small
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
                // Announced when it appears, rather than waiting to be found.
                // A banner that says the microphone was denied, or that German
                // speech is missing, is the answer to "why did nothing happen" -
                // and a user who cannot see it arriving is exactly the user most
                // likely to be asking. Polite, not Assertive: it should follow
                // what the screen reader is already saying, not cut across it.
                //
                // mergeDescendants is the half that makes it work, and its absence
                // is silent: a bare `semantics { liveRegion = ... }` publishes a
                // node carrying the property and no text, because the message sits
                // on a descendant Text. TalkBack announces a live region from the
                // node's own text, so an empty one announces nothing - the banner
                // appeared on screen and was never spoken. Verified on a Pixel 7:
                // before this, the accessibility node was a View with text='' and
                // the message on a TextView beneath it.
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                // On its own container colour and left-aligned, at body weight. It is
                // a condition to read once, not an alarm: bold error red centred over
                // three lines dominated every screen it appeared on.
                text = lastMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
