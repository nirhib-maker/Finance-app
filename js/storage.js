// Local persistence (localStorage) + backup / restore.

const KEY = 'home-budget-v1';
export const SCHEMA_VERSION = 1;

export const DEFAULT_CATEGORIES = {
  expense: [
    'מזון וסופר', 'מסעדות ובילוי', 'תחבורה ודלק', 'משכנתא ודיור', 'חשבונות',
    'טלויזיה ואינטרנט', 'תקשורת', 'מנויים', 'ביטוחים', 'בריאות', 'ילדים וחינוך',
    'ביגוד והנעלה', 'בית ותחזוקה', 'מתנות ואירועים', 'חופשות', 'אחר',
  ],
  income: ['משכורת', 'קצבאות', 'החזרים', 'מתנות', 'אחר'],
};

export function defaultState() {
  return {
    version: SCHEMA_VERSION,
    recurring: [],     // { id, kind:'income'|'expense', name, category, frequency:1|2, startMonth, endMonth, day, estimated, amounts:[{from, amount}] }
    installments: [],  // { id, name, category, total, payments, firstMonth, day }
    transactions: [],  // { id, date, type:'expense'|'income', amount, category, note }
    actuals: {},       // { 'itemId|YYYY-MM': amount }
    categories: structuredClone(DEFAULT_CATEGORIES),
  };
}

function migrate(s) {
  const base = defaultState();
  return { ...base, ...s, categories: { ...base.categories, ...(s.categories || {}) } };
}

export function load() {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) return migrate(JSON.parse(raw));
  } catch (e) {
    console.error('Failed to load state', e);
  }
  return defaultState();
}

export function save(state) {
  localStorage.setItem(KEY, JSON.stringify(state));
}

export function uid() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
}

export function exportJSON(state) {
  const blob = new Blob([JSON.stringify(state, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `budget-backup-${new Date().toISOString().slice(0, 10)}.json`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

export function parseBackup(text) {
  const s = JSON.parse(text);
  if (!s || !Array.isArray(s.transactions) || !Array.isArray(s.recurring)) {
    throw new Error('קובץ גיבוי לא תקין');
  }
  return migrate(s);
}
