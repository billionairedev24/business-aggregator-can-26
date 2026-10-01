import { createFileRoute } from '@tanstack/react-router';
import { businessQuery } from '../../features/settings/api';
import { SETTINGS_TABS, type SettingsTab } from '../../features/settings/tabs';
import { SettingsScreen } from '../../features/settings/SettingsScreen';

interface SettingsSearch { tab?: SettingsTab }

/** `/b/$id/settings?tab=business|team|security|notifications|api` — the account menu deep-links `security` and `team`. */
export const Route = createFileRoute('/b/$merchantId/settings')({
  validateSearch: (search: Record<string, unknown>): SettingsSearch =>
    (SETTINGS_TABS as readonly unknown[]).includes(search.tab) ? { tab: search.tab as SettingsTab } : {},
  loader: ({ context, params }) => { void context.queryClient.prefetchQuery(businessQuery(params.merchantId)); },
  component: SettingsRoute,
});

function SettingsRoute() {
  const { tab } = Route.useSearch();
  const navigate = Route.useNavigate();
  return <SettingsScreen tab={tab ?? 'business'} onTab={next => void navigate({ search: { tab: next }, replace: true })} />;
}
