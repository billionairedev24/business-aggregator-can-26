import type { ReactNode } from 'react';
import { Logo } from '@northline/ui';
import { useShellT } from './messages';

/**
 * A page outside the Studio's shell (an emailed link, a page that does not exist): the logo, then one white card on
 * linen, centred — the same frame as the sign-in page. `icon` sits in a round tile above the content.
 */
export function Standalone({ icon, tone = 'success', children }: { icon?: ReactNode; tone?: 'success' | 'neutral'; children: ReactNode }) {
  const t = useShellT();
  return (
    <div className="nl-solo">
      <div className="nl-solo-brand"><Logo size={30} product={t('studio')} /></div>
      <main className="nl-solo-card" id="main" tabIndex={-1}>
        {icon ? <span className="nl-solo-icon" data-tone={tone} aria-hidden>{icon}</span> : null}
        {children}
      </main>
    </div>
  );
}
