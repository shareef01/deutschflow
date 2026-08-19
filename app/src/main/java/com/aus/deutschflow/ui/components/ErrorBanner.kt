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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/**
 * The one way this app tells the user something went wrong in place.
 *
 * Transcript and Practice each carried a byte-identical copy of this, which is why
 * neither of them had anywhere to put a text-to-speech failure. Study and the
 * library now render it too.
 */
@Composable
fun ErrorBanner(
    message: String?,
    modifier: Modifier = Modifier,
    /**
     * An optional way out, for the conditions the user can actually undo.
     *
     * Most of what lands here is weather - a network blip, a recogniser that heard
     * nothing - and reading it is all anyone can do. A refused microphone is not:
     * it is fixable, in one place, and a banner that only names it leaves the person
     * who most needs help with nowhere to go.
     */
    action: (@Composable () -> Unit)? = null
) {
    // The last message outlives the null that starts the exit animation, so the
    // banner shrinks away with its text intact instead of blanking first.
    var lastMessage by remember { mutableStateOf("") }
    if (message != null) lastMessage = message

    // Announced explicitly, because a live region cannot do this job here.
    //
    // Two attempts failed on a real device before this one. Compose decides what to
    // report by diffing the semantics tree, and
    // AndroidComposeViewAccessibilityDelegateCompat opens that diff with
    // `previousSemanticsNodes[id] ?: return@forEachKey` - a node that did not exist
    // a frame ago is skipped. The banner and its message are born together, so the
    // node is always new and the change is never reported. Hoisting liveRegion onto
    // a wrapper that outlives the message does not rescue it either: with no banner
    // the wrapper has no size, Compose leaves it out of the accessibility tree, and
    // it is just as new when the message arrives. Measured both times - every
    // content-changed event came back live=none with no text.
    //
    // announceForAccessibility posts TYPE_ANNOUNCEMENT directly, which a screen
    // reader speaks without consulting any tree. Deprecated on API 34+, and kept
    // anyway: the supported alternative is the live region that demonstrably does
    // not fire for content that appears.
    val view = LocalView.current
    LaunchedEffect(message) {
        if (message != null) view.announceForAccessibility(message)
    }

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
                .padding(horizontal = 12.dp, vertical = 10.dp),
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

            if (action != null) {
                Spacer(modifier = Modifier.width(8.dp))
                action()
            }
        }
    }
}
