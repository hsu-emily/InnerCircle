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

        // Live platform(s): a full-width row.
        SocialApp.entries.filter { it.enabled }.forEach { app ->
            AppCell(app, Modifier.fillMaxWidth(), onClick = { onOpenApp(app) })
        }

        // Everything else is shown, greyed out, two to a row.
        SocialApp.entries.filter { !it.enabled }.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { app -> AppCell(app, Modifier.weight(1f), onClick = null) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(24.dp))
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
