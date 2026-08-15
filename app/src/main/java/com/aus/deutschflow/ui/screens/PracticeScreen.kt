package com.aus.deutschflow.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.components.AudioWaveform
import com.aus.deutschflow.ui.components.ErrorBanner
import com.aus.deutschflow.ui.components.GlassButton
import com.aus.deutschflow.ui.components.GlassmorphicCard
import com.aus.deutschflow.ui.components.OnLeavingScreen
import com.aus.deutschflow.ui.theme.AzureGlow
import com.aus.deutschflow.ui.theme.PillShape
import com.aus.deutschflow.ui.theme.Spacing
import com.aus.deutschflow.ui.theme.glassSurface
import com.aus.deutschflow.ui.viewmodel.PracticeFeedback
import com.aus.deutschflow.ui.viewmodel.PracticeViewModel

@Composable
fun PracticeScreen(viewModel: PracticeViewModel = viewModel()) {
    val targetSentence by viewModel.targetSentence.collectAsState()
    val feedback by viewModel.feedback.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val spokenText by viewModel.finalText.collectAsState()
    val wordResults by viewModel.wordResults.collectAsState()
    val errorState by viewModel.errorState.collectAsState()
    val partialText by viewModel.partialText.collectAsState()

    // RMS is drawn, not composed: the recogniser emits many samples a second, so the
    // level is folded into a MutableFloatState and read inside the waveform's Canvas,
    // where a change invalidates only the draw pass - never a recomposition.
    val amplitude = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(viewModel) {
        viewModel.rmsLevel.collect { amplitude.floatValue = it }
    }

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    // The level decides the colour; the resource decides the words. Deciding the
    // colour from the words is what broke the moment they were translated.
    val isPositive = feedback == PracticeFeedback.PERFECT
    val feedbackText = when (feedback) {
        PracticeFeedback.NONE -> null
        PracticeFeedback.PERFECT -> stringResource(R.string.practice_feedback_perfect)
        PracticeFeedback.GOOD -> stringResource(R.string.practice_feedback_good)
        PracticeFeedback.KEEP_GOING -> stringResource(R.string.practice_feedback_keep_going)
    }

    // Permission Mandate: Runtime Authorization
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startPractice()
        } else {
            // Android stops showing this dialog after the second refusal, so without
            // an answer here the Speak button is a control that does nothing, forever,
            // and never says why.
            viewModel.onPermissionDenied()
        }
    }

    // Without this the recognizer keeps the microphone open after the user moves to
    // another tab: this screen's ViewModel is kept alive by the saved back stack entry.
    LaunchedEffect(Unit) { viewModel.dismissTtsError() }

    OnLeavingScreen { viewModel.cancelListening() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.md)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(Spacing.sm))

        // The hero, anchored to the top rather than floated in the middle.
        //
        // This column used to be centred in the space left over, which put the one
        // thing the screen is about between two large voids. Centring was itself a fix
        // for the sentence being pinned above an empty half-screen, so the emptiness
        // had only moved: the real problem was that nothing owned the space
        // underneath. Something does now - see the result region below.
        Box(modifier = Modifier.fillMaxWidth().glassSurface()) {
            // The sentence and its Speak control on one baseline. The control used to
            // be a full-width tonal bar stacked underneath, which added a second
            // horizontal band to a card that only holds one idea - and it carried
            // primary blue on a 40%-alpha primaryContainer, which composites to blue
            // on blue and was effectively unreadable.
            Row(
                modifier = Modifier.padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // headlineSmall: the target is a whole sentence, and at
                // headlineMedium/Black it filled the card and shouted over everything
                // else on the screen.
                val textStyle = MaterialTheme.typography.headlineSmall

                if (wordResults.isEmpty()) {
                    Text(
                        text = targetSentence,
                        style = textStyle,
                        // onSurface, not primary. The sentence is the content of the
                        // card, not an accent in it, and brand blue on the container
                        // left it sitting back instead of reading first.
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    val annotatedString = buildAnnotatedString {
                        wordResults.forEach { result ->
                            withStyle(style = SpanStyle(
                                color = if (result.isCorrect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )) {
                                append(result.word + " ")
                            }
                        }
                    }
                    Text(
                        text = annotatedString,
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.md))

                // A container/on-container pair, so the glyph is guaranteed legible
                // against whatever sits behind it.
                FilledTonalIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.speak(targetSentence)
                    },
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.practice_listen),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        ErrorBanner(errorState)

        // The result region: one glass card that owns the space between the hero and
        // the actions, so the middle of the screen is never a void to balance around.
        // It carries the live waveform while recording, the instruction before the
        // first attempt, the spinner while the answer is in flight, and the verdict +
        // what was heard after one.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = Spacing.lg),
            contentAlignment = Alignment.TopCenter
        ) {
            GlassmorphicCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(Spacing.md)
            ) {
                when {
                    isListening -> {
                        // The live speech: what the engine has heard so far, above the
                        // meter that traces the input level.
                        if (partialText.isNotBlank()) {
                            Text(
                                text = partialText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(Spacing.sm))
                        }
                        AudioWaveform(
                            amplitude = amplitude,
                            isActive = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                        )
                    }

                    isProcessing -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp
                        )
                    }

                    spokenText.isEmpty() -> Text(
                        text = stringResource(R.string.practice_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.lg)
                    )

                    else -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (feedback != PracticeFeedback.NONE) {
                            Surface(
                                color = if (isPositive) MaterialTheme.colorScheme.tertiaryContainer
                                        else MaterialTheme.colorScheme.errorContainer,
                                shape = PillShape
                            ) {
                                Text(
                                    text = feedbackText.orEmpty(),
                                    modifier = Modifier.padding(
                                        horizontal = Spacing.md,
                                        vertical = Spacing.sm
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    // The container's own On colour. The verdict used to be
                                    // tertiary or error text on a 20%-alpha tint of the
                                    // same hue - the blue-on-blue mistake the Listen
                                    // button was making, in two more colours.
                                    color = if (isPositive) MaterialTheme.colorScheme.onTertiaryContainer
                                            else MaterialTheme.colorScheme.onErrorContainer,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Spacer(modifier = Modifier.height(Spacing.md))
                        }

                        // What the recogniser heard, reading as the user's own words.
                        Text(
                            text = spokenText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Outside the scroll: Speak is the point of the screen, and it used to sit
        // below the fold behind the target card, the error banner and the result
        // card - reachable only by scrolling past everything it acts on.
        Spacer(modifier = Modifier.height(Spacing.md))

        // Both actions are glass now. The Speak button takes the error role only while
        // actually recording (stop-the-world), and returns to the cyan edge otherwise.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            GlassButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (isListening) {
                        viewModel.stopPractice()
                    } else {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            viewModel.startPractice()
                        } else {
                            launcher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                enabled = !isProcessing,
                modifier = Modifier.weight(1f),
                glow = if (isListening) MaterialTheme.colorScheme.error else AzureGlow,
                contentColor = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(if (isListening) R.string.practice_evaluate else R.string.practice_speak),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            GlassButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.nextSentence()
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.practice_next),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
