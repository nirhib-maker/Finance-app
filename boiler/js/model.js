// Boiler physics + daily heating planner. Pure functions, shared by the PWA and the home agent.

export const KWH_PER_L_DEG = 4.186 / 3600; // energy to heat 1 liter of water by 1°C (kWh)
export const SHOWER_TEMP = 40;              // comfortable mixed water temperature at the tap
export const MIN_USABLE_TEMP = 45;          // below this the tank cannot supply a pleasant shower

export const defaultSettings = () => ({
  location: { name: 'תל אביב', lat: 32.08, lon: 34.78 },
  tank: { liters: 150, heaterKw: 2.5, maxTemp: 65, lossWPerDeg: 2 },
  solar: { enabled: true, areaM2: 4, efficiency: 0.35 },
  showerFlowLpm: 8,
  bathLiters: 80,
  sessions: { morning: '06:30', evening: '20:00' },
  readyBufferMin: 10,
  pricePerKwh: 0.64,
  peak: { avoid: false, start: '17:00', end: '22:00' },
  calibration: 1,
  members: [
    { id: 'm1', name: 'אבא', kind: 'shower', minutes: 8, session: 'evening' },
    { id: 'm2', name: 'אמא', kind: 'shower', minutes: 10, session: 'evening' },
    { id: 'm3', name: 'ילד/ה', kind: 'bath', minutes: 0, session: 'evening' },
  ],
});

export const toMin = (hhmm) => { const [h, m] = hhmm.split(':').map(Number); return h * 60 + m; };
export const fmtTime = (min) => {
  const m = ((Math.round(min) % 1440) + 1440) % 1440;
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
};
const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));
const round = (x, d = 1) => Math.round(x * 10 ** d) / 10 ** d;

/** Liters of 40°C water a member uses per day. */
export function memberLiters(m, s) {
  return m.kind === 'bath' ? s.bathLiters : s.showerFlowLpm * (m.minutes || 0);
}

/** Mains water temperature estimate: follows the mean air temperature of the past week. */
export function coldWaterTemp(meanAirTemps) {
  const vals = meanAirTemps.filter((v) => Number.isFinite(v));
  if (!vals.length) return 16;
  const avg = vals.reduce((a, b) => a + b, 0) / vals.length;
  return round(clamp(avg + 2, 12, 26));
}

/**
 * Solar energy (kWh) collected on `date` between sunrise and `untilMin`.
 * hourly: [{ time: 'YYYY-MM-DDTHH:MM', temp, radiation (W/m², mean of the preceding hour) }]
 */
export function solarGain(hourly, date, untilMin, s) {
  if (!s.solar.enabled) return 0;
  let wh = 0;
  for (const h of hourly) {
    if (!h.time.startsWith(date)) continue;
    const end = toMin(h.time.slice(11, 16));          // the value covers [end-60, end)
    const covered = clamp(untilMin - (end - 60), 0, 60) / 60;
    wh += (h.radiation || 0) * covered;
  }
  return round((wh / 1000) * s.solar.areaM2 * s.solar.efficiency, 2);
}

/**
 * Plan one shower session.
 * Returns how hot the tank must be, how much the sun already supplies and when/for how long to heat.
 */
export function planSession({ date, session, weather, settings: s, coldTemp }) {
  const users = s.members.filter((m) => m.session === session);
  const sessionMin = toMin(s.sessions[session]);
  const base = { date, session, sessionTime: s.sessions[session], users: users.map((u) => u.name) };
  if (!users.length) return { ...base, minutes: 0, kwh: 0, cost: 0, note: 'אין מקלחות במועד זה' };

  const V = s.tank.liters;
  const usable = 0.85; // part of the tank energy that really reaches the tap (mixing/stratification)
  const liters = users.reduce((a, m) => a + memberLiters(m, s), 0);
  const demandKwh = liters * (SHOWER_TEMP - coldTemp) * KWH_PER_L_DEG;

  let targetTemp = coldTemp + demandKwh / (V * KWH_PER_L_DEG * usable);
  targetTemp = Math.max(targetTemp, MIN_USABLE_TEMP);
  const warnings = [];
  if (targetTemp > s.tank.maxTemp) {
    warnings.push(`הדוד (${V} ל׳) קטן מדי לכל המקלחות ברצף – כדאי לפצל או לחמם שוב באמצע`);
    targetTemp = s.tank.maxTemp;
  }

  // The sun heats the tank up to the session (evening only: morning sessions start before sunrise).
  const sunKwh = session === 'evening' ? solarGain(weather.hourly, date, sessionMin, s) : 0;
  const sunTemp = Math.min(coldTemp + sunKwh / (V * KWH_PER_L_DEG), s.tank.maxTemp + 10);

  // Heating window: end shortly before the session, or before the peak tariff if asked to.
  let endMin = sessionMin - s.readyBufferMin;
  const needKwh = Math.max(0, V * KWH_PER_L_DEG * (targetTemp - sunTemp));
  let minutes = needKwh > 0 ? (needKwh / (s.tank.heaterKw * 0.98)) * 60 : 0;
  const peakS = toMin(s.peak.start); const peakE = toMin(s.peak.end);
  if (s.peak.avoid && minutes > 0 && endMin > peakS && endMin - minutes < peakE && sessionMin > peakS) {
    endMin = peakS; // finish before the expensive hours, pay a little standby loss
  }
  const idleHours = Math.max(0, (sessionMin - endMin) / 60);
  const lossKwh = needKwh > 0 ? (s.tank.lossWPerDeg / 1000) * (targetTemp - 20) * idleHours : 0;

  let kwh = (needKwh + lossKwh) * (s.calibration || 1);
  minutes = kwh > 0 ? Math.ceil(((kwh / (s.tank.heaterKw * 0.98)) * 60) / 5) * 5 : 0;
  if (minutes > 0 && minutes < 10) minutes = 10;
  kwh = round((minutes / 60) * s.tank.heaterKw, 2);

  return {
    ...base,
    liters: Math.round(liters),
    coldTemp,
    targetTemp: Math.round(targetTemp),
    sunKwh,
    sunTemp: Math.round(sunTemp),
    demandKwh: round(demandKwh, 2),
    minutes,
    start: minutes ? fmtTime(endMin - minutes) : null,
    end: minutes ? fmtTime(endMin) : null,
    startMin: minutes ? endMin - minutes : null,
    kwh,
    cost: round(kwh * s.pricePerKwh, 2),
    warnings,
    note: minutes ? '' : 'השמש מספיקה – אין צורך להדליק את הדוד ☀️',
  };
}

/** Normalize an Open-Meteo response into { hourly: [...], days: [...] }. */
export function parseOpenMeteo(json) {
  const h = json.hourly;
  const hourly = h.time.map((t, i) => ({
    time: t, temp: h.temperature_2m[i], radiation: h.shortwave_radiation[i], cloud: h.cloud_cover?.[i],
  }));
  const byDate = new Map();
  for (const x of hourly) {
    const d = x.time.slice(0, 10);
    if (!byDate.has(d)) byDate.set(d, []);
    byDate.get(d).push(x);
  }
  const days = [...byDate].map(([date, xs]) => ({
    date,
    meanTemp: round(xs.reduce((a, x) => a + x.temp, 0) / xs.length),
    maxTemp: Math.max(...xs.map((x) => x.temp)),
    minTemp: Math.min(...xs.map((x) => x.temp)),
    radiationKwhM2: round(xs.reduce((a, x) => a + (x.radiation || 0), 0) / 1000, 2),
    cloud: xs[0].cloud == null ? null : Math.round(xs.reduce((a, x) => a + (x.cloud || 0), 0) / xs.length),
  }));
  return { hourly, days };
}

export const weatherUrl = ({ lat, lon }) =>
  `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}` +
  '&hourly=temperature_2m,shortwave_radiation,cloud_cover&timezone=Asia%2FJerusalem&past_days=7&forecast_days=3';

/** Plan every session of `dates` (default: today and the next two days). */
export function planDays(weather, settings, today) {
  const past = weather.days.filter((d) => d.date < today).slice(-7).map((d) => d.meanTemp);
  const coldTemp = coldWaterTemp(past.length ? past : weather.days.map((d) => d.meanTemp));
  return weather.days.filter((d) => d.date >= today).slice(0, 3).map((day) => {
    const sessions = ['morning', 'evening'].map((session) =>
      planSession({ date: day.date, session, weather, settings, coldTemp }));
    const kwh = round(sessions.reduce((a, x) => a + x.kwh, 0), 2);
    return { ...day, coldTemp, sessions, kwh, cost: round(kwh * settings.pricePerKwh, 2) };
  });
}

/** Feedback after a shower ("cold" / "ok" / "hot") nudges the calibration factor. */
export function applyFeedback(calibration, feedback) {
  const f = { cold: 1.15, ok: 1, hot: 0.9 }[feedback] ?? 1;
  return round(clamp((calibration || 1) * f, 0.6, 1.8), 2);
}

/**
 * Agent helper: sessions whose heating should start now and were not triggered yet.
 * nowDate 'YYYY-MM-DD', nowMin minutes since midnight, done: Set of keys.
 */
export function dueActions(days, nowDate, nowMin, done, graceMin = 15) {
  const out = [];
  for (const d of days) {
    if (d.date !== nowDate) continue;
    for (const s of d.sessions) {
      if (!s.minutes) continue;
      const key = `${s.date}:${s.session}`;
      if (done.has(key)) continue;
      if (nowMin >= s.startMin && nowMin < s.startMin + graceMin) {
        // Starting late? still finish on time when possible.
        out.push({ key, minutes: s.minutes, session: s.session });
      }
    }
  }
  return out;
}
