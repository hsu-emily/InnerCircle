# InnerCircle insights server

The app never contains an OpenAI key. This small Cloudflare Worker holds the company key, and the app
asks *it* for AI insights. If the key were inside the app, anyone could extract it from the APK and
spend your money.

```
app  --(survey + usage write-up, anonymous install id)-->  Worker  --(company key)-->  OpenAI
```

## What it protects

| Risk | What stops it |
| --- | --- |
| Key stolen from the app | The key is a Worker **secret**; it is not in the app, the repo, or any file. |
| Someone using your URL as a free ChatGPT | The model, system prompt, `max_tokens` (800) and output format are fixed **here**. The app sends only text, capped at 6,000 characters. |
| Runaway spending | Daily limits (UTC): **10 per install, 40 per IP, 1,500 overall** (`LIMITS` in `src/worker.js`). |
| Key or user data in logs | Nothing from a request is logged, and responses never contain the key. Covered by tests. |

## Deploy (about 10 minutes)

You need a free Cloudflare account and Node 18+.

```bash
cd server/insights-worker
npx wrangler login

# 1. Create the counter store, then paste the id it prints into wrangler.toml (kv_namespaces.id)
npx wrangler kv namespace create LIMITS

# 2. Store the company key as a secret. Paste it when asked. It is never written to a file.
npx wrangler secret put OPENAI_API_KEY

# 3. Deploy. It prints your URL, like https://innercircle-insights.<you>.workers.dev
npx wrangler deploy
```

Then put the URL in the app, in `app/.../ai/AiConfig.kt`:

```kotlin
const val INSIGHT_ENDPOINT = "https://innercircle-insights.<you>.workers.dev/v1/insight"
```

It is not a secret, so committing it is fine. Rebuild the app.

## Do this in the OpenAI dashboard too

1. Create a **separate project** just for InnerCircle and make the key from it (not your personal key).
2. Set a **monthly budget limit** and an alert. This is the real hard stop; the Worker limits are a first line.
3. Rotate any time: `npx wrangler secret put OPENAI_API_KEY` with the new key, then delete the old key in OpenAI. The app needs no update.

## Try it on your computer first (free, no key)

```bash
npm test           # 19 tests
npm run dev        # http://localhost:8787 with a stand-in for OpenAI
```

**Debug builds already point at this server** (`AiConfig.kt` uses `http://10.0.2.2:8787/v1/insight`, the
emulator's address for your computer), so with the server running there is nothing to configure. Debug
builds allow plain HTTP to that address only; release builds require HTTPS. Physical phones can't reach
10.0.2.2, so they need the deployed Worker.

`npm run dev:real` talks to OpenAI for real, using the key in `.dev.vars` (see below). Keep that terminal
open while you use the app; if it isn't running, Statistics says it couldn't reach the insights service.

### What `.dev.vars` is for

`.dev.vars` is a file with your key in it (`OPENAI_API_KEY=sk-...`) that **only the local test server
on your computer reads**. It is git-ignored, never goes into the app, and is never uploaded anywhere.
That means putting your key there does **not** make the app talk to OpenAI by itself. It only lets
`USE_REAL_OPENAI=1 npm run dev` do so, and the app only reaches that server if `INSIGHT_ENDPOINT` points
at it (the emulator's address for your computer, above). For a phone, or anyone else, the Worker has to
be deployed (previous section): there the key is stored with `wrangler secret put`, not in this file.

## Known limits, and the upgrade path

- **Anyone can make up install ids.** The per-IP and overall daily limits bound the damage, but for a
  public release add app attestation: have the Worker verify a **Play Integrity** token (or
  **Firebase App Check**) so only genuine installs of your app are served.
- KV counters aren't atomic, so a burst of simultaneous calls can overshoot a limit slightly. Use a
  Durable Object or Cloudflare's Rate Limiting binding if you need exact caps.
- Free-text survey notes and usage totals are sent to OpenAI. Tell your users, and check OpenAI's
  current API data-usage terms for your account.

## Contract with the app

`POST /v1/insight`, header `X-Install-Id: <uuid>`, body `{"prompt": "<text>"}`
→ `200 {"content": "<the model's plain-text answer: a Usage Summary: section then a Wellness Recommendations: section>"}`
→ errors: `{"error": "<code>", "scope"?: "install|ip|global"}` with 400/401/413/415/429/502/503/504.
