#!/usr/bin/env node
// Home agent: runs 24/7 on a computer / Raspberry Pi in the house, plans the day with the same
// model as the app and turns the Switcher on (with an auto-off timer) through switcher_webapi.
//
//   node agent.mjs            run forever
//   node agent.mjs --plan     print today's plan and exit
//
// Env: CONFIG (default ./boiler-config.json, exported from the app), STATE (default ./agent-state.json),
//      SWITCHER_API (overrides switcher.baseUrl, e.g. http://switcher:8000), DRY_RUN=1 (never switch).
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { defaultSettings, planDays, parseOpenMeteo, weatherUrl, dueActions, toMin } from '../js/model.js';

const CONFIG = process.env.CONFIG || './boiler-config.json';
const STATE = process.env.STATE || './agent-state.json';
const DRY = process.env.DRY_RUN === '1';
const log = (...a) => console.log(new Date().toISOString(), ...a);

function loadConfig() {
  const raw = JSON.parse(readFileSync(CONFIG, 'utf8'));
  const settings = { ...defaultSettings(), ...raw.settings };
  const sw = { ...raw.switcher, ...(process.env.SWITCHER_API ? { baseUrl: process.env.SWITCHER_API } : {}) };
  return { settings, sw };
}

const nowIL = () => {
  const d = new Date();
  const date = d.toLocaleDateString('sv-SE', { timeZone: 'Asia/Jerusalem' });
  const t = d.toLocaleTimeString('en-GB', { timeZone: 'Asia/Jerusalem', hour: '2-digit', minute: '2-digit', hour12: false });
  return { date, min: toMin(t) };
};

let weather = null; let weatherAt = 0;
async function getWeather(settings) {
  if (weather && Date.now() - weatherAt < 60 * 60 * 1000) return weather;
  try {
    const res = await fetch(weatherUrl(settings.location));
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    weather = parseOpenMeteo(await res.json()); weatherAt = Date.now();
    log('weather updated');
  } catch (e) {
    log('weather fetch failed:', e.message, weather ? '(using last forecast)' : '');
  }
  return weather;
}

async function switcher(sw, action, body) {
  const q = new URLSearchParams({ type: sw.type || 'v4', id: sw.id, ip: sw.ip });
  if (sw.key) q.set('key', sw.key);
  if (sw.token) q.set('token', sw.token);
  const url = `${sw.baseUrl.replace(/\/$/, '')}/switcher/${action}?${q}`;
  if (DRY) { log('[dry-run]', action, body || ''); return; }
  const res = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body && JSON.stringify(body) });
  if (!res.ok) throw new Error(`${action} failed: HTTP ${res.status} ${await res.text()}`);
}

const done = new Set(existsSync(STATE) ? JSON.parse(readFileSync(STATE, 'utf8')).done : []);

async function tick() {
  const { settings, sw } = loadConfig(); // re-read every tick: a new export takes effect without restart
  const w = await getWeather(settings);
  if (!w) return;
  const { date, min } = nowIL();
  const days = planDays(w, settings, date);
  for (const a of dueActions(days, date, min, done)) {
    try {
      await switcher(sw, 'turn_on', { minutes: a.minutes });
      log(`boiler ON for ${a.minutes} min (${a.session})`);
      done.add(a.key);
      writeFileSync(STATE, JSON.stringify({ done: [...done].filter((k) => k >= date) }));
    } catch (e) {
      log('switcher error:', e.message); // retried next minute while inside the start window
    }
  }
}

if (process.argv.includes('--plan')) {
  const { settings } = loadConfig();
  const w = await getWeather(settings);
  if (!w) process.exit(1);
  for (const d of planDays(w, settings, nowIL().date)) {
    console.log(`${d.date}  cold water ${d.coldTemp}°  total ${d.kwh} kWh`);
    for (const s of d.sessions) console.log(`  ${s.session.padEnd(8)} ${s.minutes ? `${s.start}-${s.end} (${s.minutes} min) → ${s.targetTemp}°` : s.note}`);
  }
} else {
  log(`agent started${DRY ? ' (dry run)' : ''}, config ${CONFIG}`);
  await tick();
  setInterval(() => tick().catch((e) => log('tick error:', e.message)), 60 * 1000);
}
