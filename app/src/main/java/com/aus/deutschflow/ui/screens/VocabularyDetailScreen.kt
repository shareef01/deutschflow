package com.aus.deutschflow.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aus.deutschflow.R
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.ui.components.EmptyState
import com.aus.deutschflow.ui.components.GlassButton
import com.aus.deutschflow.ui.theme.Spacing
import com.aus.deutschflow.ui.theme.glassSurface

@Composable
fun VocabularyDetailScreen(
    item: VocabularyEntity?,
    exampleSentence: String = "",
    onClose: () -> Unit = {},
    onSpeak: (String) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current

    if (item == null) {
        EmptyState(
            icon = Icons.Default.Info,
            message = stringResource(R.string.detail_empty_title),
            description = stringResource(R.string.detail_empty_body)
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.md)
    ) {
        // Header Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.germanText,
                    // headlineMedium, not displaySmall/Black: entries are whole
                    // sentences here, not the single words the size assumed.
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                // The grammatical facts the single-word interrogation captured, for
                // entries that have them; hand-typed words leave this line empty.
                val grammar = listOfNotNull(
                    item.article.takeIf { it.isNotBlank() && it != "none" },
                    item.plural.takeIf { it.isNotBlank() },
                    item.conjugation.takeIf { it.isNotBlank() }
                ).joinToString("  ·  ")
                if (grammar.isNotBlank()) {
                    Text(
                        text = grammar,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = item.englishTranslation,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // The same control the Practice screen uses to speak its sentence: one
            // component, one size, one colour pair for "hear this aloud". This used
            // to be a LargeFloatingActionButton, which made a playback control out
            // of the component reserved for the one thing that creates content.
            FilledTonalIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSpeak(item.germanText)
                },
                modifier = Modifier
                    .padding(start = Spacing.md)
                    .size(56.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.action_speak),
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

        // Context / Examples Section
        Text(
            text = stringResource(R.string.detail_context),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = Spacing.sm),
            color = MaterialTheme.colorScheme.surfaceVariant
        )

        // Glass, like every other content surface: the example is a piece of
        // content, not an input, and this was the one content card still solid.
        Box(modifier = Modifier.fillMaxWidth().glassSurface()) {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                Text(
                    text = stringResource(R.string.detail_example),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = exampleSentence,
                    // The type scale's own line height: a hand-set 26sp was the one
                    // place in the app where a body text disagreed with the scale.
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Navigation Action
        GlassButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClose()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.detail_back),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
