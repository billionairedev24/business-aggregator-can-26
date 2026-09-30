import { EmptyState, PageHeader, SiteLink } from '@northline/ui';
import { useScreenT, useShellT } from './messages';
import { SCREENS, type ScreenKey } from './screens';

/**
 * Stand-in for a consumer screen whose story hasn't landed (docs/CONSUMER_WEB_PLAN.md § Routes). The story replaces the
 * route's component with its screen. Every usage must be gone before release.
 */
export function ScreenPending({ screen }: { screen: ScreenKey }) {
  const t = useShellT();
  const name = useScreenT();
  return (
    <div className="nl-page">
      <PageHeader kicker={t('pendingKicker', { state: screen })} title={name(screen)} />
      <EmptyState action={screen === 'home' ? undefined : <SiteLink href="/" className="btn btn-secondary">{t('backHome')}</SiteLink>}>
        {t('pendingBody', { story: SCREENS[screen].story })}
      </EmptyState>
    </div>
  );
}
