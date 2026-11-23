import { defaultSettings, planDays, parseOpenMeteo, weatherUrl, applyFeedback, toMin, memberLiters } from './model.js';

const KEY = 'boiler-settings';
const WKEY = 'boiler-weather';
const CITIES = [
  ['תל אביב', 32.08, 34.78], ['ירושלים', 31.77, 35.21], ['חיפה', 32.79, 34.99], ['באר שבע', 31.25, 34.79],
  ['ראשון לציון', 31.97, 34.79], ['נתניה', 32.33, 34.86], ['אשדוד', 31.80, 34.65], ['צפת', 32.96, 35.50],
  ['טבריה', 32.79, 35.53], ['אילת', 29.56, 34.95],
];
const SESSION_NAME = { morning: 'בוקר', evening: 'ערב' };
const DAY = new Intl.DateTimeFormat('he-IL', { weekday: 'long', day: 'numeric', month: 'numeric', timeZone: 'UTC' });

const load = (k, fallback) => { try { return JSON.parse(localStorage.getItem(k)) ?? fallback; } catch { return fallback; } };
const save = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* private mode */ } };

let settings = { ...defaultSettings(), ...load(KEY, {}) };
let weather = load(WKEY, null);
let tab = 'today';
const view = document.getElementById('view');

const nowIL = () => {
  const d = new Date();
  const date = d.toLocaleDateString('sv-SE', { timeZone: 'Asia/Jerusalem' });
  const t = d.toLocaleTimeString('en-GB', { timeZone: 'Asia/Jerusalem', hour: '2-digit', minute: '2-digit', hour12: false });
  return { date, min: toMin(t) };
};
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
const dayName = (date) => DAY.format(new Date(`${date}T12:00:00Z`));
function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg; t.classList.add('show');
  clearTimeout(toast.timer); toast.timer = setTimeout(() => t.classList.remove('show'), 2600);
}
function persist() { save(KEY, settings); render(); }

async function refreshWeather(quiet) {
  try {
    const res = await fetch(weatherUrl(settings.location));
    if (!res.ok) throw new Error(res.status);
    weather = { ...parseOpenMeteo(await res.json()), fetched: Date.now(), loc: settings.location.name };
    save(WKEY, weather);
    if (!quiet) toast('מזג האוויר עודכן');
  } catch {
    toast(weather ? 'אין חיבור – מוצגת התחזית האחרונה' : 'לא הצלחתי להביא תחזית מזג אוויר');
  }
  render();
}

function plans() {
  if (!weather) return null;
  return planDays(weather, settings, nowIL().date);
}

// ---------- Screens ----------
function sessionCard(s) {
  if (!s.users.length) return '';
  const sunPct = s.targetTemp ? Math.min(100, Math.round(((s.sunTemp - s.coldTemp) / Math.max(1, s.targetTemp - s.coldTemp)) * 100)) : 0;
  return `<div class="card ${s.minutes ? '' : 'sunny'}">
    <h2>${s.session === 'morning' ? '🌅' : '🌙'} מקלחות ${SESSION_NAME[s.session]} · ${s.sessionTime}</h2>
    <div class="muted">${s.users.map(esc).join(', ')} · כ-${s.liters} ליטר מים ב-40°</div>
    ${s.minutes ? `<div class="row"><span>להדליק</span><b><bdi dir="ltr">${s.start}–${s.end}</bdi> (${s.minutes} דק׳)</b></div>` : `<p>${s.note}</p>`}
    <div class="row"><span>טמפ׳ יעד בדוד</span><b>${s.targetTemp}°</b></div>
    ${settings.solar.enabled && s.session === 'evening' ? `<div class="row"><span>השמש מחממת עד</span><b>${s.sunTemp}° (${s.sunKwh} קוט״ש)</b></div>
      <div class="bar" title="חלק השמש"><i style="width:${sunPct}%"></i></div>` : ''}
    <div class="row"><span>חשמל</span><b>${s.kwh} קוט״ש · ₪${s.cost.toFixed(2)}</b></div>
    ${s.warnings.map((w) => `<div class="warn">⚠️ ${esc(w)}</div>`).join('')}
  </div>`;
}

function nextAction(days) {
  const { date, min } = nowIL();
  for (const d of days) {
    for (const s of d.sessions) {
      if (!s.minutes) continue;
      const end = s.startMin + s.minutes;
      if (d.date === date && end <= min) continue;
      const running = d.date === date && s.startMin <= min;
      return { s, running, when: d.date === date ? 'היום' : dayName(d.date) };
    }
  }
  return null;
}

function renderToday() {
  const days = plans();
  if (!days?.length) return `<div class="card"><h2>אין עדיין תחזית</h2><p class="muted">צריך חיבור לאינטרנט כדי להביא את מזג האוויר.</p>
    <button class="btn primary" data-action="refresh">הבאת תחזית</button></div>`;
  const today = days[0];
  const na = nextAction(days);
  const sw = settings.switcher;
  const hero = na
    ? `<div class="card hero"><div class="muted">${na.running ? 'הדוד אמור לפעול עכשיו' : `הפעלה הבאה · ${na.when}`}</div>
        <div class="big">${na.running ? `עד ${na.s.end}` : `${na.s.start} · ${na.s.minutes} דק׳`}</div>
        <div class="muted">למקלחות ה${SESSION_NAME[na.s.session]} (${na.s.sessionTime}) · יעד ${na.s.targetTemp}°</div>
        ${sw?.baseUrl ? `<div class="btns"><button class="btn primary" data-action="sw-on" data-min="${na.s.minutes}">הדלק עכשיו ל-${na.s.minutes} דק׳</button>
          <button class="btn" data-action="sw-off">כבה</button></div>` : ''}
      </div>`
    : `<div class="card sunny"><h2>☀️ אין צורך להדליק את הדוד</h2><p class="muted">השמש מספיקה לכל המקלחות המתוכננות.</p></div>`;
  return `${hero}
    <div class="card"><h2>מזג האוויר היום · ${esc(settings.location.name)}</h2>
      <div class="grid"><div><b>${Math.round(today.minTemp)}°–${Math.round(today.maxTemp)}°</b><span class="muted">טמפרטורה</span></div>
      <div><b>${today.cloud ?? '–'}%</b><span class="muted">עננות</span></div>
      <div><b>${today.radiationKwhM2}</b><span class="muted">קרינה קוט״ש/מ״ר</span></div></div>
      <div class="muted" style="margin-top:8px">מים קרים ברשת: כ-${today.coldTemp}° · עלות משוערת היום: ₪${today.cost.toFixed(2)}</div>
    </div>
    ${today.sessions.map(sessionCard).join('')}
    <div class="card"><h2>איך היו המים במקלחת האחרונה?</h2>
      <p class="muted">המשוב מכייל את החישוב לבית שלך (מקדם נוכחי: ${settings.calibration}).</p>
      <div class="btns"><button class="btn" data-action="fb" data-v="cold">🥶 קרים מדי</button>
      <button class="btn" data-action="fb" data-v="ok">👌 בדיוק</button><button class="btn" data-action="fb" data-v="hot">🔥 חמים מדי / נשאר הרבה</button></div>
    </div>
    <p class="muted">תחזית עודכנה: ${weather.fetched ? new Date(weather.fetched).toLocaleString('he-IL') : '–'}</p>`;
}

function renderForecast() {
  const days = plans();
  if (!days?.length) return renderToday();
  return days.map((d) => `<div class="card"><h2>${dayName(d.date)}</h2>
    <div class="muted">${Math.round(d.minTemp)}°–${Math.round(d.maxTemp)}° · עננות ${d.cloud ?? '–'}% · קרינה ${d.radiationKwhM2} קוט״ש/מ״ר</div>
    ${d.sessions.filter((s) => s.users.length).map((s) => `<div class="row"><span>${SESSION_NAME[s.session]} ${s.sessionTime}</span>
      <b>${s.minutes ? `${s.start} · ${s.minutes} דק׳` : 'לא צריך ☀️'}</b></div>`).join('')}
    <div class="row"><span>סה״כ</span><b>${d.kwh} קוט״ש · ₪${d.cost.toFixed(2)}</b></div></div>`).join('')
    + `<p class="muted">החישוב מבוסס על תחזית Open-Meteo ומתעדכן בכל פתיחה.</p>`;
}

function renderFamily() {
  const m = settings.members;
  return `<div class="card"><h2>שעות המקלחות</h2><div class="two">
      <div><label>בוקר</label><input type="time" data-set="sessions.morning" value="${settings.sessions.morning}"></div>
      <div><label>ערב</label><input type="time" data-set="sessions.evening" value="${settings.sessions.evening}"></div></div>
      <label>הדוד יסיים להתחמם לפני המקלחת ב- (דקות)</label><input type="number" min="0" max="120" data-set="readyBufferMin" value="${settings.readyBufferMin}">
    </div>
    <div class="card"><h2>בני הבית</h2>
      ${m.map((x, i) => `<div class="member">
        <div><label>שם</label><input value="${esc(x.name)}" data-m="${i}" data-f="name"></div>
        <div><label>סוג</label><select data-m="${i}" data-f="kind"><option value="shower" ${x.kind === 'shower' ? 'selected' : ''}>מקלחת</option><option value="bath" ${x.kind === 'bath' ? 'selected' : ''}>אמבטיה</option></select></div>
        <div><label>דקות מקלחת</label><input type="number" min="0" max="60" value="${x.minutes}" data-m="${i}" data-f="minutes" ${x.kind === 'bath' ? 'disabled' : ''}></div>
        <div><label>מתי</label><select data-m="${i}" data-f="session"><option value="morning" ${x.session === 'morning' ? 'selected' : ''}>בוקר</option><option value="evening" ${x.session === 'evening' ? 'selected' : ''}>ערב</option></select></div>
        <button class="del" data-action="del-member" data-i="${i}" aria-label="מחיקת ${esc(x.name)}">✕</button></div>`).join('')}
      <div class="muted">${m.map((x) => `${esc(x.name)}: ${Math.round(memberLiters(x, settings))} ל׳`).join(' · ')}</div>
      <div class="btns"><button class="btn primary" data-action="add-member">+ הוספה</button></div>
    </div>
    <div class="card"><h2>הנחות צריכה</h2><div class="two">
      <div><label>ספיקת ראש מקלחת (ל׳/דקה)</label><input type="number" step="0.5" data-set="showerFlowLpm" value="${settings.showerFlowLpm}"></div>
      <div><label>אמבטיה (ליטר)</label><input type="number" data-set="bathLiters" value="${settings.bathLiters}"></div></div></div>`;
}

function renderSettings() {
  const s = settings; const sw = s.switcher || {};
  return `<div class="card"><h2>מיקום</h2>
      <select data-action="city">${CITIES.map(([n]) => `<option ${n === s.location.name ? 'selected' : ''}>${n}</option>`).join('')}
        ${CITIES.some(([n]) => n === s.location.name) ? '' : `<option selected>${esc(s.location.name)}</option>`}</select>
      <div class="btns"><button class="btn" data-action="gps">📍 המיקום שלי</button></div></div>
    <div class="card"><h2>הדוד</h2><div class="two">
      <div><label>נפח (ליטר)</label><input type="number" data-set="tank.liters" value="${s.tank.liters}"></div>
      <div><label>גוף חימום (קילוואט)</label><input type="number" step="0.1" data-set="tank.heaterKw" value="${s.tank.heaterKw}"></div>
      <div><label>טמפ׳ מקסימלית (תרמוסטט)</label><input type="number" data-set="tank.maxTemp" value="${s.tank.maxTemp}"></div>
      <div><label>מחיר קוט״ש (₪)</label><input type="number" step="0.01" data-set="pricePerKwh" value="${s.pricePerKwh}"></div></div></div>
    <div class="card"><h2>קולטים סולאריים</h2>
      <label class="check"><input type="checkbox" data-set="solar.enabled" ${s.solar.enabled ? 'checked' : ''}> יש דוד שמש</label>
      <div class="two"><div><label>שטח קולטים (מ״ר)</label><input type="number" step="0.5" data-set="solar.areaM2" value="${s.solar.areaM2}"></div>
      <div><label>נצילות בחורף (0–1)</label><input type="number" step="0.05" min="0.1" max="0.8" data-set="solar.efficiency" value="${s.solar.efficiency}"></div></div>
      <p class="muted">קולט רגיל ≈ 2 מ״ר. נצילות 0.35 מתאימה לקולט שטוח בחורף.</p></div>
    <div class="card"><h2>תעו״ז – שעות שיא</h2>
      <label class="check"><input type="checkbox" data-set="peak.avoid" ${s.peak.avoid ? 'checked' : ''}> לסיים לחמם לפני שעות השיא</label>
      <div class="two"><div><label>מ-</label><input type="time" data-set="peak.start" value="${s.peak.start}"></div>
      <div><label>עד</label><input type="time" data-set="peak.end" value="${s.peak.end}"></div></div>
      <p class="muted">מתאים אם עברתם לתעריף לפי שעות. בדקו את השעות מול ספק החשמל שלכם.</p></div>
    <div class="card"><h2>שליטה ב-Switcher</h2>
      <p class="muted">ההדלקה האוטומטית נעשית ע״י "סוכן הבית" שרץ על מחשב/Raspberry Pi בבית (ראו README). כאן ממלאים את פרטי המתג ומורידים קובץ הגדרות לסוכן.</p>
      <div class="two"><div><label>סוג מתג</label><select data-sw="type">${['v4', 'touch', 'v2esp', 'v2qual', 'plug'].map((t) => `<option ${sw.type === t ? 'selected' : ''}>${t}</option>`).join('')}</select></div>
      <div><label>כתובת IP של המתג</label><input data-sw="ip" value="${esc(sw.ip || '')}" placeholder="192.168.1.50"></div>
      <div><label>מזהה מכשיר (id)</label><input data-sw="id" value="${esc(sw.id || '')}" placeholder="ab1c2d"></div>
      <div><label>מפתח (key, לא חובה)</label><input data-sw="key" value="${esc(sw.key || '')}"></div></div>
      <label>כתובת switcher_webapi (לכפתור "הדלק עכשיו", לא חובה)</label><input data-sw="baseUrl" value="${esc(sw.baseUrl || '')}" placeholder="http://192.168.1.10:8000">
      <div class="btns"><button class="btn primary" data-action="export">⬇️ קובץ הגדרות לסוכן</button></div></div>
    <div class="card"><h2>כיול</h2><p class="muted">מקדם כיול נוכחי: ${s.calibration} (מתעדכן לפי המשוב שלך).</p>
      <div class="btns"><button class="btn" data-action="reset-cal">איפוס כיול</button></div></div>`;
}

const TITLES = { today: 'היום', forecast: '3 ימים קדימה', family: 'בני הבית', settings: 'הגדרות' };
function render() {
  document.getElementById('title').textContent = TITLES[tab];
  document.querySelectorAll('.nav button').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
  view.innerHTML = { today: renderToday, forecast: renderForecast, family: renderFamily, settings: renderSettings }[tab]();
}

// ---------- Events ----------
function setPath(obj, path, val) {
  const keys = path.split('.'); let o = obj;
  for (const k of keys.slice(0, -1)) o = o[k] = { ...o[k] };
  o[keys.at(-1)] = val;
}

async function switcher(action, minutes) {
  const sw = settings.switcher || {};
  const q = new URLSearchParams({ type: sw.type || 'v4', id: sw.id || '', ip: sw.ip || '' });
  if (sw.key) q.set('key', sw.key);
  try {
    const res = await fetch(`${sw.baseUrl.replace(/\/$/, '')}/switcher/${action}?${q}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: minutes ? JSON.stringify({ minutes }) : undefined,
    });
    if (!res.ok) throw new Error(res.status);
    toast(action === 'turn_on' ? `הדוד הודלק ל-${minutes} דקות` : 'הדוד כובה');
  } catch {
    toast('אין גישה למתג מכאן – הסוכן הביתי ידליק אוטומטית, או השתמשו באפליקציית Switcher');
  }
}

document.addEventListener('click', (e) => {
  const t = e.target.closest('[data-tab],[data-action]');
  if (!t) return;
  if (t.dataset.tab) { tab = t.dataset.tab; render(); return; }
  const a = t.dataset.action;
  if (a === 'refresh') refreshWeather();
  if (a === 'fb') { settings.calibration = applyFeedback(settings.calibration, t.dataset.v); persist(); toast('תודה! החישוב עודכן'); }
  if (a === 'add-member') { settings.members = [...settings.members, { id: `m${Date.now()}`, name: 'חדש/ה', kind: 'shower', minutes: 8, session: 'evening' }]; persist(); }
  if (a === 'del-member') { settings.members = settings.members.filter((_, i) => i !== +t.dataset.i); persist(); }
  if (a === 'reset-cal') { settings.calibration = 1; persist(); }
  if (a === 'sw-on') switcher('turn_on', +t.dataset.min);
  if (a === 'sw-off') switcher('turn_off');
  if (a === 'gps') {
    navigator.geolocation?.getCurrentPosition((p) => {
      settings.location = { name: 'המיקום שלי', lat: +p.coords.latitude.toFixed(3), lon: +p.coords.longitude.toFixed(3) };
      persist(); refreshWeather();
    }, () => toast('לא התקבלה הרשאת מיקום'));
  }
  if (a === 'export') {
    const blob = new Blob([JSON.stringify({ settings, switcher: settings.switcher || {} }, null, 2)], { type: 'application/json' });
    const link = Object.assign(document.createElement('a'), { href: URL.createObjectURL(blob), download: 'boiler-config.json' });
    link.click(); URL.revokeObjectURL(link.href);
  }
});

document.addEventListener('change', (e) => {
  const el = e.target;
  const val = el.type === 'checkbox' ? el.checked : el.type === 'number' ? Number(el.value) : el.value;
  if (el.dataset.set) { setPath(settings, el.dataset.set, val); persist(); return; }
  if (el.dataset.m) {
    settings.members = settings.members.map((m, i) => (i === +el.dataset.m ? { ...m, [el.dataset.f]: val } : m));
    persist(); return;
  }
  if (el.dataset.sw) { settings.switcher = { ...settings.switcher, [el.dataset.sw]: val.trim() }; save(KEY, settings); return; }
  if (el.dataset.action === 'city') {
    const c = CITIES.find(([n]) => n === el.value);
    if (c) { settings.location = { name: c[0], lat: c[1], lon: c[2] }; persist(); refreshWeather(); }
  }
});

if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
render();
if (!weather || Date.now() - weather.fetched > 30 * 60 * 1000 || weather.loc !== settings.location.name) refreshWeather(true);
setInterval(render, 60 * 1000);
