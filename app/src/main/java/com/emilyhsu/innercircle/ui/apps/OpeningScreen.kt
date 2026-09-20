package com.emilyhsu.innercircle.ui.apps

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.emilyhsu.innercircle.data.SocialApp
import com.emilyhsu.innercircle.ui.components.AppTile
import com.emilyhsu.innercircle.ui.components.IcProgressBar
import com.emilyhsu.innercircle.ui.components.InnerCircleLogo
import com.emilyhsu.innercircle.ui.theme.Ic
import kotlinx.coroutines.delay

private const val OPENING_MS = 1300

/** The "InnerCircle → platform" hand-off shown for a moment before the app's WebView appears. */
@Composable
fun OpeningScreen(app: SocialApp, onReady: () -> Unit) {
    val ready by rememberUpdatedState(onReady)
    var target by remember { mutableFloatStateOf(0f) }
    val progress by animateFloatAsState(target, tween(OPENING_MS, easing = LinearEasing), label = "opening")

    LaunchedEffect(Unit) {
        target = 1f
        delay(OPENING_MS + 150L)
        ready()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ic.Background)
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            InnerCircleLogo(size = 64.dp)
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = Ic.Ink)
            AppTile(app, 64.dp)
        }
        Spacer(Modifier.height(28.dp))
        Text("Opening ${app.displayName}", style = MaterialTheme.typography.headlineSmall, color = Ic.Ink)
        Spacer(Modifier.height(28.dp))
        IcProgressBar(progress, Modifier.fillMaxWidth())
    }
}
