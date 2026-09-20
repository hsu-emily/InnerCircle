package com.emilyhsu.innercircle.ai

import com.emilyhsu.innercircle.BuildConfig

/** Where the app gets its AI insights. */
object AiConfig {
    /**
     * Debug builds talk to the test server on your computer (`npm run dev:real` in server/insights-worker).
     * 10.0.2.2 is how the Android *emulator* reaches your computer; a physical phone can't use it, so
     * on a phone use a release build with [DEPLOYED_ENDPOINT] filled in. Debug builds allow plain HTTP
     * to this address only (src/debug/res/xml/network_security_config.xml).
     */
    private const val LOCAL_DEV_ENDPOINT = "http://10.0.2.2:8787/v1/insight"

    /**
     * The URL of the deployed insights proxy: `https://<your-worker>.workers.dev/v1/insight`. Release
     * builds use this.
     *
     * It is not a secret. The OpenAI key lives only on that server (see server/insights-worker), so
     * there is no key in the app to steal. Leave it empty until you have deployed; the app then says
     * AI insights aren't set up yet instead of failing.
     */
    private const val DEPLOYED_ENDPOINT = ""

    val INSIGHT_ENDPOINT: String = if (BuildConfig.DEBUG) LOCAL_DEV_ENDPOINT else DEPLOYED_ENDPOINT
}
