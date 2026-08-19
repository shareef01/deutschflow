package com.aus.deutschflow.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aus.deutschflow.BuildConfig
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val totalVocab by viewModel.totalVocabulary.collectAsState()
    val totalTranscripts by viewModel.totalTranscripts.collectAsState()
    val userStats by viewModel.userStats.collectAsState()
    val hasApiKey by viewModel.hasApiKey.collectAsState()
    val selectedDialect by viewModel.selectedDialect.collectAsState()
    val isAutoPlay by viewModel.isAutoPlayEnabled.collectAsState()
    val isCloudConnected by viewModel.isCloudConnected.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val message by viewModel.message.collectAsState()
    
    var apiKeyInput by remember(hasApiKey) { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }
    
    val haptic = LocalHapticFeedback.current
    
    val dialects = mapOf(
        "Germany (de-DE)" to "de-DE",
        "Austria (de-AT)" to "de-AT",
        "Switzerland (de-CH)" to "de-CH"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Section: Cloud Sync (The Bridge)
        SettingsHeader("CLOUD SYNCHRONIZATION")
        
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = if (isCloudConnected) Color(0xFF30D158) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(
                                    if (isCloudConnected) R.string.settings_cloud_signed_in
                                    else R.string.settings_cloud_title
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                // One line, whatever the sign-in state: there is no backend behind
                                // any of it, so nothing here may imply a backup.
                                text = stringResource(R.string.settings_cloud_unavailable),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    if (isCloudConnected) {
                        IconButton(onClick = { viewModel.performSync() }, enabled = !isSyncing) {
                            if (isSyncing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(
                                Icons.Default.CloudSync,
                                contentDescription = stringResource(R.string.settings_cloud_header)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isCloudConnected) viewModel.signOut() else showLoginDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCloudConnected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(if (isCloudConnected) Icons.Default.Logout else Icons.Default.Login, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (isCloudConnected) R.string.settings_cloud_sign_out
                            else R.string.settings_cloud_sign_in
                        )
                    )
                }
            }
        }

        // Section: AI Configuration
        SettingsHeader("INTELLIGENCE CONFIGURATION")
        
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_api_key_label)) },
            placeholder = { Text(if (hasApiKey) "Saved (Enter new to replace)" else "Paste your key here") },
            shape = RoundedCornerShape(16.dp),
            trailingIcon = {
                IconButton(onClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.saveApiKey(apiKeyInput)
                    apiKeyInput = ""
                }) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = stringResource(R.string.action_save),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            },
            singleLine = true
        )

        // Section: Learning Stats
        SettingsHeader("LEARNING PROGRESS")
        
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
            elevation = CardDefaults.outlinedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatGridItem(Modifier.weight(1f), "VOCABULARY", totalVocab.toString())
                    StatGridItem(Modifier.weight(1f), "SESSIONS", totalTranscripts.toString())
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatGridItem(Modifier.weight(1f), "XP POINTS", (userStats?.xp ?: 0).toString())
                    StatGridItem(Modifier.weight(1f), "STREAK", "${userStats?.streak ?: 0} days")
                }
            }
        }

        // Section: Audio Preferences
        SettingsHeader("AUDIO PREFERENCES")
        
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.03f)),
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
        SettingsHeader("RECOGNITION DIALECT")
        
        dialects.forEach { (label, code) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { 
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.saveDialect(code) 
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = (selectedDialect == code),
                    onClick = { 
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.saveDialect(code) 
                    }
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        // Section: Actions
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), 
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FilledTonalButton(
                onClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.testNotification() 
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.settings_test_notification),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
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
                Text(
                    stringResource(R.string.settings_clear),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Text(
            // From BuildConfig, so the footer cannot drift from what was built.
            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 32.dp)
        )
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
}

@Composable
fun LoginDialog(onDismiss: () -> Unit, onLogin: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_cloud_sign_in_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text(stringResource(R.string.settings_cloud_email)) })
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(stringResource(R.string.settings_cloud_password)) })
            }
        },
        confirmButton = {
            Button(onClick = { onLogin(email, password) }) { Text(stringResource(R.string.settings_cloud_sign_in)) }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
fun SettingsHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Black,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 12.dp, start = 4.dp)
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
            style = MaterialTheme.typography.displaySmall, 
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary
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
