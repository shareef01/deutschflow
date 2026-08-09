package com.aus.deutschflow.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.aus.deutschflow.ui.screens.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigation(
    navController: NavHostController,
    windowSizeClass: WindowSizeClass
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val useNavigationRail = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
    val haptic = LocalHapticFeedback.current

    val currentScreen = when (currentRoute) {
        Screen.Transcript.route -> Screen.Transcript
        Screen.History.route -> Screen.History
        Screen.Vocabulary.route -> Screen.Vocabulary
        Screen.Study.route -> Screen.Study
        Screen.Practice.route -> Screen.Practice
        Screen.Settings.route -> Screen.Settings
        else -> Screen.Transcript
    }
    val isOnSettings = currentRoute == Screen.Settings.route

    val backgroundBrush = Brush.radialGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            MaterialTheme.colorScheme.background
        ),
        radius = 900f
    )

    Scaffold(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = currentScreen.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    // Settings is not in the nav items, so without this it is a dead
                    // end on any layout where the destination list is not on screen.
                    if (isOnSettings) {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (!navController.popBackStack()) {
                                navigate(navController, Screen.Transcript)
                            }
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                actions = {
                    if (!isOnSettings) {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            navigate(navController, Screen.Settings)
                        }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        bottomBar = {
            if (!useNavigationRail) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    tonalElevation = 0.dp
                ) {
                    navItems.forEach { screen ->
                        val isSelected = currentRoute == screen.route
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = {
                                if (isSelected) {
                                    Text(
                                        text = screen.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            alwaysShowLabel = false,
                            selected = isSelected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navigate(navController, screen)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
                .padding(innerPadding)
        ) {
            // Above compact width there is no bottom bar, so the rail is the only
            // way to reach the other destinations - including on a phone in
            // landscape, which reports an expanded width.
            if (useNavigationRail) {
                NavigationRail(
                    containerColor = Color.Transparent,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    navItems.forEach { screen ->
                        val isSelected = currentRoute == screen.route
                        NavigationRailItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = {
                                Text(
                                    text = screen.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            selected = isSelected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navigate(navController, screen)
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                            )
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            NavHost(
                navController = navController,
                startDestination = Screen.Transcript.route,
                modifier = Modifier.fillMaxSize(),
                enterTransition = {
                    fadeIn(animationSpec = tween(400)) + scaleIn(initialScale = 0.98f)
                },
                exitTransition = {
                    fadeOut(animationSpec = tween(300))
                }
            ) {
                composable(Screen.Transcript.route) { TranscriptScreen(hiltViewModel()) }
                composable(Screen.History.route) { HistoryScreen(hiltViewModel()) }
                composable(Screen.Vocabulary.route) { VocabularyScreen(windowSizeClass, hiltViewModel()) }
                composable(Screen.Study.route) { StudyScreen(hiltViewModel()) }
                composable(Screen.Practice.route) { PracticeScreen(hiltViewModel()) }
                composable(Screen.Settings.route) { SettingsScreen(hiltViewModel()) }
            }
        }
    }
}

private fun navigate(navController: NavHostController, screen: Screen) {
    navController.navigate(screen.route) {
        popUpTo(navController.graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
