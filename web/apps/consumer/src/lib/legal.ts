import type { Locale } from '@northline/ui';

/*
 * S-116 (Loi 96 readiness): which language the Terms of Service and Privacy Policy are presented in.
 *
 * The legal texts (design 09/10, web/packages/legal) exist in English only. A French version needs counsel's reading
 * and a certified translator (counsel question D1, docs/compliance/legal/counsel-questions.md); `make i18n-check
 * STRICT=1` fails until it exists. When `web/packages/legal/pages/<doc>.fr-CA.html` is published, add the document here
 * and every link below serves it.
 */
export type LegalDoc = 'terms' | 'privacy';
export const FRENCH_LEGAL: readonly LegalDoc[] = [];

/** The page of a legal text in a language: the French one when it exists, else the English (the only one today). */
export function legalHref(doc: LegalDoc, lang: Locale = 'en', french: readonly LegalDoc[] = FRENCH_LEGAL): string {
  return lang === 'fr' && french.includes(doc) ? `/legal/${doc}.fr-CA.html` : `/legal/${doc}.html`;
}

export interface TermsPresentation {
  /** The language the Terms are shown (and accepted) in — sent to northline-auth with the acceptance. */
  language: Locale;
  /** A French version exists to show. */
  frenchAvailable: boolean;
  /** French comes first here (French-first place or French interface); English only on an express request. */
  frenchFirst: boolean;
}

/**
 * Contracts of adhesion in a French-first place (region configuration) are presented in French first; English only
 * when the person expressly asks for it, and that request is recorded with the acceptance (identity.users,
 * terms_english_requested_at). Elsewhere the Terms follow the interface's language.
 */
export function termsPresentation(opts: { frenchFirst: boolean; locale: Locale; englishRequested: boolean; french?: readonly LegalDoc[] }): TermsPresentation {
  const french = opts.french ?? FRENCH_LEGAL;
  const frenchAvailable = french.includes('terms');
  const frenchFirst = opts.frenchFirst || opts.locale === 'fr';
  const language: Locale = frenchFirst && frenchAvailable && !opts.englishRequested ? 'fr' : 'en';
  return { language, frenchAvailable, frenchFirst };
}
