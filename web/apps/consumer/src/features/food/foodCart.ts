import { useCallback, useSyncExternalStore } from 'react';
import { z } from 'zod';

/**
 * The food order being built (S-57): one kitchen at a time, kept in this browser (`localStorage['nl.foodCart']`) and
 * separate from the shop cart (S-51's server-side, multi-shop cart) — a food order goes to one kitchen by direct
 * courier or pickup, is priced from the live menu at checkout, and never mixes with a pooled goods run
 * (docs/DECISIONS.md S-57). Prices here are what the menu showed; checkout prices again on the server.
 */
export const FoodLine = z.object({
  key: z.string(),
  kind: z.enum(['item', 'combo']),
  itemId: z.string().optional(),
  comboId: z.string().optional(),
  title: z.string(),
  qty: z.number().int().min(1).max(20),
  unitCents: z.number().int(),
  optionIds: z.array(z.string()).default([]),
  choices: z.array(z.string()).default([]),
  itemIds: z.array(z.string()).default([]),
  note: z.string().optional(),
});
export type FoodLine = z.infer<typeof FoodLine>;
export const FoodCart = z.object({
  merchantId: z.string(),
  slug: z.string(),
  name: z.string(),
  lines: z.array(FoodLine),
});
export type FoodCart = z.infer<typeof FoodCart>;

export const FOOD_CART_KEY = 'nl.foodCart';
const EVENT = 'nl-food-cart';

function read(): FoodCart | null {
  try {
    const raw = typeof window === 'undefined' ? null : window.localStorage.getItem(FOOD_CART_KEY);
    return raw ? FoodCart.parse(JSON.parse(raw)) : null;
  } catch { return null; }
}

let cached: { raw: string | null; value: FoodCart | null } = { raw: null, value: null };
function snapshot(): FoodCart | null {
  let raw: string | null = null;
  try { raw = typeof window === 'undefined' ? null : window.localStorage.getItem(FOOD_CART_KEY); } catch { raw = null; }
  if (raw !== cached.raw) cached = { raw, value: read() };
  return cached.value;
}

function write(cart: FoodCart | null) {
  try {
    if (cart && cart.lines.length > 0) window.localStorage.setItem(FOOD_CART_KEY, JSON.stringify(cart));
    else window.localStorage.removeItem(FOOD_CART_KEY);
  } catch { /* private mode: this page only */ }
  window.dispatchEvent(new Event(EVENT));
}

function subscribe(cb: () => void) {
  window.addEventListener(EVENT, cb);
  window.addEventListener('storage', cb);
  return () => { window.removeEventListener(EVENT, cb); window.removeEventListener('storage', cb); };
}

export const subtotalOf = (cart: FoodCart | null) => (cart ? cart.lines.reduce((n, l) => n + l.unitCents * l.qty, 0) : 0);

/** The order in progress; null on the server and until the browser has read it (hydration-safe). */
export function useFoodCart() {
  const cart = useSyncExternalStore(subscribe, snapshot, () => null);
  const add = useCallback((kitchen: { merchantId: string; slug: string; name: string }, line: Omit<FoodLine, 'key'>) => {
    const current = read();
    const base = current && current.merchantId === kitchen.merchantId ? current : { ...kitchen, lines: [] };
    write({ ...base, lines: [...base.lines, { ...line, key: `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}` }] });
  }, []);
  const remove = useCallback((key: string) => {
    const current = read();
    if (current) write({ ...current, lines: current.lines.filter(l => l.key !== key) });
  }, []);
  const clear = useCallback(() => write(null), []);
  return { cart, add, remove, clear };
}
