package com.aus.deutschflow.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.components.GlassmorphicCard
import com.aus.deutschflow.ui.components.OnLeavingScreen
import com.aus.deutschflow.ui.components.PrimaryActionButton
import com.aus.deutschflow.ui.components.SecondaryActionButton
import com.aus.deutschflow.ui.components.SegmentedTabs
import com.aus.deutschflow.ui.theme.*
import com.aus.deutschflow.ui.viewmodel.PracticeFeedback
import com.aus.deutschflow.ui.viewmodel.PracticeViewModel
import com.aus.deutschflow.ui.viewmodel.RoleplayViewModel
import com.aus.deutschflow.util.PermissionState
import com.aus.deutschflow.util.openAppSettings
import com.aus.deutschflow.util.recordAudioPermissionState

@Composable
fun PracticeScreen(
    viewModel: PracticeViewModel = hiltViewModel(),
    roleplayViewModel: RoleplayViewModel = hiltViewModel()
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabLabels = listOf(
        stringResource(R.string.practice_tab),
        stringResource(R.string.roleplay_tab)
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(Spacing.xs))
        SegmentedTabs(
            selectedIndex = selectedTab,
            tabs = tabLabels,
            onTabSelected = { selectedTab = it }
        )
        Spacer(modifier = Modifier.height(Spacing.xs))

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTab == 0) {
                ShadowingMode(viewModel)
            } else {
                RoleplayScreen(viewModel = roleplayViewModel)
            }
        }
    }
}

@Composable
private fun ShadowingMode(viewModel: PracticeViewModel) {
    val targetSentence by viewModel.targetSentence.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val spokenText by viewModel.finalText.collectAsStateWithLifecycle()
    val partialText by viewModel.partialText.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val wordResults by viewModel.wordResults.collectAsStateWithLifecycle()
    val errorState by viewModel.errorState.collectAsStateWithLifecycle()
    val permissionDenied by viewModel.permissionDenied.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val primaryColor = MaterialTheme.colorScheme.primary

    // This screen's banner combines the recogniser's and the voice engine's
    // errors, so a stale TTS failure from another screen would greet the user
    // here. Same entry discipline as Study and Vocabulary.
    LaunchedEffect(Unit) {
        viewModel.dismissTtsError()
    }

    val hasResult = feedback != PracticeFeedback.NONE
    val isPositive = feedback == PracticeFeedback.PERFECT || feedback == PracticeFeedback.GOOD
    // The banner reports the count it actually has. It used to say "Perfect
    // pronunciation", which the screen has no way to know: the recogniser hands
    // back a transcript, not a per-phoneme score, and its language model will
    // happily resolve poor audio into the word you meant.
    val heardCount = wordResults.count { it.isCorrect }
    val totalCount = wordResults.size
    val feedbackText = when (feedback) {
        PracticeFeedback.NONE -> null
        PracticeFeedback.PERFECT -> stringResource(R.string.practice_feedback_perfect)
        PracticeFeedback.GOOD -> stringResource(R.string.practice_feedback_good, heardCount, totalCount)
        PracticeFeedback.KEEP_GOING ->
            stringResource(R.string.practice_feedback_keep_going, heardCount, totalCount)
    }

    var askedForPermission by rememberSaveable { mutableStateOf(false) }
    // Guards against a double-tap sending two requests (or two starts) while the
    // permission dialog is open; the callback clears it when it returns.
    var permissionRequestInProgress by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        permissionRequestInProgress = false
        if (isGranted) {
            viewModel.startPractice()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    /** Mirrors Transcript: check, then either ask again or point to Settings. */
    val requestMicrophone: () -> Unit = {
        when (context.recordAudioPermissionState(askedForPermission)) {
            PermissionState.GRANTED -> viewModel.startPractice()
            PermissionState.REQUESTABLE -> {
                askedForPermission = true
                permissionRequestInProgress = true
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
            PermissionState.DENIED_PERMANENTLY -> viewModel.onPermissionDenied()
        }
    }

    // The ViewModel is scoped to the saved back stack entry, so tab switches and
    // backgrounding would otherwise leave the recogniser holding the microphone
    // behind whatever screen the user moved on to.
    OnLeavingScreen {
        viewModel.cancelListening()
        // The target sentence is speakable, so leaving mid-playback used to carry
        // the voice into whatever screen came next.
        viewModel.stopSpeaking()
    }

    // Two steps in the order they happen, and the screen scrolls as a whole: on a
    // short phone or at a large font scale, the buttons stay reachable.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // Step 1: listen. The sentence is the material, so it gets the size.
        GlassmorphicCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(Spacing.lg)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StepLabel(
                        number = 1,
                        text = stringResource(R.string.practice_listen_repeat),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.speak(targetSentence)
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.practice_listen), fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                if (wordResults.isEmpty()) {
                    Text(
                        text = targetSentence,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    // Colour plus an underline, so a missed word does not depend on
                    // telling red from green.
                    val annotatedString = buildAnnotatedString {
                        wordResults.forEach { result ->
                            withStyle(
                                style = SpanStyle(
                                    color = if (result.isCorrect) AppTheme.colors.tertiaryGreen else MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    textDecoration = if (result.isCorrect) null else TextDecoration.Underline
                                )
                            ) {
                                append(result.word + " ")
                            }
                        }
                    }
                    Text(
                        text = annotatedString,
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            }
        }

        // Error message if any
        AnimatedVisibility(
            visible = errorState != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f), MaterialTheme.shapes.small)
                    .padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = errorState ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                // Only a refused microphone is something the user can fix, and only
                // from outside the app - so the action appears only for a denial.
                if (permissionDenied) {
                    TextButton(onClick = { context.openAppSettings() }) {
                        Text(stringResource(R.string.action_open_settings))
                    }
                }
            }
        }

        // Step 2: speak, and what the recogniser made of it.
        GlassmorphicCard(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PracticeResultMinHeight),
            contentPadding = PaddingValues(Spacing.lg)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                StepLabel(number = 2, text = stringResource(R.string.practice_speak))

                when {
                    // The words as they are recognised. This screen used to stay on
                    // "waiting" until the end, so speaking looked like nothing was
                    // happening.
                    isListening -> Text(
                        text = partialText.ifEmpty { stringResource(R.string.practice_waiting) },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (partialText.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                    isProcessing -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = AppTheme.colors.azureGlow
                    )
                    feedbackText == null && spokenText.isEmpty() -> Text(
                        text = stringResource(R.string.practice_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> {
                        if (feedbackText != null) {
                            // A partial match is something to try again, not an error.
                            Surface(
                                color = if (isPositive) MaterialTheme.colorScheme.tertiaryContainer
                                else AppTheme.colors.warningContainer,
                                shape = CircleShape
                            ) {
                                Text(
                                    text = feedbackText,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isPositive) MaterialTheme.colorScheme.onTertiaryContainer
                                    else AppTheme.colors.onWarningContainer,
                                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                                )
                            }
                        }
                        if (spokenText.isNotEmpty()) {
                            Text(
                                text = spokenText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (hasResult) {
                            Text(
                                text = stringResource(R.string.practice_feedback_caption),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Speak is what this screen is for, so it takes the larger share.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            PrimaryActionButton(
                text = stringResource(
                    if (isListening) R.string.practice_evaluate else R.string.practice_speak
                ),
                icon = {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                gradientColors = if (isListening) {
                    listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
                } else {
                    listOf(AppTheme.colors.azureGlow, primaryColor)
                },
                enabled = !isProcessing,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (isListening) {
                        viewModel.stopPractice()
                    } else if (!permissionRequestInProgress) {
                        requestMicrophone()
                    }
                },
                modifier = Modifier.weight(2f)
            )

            SecondaryActionButton(
                text = stringResource(R.string.practice_next),
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                },
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.nextSentence()
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** The numbered heading of a practice step. The number is decoration, so it is not read. */
@Composable
private fun StepLabel(number: Int, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clearAndSetSemantics { }
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = UppercaseLabelTracking
        )
    }
}
