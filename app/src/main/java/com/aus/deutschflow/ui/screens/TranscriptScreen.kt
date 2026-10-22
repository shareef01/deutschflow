package com.aus.deutschflow.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.service.GrammarNote
import com.aus.deutschflow.ui.components.*
import com.aus.deutschflow.ui.theme.*
import com.aus.deutschflow.ui.viewmodel.SaveOutcome
import com.aus.deutschflow.ui.viewmodel.TranscriptViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscriptScreen(viewModel: TranscriptViewModel = hiltViewModel()) {
    val partialText by viewModel.partialText.collectAsStateWithLifecycle()
    val finalText by viewModel.finalText.collectAsStateWithLifecycle()
    val translation by viewModel.translation.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val suggestedWords by viewModel.suggestedWords.collectAsStateWithLifecycle()
    val grammarNotes by viewModel.grammarNotes.collectAsStateWithLifecycle()
    val errorState by viewModel.errorState.collectAsStateWithLifecycle()
    val ttsError by viewModel.ttsError.collectAsStateWithLifecycle()
    val aiError by viewModel.aiError.collectAsStateWithLifecycle()
    val wordDetails by viewModel.wordDetails.collectAsStateWithLifecycle()
    val wordDetailsFor by viewModel.wordDetailsFor.collectAsStateWithLifecycle()
    val wordDetailError by viewModel.wordDetailError.collectAsStateWithLifecycle()
    val interrogatingWord by viewModel.interrogatingWord.collectAsStateWithLifecycle()
    val selectedDialect by viewModel.selectedDialect.collectAsStateWithLifecycle()
    val isFirstRun by viewModel.isFirstRun.collectAsStateWithLifecycle()
    val listeningSeconds by viewModel.listeningSeconds.collectAsStateWithLifecycle()
    val permissionDenied by viewModel.permissionDenied.collectAsStateWithLifecycle()

    val amplitude = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(viewModel) {
        viewModel.rmsLevel.collect { amplitude.floatValue = it }
    }

    val context = LocalContext.current
    val view = LocalView.current
    LaunchedEffect(finalText) {
        if (finalText.isNotBlank()) view.announceForAccessibility(finalText)
    }

    val savedMessage = stringResource(R.string.transcript_saved)
    val copiedMessage = stringResource(R.string.action_copied)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Saves run in the ViewModel (see TranscriptViewModel.launchSave); this only
    // reports how they ended.
    val saveFailedMessage = stringResource(R.string.library_save_failed)
    LaunchedEffect(viewModel) {
        viewModel.saveOutcomes.collect { outcome ->
            snackbarHostState.showSnackbar(
                when (outcome) {
                    SaveOutcome.SAVED -> savedMessage
                    SaveOutcome.FAILED -> saveFailedMessage
                }
            )
        }
    }

    LaunchedEffect(wordDetailError) {
        wordDetailError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissWordDetailError(it)
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startListening()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    OnLeavingScreen {
        viewModel.cancelListening()
        // The transcript is speakable, so leaving mid-playback used to carry the
        // voice into whatever screen came next.
        viewModel.stopSpeaking()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TranscriptContent(
            partialText = partialText,
            finalText = finalText,
            translation = translation,
            isListening = isListening,
            isBusy = isBusy,
            suggestedWords = suggestedWords,
            grammarNotes = grammarNotes,
            loadingWord = interrogatingWord,
            errorState = errorState ?: aiError ?: ttsError,
            rmsAmplitude = amplitude,
            dialect = selectedDialect,
            isFirstRun = isFirstRun,
            listeningSeconds = listeningSeconds,
            onSelectDialect = { viewModel.selectDialect(it) },
            permissionDenied = permissionDenied,
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                )
            },
            onStartListening = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.startListening()
                } else {
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onStopListening = { viewModel.stopListening() },
            onWordClick = { word -> viewModel.interrogateWord(word) },
            onSpeakTranscript = { text -> viewModel.speak(text) },
            onCopyTranscript = { text ->
                copyToClipboard(context, text)
                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
            },
            onSave = { viewModel.saveTranscript(finalText, translation) },
            onSubmitTyped = { text -> viewModel.submitTypedText(text) }
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        WordDetailsBottomSheet(
            details = wordDetails,
            tappedWord = wordDetailsFor,
            onDismiss = { viewModel.dismissWordDetails() },
            onSave = { details ->
                viewModel.dismissWordDetails()
                viewModel.saveWord(details)
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptContent(
    partialText: String,
    finalText: String,
    translation: String,
    isListening: Boolean,
    isBusy: Boolean,
    suggestedWords: List<String>,
    grammarNotes: List<GrammarNote>,
    loadingWord: String?,
    errorState: String?,
    rmsAmplitude: State<Float>,
    dialect: String,
    isFirstRun: Boolean,
    listeningSeconds: Int,
    onSelectDialect: (String) -> Unit,
    permissionDenied: Boolean,
    onOpenSettings: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onWordClick: (String) -> Unit,
    onSpeakTranscript: (String) -> Unit,
    onCopyTranscript: (String) -> Unit,
    onSave: () -> Unit,
    onSubmitTyped: (String) -> Boolean
) {
    val haptic = LocalHapticFeedback.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val scrollState = rememberScrollState()
    var inputMode by rememberSaveable { mutableStateOf(InputMode.SPEAK) }
    // Held here rather than in TypedInput: the stacked and one-row docks call
    // TypedInput from different places, so state kept inside it was dropped on
    // every rotation, along with whatever the user had typed.
    var typedText by rememberSaveable { mutableStateOf("") }

    val hasTranscript = partialText.isNotEmpty() || finalText.isNotEmpty()
    val isIdle = !hasTranscript && !isListening && !isBusy
    val currentText = if (isListening) partialText else finalText

    // imePadding: the typed-input field lives in the bottom dock, and the keyboard
    // covered it, so what you typed was out of sight until you sent it.
    BoxWithConstraints(modifier = Modifier.fillMaxSize().imePadding()) {
        val dockLayout = transcriptDockLayout(
            availableHeight = maxHeight,
            imeVisible = WindowInsets.isImeVisible,
            typing = inputMode == InputMode.TYPE
        )
        Column(modifier = Modifier.fillMaxSize()) {
            if (dockLayout == DockLayout.TYPING_ONLY) {
                // Only the field fits above the keyboard in landscape; the dialect and
                // the transcript come back the moment the keyboard goes.
                Spacer(modifier = Modifier.weight(1f))
            } else {
                // ==========================================
                // 1. TOP FIXED HEADER / DIALECT REGION
                // ==========================================
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xs, bottom = Spacing.xs),
                    contentAlignment = Alignment.Center
                ) {
                    LanguageIndicator(dialect, onSelectDialect)
                }

                val fadeTop by remember {
                    derivedStateOf { scrollState.value > 0 }
                }
                val fadeBottom by remember {
                    derivedStateOf { scrollState.value < scrollState.maxValue }
                }

                // ==========================================
                // 2. SCROLLABLE CONTENT REGION
                // ==========================================
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .scrollFadingEdges(
                            topFadeHeight = 24.dp,
                            bottomFadeHeight = 32.dp,
                            fadeTop = fadeTop,
                            fadeBottom = fadeBottom
                        )
                        .verticalScroll(scrollState)
                        .padding(horizontal = Spacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(Spacing.xs))

                    // Error or Permission Banner
                    ErrorBanner(
                        message = errorState,
                        action = if (permissionDenied) {
                            {
                                TextButton(onClick = onOpenSettings) {
                                    Text(stringResource(R.string.action_open_settings))
                                }
                            }
                        } else null
                    )

                    if (isIdle) {
                        // Calm, instructional idle empty state. Follows the input mode: the
                        // speech wording sat above a text field in Type mode, telling the
                        // user to do the one thing that mode is for not doing.
                        val typing = inputMode == InputMode.TYPE
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.xxl)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.size(72.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (typing) Icons.Default.Edit else Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        tint = AppTheme.colors.azureGlow,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(Spacing.lg))
                            Text(
                                text = stringResource(
                                    if (typing) R.string.transcript_empty_title_type else R.string.transcript_empty_title
                                ),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(Spacing.sm))
                            Text(
                                text = stringResource(
                                    if (typing) R.string.transcript_empty_body_type else R.string.transcript_empty_body
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = Spacing.md)
                            )
                        }
                    } else {
                        // Live / Final Transcript Card
                        TranscriptDisplayCard(
                            text = currentText,
                            isListening = isListening,
                            isBusy = isBusy,
                            listeningSeconds = listeningSeconds,
                            rmsAmplitude = rmsAmplitude,
                            onSpeak = { onSpeakTranscript(currentText) },
                            onCopy = { onCopyTranscript(currentText) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = TranscriptCardMinHeight)
                        )
                    }

                    // Results Section: Grammar Spotlight, Translation, Key Vocabulary, Save
                    if (translation.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(Spacing.lg))

                        // Grammar Spotlight Section
                        if (grammarNotes.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.transcript_grammar_spotlight),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = UppercaseLabelTracking,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = Spacing.xs, bottom = Spacing.sm)
                            )
                            grammarNotes.forEach { note ->
                                GrammarSpotlightCard(note)
                                Spacer(modifier = Modifier.height(Spacing.sm))
                            }
                            Spacer(modifier = Modifier.height(Spacing.md))
                        }

                        // Translation Section
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.transcript_section_translation).uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = UppercaseLabelTracking,
                                modifier = Modifier.padding(bottom = Spacing.sm, start = Spacing.xs)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .glassSurface()
                                    .padding(Spacing.md)
                            ) {
                                Text(
                                    text = translation,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            if (suggestedWords.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(Spacing.lg))
                                Text(
                                    text = stringResource(R.string.transcript_section_vocabulary).uppercase(),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = UppercaseLabelTracking,
                                    modifier = Modifier.padding(bottom = Spacing.sm, start = Spacing.xs)
                                )
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                                ) {
                                    suggestedWords.forEach { word ->
                                        VocabularyChip(
                                            word = word,
                                            isLoading = loadingWord == word,
                                            onClick = { onWordClick(word) }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(Spacing.lg))
                            PrimaryActionButton(
                                text = stringResource(R.string.transcript_save),
                                onClick = onSave,
                                icon = { Icon(Icons.Default.BookmarkAdd, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // Reserved bottom space inside the scroll container so content scrolls cleanly above the dock
                    Spacer(modifier = Modifier.height(Spacing.lg))
                }
            }

            // ==========================================
            // 3. PERSISTENT DOCKED MICROPHONE ACTION REGION
            // ==========================================
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            ) {
                val inputModeLabel = stringResource(R.string.transcript_input_mode)
                val modeTabs: @Composable (Modifier) -> Unit = { tabsModifier ->
                    SegmentedTabs(
                        selectedIndex = inputMode.ordinal,
                        tabs = listOf(
                            stringResource(R.string.transcript_mode_speak),
                            stringResource(R.string.transcript_mode_type)
                        ),
                        onTabSelected = { inputMode = InputMode.entries[it] },
                        // Typing mid-recording would hide the mic, the only stop control,
                        // while recognition carried on. Same lock as the web's switch.
                        enabled = !isListening,
                        modifier = tabsModifier.semantics { contentDescription = inputModeLabel }
                    )
                }

                if (dockLayout == DockLayout.STACKED) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.xs, bottom = Spacing.sm),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        modeTabs(Modifier)
                        if (inputMode == InputMode.TYPE) {
                            TypedInput(
                                text = typedText,
                                onTextChange = { typedText = it },
                                isBusy = isBusy,
                                onSubmit = onSubmitTyped,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                            )
                        } else {
                            SpeakControls(
                                isListening = isListening,
                                isBusy = isBusy,
                                onStartListening = onStartListening,
                                onStopListening = onStopListening
                            )
                        }
                    }
                } else {
                    // One row instead of a stack: in landscape the stacked dock was taller
                    // than the space left over, so it pushed the transcript, and the banner
                    // above it, down to a couple of pixels.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // None above the keyboard: about 58dp is left there, and the
                            // padding took it below the 48dp the send button needs.
                            .padding(vertical = if (dockLayout == DockLayout.TYPING_ONLY) 0.dp else Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (dockLayout == DockLayout.ROW) {
                            modeTabs(Modifier.weight(1f))
                        }
                        Box(
                            modifier = Modifier
                                .weight(if (dockLayout == DockLayout.ROW) 1.4f else 1f)
                                .padding(horizontal = Spacing.md),
                            contentAlignment = Alignment.Center
                        ) {
                            if (inputMode == InputMode.TYPE) {
                                TypedInput(
                                    text = typedText,
                                    onTextChange = { typedText = it },
                                    isBusy = isBusy,
                                    onSubmit = onSubmitTyped,
                                    compact = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                SpeakControls(
                                    isListening = isListening,
                                    isBusy = isBusy,
                                    onStartListening = onStartListening,
                                    onStopListening = onStopListening,
                                    compact = true
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The microphone, its recording pulse and the hint that goes with it.
 *
 * [compact] puts the hint beside the button rather than under it and trims the
 * halo, for the single-row dock.
 */
@Composable
private fun SpeakControls(
    isListening: Boolean,
    isBusy: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    compact: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val haloSize = if (compact) CompactHaloSize else 100.dp
    val button: @Composable () -> Unit = {
        Box(
            modifier = Modifier.size(haloSize),
            contentAlignment = Alignment.Center
        ) {
            if (isListening && !LocalReducedMotion.current) {
                val pulseScale by rememberInfiniteTransition(label = "recordPulse").animateFloat(
                    initialValue = 1f,
                    targetValue = 1.30f,
                    animationSpec = infiniteRepeatable(
                        tween(Motion.PULSE_PERIOD, easing = Motion.Standard),
                        RepeatMode.Restart
                    ),
                    label = "pulse"
                )
                Box(
                    Modifier
                        .size(RecordButtonSize)
                        .scale(pulseScale)
                        .alpha(0.3f)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                )
            }

            val interactionSource = remember { MutableInteractionSource() }
            val micLabel = stringResource(
                if (isListening) R.string.transcript_stop else R.string.transcript_start
            )

            Box(
                modifier = Modifier
                    .size(RecordButtonSize)
                    .pressScale(interactionSource)
                    .clip(CircleShape)
                    .background(
                        if (isListening) {
                            Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
                            )
                        } else {
                            Brush.linearGradient(listOf(AppTheme.colors.azureGlow, primaryColor))
                        }
                    )
                    // Inert while a translation is in flight. Without this,
                    // isListening is already false during TRANSCRIBING, so a
                    // second tap started a fresh recording underneath the
                    // request that was still running - and the older answer
                    // then landed on the newer utterance's screen, where Save
                    // would file it against the wrong German. The web port
                    // has always disabled its microphone here.
                    .clickable(
                        enabled = !isBusy,
                        interactionSource = interactionSource,
                        indication = ripple(bounded = false)
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (isListening) onStopListening() else onStartListening()
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = micLabel
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(RecordIconSize),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
    val hintText: @Composable () -> Unit = {
        Text(
            text = stringResource(
                if (isListening) R.string.transcript_stop else R.string.transcript_idle_hint
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (compact) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            button()
            Spacer(modifier = Modifier.width(Spacing.sm))
            hintText()
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            button()
            hintText()
        }
    }
}

/** How the user gives the screen German: the microphone, or the keyboard. */
private enum class InputMode { SPEAK, TYPE }

/** How the Transcript dock is laid out for the height it has. */
internal enum class DockLayout {
    /** Tabs over the microphone or the typed field: portrait, the usual case. */
    STACKED,

    /** Tabs and the control side by side in one row: a short screen, such as landscape. */
    ROW,

    /** The typed field alone: a short screen with the keyboard up. */
    TYPING_ONLY
}

/**
 * Picks the dock's layout from the height the screen has once the keyboard is
 * accounted for.
 *
 * In landscape the stacked dock (tabs, label, field, full-width button - about
 * 230dp) was taller than the screen had to give: without the keyboard it squeezed
 * the transcript and its error banner to a couple of pixels, and with the keyboard
 * up (about 58dp left on a Pixel 7) the field and button went below it, so what
 * the user typed was out of sight.
 */
internal fun transcriptDockLayout(availableHeight: Dp, imeVisible: Boolean, typing: Boolean): DockLayout = when {
    availableHeight >= ShortTranscriptHeight -> DockLayout.STACKED
    imeVisible && typing -> DockLayout.TYPING_ONLY
    else -> DockLayout.ROW
}

/** Below this the stacked dock leaves the transcript too little room. Portrait with the keyboard up stays above it. */
internal val ShortTranscriptHeight = 360.dp

/** The field and send button in the one-row dock: the touch-target floor, so they fit above a landscape keyboard. */
private val CompactFieldHeight = 48.dp

/** The recording pulse's room in the one-row dock; the stacked dock keeps 100dp. */
private val CompactHaloSize = 88.dp

/**
 * A field for typed German and the button that sends it.
 *
 * Inert while a translation is in flight, for the reason the microphone is: an older
 * answer would land on the newer sentence. The field is cleared only once the
 * ViewModel accepts the text, so a refused submit does not throw away what was typed.
 *
 * [compact] is one row - the field, then a send button beside it - with the label
 * moved into the placeholder, for the landscape dock where the stacked field, label
 * and full-width button did not fit above the keyboard.
 */
@Composable
private fun TypedInput(
    text: String,
    onTextChange: (String) -> Unit,
    isBusy: Boolean,
    onSubmit: (String) -> Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val canSubmit = !isBusy && text.isNotBlank()
    val submit = {
        if (canSubmit && onSubmit(text)) {
            onTextChange("")
            // Out of the way, so the translation that is about to arrive is visible.
            keyboard?.hide()
        }
    }

    val keyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.Sentences,
        imeAction = ImeAction.Send
    )
    val keyboardActions = KeyboardActions(onSend = { submit() })

    if (compact) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            GlassTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = stringResource(R.string.transcript_type_label),
                enabled = !isBusy,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                minHeight = CompactFieldHeight
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            FilledIconButton(
                onClick = submit,
                enabled = canSubmit,
                modifier = Modifier.size(CompactFieldHeight)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.transcript_translate_typed)
                    )
                }
            }
        }
        return
    }

    Column(modifier = modifier) {
        GlassTextField(
            value = text,
            onValueChange = onTextChange,
            label = stringResource(R.string.transcript_type_label),
            placeholder = stringResource(R.string.transcript_type_placeholder),
            enabled = !isBusy,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        PrimaryActionButton(
            text = stringResource(R.string.transcript_translate_typed),
            onClick = submit,
            enabled = canSubmit,
            isLoading = isBusy,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Human-readable recognition dialect selector.
 */
@Composable
private fun LanguageIndicator(dialect: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val label = stringResource(R.string.transcript_change_dialect)

    val dialectLabel = when (dialect) {
        "de-AT" -> stringResource(R.string.settings_dialect_at)
        "de-CH" -> stringResource(R.string.settings_dialect_ch)
        else -> stringResource(R.string.settings_dialect_de)
    }

    Box {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                open = true
            },
            modifier = Modifier.semantics { contentDescription = label }
        ) {
            Row(
                modifier = Modifier.padding(
                    start = Spacing.md,
                    end = Spacing.sm,
                    top = 6.dp,
                    bottom = 6.dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.transcript_listening_for, dialectLabel),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            val dialects = listOf(
                "de-DE" to R.string.settings_dialect_de,
                "de-AT" to R.string.settings_dialect_at,
                "de-CH" to R.string.settings_dialect_ch
            )
            dialects.forEach { (code, nameRes) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(nameRes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (code == dialect) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(code)
                        open = false
                    },
                    trailingIcon = {
                        if (code == dialect) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
            }
        }
    }
}

/**
 * Modern transcript card with live audio visualization and copy/audio action controls.
 */
@Composable
private fun TranscriptDisplayCard(
    text: String,
    isListening: Boolean,
    isBusy: Boolean,
    listeningSeconds: Int,
    rmsAmplitude: State<Float>,
    onSpeak: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val words = remember(text) { wordCount(text) }

    GlassmorphicCard(
        modifier = modifier,
        contentPadding = PaddingValues(Spacing.md)
    ) {
        // Card Top Bar: Word count badge and action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isListening) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(
                        text = stringResource(R.string.transcript_listening_status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else if (isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = AppTheme.colors.azureGlow
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(
                        text = stringResource(R.string.transcript_processing_status),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.colors.azureGlow,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else if (text.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.history_word_count, words, words),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            if (text.isNotBlank() && !isListening) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSpeak()
                        },
                        // No explicit size: below 48dp it also shrank the touch target,
                        // which IconButton otherwise keeps at the accessible minimum.
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(R.string.transcript_listen),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCopy()
                        },
                        // No explicit size: below 48dp it also shrank the touch target,
                        // which IconButton otherwise keeps at the accessible minimum.
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.transcript_copy),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        // Main Text Content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs)
        ) {
            Text(
                text = text.ifEmpty { stringResource(R.string.transcript_placeholder) },
                style = TranscriptTextStyle,
                color = if (text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
        }

        // Live Audio Waveform while listening
        if (isListening) {
            Spacer(modifier = Modifier.height(Spacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AudioWaveform(
                    amplitude = rmsAmplitude,
                    isActive = true,
                    modifier = Modifier
                        .weight(1f)
                        .height(WaveformHeight)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(
                        R.string.transcript_elapsed,
                        listeningSeconds / 60,
                        listeningSeconds % 60
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun GrammarSpotlightCard(note: GrammarNote) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = note.case.uppercase(),
                        modifier = Modifier.padding(horizontal = Spacing.xs, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = note.phrase,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (note.explanation.isNotBlank()) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = note.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

