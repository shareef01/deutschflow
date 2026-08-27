package com.aus.deutschflow.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.components.GlassmorphicCard
import com.aus.deutschflow.ui.components.OnLeavingScreen
import com.aus.deutschflow.ui.theme.*
import com.aus.deutschflow.ui.viewmodel.ChatMessage
import com.aus.deutschflow.ui.viewmodel.RoleplayViewModel

@Composable
fun RoleplayScreen(viewModel: RoleplayViewModel = hiltViewModel()) {
    val messages by viewModel.messages.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val partialText by viewModel.partialText.collectAsState()
    val error by viewModel.error.collectAsState()
    val recognitionError by viewModel.errorState.collectAsState()
    val haptic = LocalHapticFeedback.current

    val listState = rememberLazyListState()

    // The ViewModel is scoped to the saved back stack entry, so tab switches and
    // backgrounding would otherwise leave the recogniser holding the microphone
    // behind whatever screen the user moved on to.
    OnLeavingScreen { viewModel.cancelListening() }

    // Auto-scroll to bottom when messages update
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Start session if empty
    LaunchedEffect(Unit) {
        if (messages.isEmpty()) {
            viewModel.startSession(RoleplayViewModel.SCENARIO_BERLIN_BAKERY)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Scenario Header Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.roleplay_tab).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = UppercaseLabelTracking
                    )
                    Text(
                        text = stringResource(R.string.roleplay_scenario_berlin_bakery),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.startSession(RoleplayViewModel.SCENARIO_BERLIN_BAKERY)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.roleplay_restart_session),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Conversation Chat Timeline
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            items(messages) { message ->
                ChatBubble(message, onSpeak = { viewModel.speak(it) })
            }

            if (isProcessing) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = AzureGlow
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(
                            text = stringResource(R.string.transcript_processing_status),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            error?.let { message ->
                item {
                    GlassmorphicCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(Spacing.md)
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        TextButton(onClick = { viewModel.retry() }) {
                            Text(stringResource(R.string.roleplay_retry))
                        }
                    }
                }
            }

            // Recognition-side failures (no speech heard, engine error) never reach
            // the AI error above, and the turn they ate never appears in the chat.
            // Show them so a failed turn is an explained dead end, not a silent one;
            // retrying is just tapping the microphone again.
            recognitionError?.let { message ->
                item {
                    GlassmorphicCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(Spacing.md)
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Voice Input Control Footer
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isListening && partialText.isNotBlank()) {
                    Text(
                        text = partialText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = Spacing.sm)
                    )
                }

                Box(contentAlignment = Alignment.Center) {
                    if (isListening && !LocalReducedMotion.current) {
                        val pulseScale by rememberInfiniteTransition(label = "roleplayMicPulse").animateFloat(
                            initialValue = 1f,
                            targetValue = 1.6f,
                            animationSpec = infiniteRepeatable(
                                tween(Motion.PULSE_PERIOD, easing = Motion.Standard),
                                RepeatMode.Restart
                            ),
                            label = "pulse"
                        )
                        Box(
                            Modifier
                                .size(64.dp)
                                .scale(pulseScale)
                                .alpha(0.25f)
                                .background(MaterialTheme.colorScheme.error, CircleShape)
                        )
                    }

                    val micLabel = stringResource(
                        if (isListening) R.string.roleplay_send_turn else R.string.roleplay_start_speaking
                    )

                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (isListening) viewModel.stopListeningAndSend() else viewModel.startListening()
                        },
                        enabled = !isProcessing,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(
                                if (isListening) {
                                    Brush.linearGradient(
                                        listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
                                    )
                                } else {
                                    Brush.linearGradient(listOf(AzureGlow, MaterialTheme.colorScheme.primary))
                                }
                            )
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = micLabel,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Text(
                    text = stringResource(
                        if (isListening) R.string.roleplay_tap_to_send else R.string.roleplay_tap_to_speak
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage, onSpeak: (String) -> Unit) {
    val isAI = message.role == "assistant"
    val haptic = LocalHapticFeedback.current
    var showTranslation by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isAI) Alignment.Start else Alignment.End
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isAI) 4.dp else 16.dp,
                bottomEnd = if (isAI) 16.dp else 4.dp
            ),
            color = if (isAI) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(
                1.dp,
                if (isAI) MaterialTheme.colorScheme.outlineVariant
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isAI) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onPrimaryContainer
                )

                if (isAI && !message.translation.isNullOrBlank()) {
                    AnimatedVisibility(visible = showTranslation) {
                        Column {
                            Spacer(modifier = Modifier.height(Spacing.xs))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                thickness = 0.5.dp
                            )
                            Spacer(modifier = Modifier.height(Spacing.xs))
                            Text(
                                text = message.translation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (isAI) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = Spacing.xs, top = 2.dp)
            ) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSpeak(message.content)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.roleplay_speak_message),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                if (!message.translation.isNullOrBlank()) {
                    Text(
                        text = stringResource(
                            if (showTranslation) R.string.roleplay_hide_translation
                            else R.string.roleplay_show_translation
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showTranslation = !showTranslation
                            }
                            .padding(horizontal = Spacing.xs, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

