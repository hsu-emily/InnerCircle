package com.emilyhsu.innercircle.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import com.emilyhsu.innercircle.ui.web.exitSwipe
import com.emilyhsu.innercircle.ui.web.SwipeExitHint
import com.emilyhsu.innercircle.ui.web.ExitButtonBounds
import com.emilyhsu.innercircle.ui.web.FloatingExitButton
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Alignment
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.emilyhsu.innercircle.container
import com.emilyhsu.innercircle.data.SocialApp
import com.emilyhsu.innercircle.ui.apps.AppsScreen
import com.emilyhsu.innercircle.ui.apps.OpeningScreen
import com.emilyhsu.innercircle.ui.settings.SettingsScreen
import com.emilyhsu.innercircle.ui.splash.SplashScreen
import com.emilyhsu.innercircle.ui.stats.StatsScreen
import com.emilyhsu.innercircle.ui.stats.StatsViewModel
import com.emilyhsu.innercircle.ui.survey.SurveyScreen
import com.emilyhsu.innercircle.ui.survey.SurveyViewModel
import com.emilyhsu.innercircle.ui.theme.Ic
import com.emilyhsu.innercircle.webview.InstagramWebView
import kotlinx.coroutines.delay

private object Routes {
    const val SPLASH = "splash"
    const val SURVEY = "survey"
    const val APPS = "apps"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val OPENING = "opening/{id}"
    const val WEB = "web/{id}"

    fun opening(app: SocialApp) = "opening/${app.id}"
    fun web(app: SocialApp) = "web/${app.id}"
}

private enum class Tab(val route: String, val label: String) {
    Apps(Routes.APPS, "Apps"),
    Stats(Routes.STATS, "Statistics"),
    Settings(Routes.SETTINGS, "Settings"),
}

@Composable
fun InnerCircleRoot() {
    val context = LocalContext.current
    val container = context.container
    val activity = remember(context) { context.findActivity() }
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val showBar = route in Tab.entries.map { it.route }

    Scaffold(
        containerColor = Ic.Background,
        bottomBar = { if (showBar) BottomBar(nav, route) },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = Routes.SPLASH,
            // The splash draws edge to edge; everything else respects the system bars / bottom bar.
            modifier = Modifier.padding(if (route == Routes.SPLASH) PaddingValues(0.dp) else inner),
        ) {
            composable(Routes.SPLASH) {
                SplashScreen(onDone = {
                    val next = if (container.profile.profile.value.completed) Routes.APPS else Routes.SURVEY
                    nav.navigate(next) { popUpTo(Routes.SPLASH) { inclusive = true } }
                })
            }

            composable(Routes.SURVEY) {
                val vm: SurveyViewModel = viewModel(factory = viewModelFactory { initializer { SurveyViewModel(container.profile) } })
                SurveyScreen(
                    viewModel = vm,
                    onFinished = { nav.navigate(Routes.APPS) { popUpTo(Routes.SURVEY) { inclusive = true } } },
                    onExit = {
                        // A retake from Settings goes back there. On the first run there is nothing to go
                        // back to, and Back must not become a way to skip the survey, so it leaves the app.
                        if (container.profile.profile.value.completed && nav.previousBackStackEntry != null) {
                            nav.popBackStack()
                        } else {
                            activity?.finish()
                        }
                    },
                )
            }

            composable(Routes.APPS) {
                AppsScreen(onOpenApp = { app -> nav.navigate(Routes.opening(app)) })
            }

            composable(Routes.STATS) {
                val vm: StatsViewModel = viewModel(factory = viewModelFactory { initializer { StatsViewModel(container) } })
                StatsScreen(vm)
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(container, onRetakeSurvey = { nav.navigate(Routes.SURVEY) })
            }

            composable(Routes.OPENING, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val app = SocialApp.fromId(entry.arguments?.getString("id")) ?: return@composable
                OpeningScreen(app, onReady = {
                    nav.navigate(Routes.web(app)) { popUpTo(Routes.OPENING) { inclusive = true } }
                })
            }

            composable(Routes.WEB, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val app = SocialApp.fromId(entry.arguments?.getString("id")) ?: return@composable
                AppWebScreen(app, onLeave = {
                    if (!nav.popBackStack()) nav.navigate(Routes.APPS) { popUpTo(Routes.WEB) { inclusive = true } }
                })
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun NavHostController.navigateToTab(route: String) = navigate(route) {
    popUpTo(Routes.APPS) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun BottomBar(nav: NavHostController, current: String?) {
    Column {
        HorizontalDivider(color = Ic.Divider)
        NavigationBar(containerColor = Ic.Background, tonalElevation = 0.dp) {
            Tab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = current == tab.route,
                    onClick = { if (current != tab.route) nav.navigateToTab(tab.route) },
                    icon = { TabIcon(tab, if (current == tab.route) Ic.Ink else Ic.Muted) },
                    label = { Text(tab.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Ic.Ink,
                        selectedTextColor = Ic.Ink,
                        unselectedIconColor = Ic.Muted,
                        unselectedTextColor = Ic.Muted,
                        indicatorColor = Ic.Chip,
                    ),
                )
            }
        }
    }
}

@Composable
private fun TabIcon(tab: Tab, tint: Color) {
    when (tab) {
        Tab.Apps -> GridIcon(tint)
        Tab.Stats -> BarsIcon(tint)
        Tab.Settings -> Icon(Icons.Outlined.Settings, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** 2 x 2 rounded squares. */
@Composable
private fun GridIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val gap = size.width * 0.14f
        val cell = (size.width - gap) / 2f
        for (row in 0..1) for (col in 0..1) {
            drawRoundRect(
                color,
                topLeft = Offset(col * (cell + gap), row * (cell + gap)),
                size = Size(cell, cell),
                cornerRadius = CornerRadius(cell * 0.3f),
            )
        }
    }
}

/** Three bars of rising height. */
@Composable
private fun BarsIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width * 0.24f
        val heights = listOf(0.45f, 1f, 0.7f)
        heights.forEachIndexed { i, h ->
            val bh = size.height * h
            drawRoundRect(
                color,
                topLeft = Offset(i * (w + size.width * 0.14f), size.height - bh),
                size = Size(w, bh),
                cornerRadius = CornerRadius(w * 0.35f),
            )
        }
    }
}

/**
 * A connected app full-screen. Time on screen is measured here: the session starts when this
 * screen is resumed and ends when it's paused or left, so backgrounding the phone stops the clock.
 *
 * Getting back to InnerCircle is user-configurable (Settings): a draggable floating button, a
 * swipe-right from the left side, or both. The system Back gesture always still works.
 */
@Composable
private fun AppWebScreen(app: SocialApp, onLeave: () -> Unit) {
    val container = LocalContext.current.container
    val tracker = container.tracker
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val exitMethod by container.settings.exitMethod.collectAsState()
    val haptics = LocalHapticFeedback.current
    var swipeProgress by remember { mutableFloatStateOf(0f) }
    val buttonBounds = remember { ExitButtonBounds() }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> tracker.startSession(app)
                Lifecycle.Event.ON_PAUSE -> tracker.endSession()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            tracker.endSession()
        }
    }
    // Commit time every few seconds so a kill or crash loses almost nothing.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            tracker.tick()
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Ic.Background)
            .exitSwipe(
                enabled = exitMethod.allowsSwipe,
                onProgress = { swipeProgress = it },
                onExit = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLeave()
                },
                ignoreStartsOn = buttonBounds,
            ),
    ) {
        InstagramWebView(
            modifier = Modifier.fillMaxSize(),
            startUrl = app.startUrl ?: return@BoxWithConstraints,
            onPageMessage = { tracker.onPageMessage(app, it) },
        )
        if (exitMethod.allowsSwipe && swipeProgress > 0f) {
            SwipeExitHint(swipeProgress, Modifier.align(Alignment.CenterStart))
        }
        if (exitMethod.showsButton) {
            FloatingExitButton(
                areaWidthPx = constraints.maxWidth.toFloat(),
                areaHeightPx = constraints.maxHeight.toFloat(),
                savedPosition = container.settings::buttonPosition,
                onPositionSaved = container.settings::setButtonPosition,
                onExit = onLeave,
                bounds = buttonBounds,
            )
        }
    }
}
