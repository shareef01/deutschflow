package com.aus.deutschflow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aus.deutschflow.BuildConfig
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.components.DestructiveActionButton
import com.aus.deutschflow.ui.components.GlassmorphicCard
import com.aus.deutschflow.ui.components.PrimaryActionButton
import com.aus.deutschflow.ui.components.SecondaryActionButton
import com.aus.deutschflow.ui.theme.*
import com.aus.deutschflow.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val totalVocab by viewModel.totalVocabulary.collectAsState()
    val totalTranscripts by viewModel.totalTranscripts.collectAsState()
    val userStats by viewModel.userStats.collectAsState()
    val hasApiKey by viewModel.hasApiKey.collectAsState()
    val selectedDialect by viewModel.selectedDialect.collectAsState()
    val isAutoPlay by viewModel.isAutoPlayEnabled.collectAsState()
    val isCloudConnected by viewModel.isCloudConnected.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()

    var apiKeyInput by remember(hasApiKey) { mutableStateOf("") }
    var isApiKeyVisible by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }

    val haptic = LocalHapticFeedback.current

    val dialects = mapOf(
        stringResource(R.string.settings_dialect_de) to "de-DE",
        stringResource(R.string.settings_dialect_at) to "de-AT",
        stringResource(R.string.settings_dialect_ch) to "de-CH"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // Section 1: Cloud Sync & Account
        SettingsSectionHeader(stringResource(R.string.settings_section_account))
        GlassmorphicCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(Spacing.lg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                if (isCloudConnected) TertiaryGreen.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = if (isCloudConnected) TertiaryGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.md))
                    Column {
                        Text(
                            text = stringResource(
                                if (isCloudConnected) R.string.settings_cloud_signed_in
                                else R.string.settings_cloud_title
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.settings_cloud_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isCloudConnected) {
                    IconButton(
                        onClick = { viewModel.performSync() },
                        enabled = !isSyncing,
                        modifier = Modifier.size(40.dp)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.CloudSync,
                                contentDescription = stringResource(R.string.settings_cloud_header),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            if (isCloudConnected) {
                SecondaryActionButton(
                    text = stringResource(R.string.settings_cloud_sign_out),
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.signOut()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                PrimaryActionButton(
                    text = stringResource(R.string.settings_cloud_sign_in),
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Login,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showLoginDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Section 2: AI Configuration
        SettingsSectionHeader(stringResource(R.string.settings_section_ai))
        GlassmorphicCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(Spacing.lg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.settings_api_key_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Surface(
                    color = if (hasApiKey) TertiaryGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = CircleShape
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = if (hasApiKey) Icons.Default.Lock else Icons.Default.Key,
                            contentDescription = null,
                            tint = if (hasApiKey) TertiaryGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (hasApiKey) stringResource(R.string.settings_api_key_active) else stringResource(R.string.settings_api_key_not_set),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (hasApiKey) TertiaryGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        if (hasApiKey) stringResource(R.string.settings_api_key_placeholder_saved)
                        else stringResource(R.string.settings_api_key_placeholder)
                    )
                },
                visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                shape = MaterialTheme.shapes.medium,
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                            Icon(
                                imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isApiKeyVisible) "Hide API Key" else "Show API Key"
                            )
                        }
                        if (apiKeyInput.isNotBlank()) {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.saveApiKey(apiKeyInput)
                                apiKeyInput = ""
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = stringResource(R.string.action_save),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (apiKeyInput.isNotBlank()) {
                        viewModel.saveApiKey(apiKeyInput)
                        apiKeyInput = ""
                    }
                })
            )
        }

        // Section 3: Speech & Audio Settings
        SettingsSectionHeader(stringResource(R.string.settings_section_audio))
        GlassmorphicCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(Spacing.lg)
        ) {
            // Autoplay Audio Switch Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_autoplay),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_autoplay_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isAutoPlay,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.setAutoPlayEnabled(it)
                    }
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(Spacing.md))

            // Dialect Selector
            Text(
                text = stringResource(R.string.settings_dialect_header),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.xs))

            dialects.forEach { (label, code) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .selectable(
                            selected = (selectedDialect == code),
                            role = Role.RadioButton,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.saveDialect(code)
                            }
                        )
                        .padding(vertical = Spacing.xs, horizontal = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (selectedDialect == code),
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selectedDialect == code) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedDialect == code) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Section 4: Learning Progress Overview
        SettingsSectionHeader(stringResource(R.string.settings_section_learning))
        GlassmorphicCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(Spacing.lg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                StatGridItem(Modifier.weight(1f), "VOCABULARY", totalVocab.toString())
                StatGridItem(Modifier.weight(1f), "SESSIONS", totalTranscripts.toString())
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                StatGridItem(Modifier.weight(1f), "XP POINTS", (userStats?.xp ?: 0).toString())
                StatGridItem(Modifier.weight(1f), "STREAK", "${userStats?.streak ?: 0} days")
            }
        }

        // Section 5: Data & Diagnostics
        SettingsSectionHeader(stringResource(R.string.settings_section_data))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SecondaryActionButton(
                text = stringResource(R.string.settings_test_notification),
                icon = {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.testNotification()
                },
                modifier = Modifier.fillMaxWidth()
            )

            DestructiveActionButton(
                text = stringResource(R.string.settings_clear),
                icon = {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showDeleteConfirm = true
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // App Version Footer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        Spacer(modifier = Modifier.height(Spacing.md))
    }

    if (showLoginDialog) {
        LoginDialog(
            onDismiss = { showLoginDialog = false },
            onLogin = { email, pass ->
                viewModel.signIn(email, pass)
                showLoginDialog = false
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.settings_wipe_title), fontWeight = FontWeight.Bold) },
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
}

@Composable
fun LoginDialog(onDismiss: () -> Unit, onLogin: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_cloud_sign_in_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.settings_cloud_email)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.settings_cloud_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = { onLogin(email, password) }) {
                Text(stringResource(R.string.settings_cloud_sign_in))
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = UppercaseLabelTracking,
        modifier = Modifier.padding(start = Spacing.xs, top = Spacing.xs)
    )
}

@Composable
fun StatGridItem(modifier: Modifier, label: String, value: String) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(Spacing.md),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            letterSpacing = UppercaseLabelTracking
        )
    }
}

