import { describe, expect, it } from 'vitest';
import { clock, weekday, windowRange } from './format';

/**
 * Engineering follow-ups (S-117 finding): Node's ICU wrote "6–9 p.m." where Chrome writes "6–9 p.m.", so the
 * server-rendered product page and the browser's first render differed (React error #418 on every load). Times are
 * written with plain spaces whatever the ICU version.
 */
describe('times on server-rendered pages', () => {
  const special = /[   ]/;
  it('use plain spaces in English and French', () => {
    for (const locale of ['en', 'fr'] as const) {
      expect(windowRange('2026-10-01T00:00:00Z', '2026-10-01T03:00:00Z', locale, 'America/Edmonton')).not.toMatch(special);
      expect(windowRange('2026-10-01T00:30:00Z', '2026-10-01T03:00:00Z', locale, 'America/Edmonton')).not.toMatch(special);
      expect(clock('2026-09-30T23:20:00Z', locale, 'America/Edmonton')).not.toMatch(special);
      expect(weekday('2026-09-30T23:20:00Z', locale, 'America/Edmonton')).not.toMatch(special);
    }
    expect(windowRange('2026-10-01T00:00:00Z', '2026-10-01T03:00:00Z', 'en', 'America/Edmonton')).toBe('6–9 p.m.');
  });
});
