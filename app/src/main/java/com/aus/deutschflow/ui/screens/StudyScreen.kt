package com.aus.deutschflow.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.theme.OnSurfaceMuted
import com.aus.deutschflow.ui.theme.Spacing
import com.aus.deutschflow.ui.viewmodel.StudyViewModel
import com.aus.deutschflow.ui.components.EmptyState
import com.aus.deutschflow.ui.components.ErrorBanner

@Composable
fun StudyScreen(viewModel: StudyViewModel = viewModel()) {
    val studyList by viewModel.studyList.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val isFlipped by viewModel.isFlipped.collectAsState()
    val hasLoaded by viewModel.hasLoaded.collectAsState()
    val ttsError by viewModel.ttsError.collectAsState()
    val haptic = LocalHapticFeedback.current

    // Re-entering the tab starts a fresh session, so words saved since last time
    // are included rather than waiting for the ViewModel to be recreated. This is
    // the only caller - the ViewModel deliberately does not also load on init.
    LaunchedEffect(Unit) {
        viewModel.dismissTtsError()
        viewModel.startSession()
    }

    // Hold the frame rather than claiming the library is empty before it is read.
    if (!hasLoaded) return

    if (studyList.isEmpty()) {
        EmptyState(
            icon = Icons.Default.School,
            message = stringResource(R.string.study_empty_title),
            description = stringResource(R.string.study_empty_body)
        )
        return
    }

    val currentItem = studyList[currentIndex.coerceIn(studyList.indices)]

    // Speaks the card unless auto-play is switched off in Settings.
    LaunchedEffect(currentIndex, currentItem.id) {
        viewModel.autoPlay(currentItem.germanText)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Autoplay is the one place the app speaks without being asked, so a device
        // that cannot speak German has to say so here rather than sitting silent.
        ErrorBanner(ttsError)

        // Card flip animation logic
        val rotation by animateFloatAsState(
            targetValue = if (isFlipped) 180f else 0f,
            animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
            label = "cardFlip"
        )

        // The card is centred in what is left rather than filling it. Taking the whole
        // slot made a short word sit in the middle of a large grey field, with the
        // emptiness inside the card instead of around it.
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 420.dp)
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = 12f * density
                }
                .clickable(
                    onClickLabel = stringResource(
                        if (isFlipped) R.string.study_show_german else R.string.study_show_translation
                    )
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.flipCard()
                },
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (isFlipped) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f) 
                                else MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            // fillMaxWidth, not fillMaxSize: filling meant the card always grew to
            // whatever height it was allowed, so a three-word card was mostly empty
            // grey. It wraps its content now, between a floor and a ceiling.
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.lg),
                contentAlignment = Alignment.Center
            ) {
                if (rotation <= 90f || rotation >= 270f) {
                    // Front side: German
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.library_field_german).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceMuted
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Text(
                            text = currentItem.germanText,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(Spacing.md))
                        // A real button. It carried a "Speak" description and did
                        // nothing but flip the card, because the tap fell through to
                        // the card behind it.
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.speak(currentItem.germanText)
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = stringResource(R.string.action_speak),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.study_tap_to_flip),
                            style = MaterialTheme.typography.labelSmall,
                            // Flat, not alpha-dimmed: 0.7f alpha on this ground read
                            // at about 3.2:1, under WCAG AA for body text.
                            color = OnSurfaceMuted
                        )
                    }
                } else {
                    // Back side: Translation
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.graphicsLayer { rotationY = 180f }.padding(24.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.library_field_translation).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceMuted
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Text(
                            text = currentItem.englishTranslation,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.study_got_it),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }
        }

        Spacer(modifier = Modifier.height(Spacing.lg))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.nextCard() 
                },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(stringResource(R.string.study_skip))
            }
            Button(
                onClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.rewardCurrentCard()
                    viewModel.nextCard() 
                },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(stringResource(R.string.study_got_it_action), fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        LinearProgressIndicator(
            progress = { (currentIndex.coerceIn(studyList.indices) + 1).toFloat() / studyList.size },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.study_progress, currentIndex + 1, studyList.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
