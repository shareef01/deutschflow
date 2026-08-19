package com.aus.deutschflow.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.theme.Motion
import com.aus.deutschflow.ui.theme.LocalReducedMotion
import com.aus.deutschflow.ui.theme.Spacing
import androidx.hilt.navigation.compose.hiltViewModel
import com.aus.deutschflow.ui.theme.AzureGlow
import com.aus.deutschflow.ui.theme.GlassFill
import com.aus.deutschflow.ui.theme.glassSurface
import com.aus.deutschflow.ui.viewmodel.ChatMessage
import com.aus.deutschflow.ui.viewmodel.RoleplayViewModel

@Composable
fun RoleplayScreen(viewModel: RoleplayViewModel = hiltViewModel()) {
    val messages by viewModel.messages.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val partialText by viewModel.partialText.collectAsState()
    val error by viewModel.error.collectAsState()
    val haptic = LocalHapticFeedback.current

    val listState = rememberLazyListState()

    // Auto-scroll to bottom
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Start session if empty
    LaunchedEffect(Unit) {
        if (messages.isEmpty()) {
            viewModel.startSession("Ordering at a Berlin Bakery")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            items(messages) { message ->
                ChatBubble(message, onSpeak = { viewModel.speak(it) })
            }
            
            if (isProcessing) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }

            // The turn that did not arrive, and the way back to it. Without this a
            // failed opening line left the screen blank and unrecoverable.
            error?.let { message ->
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = { viewModel.retry() }) {
                            Text(stringResource(R.string.roleplay_retry))
                        }
                    }
                }
            }
        }

        // Recording Interface
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isListening && partialText.isNotBlank()) {
                    Text(
                        text = partialText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = Spacing.sm)
                    )
                }

                Box(contentAlignment = Alignment.Center) {
                    // Same halo, same reason for stopping it - see TranscriptScreen.
                    if (isListening && !LocalReducedMotion.current) {
                        val pulseScale by rememberInfiniteTransition(label = "record").animateFloat(
                            initialValue = 1f,
                            targetValue = 1.5f,
                            animationSpec = infiniteRepeatable(
                                tween(Motion.PULSE_PERIOD, easing = Motion.Standard),
                                RepeatMode.Restart
                            ),
                            label = "pulse"
                        )
                        Box(Modifier.size(64.dp).scale(pulseScale).alpha(0.3f).background(MaterialTheme.colorScheme.primary, CircleShape))
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
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            // The screen's primary control: unlabelled, it did not
                            // exist for TalkBack at all.
                            contentDescription = micLabel,
                            tint = Color.White
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isAI) Alignment.Start else Alignment.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .glassSurface(
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isAI) 2.dp else 16.dp,
                        bottomEnd = if (isAI) 16.dp else 2.dp
                    ),
                    fill = if (isAI) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                )
                .padding(Spacing.md)
        ) {
            Column {
                Text(text = message.content, style = MaterialTheme.typography.bodyLarge)
                if (isAI && !message.translation.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = message.translation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        if (isAI) {
            // 48dp, the minimum touch target the rest of the app now holds to; the
            // icon inside stays small.
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSpeak(message.content)
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(R.string.roleplay_speak_message),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
