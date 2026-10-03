import type { ReactNode } from 'react';
import { useRouterState } from '@tanstack/react-router';
import { DeliveryLocationProvider } from '../location/useDeliveryLocation';
import { VisitorPlace } from '../location/regions';
import { useViewer } from '../session/api';
import { Footer } from './Footer';
import { GuestBanner } from './GuestBanner';
import { Header } from './Header';
import { useShellT } from './messages';
import { SCREENS, screenFor } from './screens';
import { PilotControl } from '../pilot/PilotControl';

/** Header, guest banner, the screen, footer. Every consumer route renders inside it (routes/__root.tsx). */
export function ConsumerLayout({ children, geolocation }: { children: ReactNode; geolocation?: Geolocation | null }) {
  const t = useShellT();
  const { user, loading, session } = useViewer();
  const pathname = useRouterState({ select: s => s.location.pathname });
  const screen = screenFor(pathname);
  const banner = !loading && !user && screen && SCREENS[screen].guestBanner;
  return (
    <DeliveryLocationProvider ipCity={session?.location?.city} geolocation={geolocation}>
      <VisitorPlace>
        <div className="nl-app">
          <a href="#main" className="nl-skip">{t('skip')}</a>
          <Header />
          {banner && <GuestBanner />}
          <main id="main" tabIndex={-1} className="nl-main">{children}</main>
          <Footer />
          <PilotControl signedIn={!!user} />
        </div>
      </VisitorPlace>
    </DeliveryLocationProvider>
  );
}
