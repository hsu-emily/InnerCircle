package com.emilyhsu.innercircle.ui.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.emilyhsu.innercircle.ui.components.InnerCircleLogo
import com.emilyhsu.innercircle.ui.theme.Ic
import kotlinx.coroutines.delay

private const val SPLASH_MS = 1200L

@Composable
fun SplashScreen(onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        delay(SPLASH_MS)
        done()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Ic.Splash),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            InnerCircleLogo(size = 140.dp)
            Spacer(Modifier.height(20.dp))
            Text("InnerCircle", style = MaterialTheme.typography.headlineLarge, color = Ic.Ink)
        }
    }
}
