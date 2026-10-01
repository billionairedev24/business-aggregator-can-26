import { z } from 'zod';

/**
 * "Your recent" searches (design 06 suggestions; S-44: the client's, not stored by the API): this browser's last
 * searches, newest first, in localStorage. Nothing is sent anywhere.
 */
export const RECENT_KEY = 'nl.recentSearches';
const MAX = 5;
const Recent = z.array(z.object({ q: z.string().min(1).max(100), at: z.number() }));
export type RecentSearch = z.infer<typeof Recent>[number];

export function readRecent(): RecentSearch[] {
  try {
    const raw = typeof window === 'undefined' ? null : window.localStorage.getItem(RECENT_KEY);
    return raw ? Recent.parse(JSON.parse(raw)) : [];
  } catch {
    return [];
  }
}

export function rememberSearch(q: string, now = Date.now()): void {
  const text = q.trim().slice(0, 100);
  if (!text || typeof window === 'undefined') return;
  const next = [{ q: text, at: now }, ...readRecent().filter(r => r.q.toLowerCase() !== text.toLowerCase())].slice(0, MAX);
  try { window.localStorage.setItem(RECENT_KEY, JSON.stringify(next)); } catch { /* private mode: nothing remembered */ }
}

/** Whole days between `at` and `now` in the browser's calendar (0 = today, 1 = yesterday). */
export function daysAgo(at: number, now = Date.now()): number {
  const day = (t: number) => { const d = new Date(t); return Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()); };
  return Math.max(0, Math.round((day(now) - day(at)) / 86_400_000));
}
