import { describe, expect, it } from 'vitest';
import { chosenLocale, pickLocale } from './locale';

describe('pickLocale', () => {
  it('prefers the saved choice, then the browser language', () => {
    expect(pickLocale('fr', 'en-CA')).toBe('fr');
    expect(pickLocale('en', 'fr-CA,fr;q=0.9')).toBe('en');
    expect(pickLocale(undefined, 'fr-CA,fr;q=0.9,en;q=0.8')).toBe('fr');
    expect(pickLocale('de', 'en-US')).toBe('en');
    expect(pickLocale(null, null)).toBe('en');
  });

  // S-116: "the browser asks for French" = French weighs more than English, whatever the order
  it('reads the weights of Accept-Language', () => {
    expect(pickLocale(undefined, 'en-US;q=0.5,fr-CA;q=0.9')).toBe('fr');
    expect(pickLocale(undefined, 'de-DE,fr;q=0.8,en;q=0.7')).toBe('fr');
    expect(pickLocale(undefined, 'en-CA,fr-CA;q=0.9')).toBe('en');
    expect(pickLocale(undefined, '*,fr;q=0.5')).toBe('en');
    expect(pickLocale(undefined, 'fr;q=0,en;q=0.1')).toBe('en');
  });

  it('knows when the visitor chose a language (a French-first place never overrides it)', () => {
    expect(chosenLocale(undefined, 'https://x.test/?lang=en')).toBe('en');
    expect(chosenLocale('fr', 'https://x.test/')).toBe('fr');
    expect(chosenLocale(undefined, 'https://x.test/')).toBeUndefined();
  });
});
