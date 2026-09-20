/**
 * InnerCircle insights proxy.
 *
 * The app never sees the OpenAI key. It sends the survey answers and usage totals it has already
 * written up as text, and this Worker adds the instructions, calls OpenAI with the company key, and
 * hands back the model's text. Because anyone who learns the URL could try to use it, it does not
 * trust the caller: the model, token limit and system prompt are fixed here, the input size is
 * capped, and usage is limited per install, per IP and overall, per day.
 *
 * Nothing the caller sends is logged, and the key is only ever read from `env.OPENAI_API_KEY`.
 */

/**
 * What the model is told to do. Lives here, not in the app, so a caller can't replace it.
 *
 * PLAYGROUND_PROMPT is the prompt written and tuned in the OpenAI Playground, kept word for word.
 * APP_INSTRUCTIONS is appended after it and takes precedence, because a few things in the Playground
 * version don't fit an app card (it says "weekly" but the app shows a day, week or month; it asks the
 * user for missing data but there is no chat; it uses markdown but the card is plain text).
 */
export const PLAYGROUND_PROMPT = `Summarize user screen time data from the InnerCircle app and provide personalized wellness and mindfulness recommendations to encourage mindful device use and reduced screen dependency.

Read the user's weekly total screen time and time-of-day usage input, including session start and end times for each day. First, analyze and summarize usage patterns: identify when the user most often uses the app, highlight peak usage times, and mention total time spent. Clearly state any consistent late-night or high-frequency usage patterns. After providing this analysis, recommend one or more wellness and mindfulness alternatives that address the user's individual usage patterns. Wellness interventions should be actionable (such as suggesting breaks at observed peak times, guided mindfulness activities, screen-free routines before bed, or outdoor alternatives), relevant, and supportive of healthier digital habits.

Always conduct a step-by-step reasoning process:  
**First:** analyze the input data in detail (calculate totals, identify trends and trigger points, mention potential concerns such as late-night usage if present, etc.).  
**Then:** based on this reasoning, generate 1-3 concise, specific recommendations targeted to the user’s habits.  
**Conclusion/results (summary and recommendations) must always appear last. Do not provide recommendations before the screen time analysis.**

**Format your response as follows:**  
- **Usage Summary:** [A concise summary of screen time statistics and patterns, including total time, common usage times, and any notable trends or concerns.]  
- **Wellness Recommendations:** [A bulleted or numbered list of 1-3 tailored, actionable wellness/mindfulness suggestions directly addressing the user’s usage habits.]

**Sample Example 1:**  
**Input:**  
- Total weekly screen time: 16 hours  
- Session times: Mon: 8-9am, 9-10pm; Tue: 8:30-9:15am, 10-10:45pm; ...  
**Response:**  
Usage Summary:  
You spent a total of 16 hours on InnerCircle this week, commonly logging in during early mornings and late nights, with the longest average session occurring around bedtime. Consistent late-night usage is observed on several days.  
Wellness Recommendations:  
1. Try a wind-down routine 30 minutes before bed without device use, such as gentle stretching or reading.  
2. Set a morning intention before opening the app to bring awareness to device use.  
3. Explore a guided mindfulness meditation when late-night screen use is tempting.

**(For actual examples, use inputs representative of real app usage data with at least 3-5 sessions and detailed patterns; outputs should reflect observed behaviors in detail.)**

If the input is incomplete, politely request missing data before response.  
Be concise (1-2 short paragraphs per section).  
Do not use code blocks.  
If feedback is requested, always follow the summary and recommendations with a brief optional improvement note.

---

**REMINDER:**  
Carefully analyze and summarize the user's app usage before suggesting wellness interventions. Recommendations should ALWAYS come after the reasoning/summary. Use the specified response format.`;

export const APP_INSTRUCTIONS = `

---

HOW THIS APP USES YOUR OUTPUT. These points take precedence over anything above:

1. The input covers ONE period: a day, a week or a month, stated at the top of the input. It is not always a week. Describe it as given ("today", "this week", "this month", or the dates shown).
2. The input lists, for each day, the time-of-day windows in which the app was used, with minutes in each. Treat each window as a session. Exact start and end times are not available; use the windows.
3. The input may include the user's own goals, hardest times of day, triggers and daily limit from their survey. Tie your recommendations to these whenever they are relevant.
4. There is no chat: you cannot ask the user for anything. If the data is thin, say so briefly in the Usage Summary and keep the recommendations general. Never ask for more data.
5. Do the step-by-step reasoning privately. Your visible reply must be ONLY these two sections, in exactly this plain-text form:

Usage Summary:
<one or two short paragraphs>

Wellness Recommendations:
1. <one recommendation>
2. <one recommendation>
3. <one recommendation>

Use no markdown at all (no asterisks, no #, no bullet symbols), no code blocks, and no extra sections, notes or sign-offs. Give 1 to 3 recommendations.
6. Use only the numbers in the input. Do not invent data. Do not give medical advice.`;

export const SYSTEM_PROMPT = PLAYGROUND_PROMPT + APP_INSTRUCTIONS;

export const MAX_PROMPT_CHARS = 6000;
export const MAX_OUTPUT_TOKENS = 800;
export const UPSTREAM_TIMEOUT_MS = 45_000;

/** Per-day ceilings (UTC days). Tune these to your budget. */
export const LIMITS = { perInstall: 10, perIp: 40, global: 1500 };

const INSTALL_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function json(status, body, extra = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...extra },
  });
}

function secondsUntilUtcMidnight(now) {
  const next = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1);
  return Math.max(1, Math.ceil((next - now.getTime()) / 1000));
}

async function count(kv, key) {
  return parseInt((await kv.get(key)) ?? '0', 10) || 0;
}

export async function handle(request, env, now = new Date()) {
  const url = new URL(request.url);
  if (url.pathname !== '/v1/insight') return json(404, { error: 'not_found' });
  if (request.method !== 'POST') return json(405, { error: 'method_not_allowed' }, { Allow: 'POST' });

  if (!env.OPENAI_API_KEY) return json(500, { error: 'not_configured' });

  const installId = request.headers.get('X-Install-Id') ?? '';
  if (!INSTALL_ID.test(installId)) return json(401, { error: 'bad_install_id' });

  if (!(request.headers.get('Content-Type') ?? '').toLowerCase().includes('application/json')) {
    return json(415, { error: 'unsupported_media_type' });
  }
  let body;
  try {
    body = await request.json();
  } catch {
    return json(400, { error: 'bad_json' });
  }
  const prompt = typeof body?.prompt === 'string' ? body.prompt.trim() : '';
  if (!prompt) return json(400, { error: 'empty_prompt' });
  if (prompt.length > MAX_PROMPT_CHARS) return json(413, { error: 'prompt_too_large' });

  // Usage limits. KV has no atomic increment, so a burst of parallel calls can overshoot a little;
  // that is fine for a spending guard (the hard stop is the budget set in the OpenAI dashboard).
  const day = now.toISOString().slice(0, 10);
  const ip = request.headers.get('CF-Connecting-IP') ?? 'unknown';
  const buckets = [
    ['global', `g:${day}`, LIMITS.global],
    ['install', `i:${day}:${installId}`, LIMITS.perInstall],
    ['ip', `p:${day}:${ip}`, LIMITS.perIp],
  ];
  for (const [scope, key, max] of buckets) {
    if ((await count(env.LIMITS, key)) >= max) {
      return json(429, { error: 'rate_limited', scope }, { 'Retry-After': String(secondsUntilUtcMidnight(now)) });
    }
  }
  await Promise.all(buckets.map(async ([, key]) => env.LIMITS.put(key, String((await count(env.LIMITS, key)) + 1), { expirationTtl: 172_800 })));

  let upstream;
  try {
    upstream = await fetch(`${env.OPENAI_BASE_URL ?? 'https://api.openai.com'}/v1/chat/completions`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${env.OPENAI_API_KEY}`, 'Content-Type': 'application/json' },
      // Everything that costs money is decided here, never by the caller.
      body: JSON.stringify({
        model: env.OPENAI_MODEL || 'gpt-4o-mini',
        temperature: 0.6,
        max_tokens: MAX_OUTPUT_TOKENS,
        messages: [
          { role: 'system', content: SYSTEM_PROMPT },
          { role: 'user', content: prompt },
        ],
      }),
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
  } catch {
    return json(504, { error: 'upstream_unreachable' });
  }

  // Our own key being rejected or out of quota is our problem, not the user's: don't pass details on.
  if (upstream.status === 401 || upstream.status === 403) return json(502, { error: 'service_unavailable' });
  if (upstream.status === 429) return json(503, { error: 'busy' }, { 'Retry-After': '30' });
  if (!upstream.ok) return json(502, { error: 'upstream_error' });

  let content;
  try {
    content = (await upstream.json())?.choices?.[0]?.message?.content;
  } catch {
    content = undefined;
  }
  if (typeof content !== 'string' || !content.trim()) return json(502, { error: 'bad_upstream_response' });
  return json(200, { content });
}

export default {
  fetch: (request, env) => handle(request, env),
};
