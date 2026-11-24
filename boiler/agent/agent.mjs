#!/usr/bin/env node
// Home agent: runs 24/7 on a computer / Raspberry Pi in the house, next to the Switcher.
//  • plans the day with the same model as the app and turns the Switcher on (with an auto-off timer)
//  • reads the Switcher's power every minute while heating, measures the real heater power and
//    learns from thermostat cut-offs (automatic calibration)
//  • serves the app on the home network (http://<this-machine>:8080) with a small API, so the
//    phone shows the live state and the same plan the agent runs
//
//   node agent.mjs            run forever
//   node agent.mjs --plan     print today's plan and exit
//
// Env: CONFIG (default ./boiler-config.json), STATE (default ./agent-state.json), PORT (8080, 0 = no web),
//      SWITCHER_API (overrides switcher.baseUrl, e.g. http://127.0.0.1:8000), DRY_RUN=1 (never switch),
//      TICK_SECONDS (60; how often to plan and read the switcher).
import { readFileSync, writeFileSync, existsSync, renameSync } from 'node:fs';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { join, normalize, extname } from 'node:path';
import {
  defaultSettings, planDays, parseOpenMeteo, weatherUrl, dueActions, toMin,
  effectiveSettings, learnFromRun, parseSwitcherState,
} from '../js/model.js';

const CONFIG = process.env.CONFIG || './boiler-config.json';
const STATE = process.env.STATE || './agent-state.json';
const PORT = Number(process.env.PORT ?? 8080);
const DRY = process.env.DRY_RUN === '1';
const TICK_MS = Number(process.env.TICK_SECONDS || 60) * 1000;
const APP_DIR = fileURLToPath(new URL('..', import.meta.url));
const log = (...a) => console.log(new Date().toISOString(), ...a);

// ---------- config & state ----------
function loadConfig() {
  const raw = existsSync(CONFIG) ? JSON.parse(readFileSync(CONFIG, 'utf8')) : {};
  const settings = { ...defaultSettings(), ...raw.settings };
  const sw = { ...raw.switcher, ...(process.env.SWITCHER_API ? { baseUrl: process.env.SWITCHER_API } : {}) };
  return { raw, settings, sw };
}
function writeJson(path, data) { writeFileSync(`${path}.tmp`, JSON.stringify(data, null, 2)); renameSync(`${path}.tmp`, path); }

const state = {
  done: [], learn: { autoFactor: 1, heaterKw: null }, runs: [], current: null,
  ...(existsSync(STATE) ? JSON.parse(readFileSync(STATE, 'utf8')) : {}),
};
const saveState = () => writeJson(STATE, state);
let live = null; // last switcher reading { on, watts, timeLeft, at }

const nowIL = () => {
  const d = new Date();
  const date = d.toLocaleDateString('sv-SE', { timeZone: 'Asia/Jerusalem' });
  const t = d.toLocaleTimeString('en-GB', { timeZone: 'Asia/Jerusalem', hour: '2-digit', minute: '2-digit', hour12: false });
  return { date, min: toMin(t) };
};

let weather = null; let weatherAt = 0;
async function getWeather(settings) {
  if (weather && Date.now() - weatherAt < 60 * 60 * 1000 && weather.loc === settings.location.name) return weather;
  try {
    const res = await fetch(weatherUrl(settings.location));
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    weather = { ...parseOpenMeteo(await res.json()), loc: settings.location.name }; weatherAt = Date.now();
    log('weather updated');
  } catch (e) {
    log('weather fetch failed:', e.message, weather ? '(using last forecast)' : '');
  }
  return weather;
}

// ---------- switcher ----------
async function switcher(sw, action, body) {
  if (!sw.baseUrl || !sw.id || !sw.ip) throw new Error('switcher not configured');
  const q = new URLSearchParams({ type: sw.type || 'v4', id: sw.id, ip: sw.ip });
  if (sw.key) q.set('key', sw.key);
  if (sw.token) q.set('token', sw.token);
  const url = `${sw.baseUrl.replace(/\/$/, '')}/switcher/${action}?${q}`;
  if (DRY && action !== 'get_state') { log('[dry-run]', action, body ? JSON.stringify(body) : ''); return {}; }
  const res = await fetch(url, action === 'get_state' ? {} : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body && JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${action} failed: HTTP ${res.status} ${await res.text()}`);
  return res.json().catch(() => ({}));
}

async function turnOn(sw, minutes, plan, source) {
  await switcher(sw, 'turn_on', { minutes });
  state.current = {
    plan, source, plannedMin: minutes, startedAt: Date.now(), lastAt: Date.now(),
    energyWh: 0, heatWattsSum: 0, heatSamples: 0, cutoffMin: null,
  };
  saveState();
  log(`boiler ON for ${minutes} min (${source})`);
}

// ---------- power monitoring & learning ----------
async function monitor(sw, settings) {
  let reading;
  try { reading = parseSwitcherState(await switcher(sw, 'get_state')); } catch (e) {
    if (state.current) log('get_state failed:', e.message);
    return;
  }
  live = { ...reading, at: Date.now() };
  const c = state.current;
  if (!c) return;
  const now = Date.now();
  const dtH = Math.min(now - c.lastAt, 5 * 60 * 1000) / 3600000; // ignore gaps (agent was down)
  c.energyWh += reading.watts * dtH;
  c.lastAt = now;
  const elapsedMin = (now - c.startedAt) / 60000;
  if (reading.watts > 300) { c.heatWattsSum += reading.watts; c.heatSamples++; c.lowSince = null; }
  else if (reading.on && c.heatSamples > 0 && c.cutoffMin == null) {
    // two low readings in a row (not a single glitch) while the switch is on = the thermostat cut the power
    if (c.lowSince == null) c.lowSince = elapsedMin;
    else {
      c.cutoffMin = Math.round(c.lowSince);
      log(`thermostat cut-off after ${c.cutoffMin} min`);
    }
  }
  // OFF once we saw it heating (or after 1.5 min, in case it never drew power), or long past the timer
  const finished = (!reading.on && (c.heatSamples > 0 || elapsedMin > 1.5)) || elapsedMin > c.plannedMin + 10;
  if (finished) finishRun(settings);
  else saveState();
}

function finishRun(settings) {
  const c = state.current;
  state.current = null;
  if (!c.plan || c.source === 'manual') { saveState(); return; } // manual runs: no plan to compare with
  const run = {
    plan: c.plan, energyKwh: c.energyWh / 1000,
    avgKw: c.heatSamples ? c.heatWattsSum / c.heatSamples / 1000 : 0, cutoff: c.cutoffMin != null,
  };
  const { learn, reason } = learnFromRun(state.learn, run, settings);
  state.learn = learn;
  state.runs = [{
    date: c.plan.date, session: c.plan.session, plannedMin: c.plannedMin, cutoffMin: c.cutoffMin,
    kwh: Math.round(run.energyKwh * 100) / 100, avgKw: Math.round(run.avgKw * 100) / 100,
    autoFactor: learn.autoFactor, reason,
  }, ...state.runs].slice(0, 30);
  saveState();
  log(`run finished: ${state.runs[0].kwh} kWh, ${reason} → autoFactor ${learn.autoFactor}`);
}

// ---------- main loop ----------
let lastPlan = null;
async function tick() {
  const { settings, sw } = loadConfig();
  const eff = effectiveSettings(settings, state.learn);
  await monitor(sw, settings);
  const w = await getWeather(settings);
  if (!w) return;
  const { date, min } = nowIL();
  lastPlan = planDays(w, eff, date);
  const done = new Set(state.done);
  for (const a of dueActions(lastPlan, date, min, done)) {
    if (state.current) break; // already heating
    const plan = lastPlan[0].sessions.find((s) => s.session === a.session);
    try {
      await turnOn(sw, a.minutes, plan, a.session);
      state.done = [...done.add(a.key)].filter((k) => k >= date);
      saveState();
    } catch (e) {
      log('switcher error:', e.message); // retried next minute while inside the start window
    }
  }
}

// ---------- web: the app + API on the home network ----------
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.png': 'image/png' };
const send = (res, code, body, type = 'application/json') => {
  res.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
};
const readBody = (req) => new Promise((ok, fail) => {
  let b = ''; req.on('data', (c) => { b += c; if (b.length > 1e5) req.destroy(); });
  req.on('end', () => { try { ok(JSON.parse(b || '{}')); } catch (e) { fail(e); } });
});

async function api(req, res, path) {
  const { raw, settings, sw } = loadConfig();
  if (req.method === 'GET' && path === '/api/status') {
    const c = state.current;
    return send(res, 200, {
      settings, learn: state.learn, runs: state.runs, plan: lastPlan,
      switcherConfigured: Boolean(sw.baseUrl && sw.id && sw.ip),
      switcher: { type: sw.type, ip: sw.ip, id: sw.id }, // the key is never sent
      live, current: c && { source: c.source, plannedMin: c.plannedMin, startedAt: c.startedAt,
        kwh: Math.round(c.energyWh) / 1000, cutoffMin: c.cutoffMin },
      dryRun: DRY,
    });
  }
  if (req.method === 'PUT' && path === '/api/settings') {
    const body = await readBody(req);
    const { switcher: newSw, ...newSettings } = body;
    const swMerged = { ...raw.switcher, ...Object.fromEntries(Object.entries(newSw || {}).filter(([, v]) => v !== '' && v != null)) };
    writeJson(CONFIG, { ...raw, settings: { ...newSettings, switcher: undefined }, switcher: swMerged });
    tick().catch(() => {});
    return send(res, 200, { ok: true });
  }
  if (req.method === 'POST' && path === '/api/switch') {
    const { action, minutes } = await readBody(req);
    try {
      if (action === 'on') await turnOn(sw, Math.min(240, Math.max(1, Number(minutes) || 30)), null, 'manual');
      else { await switcher(sw, 'turn_off'); if (state.current) finishRun(settings); }
      return send(res, 200, { ok: true });
    } catch (e) { return send(res, 502, { error: e.message }); }
  }
  if (req.method === 'POST' && path === '/api/reset-learning') {
    state.learn = { autoFactor: 1, heaterKw: null }; state.runs = []; saveState();
    return send(res, 200, { ok: true });
  }
  return send(res, 404, { error: 'not found' });
}

function serve() {
  createServer(async (req, res) => {
    try {
      const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
      if (path.startsWith('/api/')) return await api(req, res, path);
      const rel = normalize(path === '/' ? '/index.html' : path);
      // only the app itself: never the agent folder (config holds the switcher key), tests or dotfiles
      if (rel.includes('..') || /^\/(agent|tests)\//.test(rel) || /\/\./.test(rel)) return send(res, 404, 'not found', 'text/plain');
      return send(res, 200, readFileSync(join(APP_DIR, rel)), TYPES[extname(rel)] || 'application/octet-stream');
    } catch (e) {
      return send(res, e.code === 'ENOENT' ? 404 : 500, e.code === 'ENOENT' ? 'not found' : e.message, 'text/plain');
    }
  }).listen(PORT, () => log(`app + API on http://0.0.0.0:${PORT}`));
}

if (process.argv.includes('--plan')) {
  const { settings } = loadConfig();
  const w = await getWeather(settings);
  if (!w) process.exit(1);
  console.log(`learned: heater ${state.learn.heaterKw ?? '-'} kW, auto factor ${state.learn.autoFactor}`);
  for (const d of planDays(w, effectiveSettings(settings, state.learn), nowIL().date)) {
    console.log(`${d.date}  cold water ${d.coldTemp}°  total ${d.kwh} kWh`);
    for (const s of d.sessions) console.log(`  ${s.session.padEnd(8)} ${s.minutes ? `${s.start}-${s.end} (${s.minutes} min) → ${s.targetTemp}°` : s.note}`);
  }
} else {
  log(`agent started${DRY ? ' (dry run)' : ''}, config ${CONFIG}`);
  if (PORT) serve();
  await tick().catch((e) => log('tick error:', e.message));
  setInterval(() => tick().catch((e) => log('tick error:', e.message)), TICK_MS);
}
