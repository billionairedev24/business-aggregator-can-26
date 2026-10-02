import { useCallback, useSyncExternalStore } from 'react';

import type { Hold } from '../api/services';

/**
 * The booking wizard's answers across its three screens (design 01 `book_service` → `book_slot` → `book_review`),
 * one draft per provider, in memory only (nothing personal is kept on the phone). Booking or leaving the provider for
 * another one doesn't clear it until a booking is made ({@link clearDraft}).
 */
export interface BookingDraft {
  serviceId?: string;
  /** "2018 Honda Civic · BKT 4471" (the design's one field), split for the api by {@link parseVehicle}. */
  vehicle: string;
  /** "Tell … anything useful" — the job's description. */
  note: string;
  /** The picked slot. */
  startsAt?: string;
  /** The slot held for 10 minutes (signed in only). */
  hold?: Hold;
  address: string;
  /** Index into the "Where is the vehicle?" / "How do we get in?" options. */
  spot?: number;
  access: string;
  /** "Ask for a quote" instead of booking a fixed price. */
  quote: boolean;
}

const EMPTY: BookingDraft = { vehicle: '', note: '', address: '', access: '', quote: false };
const drafts = new Map<string, BookingDraft>();
const listeners = new Set<() => void>();
const emit = () => listeners.forEach((l) => l());
const subscribe = (l: () => void) => {
  listeners.add(l);
  return () => void listeners.delete(l);
};

export function getDraft(slug: string): BookingDraft {
  return drafts.get(slug) ?? EMPTY;
}

export function updateDraft(slug: string, patch: Partial<BookingDraft>) {
  drafts.set(slug, { ...getDraft(slug), ...patch });
  emit();
}

export function clearDraft(slug?: string) {
  if (slug) drafts.delete(slug);
  else drafts.clear();
  emit();
}

/** The provider's draft and a setter (re-renders on every change). */
export function useDraft(slug: string): [BookingDraft, (patch: Partial<BookingDraft>) => void] {
  const draft = useSyncExternalStore(subscribe, () => getDraft(slug), () => getDraft(slug));
  const update = useCallback((patch: Partial<BookingDraft>) => updateDraft(slug, patch), [slug]);
  return [draft, update];
}

/**
 * "2018 Honda Civic · BKT 4471" → year, make, model and plate (the plate after "·" is optional). Null when the year,
 * make or model is missing — the api's own rule ("Tell us the vehicle year, make and model.").
 */
export function parseVehicle(text: string): { year: string; make: string; model: string; plate?: string } | null {
  const [car = '', plate] = text.split('·').map((s) => s.trim());
  const m = /^(\d{4})\s+(\S+)\s+(.+)$/.exec(car);
  if (!m) return null;
  return { year: m[1]!, make: m[2]!, model: m[3]!.trim(), ...(plate ? { plate } : {}) };
}
