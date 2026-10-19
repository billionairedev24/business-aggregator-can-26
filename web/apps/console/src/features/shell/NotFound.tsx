import type { ReactNode } from 'react';
import { Link } from '@tanstack/react-router';
import { Compass } from '@phosphor-icons/react';
import { Logo } from '@northline/ui';
import { useShellT } from './messages';
import { SCREEN_PATH } from './screens';

/**
 * A page outside the console's shell (an address that does not exist, a failure before the shell could draw): the logo,
 * then one white card on linen — the Studio's standalone frame (DECISIONS "Studio pages on the light direction").
 */
export function Standalone({ icon, children }: { icon?: ReactNode; children: ReactNode }) {
  const t = useShellT();
  return (
    <div className="nl-solo">
      <div className="nl-solo-brand"><Logo size={30} product={t('console')} /></div>
      <main className="nl-solo-card" id="main" tabIndex={-1}>
        {icon ? <span className="nl-solo-icon" data-tone="neutral" aria-hidden>{icon}</span> : null}
        {children}
      </main>
    </div>
  );
}

export function NotFound() {
  const t = useShellT();
  return (
    <Standalone icon={<Compass size={28} />}>
      <h1>{t('notFoundTitle')}</h1>
      <p>{t('notFound')} {t('notFoundBody')}</p>
      <div className="nl-solo-actions"><Link to={SCREEN_PATH.overview} className="btn btn-primary">{t('backToOverview')}</Link></div>
    </Standalone>
  );
}
