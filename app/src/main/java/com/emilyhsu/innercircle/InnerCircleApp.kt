package com.emilyhsu.innercircle

import android.app.Application
import android.content.Context
import com.emilyhsu.innercircle.ai.AiConfig
import com.emilyhsu.innercircle.ai.InsightClient
import com.emilyhsu.innercircle.data.ProfileRepository
import com.emilyhsu.innercircle.data.SettingsRepository
import com.emilyhsu.innercircle.data.UsageRepository
import com.emilyhsu.innercircle.tracking.UsageTracker

/** Hand-rolled dependency container: small enough that a DI framework would only add noise. */
class AppContainer(context: Context) {
    val profile = ProfileRepository(context)
    val usage = UsageRepository(context)
    val settings = SettingsRepository(context)
    val tracker = UsageTracker(usage)
    val insights = InsightClient(endpoint = { AiConfig.INSIGHT_ENDPOINT }, installId = { settings.installId() })
}

class InnerCircleApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

val Context.container: AppContainer
    get() = (applicationContext as InnerCircleApp).container
