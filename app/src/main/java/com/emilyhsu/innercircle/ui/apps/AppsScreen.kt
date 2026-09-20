package com.emilyhsu.innercircle.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.emilyhsu.innercircle.data.SocialApp
import com.emilyhsu.innercircle.ui.components.AppTile
import com.emilyhsu.innercircle.ui.components.InnerCircleLogo
import com.emilyhsu.innercircle.ui.components.TileSize
import com.emilyhsu.innercircle.ui.theme.Ic

@Composable
fun AppsScreen(onOpenApp: (SocialApp) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ic.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(72.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            InnerCircleLogo(size = 36.dp)
            Spacer(Modifier.size(12.dp))
            Text("InnerCircle", style = MaterialTheme.typography.titleLarge, color = Ic.Ink)
        }

        Spacer(Modifier.height(28.dp))
        Text("Disconnect from the noise.", style = MaterialTheme.typography.headlineMedium, color = Ic.Ink)
        Spacer(Modifier.height(32.dp))

        // Live platforms first, then the ones that aren't built yet (faded). Both are laid out the same
        // way: two to a row, and an odd one out sits alone in its own row.
        AppGrid(SocialApp.entries.filter { it.enabled }, onOpenApp)
        AppGrid(SocialApp.entries.filter { !it.enabled }, onOpenApp = null)
        Spacer(Modifier.height(24.dp))
    }
}

/** [apps] two to a row; a lone last app takes the left cell of its own row. Not tappable without [onOpenApp]. */
@Composable
private fun AppGrid(apps: List<SocialApp>, onOpenApp: ((SocialApp) -> Unit)?) {
    apps.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            row.forEach { app ->
                AppCell(app, Modifier.weight(1f), onClick = onOpenApp?.let { open -> { open(app) } })
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun AppCell(app: SocialApp, modifier: Modifier, onClick: (() -> Unit)?) {
    Column(
        modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Spacer(Modifier.height(20.dp))
        AppTile(app, TileSize)
        Spacer(Modifier.height(10.dp))
        Text(
            app.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = if (app.enabled) Ic.Ink else Ic.DisabledText,
        )
        Spacer(Modifier.height(4.dp))
    }
}
