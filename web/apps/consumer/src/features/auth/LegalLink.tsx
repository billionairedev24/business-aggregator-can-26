import type { ReactNode } from 'react';
import { useAuthT } from './messages';

/** Terms / Privacy (design 09 / 10, verbatim): always in a new tab, so the form isn't lost (CLAUDE.md). */
export function LegalLink({ doc, children }: { doc: 'terms' | 'privacy'; children: ReactNode }) {
  const t = useAuthT();
  return (
    <a href={`/legal/${doc}.html`} target="_blank" rel="noopener">
      {children}<span className="nl-sr-only"> {t('newTab')}</span>
    </a>
  );
}
