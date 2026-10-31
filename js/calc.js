// Pure calculation logic (no DOM). Months are 'YYYY-MM' strings, dates 'YYYY-MM-DD'.

export const pad = (n) => String(n).padStart(2, '0');
export const ym = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}`;
export const ymd = (d) => `${ym(d)}-${pad(d.getDate())}`;

export function monthIndex(m) {
  const [y, mo] = m.split('-').map(Number);
  return y * 12 + mo - 1;
}

export function addMonths(m, n) {
  const i = monthIndex(m) + n;
  return `${Math.floor(i / 12)}-${pad((i % 12) + 1)}`;
}

export function daysInMonth(m) {
  const [y, mo] = m.split('-').map(Number);
  return new Date(y, mo, 0).getDate();
}

const round2 = (x) => Math.round(x * 100) / 100;
const sum = (arr, f = (x) => x) => arr.reduce((s, x) => s + f(x), 0);

/** Planned amount of a recurring item for a given month (amount history aware). */
export function amountFor(item, m) {
  const list = [...(item.amounts || [])].sort((a, b) => (a.from < b.from ? -1 : 1));
  let amount = list.length ? list[0].amount : 0;
  for (const e of list) if (e.from <= m) amount = e.amount;
  return amount;
}

export function isRecurringDue(item, m) {
  const diff = monthIndex(m) - monthIndex(item.startMonth);
  if (diff < 0) return false;
  if (item.endMonth && m > item.endMonth) return false;
  return diff % (item.frequency || 1) === 0;
}

/** Next month (>= from) in which a recurring item is due, or null. */
export function nextDueMonth(item, from) {
  for (let i = 0; i < 24; i++) {
    const m = addMonths(from, i);
    if (isRecurringDue(item, m)) return m;
  }
  return null;
}

/** Installment info for a month: { n, of, amount } or null if not due. */
export function installmentFor(inst, m) {
  const idx = monthIndex(m) - monthIndex(inst.firstMonth);
  if (idx < 0 || idx >= inst.payments) return null;
  const base = Math.floor((inst.total / inst.payments) * 100) / 100;
  const last = round2(inst.total - base * (inst.payments - 1));
  return { n: idx + 1, of: inst.payments, amount: idx === inst.payments - 1 ? last : base };
}

/** All fixed lines (recurring incomes/expenses + installments) for a month. */
export function fixedLinesFor(state, m) {
  const lines = [];
  for (const item of state.recurring) {
    if (!isRecurringDue(item, m)) continue;
    const planned = amountFor(item, m);
    const key = `${item.id}|${m}`;
    const actual = state.actuals[key];
    lines.push({
      id: item.id,
      key,
      source: 'recurring',
      kind: item.kind,
      group: item.frequency === 2 ? 'bimonthly' : 'monthly',
      name: item.name,
      category: item.category,
      day: item.day || 1,
      estimated: !!item.estimated,
      planned,
      actual: actual ?? null,
      amount: actual ?? planned,
    });
  }
  for (const inst of state.installments) {
    const info = installmentFor(inst, m);
    if (!info) continue;
    lines.push({
      id: inst.id,
      key: `${inst.id}|${m}`,
      source: 'installment',
      kind: 'expense',
      group: 'installment',
      name: inst.name,
      category: inst.category,
      day: inst.day || 1,
      estimated: false,
      planned: info.amount,
      actual: null,
      amount: info.amount,
      n: info.n,
      of: info.of,
    });
  }
  return lines.sort((a, b) => a.day - b.day);
}

export function txFor(state, m) {
  return state.transactions.filter((t) => t.date.startsWith(m));
}

/** Months (sorted) that have at least one variable transaction. */
export function trackedMonths(state) {
  return [...new Set(state.transactions.map((t) => t.date.slice(0, 7)))].sort();
}

/** Average daily variable expense over up to `n` tracked months before `m`. */
export function historicalDailyAvg(state, m, n = 3) {
  const months = trackedMonths(state).filter((x) => x < m).slice(-n);
  if (!months.length) return null;
  const total = sum(months, (mm) => sum(txFor(state, mm).filter((t) => t.type === 'expense'), (t) => t.amount));
  const days = sum(months, (mm) => daysInMonth(mm));
  return total / days;
}

/**
 * Full summary of a month. For the current month includes a forecast based on
 * the daily average; for past months it's the actual result.
 */
export function monthSummary(state, m, today = new Date()) {
  const cur = ym(today);
  const status = m === cur ? 'current' : m < cur ? 'past' : 'future';
  const lines = fixedLinesFor(state, m);
  const tx = txFor(state, m);

  const fixedIncome = sum(lines.filter((l) => l.kind === 'income'), (l) => l.amount);
  const fixedExpense = sum(lines.filter((l) => l.kind === 'expense'), (l) => l.amount);
  const varIncome = sum(tx.filter((t) => t.type === 'income'), (t) => t.amount);
  const varExpense = sum(tx.filter((t) => t.type === 'expense'), (t) => t.amount);

  const daysTotal = daysInMonth(m);
  const daysElapsed = status === 'current' ? today.getDate() : status === 'past' ? daysTotal : 0;
  const daysLeft = daysTotal - daysElapsed;
  const histAvg = historicalDailyAvg(state, m);
  const currentAvg = daysElapsed ? varExpense / daysElapsed : 0;

  let dailyAvg = currentAvg;
  let projectedVar = varExpense;
  if (status === 'current') {
    if (histAvg != null) {
      const w = Math.min(daysElapsed / 10, 1);
      dailyAvg = w * currentAvg + (1 - w) * histAvg;
    }
    projectedVar = varExpense + dailyAvg * daysLeft;
  } else if (status === 'future') {
    dailyAvg = histAvg ?? 0;
    projectedVar = varExpense + dailyAvg * daysTotal;
  }

  const income = fixedIncome + varIncome;
  const expenses = fixedExpense + projectedVar;
  const net = income - expenses;
  // How much can be spent per remaining day without going into a loss.
  const freeDaily = daysLeft > 0 ? (income - fixedExpense - varExpense) / daysLeft : null;

  return {
    month: m, status, lines, tx,
    fixedIncome: round2(fixedIncome), fixedExpense: round2(fixedExpense),
    varIncome: round2(varIncome), varExpense: round2(varExpense),
    projectedVar: round2(projectedVar),
    income: round2(income), expenses: round2(expenses), net: round2(net),
    daysTotal, daysElapsed, daysLeft,
    dailyAvg: round2(dailyAvg), currentAvg: round2(currentAvg),
    histAvg: histAvg == null ? null : round2(histAvg),
    freeDaily: freeDaily == null ? null : round2(freeDaily),
  };
}

/** Expenses by category for a month (fixed + variable). Sorted desc. */
export function categoryBreakdown(state, m, { variableOnly = false } = {}) {
  const map = new Map();
  const add = (cat, amt) => map.set(cat, (map.get(cat) || 0) + amt);
  for (const t of txFor(state, m)) if (t.type === 'expense') add(t.category, t.amount);
  if (!variableOnly) for (const l of fixedLinesFor(state, m)) if (l.kind === 'expense') add(l.category, l.amount);
  return [...map.entries()].map(([category, amount]) => ({ category, amount: round2(amount) }))
    .sort((a, b) => b.amount - a.amount);
}

/** Month range from the first month with any data up to `to` (max `limit`). */
export function historyMonths(state, to, limit = 12) {
  const starts = [
    ...state.transactions.map((t) => t.date.slice(0, 7)),
    ...state.recurring.map((r) => r.startMonth),
    ...state.installments.map((i) => i.firstMonth),
  ].filter((x) => x <= to).sort();
  const first = starts[0] || to;
  const out = [];
  for (let m = to; m >= first && out.length < limit; m = addMonths(m, -1)) out.push(m);
  return out;
}
