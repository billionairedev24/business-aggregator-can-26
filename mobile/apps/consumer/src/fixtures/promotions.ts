/**
 * Mobile gaps part 2 in the fixture backend: made-up promo codes and the points rule, shared by the shop's quote and the
 * booking's price. Like the api: the code comes off the taxable price (tax on what's left), points pay like money
 * (1 point = 1 ¢, at most half of what's left to pay, from 100 points), and a refused code is a 422 on `promoCode`.
 */
export const FIXTURE_CODES: Record<string, { amountCents?: number; percent?: number; minSpendCents?: number; scope: Array<'goods' | 'service'> }> = {
  WELCOME5: { amountCents: 500, minSpendCents: 2000, scope: ['goods', 'service'] },
  SAVE10: { percent: 10, scope: ['goods', 'service'] },
  SHOPONLY: { amountCents: 300, scope: ['goods'] },
};

export type CodeAnswer = { discountCents: number; code?: string } | { error: string };

export function applyCode(raw: unknown, scope: 'goods' | 'service', subtotalCents: number): CodeAnswer {
  const code = typeof raw === 'string' ? raw.replace(/\s+/g, '').toUpperCase() : '';
  if (!code) return { discountCents: 0 };
  const c = FIXTURE_CODES[code];
  if (!c) return { error: "This code isn't valid." };
  if (!c.scope.includes(scope)) return { error: "This code doesn't apply to this order." };
  if (c.minSpendCents && subtotalCents < c.minSpendCents) return { error: `Spend at least $${Math.floor(c.minSpendCents / 100)}.${String(c.minSpendCents % 100).padStart(2, '0')} to use this code.` };
  const off = c.amountCents ?? Math.round((subtotalCents * (c.percent ?? 0)) / 100);
  return { discountCents: Math.min(off, Math.max(0, subtotalCents - 100)), code };
}

/** Points spent on `dueCents` when switched on: whole points, at most half, none under 100. */
export function pointsFor(balance: number, dueCents: number, use: unknown): number {
  if (use !== true || balance < 100) return 0;
  return Math.max(0, Math.min(balance, Math.floor(dueCents / 2)));
}

export const promoError = (message: string) => ({ errors: [{ field: 'promoCode', rule: 'promo', message }] });
