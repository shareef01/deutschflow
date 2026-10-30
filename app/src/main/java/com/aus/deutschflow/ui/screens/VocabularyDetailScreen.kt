package com.aus.deutschflow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.aus.deutschflow.R
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.ui.components.EmptyState
import com.aus.deutschflow.ui.components.GlassButton
import com.aus.deutschflow.ui.theme.UppercaseLabelTracking
import com.aus.deutschflow.ui.theme.Spacing
import com.aus.deutschflow.ui.theme.glassSurface

/**
 * One word, in the order a learner reads it: the word with its article, what it
 * means, its grammar, the example, then related words. Mirrors VocabularyDetail in
 * web/src/app/(app)/vocabulary/page.tsx.
 */
@Composable
fun VocabularyDetailScreen(
    item: VocabularyEntity?,
    exampleSentence: String = "",
    onClose: () -> Unit = {},
    onSpeak: (String) -> Unit = {},
    onEdit: (VocabularyEntity) -> Unit = {},
    onDelete: (VocabularyEntity) -> Unit = {},
    /** False beside the list on a wide screen, where there is nothing to go back to. */
    showBack: Boolean = true
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

    val article = item.displayArticle()
    // The article leads the headline, so it is not repeated as a fact here.
    val grammar = listOfNotNull(
        item.plural.trim().takeIf { it.isNotEmpty() }?.let { stringResource(R.string.detail_plural) to it },
        item.conjugation.trim().takeIf { it.isNotEmpty() }?.let { stringResource(R.string.detail_forms) to it }
    )
    val relations = listOfNotNull(
        item.synonyms.trim().takeIf { it.isNotEmpty() }?.let { stringResource(R.string.detail_synonyms) to it },
        item.antonyms.trim().takeIf { it.isNotEmpty() }?.let { stringResource(R.string.detail_antonyms) to it }
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Scrolls on its own, so a long example or a word with every field filled
        // never pushes Edit and Delete off the bottom of the screen.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md)
        ) {
            if (showBack) {
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClose()
                    },
                    modifier = Modifier.offset(x = (-Spacing.sm))
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.detail_back), style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Spacer(modifier = Modifier.height(Spacing.md))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = buildAnnotatedString {
                            if (article != null) {
                                withStyle(
                                    SpanStyle(
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                ) { append(article) }
                                append(" ")
                            }
                            append(item.germanText)
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = item.englishTranslation,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledTonalIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSpeak(item.germanText)
                    },
                    modifier = Modifier
                        .padding(start = Spacing.md)
                        .size(48.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.action_speak)
                    )
                }
            }

            if (grammar.isNotEmpty()) {
                SectionLabel(stringResource(R.string.detail_grammar))
                FactRow(grammar)
            }

            SectionLabel(stringResource(R.string.detail_context))
            // A quotation, not a card with a label inside it: the rule on the left
            // says "example" without spending a line on the word.
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(2.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                )
                Text(
                    text = exampleSentence,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = Spacing.md, top = Spacing.xs, bottom = Spacing.xs)
                )
            }

            SectionLabel(stringResource(R.string.detail_linguistic_connections))
            if (relations.isNotEmpty()) {
                FactRow(relations)
            } else {
                // A sentence, not two boxes of dashes.
                Text(
                    text = stringResource(R.string.detail_no_relations),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            GlassButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onEdit(item)
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.action_edit),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            GlassButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDelete(item)
                },
                modifier = Modifier.weight(1f),
                glow = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.error
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.action_delete),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** The one section label the detail uses, so every section reads at the same weight. */
@Composable
private fun SectionLabel(text: String) {
    Spacer(modifier = Modifier.height(Spacing.lg))
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = UppercaseLabelTracking,
        modifier = Modifier.semantics { heading() }
    )
    Spacer(modifier = Modifier.height(Spacing.sm))
}

/** Labelled values side by side: plural and forms, or synonyms and antonyms. */
@Composable
private fun FactRow(facts: List<Pair<String, String>>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        facts.forEach { (label, value) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .glassSurface(shape = MaterialTheme.shapes.medium)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
