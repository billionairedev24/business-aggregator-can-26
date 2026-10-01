import { Link } from '@tanstack/react-router';
import { EmptyState, PageHeader } from '@northline/ui';
import { useShellT, type ShellKey } from './messages';
import { SCREEN_PATH, SCREEN_STORY, type ScreenKey } from './screens';

const TITLE: Partial<Record<ScreenKey, ShellKey>> = { profile: 'profileScreen', oncall: 'oncallScreen' };

/**
 * Stand-in for a console screen whose story hasn't landed (docs/CONSOLE_PLAN.md § Routes): the design's kicker, the
 * screen's name and the story that builds it. The story replaces the route's component. Every usage must be gone
 * before release.
 */
export function ScreenPending({ screen }: { screen: ScreenKey }) {
  const t = useShellT();
  return (
    <div>
      <PageHeader kicker={t(`k_${screen}` as ShellKey)} title={t(TITLE[screen] ?? (screen as ShellKey))} />
      <EmptyState action={screen === 'overview' ? undefined : <Link to={SCREEN_PATH.overview} className="btn btn-secondary">{t('backToOverview')}</Link>}>
        {t('pendingBody', { story: SCREEN_STORY[screen] })}
      </EmptyState>
    </div>
  );
}
