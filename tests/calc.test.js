import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  addMonths, daysInMonth, isRecurringDue, amountFor, installmentFor, monthSummary, historicalDailyAvg, nextDueMonth,
} from '../js/calc.js';
import { defaultState } from '../js/storage.js';
import { generateInsights } from '../js/insights.js';
import { demoState } from '../js/demo.js';

const rec = (o) => ({ id: o.name, kind: 'expense', category: 'חשבונות', frequency: 1, startMonth: '2026-01', endMonth: null, day: 1, estimated: false, amounts: [{ from: o.startMonth || '2026-01', amount: o.amount }], ...o });

test('month helpers', () => {
  assert.equal(addMonths('2026-11', 2), '2027-01');
  assert.equal(addMonths('2026-01', -1), '2025-12');
  assert.equal(daysInMonth('2026-02'), 28);
  assert.equal(daysInMonth('2028-02'), 29);
});

test('bimonthly charges are due every other month', () => {
  const r = rec({ name: 'חשמל', amount: 600, frequency: 2, startMonth: '2026-01' });
  assert.equal(isRecurringDue(r, '2026-01'), true);
  assert.equal(isRecurringDue(r, '2026-02'), false);
  assert.equal(isRecurringDue(r, '2026-03'), true);
  assert.equal(isRecurringDue(r, '2025-11'), false);
  assert.equal(nextDueMonth(r, '2026-04'), '2026-05');
});

test('ended charge is not due after endMonth', () => {
  const r = rec({ name: 'x', amount: 1, endMonth: '2026-05' });
  assert.equal(isRecurringDue(r, '2026-05'), true);
  assert.equal(isRecurringDue(r, '2026-06'), false);
});

test('amount history keeps past months unchanged', () => {
  const r = rec({ name: 'אינטרנט', amount: 100 });
  r.amounts.push({ from: '2026-06', amount: 130 });
  assert.equal(amountFor(r, '2026-05'), 100);
  assert.equal(amountFor(r, '2026-06'), 130);
  assert.equal(amountFor(r, '2026-12'), 130);
});

test('installments split with remainder on last payment', () => {
  const inst = { id: 'i', name: 'מקרר', total: 1000, payments: 3, firstMonth: '2026-10' };
  assert.deepEqual(installmentFor(inst, '2026-10'), { n: 1, of: 3, amount: 333.33 });
  assert.deepEqual(installmentFor(inst, '2026-12'), { n: 3, of: 3, amount: 333.34 });
  assert.equal(installmentFor(inst, '2027-01'), null);
  assert.equal(installmentFor(inst, '2026-09'), null);
});

test('current month forecast uses daily average', () => {
  const s = defaultState();
  s.recurring.push(rec({ name: 'משכורת', kind: 'income', amount: 10000 }));
  s.recurring.push(rec({ name: 'משכנתא', amount: 4000 }));
  s.actuals['משכנתא|2026-11'] = 4100;
  s.transactions.push({ id: 'a', date: '2026-11-02', type: 'expense', amount: 300, category: 'מזון וסופר', note: '' });
  s.transactions.push({ id: 'b', date: '2026-11-09', type: 'expense', amount: 700, category: 'מזון וסופר', note: '' });
  const today = new Date(2026, 10, 10); // Nov 10, 30 days
  const r = monthSummary(s, '2026-11', today);
  assert.equal(r.status, 'current');
  assert.equal(r.daysElapsed, 10);
  assert.equal(r.daysLeft, 20);
  assert.equal(r.dailyAvg, 100);          // 1000 / 10, no history
  assert.equal(r.projectedVar, 3000);     // 1000 + 100*20
  assert.equal(r.fixedExpense, 4100);     // actual overrides planned
  assert.equal(r.income, 10000);
  assert.equal(r.net, 10000 - 4100 - 3000);
  assert.equal(r.freeDaily, (10000 - 4100 - 1000) / 20);
});

test('early month blends with historical average', () => {
  const s = defaultState();
  // October: 3100 over 31 days = 100/day
  s.transactions.push({ id: 'o', date: '2026-10-05', type: 'expense', amount: 3100, category: 'אחר', note: '' });
  s.transactions.push({ id: 'n', date: '2026-11-01', type: 'expense', amount: 600, category: 'אחר', note: '' });
  assert.equal(historicalDailyAvg(s, '2026-11'), 100);
  const r = monthSummary(s, '2026-11', new Date(2026, 10, 2)); // day 2: cur avg 300
  // w = 0.2 -> 0.2*300 + 0.8*100 = 140
  assert.equal(r.dailyAvg, 140);
});

test('past month shows actual totals', () => {
  const s = defaultState();
  s.transactions.push({ id: 'o', date: '2026-10-05', type: 'expense', amount: 500, category: 'אחר', note: '' });
  const r = monthSummary(s, '2026-10', new Date(2026, 10, 15));
  assert.equal(r.status, 'past');
  assert.equal(r.projectedVar, 500);
  assert.equal(r.net, -500);
});

test('insights run on demo data and flag the large gift', () => {
  const today = new Date(2026, 9, 20);
  const s = demoState(today);
  const list = generateInsights(s, today);
  assert.ok(list.length > 0);
  assert.ok(list.some((i) => i.title === 'הוצאה חריגה'));
  assert.ok(list.some((i) => i.title.includes('חשמל התייקר')));
});
