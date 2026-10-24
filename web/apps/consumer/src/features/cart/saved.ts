import { useCallback, useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { http } from '@northline/client';
import { cartQuery, type CartLine } from './api';

/**
 * S-173 "Save for later" under the cart. The api has no saved-for-later list (no contract change in E-15), so the saved
 * lines are kept in this browser (`localStorage['nl.savedForLater']`, like the food order being built): what is needed
 * to show the line and to put it back (`POST /api/v1/cart/items { offerId, variantId, qty }`, the product page's call).
 * Saving removes the line from the cart (`DELETE …/items/{id}`). Read after hydration only (the server has no storage),
 * so the list appears in the browser's second render.
 */
export const SAVED_KEY = 'nl.savedForLater';
const MAX = 50;

export interface SavedLine {
  offerId: string; variantId?: string | null; productId: string; name: string; option?: string | null; unit?: string | null;
  imageUrl?: string | null; unitCents: number; qty: number; shopName: string;
}

export function readSaved(): SavedLine[] {
  try {
    const raw = typeof window === 'undefined' ? null : window.localStorage.getItem(SAVED_KEY);
    const list: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(list) ? list.filter((l): l is SavedLine => !!l && typeof l === 'object' && typeof (l as SavedLine).offerId === 'string' && typeof (l as SavedLine).name === 'string') : [];
  } catch { return []; }
}

function write(list: SavedLine[]) {
  try {
    if (list.length) window.localStorage.setItem(SAVED_KEY, JSON.stringify(list.slice(0, MAX)));
    else window.localStorage.removeItem(SAVED_KEY);
  } catch { /* private mode: nothing remembered */ }
}

const same = (a: Pick<SavedLine, 'offerId' | 'variantId'>, b: Pick<SavedLine, 'offerId' | 'variantId'>) => a.offerId === b.offerId && (a.variantId ?? null) === (b.variantId ?? null);

export function useSavedForLater() {
  const qc = useQueryClient();
  const [saved, setSaved] = useState<SavedLine[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  useEffect(() => { setSaved(readSaved()); }, []);
  const update = useCallback((next: SavedLine[]) => { write(next); setSaved(next); }, []);

  /** Moves a cart line to the saved list (the newest first), then out of the cart. */
  const save = async (line: CartLine, shopName: string) => {
    setBusy(true);
    setFailed(false);
    try {
      const entry: SavedLine = { offerId: line.offerId, variantId: line.variantId ?? null, productId: line.productId, name: line.name, option: line.option, unit: line.unit, imageUrl: line.imageUrl, unitCents: line.unitCents, qty: line.qty, shopName };
      update([entry, ...readSaved().filter(l => !same(l, entry))]);
      await http(`/api/v1/cart/items/${encodeURIComponent(line.itemId)}`, { method: 'DELETE' });
      void qc.invalidateQueries({ queryKey: cartQuery.queryKey });
      void qc.invalidateQueries({ queryKey: ['checkout'] });
    } catch { setFailed(true); } finally { setBusy(false); }
  };

  /** Puts a saved line back in the cart; it leaves the saved list once the cart took it. */
  const moveToCart = async (entry: SavedLine) => {
    setBusy(true);
    setFailed(false);
    try {
      await http('/api/v1/cart/items', { method: 'POST', body: { offerId: entry.offerId, ...(entry.variantId ? { variantId: entry.variantId } : {}), qty: entry.qty } });
      update(readSaved().filter(l => !same(l, entry)));
      void qc.invalidateQueries({ queryKey: cartQuery.queryKey });
      void qc.invalidateQueries({ queryKey: ['checkout'] });
    } catch { setFailed(true); } finally { setBusy(false); }
  };

  const remove = (entry: SavedLine) => update(readSaved().filter(l => !same(l, entry)));

  return { saved, busy, failed, save, moveToCart, remove };
}
