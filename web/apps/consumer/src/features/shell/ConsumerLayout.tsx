import type { ReactNode } from 'react';
import { testingToolsEnabled } from '@northline/ui';
import { DeliveryLocationProvider } from '../location/useDeliveryLocation';
import { VisitorPlace } from '../location/regions';
import { LocationChooser } from '../location/LocationChooser';
import { useViewer } from '../session/api';
import { Footer } from './Footer';
import { Header } from './Header';
import { useShellT } from './messages';
import { PilotControl } from '../pilot/PilotControl';

/**
 * Header, the screen, footer. Every consumer route renders inside it (routes/__root.tsx). No guest banner (redesign
 * § 3.4: guests sign in at the checkout or booking step, or from the avatar menu); the pilot's "Send feedback" control
 * only where the testing tools are on (§ 3.5, `VITE_NL_TESTING_TOOLS`).
 */
export function ConsumerLayout({ children }: { children: ReactNode }) {
  const t = useShellT();
  const { user, session, loading } = useViewer();
  return (
    <DeliveryLocationProvider ipCity={session?.location?.city} signedIn={loading ? undefined : !!user}>
      <VisitorPlace>
        <div className="nl-app">
          <a href="#main" className="nl-skip">{t('skip')}</a>
          <Header />
          <main id="main" tabIndex={-1} className="nl-main">{children}</main>
          <Footer />
          <LocationChooser />
          {testingToolsEnabled() ? <PilotControl signedIn={!!user} /> : null}
        </div>
      </VisitorPlace>
    </DeliveryLocationProvider>
  );
}
