import {
  ym, ymd, addMonths, monthSummary, categoryBreakdown, historyMonths, nextDueMonth,
  installmentFor, amountFor, txFor,
} from './calc.js';
import { generateInsights } from './insights.js';
import { load, save, uid, exportJSON, parseBackup, defaultState } from './storage.js';
import { demoState } from './demo.js';

export const APP_VERSION = '0.1.0';

let state = load();
const ui = { tab: 'dashboard', month: ym(new Date()) };

// ---------- helpers ----------
const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmt = (n, digits = 0) => {
  const v = Math.abs(n).toLocaleString('he-IL', { maximumFractionDigits: digits, minimumFractionDigits: 0 });
  return (n < -0.004 ? '-' : '') + '₪' + v;
};
const monthName = (m) => {
  const [y, mo] = m.split('-').map(Number);
  return new Date(y, mo - 1, 1).toLocaleDateString('he-IL', { month: 'long', year: 'numeric' });
};
const shortMonth = (m) => {
  const [y, mo] = m.split('-').map(Number);
  return new Date(y, mo - 1, 1).toLocaleDateString('he-IL', { month: 'short' });
};
const dateLabel = (d) => {
  const dt = new Date(d + 'T00:00');
  return dt.toLocaleDateString('he-IL', { weekday: 'long', day: 'numeric', month: 'numeric' });
};
const today = () => new Date();
const curMonth = () => ym(today());
const GROUP_LABEL = { monthly: 'חודשי', bimonthly: 'דו-חודשי', installment: 'תשלומים' };

function persist() {
  save(state);
  render();
}

function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toast._t);
  toast._t = setTimeout(() => t.classList.remove('show'), 2200);
}

function monthNav() {
  const m = ui.month;
  return `<div class="month-nav">
    <button class="icon-btn" data-action="month" data-delta="-1" aria-label="חודש קודם">›</button>
    <button class="month-label" data-action="month-today">${esc(monthName(m))}${m === curMonth() ? '' : ' <small>(חזרה להיום)</small>'}</button>
    <button class="icon-btn" data-action="month" data-delta="1" aria-label="חודש הבא">‹</button>
  </div>`;
}

// ---------- views ----------
function viewDashboard() {
  const s = monthSummary(state, ui.month, today());
  const empty = !state.recurring.length && !state.transactions.length && !state.installments.length;
  if (empty) {
    return `${monthNav()}<div class="card empty">
      <h2>ברוכים הבאים 👋</h2>
      <p>כדי להתחיל, הגדירו את ההכנסות והחיובים הקבועים (משכורות, משכנתא, ארנונה, חשמל...) ואז הזינו הוצאות יומיות.</p>
      <div class="row">
        <button class="btn primary" data-action="tab" data-tab="fixed">הגדרת קבועים</button>
        <button class="btn" data-action="load-demo">טעינת נתוני דוגמה</button>
      </div></div>`;
  }
  const heroLabel = { current: 'צפי רווח / הפסד החודש', past: 'רווח / הפסד בפועל', future: 'צפי רווח / הפסד' }[s.status];
  const pos = s.net >= 0;
  const varPct = s.projectedVar > 0 ? Math.min(100, (s.varExpense / s.projectedVar) * 100) : 0;
  const insights = s.status === 'current' ? generateInsights(state, today()).filter((i) => i.level !== 'good').slice(0, 2) : [];
  const cats = categoryBreakdown(state, ui.month);
  const maxCat = cats[0]?.amount || 1;
  const nowDay = today().getDate();

  const tiles = [
    ['הכנסות' + (s.status === 'past' ? '' : ' צפויות'), fmt(s.income), `קבועות ${fmt(s.fixedIncome)}${s.varIncome ? ` + נוספות ${fmt(s.varIncome)}` : ''}`],
    ['הוצאות' + (s.status === 'past' ? '' : ' צפויות'), fmt(s.expenses), `קבועות ${fmt(s.fixedExpense)} + משתנות ${fmt(s.projectedVar)}`],
  ];
  if (s.status === 'current') {
    tiles.push(['ממוצע הוצאה יומי', fmt(s.dailyAvg), s.histAvg != null ? `בחודשים קודמים ${fmt(s.histAvg)}` : `${s.daysElapsed} ימים עד כה`]);
    tiles.push(['ימים שנותרו', String(s.daysLeft), s.freeDaily != null ? `פנוי להוצאה: ${fmt(Math.max(0, s.freeDaily))} ליום` : '']);
  } else if (s.status === 'past') {
    tiles.push(['ממוצע הוצאה יומי', fmt(s.currentAvg), `${s.daysTotal} ימים`]);
    tiles.push(['שיעור חיסכון', s.income ? Math.round((s.net / s.income) * 100) + '%' : '—', 'מתוך ההכנסות']);
  }

  return `${monthNav()}
  <div class="card hero ${pos ? 'pos' : 'neg'}">
    <div class="hero-label">${heroLabel}</div>
    <div class="hero-value">${pos ? '▲' : '▼'} ${fmt(s.net)}</div>
    <div class="hero-sub">${s.status === 'current' ? `נכון ל-${nowDay}/${ui.month.slice(5)} · לפי ממוצע יומי ${fmt(s.dailyAvg)}` : ''}</div>
  </div>
  <div class="tiles">${tiles.map(([l, v, sub]) => `<div class="tile"><div class="tile-label">${l}</div><div class="tile-value">${v}</div><div class="tile-sub">${sub}</div></div>`).join('')}</div>

  ${s.status !== 'future' ? `<div class="card">
    <div class="card-head"><h3>הוצאות משתנות</h3><span class="muted">${fmt(s.varExpense)}${s.status === 'current' ? ` מתוך צפי ${fmt(s.projectedVar)}` : ''}</span></div>
    <div class="bar"><div class="bar-fill" style="width:${varPct}%"></div></div>
    ${s.status === 'current' ? `<div class="muted small">יום ${s.daysElapsed} מתוך ${s.daysTotal} (${Math.round((s.daysElapsed / s.daysTotal) * 100)}% מהחודש)</div>` : ''}
  </div>` : ''}

  ${insights.length ? `<div class="card">
    <div class="card-head"><h3>תובנות</h3><button class="link" data-action="tab" data-tab="insights">הכל ›</button></div>
    ${insights.map(insightHTML).join('')}
  </div>` : ''}

  <div class="card">
    <div class="card-head"><h3>קבועים ותשלומים החודש</h3><button class="link" data-action="tab" data-tab="fixed">ניהול ›</button></div>
    ${s.lines.length ? `<ul class="list">${s.lines.map((l) => {
      const done = s.status === 'past' || (s.status === 'current' && l.day <= nowDay);
      return `<li class="${l.source === 'recurring' ? 'tap' : ''}" ${l.source === 'recurring' ? `data-action="actual" data-key="${l.key}"` : ''}>
        <div class="li-main">
          <div class="li-title">${done ? '<span class="ok" title="בוצע">✓</span> ' : ''}${esc(l.name)}</div>
          <div class="li-sub">${l.day} בחודש · ${GROUP_LABEL[l.group]}${l.n ? ` ${l.n}/${l.of}` : ''}${l.estimated ? (l.actual == null ? ' · <span class="warn-text">משוער – הקישו לעדכון</span>' : ' · עודכן בפועל') : ''}</div>
        </div>
        <div class="li-amount ${l.kind === 'income' ? 'inc' : ''}">${l.kind === 'income' ? '+' : ''}${fmt(l.amount)}</div>
      </li>`;
    }).join('')}</ul>` : '<p class="muted">אין חיובים קבועים בחודש זה.</p>'}
  </div>

  ${cats.length ? `<div class="card">
    <div class="card-head"><h3>פילוח הוצאות</h3><span class="muted">${fmt(cats.reduce((a, c) => a + c.amount, 0))}</span></div>
    <div class="cats">${cats.map((c) => `<div class="cat-row">
      <div class="cat-name">${esc(c.category)}</div>
      <div class="cat-bar"><div style="width:${(c.amount / maxCat) * 100}%"></div></div>
      <div class="cat-amt">${fmt(c.amount)}</div></div>`).join('')}</div>
  </div>` : ''}`;
}

function viewTransactions() {
  const tx = txFor(state, ui.month).sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0));
  const exp = tx.filter((t) => t.type === 'expense').reduce((a, t) => a + t.amount, 0);
  const inc = tx.filter((t) => t.type === 'income').reduce((a, t) => a + t.amount, 0);
  const byDay = new Map();
  for (const t of tx) {
    if (!byDay.has(t.date)) byDay.set(t.date, []);
    byDay.get(t.date).push(t);
  }
  return `${monthNav()}
  <div class="tiles">
    <div class="tile"><div class="tile-label">הוצאות משתנות</div><div class="tile-value">${fmt(exp)}</div><div class="tile-sub">${tx.filter((t) => t.type === 'expense').length} תנועות</div></div>
    <div class="tile"><div class="tile-label">הכנסות נוספות</div><div class="tile-value">${fmt(inc)}</div><div class="tile-sub">&nbsp;</div></div>
  </div>
  ${tx.length ? [...byDay.entries()].map(([d, list]) => `<div class="card day">
      <div class="card-head"><h3>${esc(dateLabel(d))}</h3><span class="muted">${fmt(list.reduce((a, t) => a + (t.type === 'expense' ? t.amount : 0), 0))}</span></div>
      <ul class="list">${list.map((t) => `<li class="tap" data-action="edit-tx" data-id="${t.id}">
        <div class="li-main"><div class="li-title">${esc(t.note || t.category)}</div><div class="li-sub">${esc(t.category)}</div></div>
        <div class="li-amount ${t.type === 'income' ? 'inc' : ''}">${t.type === 'income' ? '+' : ''}${fmt(t.amount, 2)}</div>
      </li>`).join('')}</ul></div>`).join('')
    : '<div class="card empty"><p>אין תנועות משתנות בחודש זה.</p><button class="btn primary" data-action="add-tx">הוספת הוצאה</button></div>'}`;
}

function viewFixed() {
  const m = curMonth();
  const active = (r) => !r.endMonth || r.endMonth >= m;
  const recRow = (r) => {
    const amt = amountFor(r, m);
    const next = active(r) ? nextDueMonth(r, m) : null;
    return `<li class="tap ${active(r) ? '' : 'ended'}" data-action="edit-rec" data-id="${r.id}">
      <div class="li-main"><div class="li-title">${esc(r.name)}${r.estimated ? ' <span class="chip">משוער</span>' : ''}${active(r) ? '' : ' <span class="chip">הסתיים</span>'}</div>
      <div class="li-sub">${esc(r.category)} · ${r.day} בחודש${r.frequency === 2 && next ? ` · חיוב הבא: ${shortMonth(next)}` : ''}</div></div>
      <div class="li-amount ${r.kind === 'income' ? 'inc' : ''}">${fmt(amt)}</div></li>`;
  };
  const section = (title, items, preset, hint) => `<div class="card">
    <div class="card-head"><h3>${title}</h3><button class="btn small" data-action="add-rec" data-preset="${preset}">+ הוספה</button></div>
    ${hint ? `<p class="muted small">${hint}</p>` : ''}
    ${items.length ? `<ul class="list">${items.map(recRow).join('')}</ul>` : '<p class="muted">עדיין אין פריטים.</p>'}
  </div>`;
  const incomes = state.recurring.filter((r) => r.kind === 'income');
  const monthly = state.recurring.filter((r) => r.kind === 'expense' && r.frequency !== 2);
  const bimonthly = state.recurring.filter((r) => r.kind === 'expense' && r.frequency === 2);
  const sortFn = (a, b) => (active(b) - active(a)) || amountFor(b, m) - amountFor(a, m);

  const incTotal = incomes.filter(active).reduce((a, r) => a + amountFor(r, m) / (r.frequency || 1), 0);
  const expTotal = [...monthly, ...bimonthly].filter(active).reduce((a, r) => a + amountFor(r, m) / (r.frequency || 1), 0);
  const instTotal = state.installments.reduce((a, i) => a + (installmentFor(i, m)?.amount || 0), 0);

  return `<div class="tiles">
    <div class="tile"><div class="tile-label">הכנסות קבועות לחודש</div><div class="tile-value">${fmt(incTotal)}</div><div class="tile-sub">&nbsp;</div></div>
    <div class="tile"><div class="tile-label">חיובים קבועים לחודש</div><div class="tile-value">${fmt(expTotal + instTotal)}</div><div class="tile-sub">ממוצע, כולל תשלומים</div></div>
  </div>
  ${section('הכנסות קבועות', incomes.sort(sortFn), 'income', 'משכורת 1, משכורת 2, קצבאות...')}
  ${section('חיובים חודשיים', monthly.sort(sortFn), 'monthly', 'משכנתא, אינטרנט, טלויזיה, ביטוחים, גן...')}
  ${section('חיובים דו-חודשיים', bimonthly.sort(sortFn), 'bimonthly', 'ארנונה, מים, חשמל, גז – פעם בחודשיים')}
  <div class="card">
    <div class="card-head"><h3>חיובים בתשלומים</h3><button class="btn small" data-action="add-inst">+ הוספה</button></div>
    ${state.installments.length ? `<ul class="list">${state.installments.map((i) => {
      const info = installmentFor(i, m);
      const lastM = addMonths(i.firstMonth, i.payments - 1);
      const status = info ? `תשלום ${info.n} מתוך ${info.of}` : (m < i.firstMonth ? `מתחיל ב-${shortMonth(i.firstMonth)}` : 'הסתיים');
      const pct = info ? (info.n / info.of) * 100 : (m > lastM ? 100 : 0);
      return `<li class="tap ${m > lastM ? 'ended' : ''}" data-action="edit-inst" data-id="${i.id}">
        <div class="li-main"><div class="li-title">${esc(i.name)}</div>
        <div class="li-sub">${status} · סה"כ ${fmt(i.total)}</div>
        <div class="bar thin"><div class="bar-fill" style="width:${pct}%"></div></div></div>
        <div class="li-amount">${fmt(i.total / i.payments)}</div></li>`;
    }).join('')}</ul>` : '<p class="muted">עדיין אין חיובים בתשלומים.</p>'}
  </div>`;
}

function historyChart(rows) {
  // rows chronological: [{month, income, expenses}]
  const W = 340, H = 180, pad = { t: 10, r: 6, b: 24, l: 40 };
  const max = Math.max(1, ...rows.flatMap((r) => [r.income, r.expenses]));
  const nice = (() => { const p = 10 ** Math.floor(Math.log10(max)); return Math.ceil(max / p) * p; })();
  const iw = W - pad.l - pad.r, ih = H - pad.t - pad.b;
  const gw = iw / rows.length, bw = Math.max(3, Math.min(14, gw / 2 - 3));
  const y = (v) => pad.t + ih - (v / nice) * ih;
  const ticks = [0, 0.5, 1].map((f) => f * nice);
  const k = (v) => (v >= 1000 ? Math.round(v / 1000) + 'K' : Math.round(v));
  const bar = (x, v, cls, label) => {
    const h = Math.max(0, pad.t + ih - y(v));
    const r = Math.min(4, bw / 2, h);
    const top = y(v);
    // rounded top corners only, anchored to baseline
    return `<path class="${cls}" d="M${x},${pad.t + ih} V${top + r} Q${x},${top} ${x + r},${top} H${x + bw - r} Q${x + bw},${top} ${x + bw},${top + r} V${pad.t + ih} Z"><title>${label}</title></path>`;
  };
  return `<svg viewBox="0 0 ${W} ${H}" class="chart" role="img" aria-label="הכנסות מול הוצאות לפי חודש" direction="ltr">
    ${ticks.map((t) => `<line class="grid" x1="${pad.l}" x2="${W - pad.r}" y1="${y(t)}" y2="${y(t)}"/><text class="axis" x="${pad.l - 6}" y="${y(t) + 4}" text-anchor="end">${k(t)}</text>`).join('')}
    ${rows.map((r, i) => {
      const cx = pad.l + gw * i + gw / 2;
      return `<g class="grp" data-action="open-month" data-month="${r.month}">
        <rect x="${pad.l + gw * i}" y="${pad.t}" width="${gw}" height="${ih + pad.b}" fill="transparent"/>
        ${bar(cx - bw - 1, r.income, 's1', `${monthName(r.month)} · הכנסות ${fmt(r.income)}`)}
        ${bar(cx + 1, r.expenses, 's2', `${monthName(r.month)} · הוצאות ${fmt(r.expenses)}`)}
        <text class="axis" x="${cx}" y="${H - 6}" text-anchor="middle">${shortMonth(r.month)}</text>
      </g>`;
    }).join('')}
  </svg>`;
}

function viewHistory() {
  const months = historyMonths(state, curMonth(), 12);
  const rows = months.map((m) => {
    const s = monthSummary(state, m, today());
    return { month: m, income: s.income, expenses: s.expenses, net: s.net, status: s.status, varExpense: s.varExpense };
  });
  const past = rows.filter((r) => r.status === 'past');
  const avg = (f) => (past.length ? past.reduce((a, r) => a + f(r), 0) / past.length : 0);
  return `<div class="card">
    <div class="card-head"><h3>הכנסות מול הוצאות</h3></div>
    <div class="legend"><span><i class="sw s1"></i>הכנסות</span><span><i class="sw s2"></i>הוצאות</span></div>
    ${historyChart([...rows].reverse())}
    <p class="muted small">החודש הנוכחי מוצג לפי הצפי. הקישו על חודש לפרטים.</p>
  </div>
  ${past.length ? `<div class="tiles">
    <div class="tile"><div class="tile-label">ממוצע הכנסות</div><div class="tile-value">${fmt(avg((r) => r.income))}</div><div class="tile-sub">${past.length} חודשים</div></div>
    <div class="tile"><div class="tile-label">ממוצע הוצאות</div><div class="tile-value">${fmt(avg((r) => r.expenses))}</div><div class="tile-sub">משתנות ${fmt(avg((r) => r.varExpense))}</div></div>
    <div class="tile wide"><div class="tile-label">ממוצע רווח / הפסד חודשי</div><div class="tile-value ${avg((r) => r.net) >= 0 ? 'inc' : 'neg-text'}">${fmt(avg((r) => r.net))}</div><div class="tile-sub">סה"כ בתקופה: ${fmt(past.reduce((a, r) => a + r.net, 0))}</div></div>
  </div>` : ''}
  <div class="card">
    <div class="card-head"><h3>חודשים</h3></div>
    <table class="table"><thead><tr><th>חודש</th><th>הכנסות</th><th>הוצאות</th><th>רווח/הפסד</th></tr></thead>
    <tbody>${rows.map((r) => `<tr class="tap" data-action="open-month" data-month="${r.month}">
      <td>${esc(monthName(r.month))}${r.status === 'current' ? ' <span class="chip">צפי</span>' : ''}</td>
      <td>${fmt(r.income)}</td><td>${fmt(r.expenses)}</td>
      <td class="${r.net >= 0 ? 'inc' : 'neg-text'}">${fmt(r.net)}</td></tr>`).join('')}</tbody></table>
  </div>`;
}

function insightHTML(i) {
  return `<div class="insight ${i.level}"><div class="insight-icon">${i.icon}</div>
    <div><div class="insight-title">${esc(i.title)}</div><div class="insight-text">${esc(i.text)}</div></div></div>`;
}

function viewInsights() {
  const list = generateInsights(state, today());
  return `<div class="card">
    <div class="card-head"><h3>תובנות לחודש ${esc(monthName(curMonth()))}</h3></div>
    ${list.length ? list.map(insightHTML).join('') : '<p class="muted">אין עדיין מספיק נתונים לתובנות. המשיכו להזין הוצאות – התובנות ישתפרו עם הזמן.</p>'}
  </div>
  <p class="muted small center">התובנות מתעדכנות אוטומטית בכל כניסה לאפליקציה ובכל הזנה.</p>`;
}

const VIEWS = { dashboard: viewDashboard, transactions: viewTransactions, fixed: viewFixed, history: viewHistory, insights: viewInsights };
const TITLES = { dashboard: 'סקירה', transactions: 'תנועות', fixed: 'קבועים', history: 'היסטוריה', insights: 'תובנות' };

function render() {
  $('#view').innerHTML = VIEWS[ui.tab]();
  $('#title').textContent = TITLES[ui.tab];
  document.querySelectorAll('.nav button').forEach((b) => b.classList.toggle('active', b.dataset.tab === ui.tab));
  const alerts = generateInsights(state, today()).filter((i) => i.level === 'critical' || i.level === 'warning').length;
  const badge = $('#insight-badge');
  badge.textContent = alerts;
  badge.hidden = !alerts;
  $('#fab').hidden = !['dashboard', 'transactions'].includes(ui.tab);
}

// ---------- modal forms ----------
const dlg = () => $('#modal');
function openModal(html, onSubmit) {
  const d = dlg();
  d.innerHTML = html;
  const form = d.querySelector('form');
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const btn = e.submitter?.value || 'save';
    const data = Object.fromEntries(new FormData(form).entries());
    if (onSubmit(btn, data, form) !== false) d.close();
  });
  d.querySelector('[data-close]')?.addEventListener('click', () => d.close());
  d.showModal();
  const first = d.querySelector('[autofocus]');
  if (first) setTimeout(() => first.focus(), 50);
}

const options = (list, selected) => list.map((c) => `<option ${c === selected ? 'selected' : ''}>${esc(c)}</option>`).join('');
const num = (v) => Math.round(parseFloat(String(v).replace(/[,₪\s]/g, '')) * 100) / 100;

function txForm(tx) {
  const isNew = !tx;
  const t = tx || { type: 'expense', amount: '', category: state.categories.expense[0], date: ymd(today()), note: '' };
  if (isNew && ui.month !== curMonth()) t.date = `${ui.month}-01`;
  openModal(`<form class="sheet">
    <div class="sheet-head"><h2>${isNew ? 'תנועה חדשה' : 'עריכת תנועה'}</h2><button type="button" class="icon-btn" data-close aria-label="סגירה">✕</button></div>
    <div class="seg">
      <label><input type="radio" name="type" value="expense" ${t.type === 'expense' ? 'checked' : ''}><span>הוצאה</span></label>
      <label><input type="radio" name="type" value="income" ${t.type === 'income' ? 'checked' : ''}><span>הכנסה</span></label>
    </div>
    <label class="field"><span>סכום (₪)</span><input name="amount" inputmode="decimal" required value="${t.amount}" autofocus placeholder="0"></label>
    <label class="field"><span>קטגוריה</span><select name="category">${options(state.categories[t.type], t.category)}</select></label>
    <label class="field"><span>תאריך</span><input type="date" name="date" required value="${t.date}"></label>
    <label class="field"><span>תיאור (לא חובה)</span><input name="note" value="${esc(t.note)}" placeholder="למשל: שופרסל"></label>
    <div class="row">
      <button class="btn primary grow" value="save">שמירה</button>
      ${isNew ? '<button class="btn" value="again">שמירה והוספת עוד</button>' : '<button class="btn danger" value="delete" formnovalidate>מחיקה</button>'}
    </div></form>`, (btn, d) => {
    if (btn === 'delete') {
      if (!confirm('למחוק את התנועה?')) return false;
      state.transactions = state.transactions.filter((x) => x.id !== tx.id);
      persist(); toast('התנועה נמחקה'); return;
    }
    const amount = num(d.amount);
    if (!(amount > 0)) { alert('יש להזין סכום חיובי'); return false; }
    const rec = { id: tx?.id || uid(), type: d.type, amount, category: d.category, date: d.date, note: d.note.trim() };
    if (isNew) state.transactions.push(rec);
    else Object.assign(state.transactions.find((x) => x.id === tx.id), rec);
    persist();
    toast('נשמר ✓');
    if (btn === 'again') setTimeout(() => txForm(), 0);
  });
  // switch categories with type
  dlg().querySelectorAll('input[name=type]').forEach((r) => r.addEventListener('change', () => {
    dlg().querySelector('select[name=category]').innerHTML = options(state.categories[r.value]);
  }));
}

function recForm(item, preset = 'monthly') {
  const isNew = !item;
  const m = curMonth();
  const it = item || {
    kind: preset === 'income' ? 'income' : 'expense',
    name: '', category: preset === 'income' ? 'משכורת' : preset === 'bimonthly' ? 'חשבונות' : state.categories.expense[0],
    frequency: preset === 'bimonthly' ? 2 : 1, startMonth: m, day: 1, estimated: preset === 'bimonthly',
    amounts: [{ from: m, amount: '' }],
  };
  const cats = state.categories[it.kind];
  openModal(`<form class="sheet">
    <div class="sheet-head"><h2>${isNew ? 'הוספת' : 'עריכת'} ${it.kind === 'income' ? 'הכנסה קבועה' : 'חיוב קבוע'}</h2><button type="button" class="icon-btn" data-close aria-label="סגירה">✕</button></div>
    <label class="field"><span>שם</span><input name="name" required value="${esc(it.name)}" placeholder="${it.kind === 'income' ? 'משכורת 1' : 'למשל: חשמל'}" ${isNew ? 'autofocus' : ''}></label>
    <label class="field"><span>סכום ${it.estimated ? 'משוער ' : ''}(₪)</span><input name="amount" inputmode="decimal" required value="${isNew ? '' : amountFor(it, m)}"></label>
    ${isNew ? '' : `<label class="field"><span>שינוי סכום בתוקף מחודש</span><input type="month" name="effective" value="${m}"><small class="muted">חודשים קודמים ישמרו את הסכום הקודם</small></label>`}
    <label class="field"><span>קטגוריה</span><select name="category">${options(cats.includes(it.category) ? cats : [it.category, ...cats], it.category)}</select></label>
    <label class="field"><span>תדירות</span><select name="frequency">
      <option value="1" ${it.frequency !== 2 ? 'selected' : ''}>כל חודש</option>
      <option value="2" ${it.frequency === 2 ? 'selected' : ''}>כל חודשיים</option></select></label>
    <label class="field"><span>${isNew ? 'חודש החיוב הראשון / הקרוב' : 'חודש התחלה'}</span><input type="month" name="startMonth" required value="${it.startMonth}">
      <small class="muted">בחיוב דו-חודשי קובע באילו חודשים מגיע החיוב</small></label>
    <label class="field"><span>יום בחודש</span><input type="number" name="day" min="1" max="31" value="${it.day}"></label>
    <label class="check"><input type="checkbox" name="estimated" ${it.estimated ? 'checked' : ''}><span>סכום משתנה (משוער) – אעדכן את הסכום בפועל בכל חיוב</span></label>
    <div class="row">
      <button class="btn primary grow" value="save">שמירה</button>
      ${isNew ? '' : `${!it.endMonth ? '<button class="btn" value="end" formnovalidate>הפסקה</button>' : '<button class="btn" value="resume" formnovalidate>חידוש</button>'}
      <button class="btn danger" value="delete" formnovalidate>מחיקה</button>`}
    </div>
    ${!isNew && !it.endMonth ? '<p class="muted small">"הפסקה" – החיוב לא יופיע מהחודש הבא, ההיסטוריה נשמרת. "מחיקה" – מוחק גם מההיסטוריה.</p>' : ''}
  </form>`, (btn, d) => {
    if (btn === 'delete') {
      if (!confirm(`למחוק את "${it.name}" לצמיתות, כולל מההיסטוריה?`)) return false;
      state.recurring = state.recurring.filter((x) => x.id !== it.id);
      for (const k of Object.keys(state.actuals)) if (k.startsWith(it.id + '|')) delete state.actuals[k];
      persist(); toast('נמחק'); return;
    }
    if (btn === 'end') { item.endMonth = m; persist(); toast('החיוב הופסק מהחודש הבא'); return; }
    if (btn === 'resume') { item.endMonth = null; persist(); toast('החיוב חודש'); return; }
    const amount = num(d.amount);
    if (!(amount >= 0)) { alert('סכום לא תקין'); return false; }
    const fields = {
      name: d.name.trim(), category: d.category, frequency: Number(d.frequency),
      startMonth: d.startMonth, day: Math.min(31, Math.max(1, Number(d.day) || 1)), estimated: !!d.estimated,
    };
    if (isNew) {
      state.recurring.push({ id: uid(), kind: it.kind, endMonth: null, ...fields, amounts: [{ from: fields.startMonth, amount }] });
    } else {
      Object.assign(item, fields);
      if (amount !== amountFor(item, d.effective || m)) {
        const eff = d.effective || m;
        item.amounts = item.amounts.filter((a) => a.from < eff);
        item.amounts.push({ from: eff, amount });
      }
      if (!item.amounts.some((a) => a.from <= item.startMonth)) item.amounts[0].from = item.startMonth;
    }
    persist(); toast('נשמר ✓');
  });
}

function instForm(inst) {
  const isNew = !inst;
  const i = inst || { name: '', category: 'בית ותחזוקה', total: '', payments: 12, firstMonth: curMonth(), day: 2 };
  openModal(`<form class="sheet">
    <div class="sheet-head"><h2>${isNew ? 'חיוב חדש בתשלומים' : 'עריכת תשלומים'}</h2><button type="button" class="icon-btn" data-close aria-label="סגירה">✕</button></div>
    <label class="field"><span>מה נקנה</span><input name="name" required value="${esc(i.name)}" placeholder="למשל: מקרר" ${isNew ? 'autofocus' : ''}></label>
    <label class="field"><span>סכום כולל (₪)</span><input name="total" inputmode="decimal" required value="${i.total}"></label>
    <label class="field"><span>מספר תשלומים</span><input type="number" name="payments" min="1" max="120" required value="${i.payments}"></label>
    <label class="field"><span>חודש התשלום הראשון</span><input type="month" name="firstMonth" required value="${i.firstMonth}"></label>
    <label class="field"><span>קטגוריה</span><select name="category">${options(state.categories.expense, i.category)}</select></label>
    <label class="field"><span>יום חיוב בחודש</span><input type="number" name="day" min="1" max="31" value="${i.day}"></label>
    <p class="muted small" id="inst-calc"></p>
    <div class="row">
      <button class="btn primary grow" value="save">שמירה</button>
      ${isNew ? '' : '<button class="btn danger" value="delete" formnovalidate>מחיקה</button>'}
    </div></form>`, (btn, d) => {
    if (btn === 'delete') {
      if (!confirm('למחוק את החיוב בתשלומים?')) return false;
      state.installments = state.installments.filter((x) => x.id !== inst.id);
      persist(); toast('נמחק'); return;
    }
    const rec = {
      name: d.name.trim(), total: num(d.total), payments: Math.max(1, parseInt(d.payments, 10) || 1),
      firstMonth: d.firstMonth, category: d.category, day: Math.min(31, Math.max(1, Number(d.day) || 1)),
    };
    if (!(rec.total > 0)) { alert('יש להזין סכום'); return false; }
    if (isNew) state.installments.push({ id: uid(), ...rec });
    else Object.assign(inst, rec);
    persist(); toast('נשמר ✓');
  });
  const f = dlg().querySelector('form');
  const upd = () => {
    const t = num(f.total.value), p = parseInt(f.payments.value, 10);
    f.querySelector('#inst-calc').textContent = t > 0 && p > 0
      ? `${p} תשלומים של ${fmt(t / p, 2)} · אחרון ב-${shortMonth(addMonths(f.firstMonth.value || curMonth(), p - 1))} ${addMonths(f.firstMonth.value || curMonth(), p - 1).slice(0, 4)}` : '';
  };
  f.addEventListener('input', upd); upd();
}

function actualForm(key) {
  const [id, m] = key.split('|');
  const item = state.recurring.find((r) => r.id === id);
  if (!item) return;
  const planned = amountFor(item, m);
  const actual = state.actuals[key];
  openModal(`<form class="sheet">
    <div class="sheet-head"><h2>${esc(item.name)} – ${esc(monthName(m))}</h2><button type="button" class="icon-btn" data-close aria-label="סגירה">✕</button></div>
    <p class="muted">סכום ${item.estimated ? 'משוער' : 'מתוכנן'}: ${fmt(planned, 2)}</p>
    <label class="field"><span>סכום בפועל לחודש זה (₪)</span><input name="amount" inputmode="decimal" required value="${actual ?? ''}" placeholder="${planned}" autofocus></label>
    <div class="row">
      <button class="btn primary grow" value="save">שמירה</button>
      ${actual != null ? '<button class="btn" value="clear" formnovalidate>חזרה לסכום המתוכנן</button>' : ''}
      <button type="button" class="btn" data-action="edit-rec" data-id="${item.id}">עריכת החיוב</button>
    </div></form>`, (btn, d) => {
    if (btn === 'clear') delete state.actuals[key];
    else {
      const a = num(d.amount);
      if (!(a >= 0)) { alert('סכום לא תקין'); return false; }
      state.actuals[key] = a;
    }
    persist(); toast('עודכן ✓');
  });
}

function settingsForm() {
  openModal(`<form class="sheet">
    <div class="sheet-head"><h2>הגדרות וגיבוי</h2><button type="button" class="icon-btn" data-close aria-label="סגירה">✕</button></div>
    <p class="muted small">הנתונים נשמרים רק במכשיר הזה. מומלץ לגבות מדי פעם לקובץ (למשל ל-Google Drive).</p>
    <div class="stack">
      <button type="button" class="btn" data-action="export">⬇️ גיבוי לקובץ</button>
      <label class="btn">⬆️ שחזור מקובץ<input type="file" accept="application/json,.json" id="import-file" hidden></label>
      <button type="button" class="btn" data-action="load-demo">🧪 טעינת נתוני דוגמה</button>
      <button type="button" class="btn danger" data-action="reset">🗑️ מחיקת כל הנתונים</button>
    </div>
    <p class="muted small center">גרסה ${APP_VERSION} · ${state.transactions.length} תנועות · ${state.recurring.length} קבועים</p>
  </form>`, () => {});
  $('#import-file').addEventListener('change', async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    try {
      const s = parseBackup(await file.text());
      if (!confirm('השחזור יחליף את כל הנתונים הנוכחיים. להמשיך?')) return;
      state = s; dlg().close(); persist(); toast('הנתונים שוחזרו ✓');
    } catch (err) { alert('שגיאה בקריאת הקובץ: ' + err.message); }
  });
}

// ---------- events ----------
document.addEventListener('click', (e) => {
  const el = e.target.closest('[data-action]');
  if (!el) return;
  const { action } = el.dataset;
  switch (action) {
    case 'tab': ui.tab = el.dataset.tab; if (dlg().open) dlg().close(); render(); window.scrollTo(0, 0); break;
    case 'month': ui.month = addMonths(ui.month, Number(el.dataset.delta)); render(); break;
    case 'month-today': ui.month = curMonth(); render(); break;
    case 'open-month': ui.month = el.dataset.month; ui.tab = 'dashboard'; render(); window.scrollTo(0, 0); break;
    case 'add-tx': txForm(); break;
    case 'edit-tx': txForm(state.transactions.find((t) => t.id === el.dataset.id)); break;
    case 'add-rec': recForm(null, el.dataset.preset); break;
    case 'edit-rec': if (dlg().open) dlg().close(); recForm(state.recurring.find((r) => r.id === el.dataset.id)); break;
    case 'add-inst': instForm(); break;
    case 'edit-inst': instForm(state.installments.find((i) => i.id === el.dataset.id)); break;
    case 'actual': actualForm(el.dataset.key); break;
    case 'settings': settingsForm(); break;
    case 'export': exportJSON(state); break;
    case 'load-demo': {
      const hasData = state.transactions.length || state.recurring.length;
      if (hasData && !confirm('נתוני הדוגמה יחליפו את הנתונים הקיימים. להמשיך?')) return;
      state = demoState(today()); if (dlg().open) dlg().close(); ui.tab = 'dashboard'; persist(); toast('נטענו נתוני דוגמה');
      break;
    }
    case 'reset':
      if (!confirm('למחוק את כל הנתונים? לא ניתן לבטל.')) return;
      state = defaultState(); dlg().close(); persist(); toast('כל הנתונים נמחקו');
      break;
  }
});

// light backdrop click closes the sheet
dlg().addEventListener('click', (e) => { if (e.target === dlg()) dlg().close(); });

// refresh when returning to the app on a new day
document.addEventListener('visibilitychange', () => { if (!document.hidden) render(); });

if ('serviceWorker' in navigator && location.protocol !== 'file:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}

render();
