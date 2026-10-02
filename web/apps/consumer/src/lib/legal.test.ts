import { describe, expect, it } from 'vitest';
import { legalHref, termsPresentation } from './legal';

/** S-116: the Terms in French first where the place is French-first; English only on an express request. */
describe('legal texts by language', () => {
  it('serves the French page only when it exists', () => {
    expect(legalHref('terms', 'fr')).toBe('/legal/terms.html'); // no French version yet (counsel question D1)
    expect(legalHref('terms', 'fr', ['terms'])).toBe('/legal/terms.fr-CA.html');
    expect(legalHref('privacy', 'fr', ['terms'])).toBe('/legal/privacy.html');
    expect(legalHref('terms', 'en', ['terms'])).toBe('/legal/terms.html');
  });

  it('presents the Terms in French first in a French-first place, English on request', () => {
    const french = ['terms', 'privacy'] as const;
    expect(termsPresentation({ frenchFirst: true, locale: 'en', englishRequested: false, french }).language).toBe('fr');
    expect(termsPresentation({ frenchFirst: true, locale: 'en', englishRequested: true, french }).language).toBe('en');
    expect(termsPresentation({ frenchFirst: false, locale: 'en', englishRequested: false, french }).language).toBe('en');
    expect(termsPresentation({ frenchFirst: false, locale: 'fr', englishRequested: false, french }).language).toBe('fr');
    // today: no French text, so English, and the form says so
    const today = termsPresentation({ frenchFirst: true, locale: 'fr', englishRequested: false });
    expect(today).toEqual({ language: 'en', frenchAvailable: false, frenchFirst: true });
  });
});
