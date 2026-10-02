import type { ReactNode } from 'react';
import type { Locale } from '@northline/ui';
import { legalHref, type LegalDoc } from '../../lib/legal';
import { useAuthT } from './messages';

/**
 * Terms / Privacy (design 09 / 10, verbatim): always in a new tab, so the form isn't lost (CLAUDE.md). S-116: in the
 * language they are presented in (`lang`), when that version exists.
 */
export function LegalLink({ doc, lang = 'en', children }: { doc: LegalDoc; lang?: Locale; children: ReactNode }) {
  const t = useAuthT();
  const href = legalHref(doc, lang);
  return (
    <a href={href} hrefLang={href.endsWith('.fr-CA.html') ? 'fr-CA' : 'en-CA'} target="_blank" rel="noopener">
      {children}<span className="nl-sr-only"> {t('newTab')}</span>
    </a>
  );
}
