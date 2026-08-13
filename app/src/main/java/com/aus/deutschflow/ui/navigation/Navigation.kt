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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.aus.deutschflow.R
import com.aus.deutschflow.ui.theme.AzureGlow
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

    // No background gradient at all. A tinted wash behind every screen bands badly on
    // a dark ground and competes with the surfaces in front of it. Depth is carried
    // entirely by the surface/surfaceContainer elevation ramp from here on.

    Scaffold(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(currentScreen.title),
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
                                navigateToTab(navController, Screen.Transcript)
                            }
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                actions = {
                    if (!isOnSettings) {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            navigateToDetail(navController, Screen.Settings)
                        }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.nav_settings),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        bottomBar = {
            if (!useNavigationRail) {
                // A solid container with a hairline above it. At 80% alpha over a
                // near-black ground the bar had no edge at all, so the five
                // destinations looked like they were floating on the content.
                // Column, because Scaffold measures this slot as a single layout and
                // two siblings would be drawn on top of each other.
                Column {
                HorizontalDivider(
                    thickness = Dp.Hairline,
                    color = AzureGlow.copy(alpha = 0.15f)
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp
                ) {
                    navItems.forEach { screen ->
                        val isSelected = currentRoute == screen.route
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = stringResource(screen.title)) },
                            label = {
                                Text(
                                    text = stringResource(screen.title),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            },
                            // Always: with labels only on the selected item the bar
                            // was four unexplained grey glyphs and one word, and the
                            // row jumped sideways as the label appeared and vanished.
                            alwaysShowLabel = true,
                            selected = isSelected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navigateToTab(navController, screen)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
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
                            icon = { Icon(screen.icon, contentDescription = stringResource(screen.title)) },
                            label = {
                                Text(
                                    text = stringResource(screen.title),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            selected = isSelected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navigateToTab(navController, screen)
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

/**
 * Switches between the five tab destinations, keeping one of them on the stack.
 */
private fun navigateToTab(navController: NavHostController, screen: Screen) {
    navController.navigate(screen.route) {
        popUpTo(navController.graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Pushes a non-tab destination on top of wherever the user already is.
 *
 * Settings used to go through [navigateToTab], which popped the current tab before
 * pushing - so opening Settings from History and pressing back landed on Transcript.
 */
private fun navigateToDetail(navController: NavHostController, screen: Screen) {
    navController.navigate(screen.route) {
        launchSingleTop = true
    }
}
