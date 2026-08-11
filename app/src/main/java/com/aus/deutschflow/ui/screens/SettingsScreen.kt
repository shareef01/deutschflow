package com.aus.deutschflow.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
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
import com.aus.deutschflow.ui.theme.Spacing
import com.aus.deutschflow.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val totalVocab by viewModel.totalVocabulary.collectAsState()
    val totalTranscripts by viewModel.totalTranscripts.collectAsState()
    val userStats by viewModel.userStats.collectAsState()
    val storedKey by viewModel.apiKey.collectAsState()
    val selectedDialect by viewModel.selectedDialect.collectAsState()
    val isAutoPlay by viewModel.isAutoPlayEnabled.collectAsState()
    val message by viewModel.message.collectAsState()
    val streak = userStats?.streak ?: 0

    // Null until the user types, and only then does the field stop following the
    // stored key. Re-seeding on every change to that key looked equivalent, but the
    // DataStore's first emission is "" and the real key lands a moment later - so
    // anything typed in that window was silently replaced.
    var typedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val apiKeyInput = typedKey ?: storedKey
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
            value = apiKeyInput,
            onValueChange = { typedKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_api_key_label)) },
            placeholder = { Text(stringResource(R.string.settings_api_key_hint)) },
            shape = RoundedCornerShape(16.dp),
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
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.saveApiKey(apiKeyInput)
                    }) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.action_save), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            singleLine = true
        )
        Text(
            text = stringResource(R.string.settings_api_key_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp)
        )

        // Section: Learning Progress
        SettingsHeader(stringResource(R.string.settings_progress_header))
        
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.outlinedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
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
        
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
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
            FilledTonalButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    requestTestNotification()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                // Mandatory Title Case override
                Text(stringResource(R.string.settings_test_notification), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showDeleteConfirm = true 
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                // Mandatory Title Case override
                Text(stringResource(R.string.settings_clear), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
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

@Composable
fun StatGridItem(modifier: Modifier, label: String, value: String) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = value,
            // headlineMedium, and allowed to shrink: "0 days" at displaySmall/Black
            // already reached the edges of its tile, and a two-digit streak clipped.
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label, 
            style = MaterialTheme.typography.labelMedium, 
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}
