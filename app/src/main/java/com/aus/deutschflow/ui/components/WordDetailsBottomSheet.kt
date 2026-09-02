package com.aus.deutschflow.ui.components

import com.aus.deutschflow.ui.theme.AppTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aus.deutschflow.R
import com.aus.deutschflow.service.WordDetails
import com.aus.deutschflow.ui.theme.Spacing

/**
 * The interrogation result, in a bottom sheet on the dark theme.
 *
 * The word, article and plural are read first; the English meaning and a contextual
 * German example follow, and one glowing button saves exactly this structured word to
 * the library - no whole-transcript "save all". The sheet's container sits on the
 * surface ramp and the example carries the glass edge every card does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordDetailsBottomSheet(
    details: WordDetails?,
    onDismiss: () -> Unit,
    onSave: (WordDetails) -> Unit,
    modifier: Modifier = Modifier
) {
    if (details == null) return

    val haptic = LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .padding(bottom = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // The word itself, then its grammatical identity.
            Text(
                text = details.word,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )

            val facts = buildList {
                if (details.article.isNotBlank() && details.article != "none") {
                    add(stringResource(R.string.word_sheet_article, details.article))
                }
                if (details.plural.isNotBlank()) {
                    add(stringResource(R.string.word_sheet_plural, details.plural))
                }
                if (details.conjugationOrInfinitive.isNotBlank()) {
                    add(stringResource(R.string.word_sheet_verb, details.conjugationOrInfinitive))
                }
            }
            if (facts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = facts.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            Text(
                text = stringResource(R.string.word_sheet_meaning),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = details.meaning,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            if (details.exampleSentence.isNotBlank()) {
                Spacer(modifier = Modifier.height(Spacing.lg))
                GlassmorphicCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(Spacing.md)
                ) {
                    Text(
                        text = stringResource(R.string.detail_example),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = details.exampleSentence,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // One glowing confirmation for one structured word.
            GlassButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSave(details)
                },
                modifier = Modifier.fillMaxWidth(),
                contentColor = AppTheme.colors.azureGlow
            ) {
                Icon(
                    imageVector = Icons.Default.BookmarkAdd,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.transcript_save),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
