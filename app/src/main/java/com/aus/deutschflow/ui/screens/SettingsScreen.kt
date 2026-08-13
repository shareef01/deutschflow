package com.aus.deutschflow.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aus.deutschflow.BuildConfig
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.theme.ActionButtonHeight
import com.aus.deutschflow.ui.theme.pressScale
import com.aus.deutschflow.ui.theme.rememberPressSource
import com.aus.deutschflow.ui.theme.GlassFillRaised
import com.aus.deutschflow.ui.theme.azureBrush
import com.aus.deutschflow.ui.theme.glassSurface
import com.aus.deutschflow.ui.theme.PillShape
import com.aus.deutschflow.ui.theme.Spacing
import com.aus.deutschflow.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val totalVocab by viewModel.totalVocabulary.collectAsState()
    val totalTranscripts by viewModel.totalTranscripts.collectAsState()
    val userStats by viewModel.userStats.collectAsState()
    val hasApiKey by viewModel.hasApiKey.collectAsState()
    val selectedDialect by viewModel.selectedDialect.collectAsState()
    val isAutoPlay by viewModel.isAutoPlayEnabled.collectAsState()
    val message by viewModel.message.collectAsState()
    val streak = userStats?.streak ?: 0

    // The field starts empty and stays empty, even when a key is stored.
    //
    // It used to be seeded with the decrypted key on every visit, which put the
    // plaintext back into UI state each time Settings opened and handed a filled
    // password field to whatever password manager the device runs. A key is write-only
    // from here now: the screen says whether one is saved, and typing replaces it.
    //
    // remember, not rememberSaveable: saved instance state is handed to the system
    // process and kept for as long as the task lives, which would put a half-typed key
    // in the clear outside the store it is encrypted in. Losing it on rotation is the
    // right way round for a credential.
    var typedKey by remember { mutableStateOf("") }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var isKeyVisible by rememberSaveable { mutableStateOf(false) }

    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    // Asked for here, at the moment the user requests a notification, rather than
    // being demanded on the very first launch before they have seen a screen.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.testNotification() }

    fun requestTestNotification() {
        val alreadyGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        if (alreadyGranted) {
            viewModel.testNotification()
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // A list of resource ids, not a map keyed by an English label: the key was the
    // thing shown on screen, so translating it would have changed the map's identity.
    val dialects = listOf(
        R.string.settings_dialect_de to "de-DE",
        R.string.settings_dialect_at to "de-AT",
        R.string.settings_dialect_ch to "de-CH"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            // enableEdgeToEdge() turns off the manifest's adjustResize, so without
            // this the keyboard lands directly on top of the API key field.
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Section: AI Configuration
        SettingsHeader(stringResource(R.string.settings_ai_header))
        
        OutlinedTextField(
            value = typedKey,
            onValueChange = { typedKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_api_key_label)) },
            placeholder = {
                Text(
                    stringResource(
                        if (hasApiKey) R.string.settings_api_key_replace
                        else R.string.settings_api_key_hint
                    )
                )
            },
            shape = MaterialTheme.shapes.medium,
            // Masked by default, and typed as a password so the keyboard stops
            // offering the credential back as an autocomplete suggestion.
            visualTransformation = if (isKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false
            ),
            trailingIcon = {
                Row {
                    IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                        Icon(
                            imageVector = if (isKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(
                                if (isKeyVisible) R.string.settings_hide_key else R.string.settings_show_key
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        // Nothing typed is nothing to save, and an empty save would
                        // silently wipe a working key.
                        enabled = typedKey.isNotBlank(),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.saveApiKey(typedKey)
                            // The plaintext must not outlive the save.
                            typedKey = ""
                            isKeyVisible = false
                        }
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.action_save),
                            // Follows the enabled state. A hardcoded primary tint made
                            // the control look live while it was doing nothing, which
                            // is worse than being obviously unavailable.
                            tint = if (typedKey.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            }
                        )
                    }
                }
            },
            singleLine = true
        )
        // Says whether a key is stored without ever showing it.
        Text(
            text = stringResource(
                if (hasApiKey) R.string.settings_api_key_saved_state
                else R.string.settings_api_key_none
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (hasApiKey) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm, start = Spacing.xs)
        )
        Text(
            text = stringResource(R.string.settings_api_key_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp)
        )

        // Section: Learning Progress
        SettingsHeader(stringResource(R.string.settings_progress_header))
        
        Box(modifier = Modifier.fillMaxWidth().glassSurface()) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatGridItem(Modifier.weight(1f), stringResource(R.string.settings_stat_vocabulary), totalVocab.toString())
                    StatGridItem(Modifier.weight(1f), stringResource(R.string.settings_stat_sessions), totalTranscripts.toString())
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatGridItem(Modifier.weight(1f), stringResource(R.string.settings_stat_xp), (userStats?.xp ?: 0).toString())
                    StatGridItem(
                        Modifier.weight(1f),
                        stringResource(R.string.settings_stat_streak),
                        // Plural, not "$n days": German needs Tag for one and Tage for
                        // the rest, and English needs the same distinction.
                        pluralStringResource(R.plurals.streak_days, streak, streak)
                    )
                }
            }
        }

        // Section: Audio Preferences
        SettingsHeader(stringResource(R.string.settings_audio_header))
        
        Box(modifier = Modifier.fillMaxWidth().glassSurface()) {
            Row(
                modifier = Modifier.padding(Spacing.md).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.settings_autoplay), 
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Switch(
                    checked = isAutoPlay,
                    onCheckedChange = { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.setAutoPlayEnabled(it) 
                    }
                )
            }
        }

        // Section: Dialect
        SettingsHeader(stringResource(R.string.settings_dialect_header))
        
        dialects.forEach { (labelRes, code) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.saveDialect(code) 
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = (selectedDialect == code),
                    onClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.saveDialect(code) 
                    }
                )
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp)) // Mandatory Spacer
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        // Section: Actions
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), 
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // A neutral surface, not the default tonal fill. FilledTonalButton takes
            // secondaryContainer when it is not told otherwise, and this palette's
            // secondary is orange - so the container resolved to a muddy brown that
            // appears nowhere else in the app and read as a warning it is not.
            val notifySource = rememberPressSource()
            val wipeSource = rememberPressSource()

            FilledTonalButton(
                interactionSource = notifySource,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    requestTestNotification()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ActionButtonHeight)
                    .pressScale(notifySource),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                shape = PillShape
            ) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(stringResource(R.string.settings_test_notification), style = MaterialTheme.typography.labelLarge)
            }

            // The error container/on-container pair, which is the one place in the app
            // that role is correct: this is the destructive action.
            Button(
                interactionSource = wipeSource,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showDeleteConfirm = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ActionButtonHeight)
                    .pressScale(wipeSource),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                shape = PillShape
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(stringResource(R.string.settings_clear), style = MaterialTheme.typography.labelLarge)
            }
        }

        Text(
            // Read from the build rather than typed here, where it drifted to
            // 1.2.0 while the build said 1.0.
            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp)) // Mandatory bottom padding
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.settings_wipe_title), fontWeight = FontWeight.Black) },
            text = { Text(stringResource(R.string.settings_wipe_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.clearAllProgress()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.settings_wipe_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.settings_wipe_cancel))
                }
            }
        )
    }

    message?.let { messageRes ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissMessage() },
            text = { Text(stringResource(messageRes)) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissMessage() }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }
}

@Composable
fun SettingsHeader(title: String) {
    Text(
        text = title,
        // A section label, not a headline: Black weight in full primary made every
        // heading compete with the content underneath it.
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xl, bottom = Spacing.sm, start = Spacing.xs)
    )
}

/**
 * One cell of the telemetry matrix.
 *
 * The number is painted with a brush rather than a colour - Compose takes a Brush on
 * TextStyle, so the azure ramp runs through the glyphs themselves instead of sitting
 * behind them. It is the only text in the app treated that way, which is what makes
 * the four figures read as instrument output rather than as more copy.
 */
@Composable
fun StatGridItem(modifier: Modifier, label: String, value: String) {
    Column(
        modifier = modifier
            // Raised glass: this tile sits on the card that holds it, so it takes the
            // brighter fill or the two would be indistinguishable.
            .glassSurface(shape = MaterialTheme.shapes.medium, fill = GlassFillRaised)
            .padding(Spacing.md),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = value,
            // headlineLarge and allowed to shrink. Black weight with negative tracking
            // comes from the scale; the brush is what is specific to the matrix.
            style = MaterialTheme.typography.headlineLarge.copy(brush = azureBrush()),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            // Uppercased here rather than in the resource, so the string stays a
            // sentence for a screen reader and for translation.
            text = label.uppercase(),
            // The wide tracking lives here rather than on labelSmall: this is the one
            // place it is wanted, and the navigation bar renders its labels at that
            // same style inside slots too narrow to take it.
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
