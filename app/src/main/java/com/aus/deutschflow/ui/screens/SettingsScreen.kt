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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aus.deutschflow.BuildConfig
import com.aus.deutschflow.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val totalVocab by viewModel.totalVocabulary.collectAsState()
    val totalTranscripts by viewModel.totalTranscripts.collectAsState()
    val userStats by viewModel.userStats.collectAsState()
    val geminiApiKey by viewModel.geminiApiKey.collectAsState()
    val selectedDialect by viewModel.selectedDialect.collectAsState()
    val isAutoPlay by viewModel.isAutoPlayEnabled.collectAsState()
    val message by viewModel.message.collectAsState()

    var apiKeyInput by remember(geminiApiKey) { mutableStateOf(geminiApiKey) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
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
        // Section: AI Configuration
        SettingsHeader("Intelligence Configuration")
        
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Gemini API Key") },
            placeholder = { Text("Paste your Google AI key here") },
            shape = RoundedCornerShape(16.dp),
            trailingIcon = {
                IconButton(onClick = { 
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.saveGeminiApiKey(apiKeyInput) 
                }) {
                    Icon(Icons.Default.Save, contentDescription = "Save", tint = MaterialTheme.colorScheme.primary)
                }
            },
            singleLine = true
        )
        Text(
            text = "Required for automatic vocabulary extraction and translations.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp)
        )

        // Section: Learning Progress
        SettingsHeader("Learning Progress")
        
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
            elevation = CardDefaults.outlinedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatGridItem(Modifier.weight(1f), "VOCABULARY", totalVocab.toString())
                    StatGridItem(Modifier.weight(1f), "SESSIONS", totalTranscripts.toString())
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatGridItem(Modifier.weight(1f), "XP POINTS", (userStats?.xp ?: 0).toString())
                    StatGridItem(Modifier.weight(1f), "STREAK", "${userStats?.streak ?: 0} days")
                }
            }
        }

        // Section: Audio Preferences
        SettingsHeader("Audio Preferences")
        
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
                    text = "Auto-play German Audio", 
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
        SettingsHeader("Recognition Dialect")
        
        dialects.forEach { (label, code) ->
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
                    text = label,
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
                    viewModel.testNotification() 
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                // Mandatory Title Case override
                Text("Test Daily Notification", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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
                Text("Clear All Progress", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
            }
        }

        Text(
            // Read from the build rather than typed here, where it drifted to
            // 1.2.0 while the build said 1.0.
            text = "DeutschFlow v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp)) // Mandatory bottom padding
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Wipe All Progress?", fontWeight = FontWeight.Black) },
            text = { Text("This will permanently delete your library, history, and earnings. This action is final.") },
            confirmButton = {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.clearAllProgress()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Keep Progress")
                }
            }
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissMessage() },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissMessage() }) {
                    Text("OK")
                }
            }
        )
    }
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
