// Golden vectors for the Kotlin planner port, produced by the reference implementation (boiler/js/model.js).
//   node tools/planner_vectors.mjs > core/src/test/resources/planner_vectors.json
import { defaultSettings, planDays, parseOpenMeteo } from '../../js/model.js';

const dates = ['2026-12-01', '2026-12-02', '2026-12-03', '2026-12-04', '2026-12-05'];
function weather(peakW, baseTemp, flat = false) {
  const time = []; const temperature_2m = []; const shortwave_radiation = []; const cloud_cover = [];
  dates.forEach((d, di) => {
    for (let h = 0; h < 24; h++) {
      time.push(`${d}T${String(h).padStart(2, '0')}:00`);
      temperature_2m.push(flat ? baseTemp : Math.round((baseTemp + di * 0.7 + 4 * Math.sin(((h - 9) / 24) * 2 * Math.PI)) * 10) / 10);
      shortwave_radiation.push(h >= 7 && h <= 17 ? Math.round(peakW * (flat ? 1 : 1 - di * 0.15) * Math.sin(((h - 6) / 12) * Math.PI)) : 0);
      cloud_cover.push((h * 7 + di * 13) % 100);
    }
  });
  return { hourly: { time, temperature_2m, shortwave_radiation, cloud_cover } };
}

const base = defaultSettings();
const cases = [
  { name: 'default sunny', settings: base, weather: weather(520, 13), today: '2026-12-03' },
  { name: 'default cloudy', settings: base, weather: weather(60, 9), today: '2026-12-03' },
  { name: 'no solar, morning showers', settings: { ...base, solar: { ...base.solar, enabled: false },
    members: [...base.members, { id: 'm4', name: 'סבא', kind: 'shower', minutes: 12, session: 'morning' }] },
    weather: weather(300, 12), today: '2026-12-02' },
  { name: 'peak avoid + calibration', settings: { ...base, peak: { avoid: true, start: '17:00', end: '22:00' }, calibration: 1.23 },
    weather: weather(200, 11), today: '2026-12-01' },
  { name: 'small tank warning', settings: { ...base, tank: { ...base.tank, liters: 60, heaterKw: 1.8 },
    members: Array.from({ length: 5 }, (_, i) => ({ id: `x${i}`, name: `x${i}`, kind: 'shower', minutes: 9, session: 'evening' })) },
    weather: weather(100, 10), today: '2026-12-03' },
  { name: 'tiny need → 10 min minimum', settings: { ...base, members: [{ id: 'a', name: 'a', kind: 'shower', minutes: 3, session: 'evening' }] },
    weather: weather(450, 15, true), today: '2026-12-03' },
  { name: 'sun covers everything', settings: { ...base, members: [{ id: 'a', name: 'a', kind: 'shower', minutes: 3, session: 'evening' }] },
    weather: weather(520, 15, true), today: '2026-12-03' },
];

// same normalisation the app does before planning
const out = cases.map((c) => ({ ...c, plan: planDays(parseOpenMeteo(c.weather), c.settings, c.today) }));
console.log(JSON.stringify(out, null, 1));
