// Runs the Worker's real request handler on your computer, so the app can be tried before deploying.
//
//   node dev-server.mjs                       -> fake AI answers, no key needed, costs nothing
//   USE_REAL_OPENAI=1 node dev-server.mjs    -> talks to OpenAI for real, key read from .dev.vars
//
// The Android emulator reaches this machine at http://10.0.2.2:8787
import http from 'node:http';
import { existsSync, readFileSync } from 'node:fs';
import { handle } from './src/worker.js';
import { parseDevVars } from './dev-vars.mjs';

// Pick up secrets from .dev.vars (git-ignored) so the key never has to go in your shell history.
const devVarsPath = new URL('./.dev.vars', import.meta.url);
if (existsSync(devVarsPath)) {
  for (const [key, value] of Object.entries(parseDevVars(readFileSync(devVarsPath, 'utf8')))) {
    if (value && !(key in process.env)) process.env[key] = value;
  }
}

const PORT = Number(process.env.PORT ?? 8787);
const FAKE_PORT = PORT + 1;
const useReal = process.env.USE_REAL_OPENAI === '1';
if (useReal && !process.env.OPENAI_API_KEY) { console.error('USE_REAL_OPENAI=1 needs OPENAI_API_KEY: paste it into server/insights-worker/.dev.vars and save'); process.exit(1); }

// Dev-only diagnostics, both off by default. They never print the API key.
//   DEV_LOG_BODIES=1  prints each prompt and the model's reply (only use this with sample data)
const logBodies = process.env.DEV_LOG_BODIES === '1';
const upstreamFetch = globalThis.fetch;
globalThis.fetch = async (...args) => {
  const res = await upstreamFetch(...args);
  if (String(args[0]).includes('openai.com')) console.log(`  upstream OpenAI -> ${res.status}`); // status only
  return res;
};

const store = new Map();
const env = {
  OPENAI_API_KEY: process.env.OPENAI_API_KEY ?? 'dev-fake-key',
  OPENAI_MODEL: process.env.OPENAI_MODEL ?? 'gpt-4o-mini',
  OPENAI_BASE_URL: useReal ? undefined : `http://127.0.0.1:${FAKE_PORT}`,
  LIMITS: { get: async (k) => store.get(k) ?? null, put: async (k, v) => void store.set(k, v) },
};

if (!useReal) {
  http.createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', () => {
      const asked = JSON.parse(raw).messages.at(-1).content;
      const content = [
        'Usage Summary:',
        `(Local test answer, not from OpenAI.) The server received ${asked.length} characters of your survey and usage data. This text is a stand-in so you can see the whole flow before deploying.`,
        '',
        'Wellness Recommendations:',
        '1. Pick one wind-down time and keep the phone out of reach afterwards.',
        '2. Set a short intention before opening the app.',
        '3. Try a two-minute breathing break when you notice the urge to scroll.',
      ].join('\n');
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ choices: [{ message: { content } }] }));
    });
  }).listen(FAKE_PORT, '127.0.0.1');
}

http.createServer(async (req, res) => {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const body = chunks.length ? Buffer.concat(chunks) : undefined;
  const headers = { ...req.headers, 'cf-connecting-ip': req.socket.remoteAddress };
  const response = await handle(new Request(`http://${req.headers.host}${req.url}`, { method: req.method, headers, body: req.method === 'GET' || req.method === 'HEAD' ? undefined : body }), env);
  const payload = Buffer.from(await response.arrayBuffer());
  res.writeHead(response.status, Object.fromEntries(response.headers));
  res.end(payload);
  console.log(`${req.method} ${req.url} -> ${response.status}`); // never the body, never the key
  if (logBodies && body && response.status === 200) {
    console.log('----- prompt sent -----\n' + JSON.parse(body.toString()).prompt);
    console.log('----- model reply -----\n' + JSON.parse(payload.toString()).content + '\n-----------------------');
  }
}).listen(PORT, '0.0.0.0', () => console.log(`insights proxy on :${PORT} (${useReal ? 'REAL OpenAI' : 'fake AI, no key needed'})`));
