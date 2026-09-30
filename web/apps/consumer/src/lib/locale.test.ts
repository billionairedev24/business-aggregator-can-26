import { describe, expect, it } from 'vitest';
import { pickLocale } from './locale';

describe('pickLocale', () => {
  it('prefers the saved choice, then the browser language', () => {
    expect(pickLocale('fr', 'en-CA')).toBe('fr');
    expect(pickLocale('en', 'fr-CA,fr;q=0.9')).toBe('en');
    expect(pickLocale(undefined, 'fr-CA,fr;q=0.9,en;q=0.8')).toBe('fr');
    expect(pickLocale('de', 'en-US')).toBe('en');
    expect(pickLocale(null, null)).toBe('en');
  });
});
