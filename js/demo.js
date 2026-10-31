// Sample data so the app can be explored before entering real data.
import { ym, ymd, addMonths, daysInMonth } from './calc.js';
import { defaultState, uid } from './storage.js';

export function demoState(today = new Date()) {
  const s = defaultState();
  const m = ym(today);
  const start = addMonths(m, -4);
  const rec = (kind, name, category, amount, opts = {}) => s.recurring.push({
    id: uid(), kind, name, category, frequency: opts.frequency || 1,
    startMonth: opts.startMonth || start, endMonth: null, day: opts.day || 1,
    estimated: !!opts.estimated, amounts: [{ from: opts.startMonth || start, amount }],
  });
  rec('income', 'משכורת 1', 'משכורת', 14500, { day: 10 });
  rec('income', 'משכורת 2', 'משכורת', 9800, { day: 1 });
  rec('income', 'קצבת ילדים', 'קצבאות', 340, { day: 20 });
  rec('expense', 'משכנתא', 'משכנתא ודיור', 5200, { day: 15 });
  rec('expense', 'אינטרנט', 'טלויזיה ואינטרנט', 120, { day: 5 });
  rec('expense', 'טלויזיה', 'טלויזיה ואינטרנט', 180, { day: 5 });
  rec('expense', 'סלולר', 'תקשורת', 110, { day: 8 });
  rec('expense', 'ביטוח רכב', 'ביטוחים', 290, { day: 2 });
  rec('expense', 'גן ילדים', 'ילדים וחינוך', 2600, { day: 1 });
  rec('expense', 'ארנונה', 'חשבונות', 980, { frequency: 2, day: 1, startMonth: addMonths(start, 1) });
  rec('expense', 'מים', 'חשבונות', 260, { frequency: 2, day: 20, estimated: true });
  rec('expense', 'חשמל', 'חשבונות', 650, { frequency: 2, day: 25, estimated: true, startMonth: addMonths(start, 1) });
  rec('expense', 'גז', 'חשבונות', 90, { frequency: 2, day: 12, estimated: true });

  // A few recorded actual bills
  const elec = s.recurring.find((r) => r.name === 'חשמל');
  s.actuals[`${elec.id}|${addMonths(start, 1)}`] = 610;
  s.actuals[`${elec.id}|${addMonths(start, 3)}`] = 780;

  s.installments.push({
    id: uid(), name: 'מקרר', category: 'בית ותחזוקה', total: 6000, payments: 12,
    firstMonth: addMonths(m, -4), day: 2,
  });

  // Variable transactions
  let seed = 7;
  const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
  const patterns = [
    ['מזון וסופר', 0.35, 120, 450, ['שופרסל', 'רמי לוי', 'מכולת', 'ירקן']],
    ['מסעדות ובילוי', 0.15, 60, 280, ['פיצה', 'בית קפה', 'סושי', 'קולנוע']],
    ['תחבורה ודלק', 0.12, 150, 320, ['דלק', 'חניה', 'רב-קו']],
    ['ביגוד והנעלה', 0.04, 100, 450, ['נעליים', 'בגדים לילדים']],
    ['בריאות', 0.04, 40, 250, ['בית מרקחת', 'רופא שיניים']],
    ['אחר', 0.05, 30, 200, ['שונות']],
  ];
  for (let i = 4; i >= 0; i--) {
    const mm = addMonths(m, -i);
    const days = i === 0 ? today.getDate() : daysInMonth(mm);
    for (let d = 1; d <= days; d++) {
      for (const [category, p, min, max, notes] of patterns) {
        if (rnd() < p) {
          s.transactions.push({
            id: uid(), date: `${mm}-${String(d).padStart(2, '0')}`, type: 'expense',
            amount: Math.round(min + rnd() * (max - min)), category,
            note: notes[Math.floor(rnd() * notes.length)],
          });
        }
      }
    }
  }
  s.transactions.push({ id: uid(), date: ymd(today), type: 'expense', amount: 2400, category: 'מתנות ואירועים', note: 'מתנה לחתונה' });
  s.transactions.push({ id: uid(), date: `${addMonths(m, -1)}-14`, type: 'income', amount: 650, category: 'החזרים', note: 'החזר מס' });
  return s;
}
