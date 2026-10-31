// Insight engine: finds unusual spending and saving opportunities.
import {
  ym, addMonths, monthSummary, categoryBreakdown, txFor, trackedMonths,
  fixedLinesFor, isRecurringDue, amountFor,
} from './calc.js';

const fmt = (n) => '₪' + Math.round(n).toLocaleString('he-IL');
const median = (arr) => {
  if (!arr.length) return 0;
  const s = [...arr].sort((a, b) => a - b);
  const h = Math.floor(s.length / 2);
  return s.length % 2 ? s[h] : (s[h - 1] + s[h]) / 2;
};

/**
 * Returns a list of insights: { level: 'critical'|'warning'|'info'|'good', icon, title, text }
 */
export function generateInsights(state, today = new Date()) {
  const out = [];
  const m = ym(today);
  const s = monthSummary(state, m, today);
  const prevTracked = trackedMonths(state).filter((x) => x < m).slice(-3);

  // 1. Forecast for the month
  if (s.income > 0 || s.expenses > 0) {
    if (s.net < 0) {
      out.push({
        level: 'critical', icon: '⚠️', title: 'צפי להפסד החודש',
        text: `לפי הקצב הנוכחי החודש צפוי להסתיים בהפסד של ${fmt(-s.net)}. ` +
          (s.freeDaily != null && s.freeDaily > 0
            ? `כדי לסיים מאוזן, נסו להוציא עד ${fmt(s.freeDaily)} ביום עד סוף החודש.`
            : 'ההוצאות כבר עברו את ההכנסות – כדאי לצמצם הוצאות משתנות.'),
      });
    } else if (s.income > 0) {
      out.push({
        level: 'good', icon: '✅', title: 'בדרך לחודש חיובי',
        text: `צפי לחיסכון של ${fmt(s.net)} החודש (${Math.round((s.net / s.income) * 100)}% מההכנסות).`,
      });
    }
  }

  // 2. Daily average vs previous months
  if (s.histAvg && s.daysElapsed >= 5 && s.currentAvg > s.histAvg * 1.2) {
    out.push({
      level: 'warning', icon: '📈', title: 'קצב הוצאות יומי גבוה מהרגיל',
      text: `ממוצע יומי החודש ${fmt(s.currentAvg)} לעומת ${fmt(s.histAvg)} בחודשים הקודמים ` +
        `(+${Math.round((s.currentAvg / s.histAvg - 1) * 100)}%).`,
    });
  }

  // 3. Category above its usual level
  if (prevTracked.length) {
    const avgByCat = new Map();
    for (const pm of prevTracked) {
      for (const { category, amount } of categoryBreakdown(state, pm, { variableOnly: true })) {
        avgByCat.set(category, (avgByCat.get(category) || 0) + amount / prevTracked.length);
      }
    }
    for (const { category, amount } of categoryBreakdown(state, m, { variableOnly: true })) {
      const avg = avgByCat.get(category);
      if (!avg) continue;
      if (amount > avg * 1.2 && amount - avg > 100) {
        out.push({
          level: 'warning', icon: '🔺', title: `חריגה ב${category}`,
          text: `כבר הוצאו ${fmt(amount)} על ${category} החודש, לעומת ממוצע של ${fmt(avg)} בחודש רגיל.`,
        });
      }
    }
  }

  // 4. Unusually large single transactions (last 30 days)
  const expenses = state.transactions.filter((t) => t.type === 'expense');
  if (expenses.length >= 8) {
    const since = addMonths(m, -3);
    const recentPool = expenses.filter((t) => t.date.slice(0, 7) >= since).map((t) => t.amount);
    const med = median(recentPool);
    const cutoff = new Date(today); cutoff.setDate(cutoff.getDate() - 30);
    const cutoffStr = cutoff.toISOString().slice(0, 10);
    const big = expenses
      .filter((t) => t.date >= cutoffStr && t.amount >= Math.max(med * 4, 400))
      .sort((a, b) => b.amount - a.amount)
      .slice(0, 3);
    for (const t of big) {
      out.push({
        level: 'info', icon: '🔎', title: 'הוצאה חריגה',
        text: `${fmt(t.amount)} ב${t.category}${t.note ? ` (${t.note})` : ''} בתאריך ` +
          `${t.date.split('-').reverse().join('/')} – פי ${Math.round(t.amount / (med || 1))} מהוצאה טיפוסית.`,
      });
    }
  }

  // 5. Bills (estimated / bimonthly) that rose compared to their previous bill
  for (const item of state.recurring.filter((r) => r.kind === 'expense')) {
    const months = [];
    for (let i = 0; i < 14 && months.length < 2; i++) {
      const mm = addMonths(m, -i);
      if (isRecurringDue(item, mm) && state.actuals[`${item.id}|${mm}`] != null) months.push(mm);
    }
    if (months.length < 2) continue;
    const [last, prev] = months.map((mm) => state.actuals[`${item.id}|${mm}`]);
    if (prev > 0 && last > prev * 1.15) {
      out.push({
        level: 'warning', icon: '💡', title: `${item.name} התייקר`,
        text: `החיוב האחרון ${fmt(last)} לעומת ${fmt(prev)} בחיוב הקודם (+${Math.round((last / prev - 1) * 100)}%). ` +
          'כדאי לבדוק צריכה או חריגה בחשבון.',
      });
    }
  }

  // 6. Saving opportunities
  const lines = fixedLinesFor(state, m);
  const subsCats = ['תקשורת', 'מנויים', 'טלויזיה ואינטרנט'];
  const monthlySubs = state.recurring.filter((r) => r.kind === 'expense' && subsCats.includes(r.category) &&
    (!r.endMonth || r.endMonth >= m));
  const subsTotal = monthlySubs.reduce((acc, r) => acc + amountFor(r, m) / (r.frequency || 1), 0);
  if (subsTotal >= 300) {
    out.push({
      level: 'info', icon: '📺', title: 'מנויים ותקשורת',
      text: `${monthlySubs.length} מנויים בעלות של כ-${fmt(subsTotal)} בחודש (${fmt(subsTotal * 12)} בשנה). ` +
        'שיחה עם הספקים או מעבר חבילה יכולים לחסוך מאות שקלים בשנה.',
    });
  }

  const varCats = categoryBreakdown(state, m, { variableOnly: true });
  const varTotal = varCats.reduce((a, c) => a + c.amount, 0);
  if (varTotal > 0 && varCats[0] && varCats[0].amount / varTotal > 0.35 && s.daysElapsed >= 7) {
    const top = varCats[0];
    const cut = top.amount * 0.1 * (s.daysTotal / Math.max(s.daysElapsed, 1));
    out.push({
      level: 'info', icon: '🎯', title: `מקום לחיסכון: ${top.category}`,
      text: `${top.category} מהווה ${Math.round((top.amount / varTotal) * 100)}% מההוצאות המשתנות החודש. ` +
        `הפחתה של 10% תחסוך כ-${fmt(cut)} בחודש.`,
    });
  }

  const eatOut = varCats.find((c) => c.category === 'מסעדות ובילוי');
  const food = varCats.find((c) => c.category === 'מזון וסופר');
  if (eatOut && food && eatOut.amount > food.amount * 0.5 && eatOut.amount > 400) {
    out.push({
      level: 'info', icon: '🍽️', title: 'מסעדות ובילוי',
      text: `ההוצאה על מסעדות ובילוי (${fmt(eatOut.amount)}) גבוהה יחסית להוצאה על סופר (${fmt(food.amount)}).`,
    });
  }

  // 7. Installments ending soon
  for (const inst of state.installments) {
    const lastMonth = addMonths(inst.firstMonth, inst.payments - 1);
    if (lastMonth === m || lastMonth === addMonths(m, 1)) {
      out.push({
        level: 'good', icon: '🎉', title: `${inst.name} – תשלומים מסתיימים`,
        text: `התשלום האחרון יהיה ב-${lastMonth.split('-').reverse().join('/')}. ` +
          `מהחודש שאחריו יתפנו ${fmt(inst.total / inst.payments)} בחודש.`,
      });
    }
  }

  // 8. Data hygiene
  const pendingEstimates = lines.filter((l) => l.estimated && l.actual == null && l.day < today.getDate());
  if (pendingEstimates.length) {
    out.push({
      level: 'info', icon: '✏️', title: 'עדכון סכומים בפועל',
      text: `${pendingEstimates.map((l) => l.name).join(', ')} – הסכום משוער. עדכנו את הסכום בפועל לצפי מדויק יותר.`,
    });
  }
  if (!txFor(state, m).length && today.getDate() > 3 && state.transactions.length) {
    out.push({
      level: 'info', icon: '📝', title: 'לא הוזנו הוצאות החודש',
      text: 'כדי שהצפי יהיה מדויק, הזינו גם הוצאות יומיות (קניות, דלק, בילויים).',
    });
  }

  const order = { critical: 0, warning: 1, info: 2, good: 3 };
  return out.sort((a, b) => order[a.level] - order[b.level]);
}
