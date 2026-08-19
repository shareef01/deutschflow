package com.aus.deutschflow.ui.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable

/**
 * One treatment for "this one is selected", wherever the question is asked.
 *
 * The app had three. The bottom bar marked its selection with a primary-blue pill,
 * the Study tabs with primary blue, and the Library sort chips with *teal* - not
 * because anyone chose teal, but because a bare `FilterChip` takes
 * `FilterChipDefaults`, which reaches for the **secondary** container role, and this
 * theme's secondary is the cyan end of the ramp. Three treatments of the same idea
 * were visible in a single viewport of the Library screen.
 *
 * Selection is a single idea and gets a single colour: the primary container, with
 * its paired `on` colour. Cyan stays what it always was - the listening state and
 * transcription accents - rather than doubling as a UI selection.
 */
object SelectionDefaults {

    /**
     * Chip colours for anything that filters or sorts.
     *
     * The unselected half is left to Material: an outlined chip on the ground is
     * already the right answer, and overriding it would only re-state the default.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun filterChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
    )
}
