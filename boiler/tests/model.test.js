import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  defaultSettings, coldWaterTemp, solarGain, planSession, planDays, applyFeedback, dueActions, parseOpenMeteo, toMin,
} from '../js/model.js';

// Synthetic weather: `days` dates, sunny bell curve peaking at `peakW` W/m² at noon.
function fakeWeather(dates, peakW, temp = 14) {
  const time = []; const t = []; const r = []; const c = [];
  for (const d of dates) {
    for (let h = 0; h < 24; h++) {
      time.push(`${d}T${String(h).padStart(2, '0')}:00`);
      t.push(temp);
      r.push(h >= 7 && h <= 17 ? Math.round(peakW * Math.sin(((h - 6) / 12) * Math.PI)) : 0);
      c.push(20);
    }
  }
  return parseOpenMeteo({ hourly: { time, temperature_2m: t, shortwave_radiation: r, cloud_cover: c } });
}

test('cold water follows the weekly air temperature, within limits', () => {
  assert.equal(coldWaterTemp([12, 14, 13]), 15);
  assert.equal(coldWaterTemp([2, 3]), 12);
  assert.equal(coldWaterTemp([]), 16);
});

test('solar gain counts only sunshine before the session', () => {
  const s = defaultSettings();
  const w = fakeWeather(['2026-12-01'], 500);
  const all = solarGain(w.hourly, '2026-12-01', toMin('20:00'), s);
  const morning = solarGain(w.hourly, '2026-12-01', toMin('10:00'), s);
  assert.ok(all > morning && morning > 0);
  s.solar.enabled = false;
  assert.equal(solarGain(w.hourly, '2026-12-01', toMin('20:00'), s), 0);
});

test('cloudy day needs more heating than a sunny day', () => {
  const s = defaultSettings();
  const sunny = planSession({ date: '2026-12-01', session: 'evening', weather: fakeWeather(['2026-12-01'], 450), settings: s, coldTemp: 16 });
  const cloudy = planSession({ date: '2026-12-01', session: 'evening', weather: fakeWeather(['2026-12-01'], 60), settings: s, coldTemp: 16 });
  assert.ok(cloudy.minutes > sunny.minutes, `${cloudy.minutes} > ${sunny.minutes}`);
  assert.ok(cloudy.minutes % 5 === 0);
  assert.equal(cloudy.end, '19:50');
});

test('very sunny day needs no electric heating', () => {
  const s = defaultSettings();
  s.members = [{ id: 'a', name: 'א', kind: 'shower', minutes: 6, session: 'evening' }];
  const p = planSession({ date: '2026-12-01', session: 'evening', weather: fakeWeather(['2026-12-01'], 700), settings: s, coldTemp: 16 });
  assert.equal(p.minutes, 0);
  assert.equal(p.start, null);
});

test('more people means more heating, too many triggers a warning', () => {
  const s = defaultSettings();
  const w = fakeWeather(['2026-12-01'], 0);
  const few = planSession({ date: '2026-12-01', session: 'evening', weather: w, settings: s, coldTemp: 15 });
  s.members = Array.from({ length: 7 }, (_, i) => ({ id: `x${i}`, name: `${i}`, kind: 'shower', minutes: 10, session: 'evening' }));
  const many = planSession({ date: '2026-12-01', session: 'evening', weather: w, settings: s, coldTemp: 15 });
  assert.ok(many.minutes > few.minutes);
  assert.equal(many.targetTemp, s.tank.maxTemp);
  assert.equal(many.warnings.length, 1);
});

test('avoid-peak finishes heating before the expensive hours', () => {
  const s = defaultSettings();
  s.peak = { avoid: true, start: '17:00', end: '22:00' };
  const p = planSession({ date: '2026-12-01', session: 'evening', weather: fakeWeather(['2026-12-01'], 0), settings: s, coldTemp: 15 });
  assert.equal(p.end, '17:00');
  s.peak.avoid = false;
  const q = planSession({ date: '2026-12-01', session: 'evening', weather: fakeWeather(['2026-12-01'], 0), settings: s, coldTemp: 15 });
  assert.ok(p.kwh >= q.kwh, 'waiting costs standby losses');
});

test('session with nobody needs nothing', () => {
  const s = defaultSettings();
  const p = planSession({ date: '2026-12-01', session: 'morning', weather: fakeWeather(['2026-12-01'], 0), settings: s, coldTemp: 15 });
  assert.equal(p.minutes, 0);
});

test('planDays plans from today on, using past days for cold water', () => {
  const w = fakeWeather(['2026-11-30', '2026-12-01', '2026-12-02', '2026-12-03'], 300, 10);
  const days = planDays(w, defaultSettings(), '2026-12-01');
  assert.deepEqual(days.map((d) => d.date), ['2026-12-01', '2026-12-02', '2026-12-03']);
  assert.equal(days[0].coldTemp, 12);
  assert.equal(days[0].sessions.length, 2);
});

test('feedback calibrates within limits', () => {
  assert.equal(applyFeedback(1, 'cold'), 1.15);
  assert.equal(applyFeedback(1, 'hot'), 0.9);
  assert.equal(applyFeedback(1.8, 'cold'), 1.8);
});

test('agent triggers each session once, inside the window', () => {
  const days = [{ date: '2026-12-01', sessions: [{ date: '2026-12-01', session: 'evening', minutes: 40, startMin: 1150 }] }];
  const done = new Set();
  assert.equal(dueActions(days, '2026-12-01', 1149, done).length, 0);
  const a = dueActions(days, '2026-12-01', 1152, done);
  assert.deepEqual(a, [{ key: '2026-12-01:evening', minutes: 40, session: 'evening' }]);
  done.add(a[0].key);
  assert.equal(dueActions(days, '2026-12-01', 1153, done).length, 0);
});
