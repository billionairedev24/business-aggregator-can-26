import { afterEach, describe, expect, it } from 'vitest';
import { configurePlatformTimeZone, formatDate, platformTimeZone, setTimeZone, timeZone } from './i18n';

/** S-134: no zone in code — the platform zone is configured and the market's or merchant's zone is set on top. */
describe('time zones', () => {
  afterEach(() => { setTimeZone(undefined); configurePlatformTimeZone('UTC'); });

  it('reads in the platform zone until a market zone is set, and ignores unknown zones', () => {
    configurePlatformTimeZone('America/Halifax');
    expect(timeZone()).toBe('America/Halifax');
    setTimeZone('America/Vancouver');
    expect(timeZone()).toBe('America/Vancouver');
    expect(platformTimeZone()).toBe('America/Halifax');
    setTimeZone('Not/AZone');
    expect(timeZone()).toBe('America/Halifax');
    configurePlatformTimeZone('');
    expect(platformTimeZone()).toBe('America/Halifax');
  });

  it('formats in the current zone, or the one given', () => {
    const at = '2026-09-30T05:30:00Z';
    setTimeZone('America/Vancouver'); // 22:30 on the 29th
    expect(formatDate(at, 'en', 'date')).toBe('Sep 29');
    expect(formatDate(at, 'en', 'date', 'America/Toronto')).toBe('Sep 30'); // 01:30 on the 30th
  });
});
