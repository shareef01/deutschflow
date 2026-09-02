package com.aus.deutschflow.ui.components

import com.aus.deutschflow.ui.theme.AppTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aus.deutschflow.ui.theme.Spacing
import com.aus.deutschflow.ui.theme.glassBorderBrush
import com.aus.deutschflow.ui.theme.pressScale

/**
 * One extracted vocabulary word as an interactive glass pill.
 *
 * A tap interrogates the word; while the Groq call is in flight the pill shows a
 * small azure spinner in place of nothing, which is the "glowing loading state" - the
 * control stays put and just lights up, rather than being replaced by a spinner.
 */
@Composable
fun VocabularyChip(
    word: String,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(AppTheme.colors.glassFillRaised, MaterialTheme.shapes.small)
            .border(
                BorderStroke(1.dp, glassBorderBrush(AppTheme.colors.azureGlow, alpha = 0.22f)),
                MaterialTheme.shapes.small
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .pressScale(interactionSource)
            .padding(horizontal = Spacing.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = word,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (isLoading) {
            Spacer(modifier = Modifier.width(Spacing.xs))
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = AppTheme.colors.azureGlow,
                strokeWidth = 2.dp
            )
        }
    }
}
