import { createFileRoute, Outlet } from '@tanstack/react-router';
import { brand } from '@northline/tokens';
import { FailurePage, PageSkeleton, PageState } from '@northline/ui';
import { failureKind } from '../lib/http';
import { ConsoleLayout, DeniedBanner } from '../features/shell/ConsoleLayout';
import { loadStaff, requireSession } from '../features/shell/guards';
import { useShellT } from '../features/shell/messages';
import { Overview } from '../features/overview/Overview';

/** Every console screen: signed in (console-bff: staff with a second factor), roles loaded, the shell around it. */
export const Route = createFileRoute('/_console')({
  codeSplitGroupings: [['component'], ['pendingComponent'], ['errorComponent'], ['notFoundComponent']],
  beforeLoad: ({ context, location }) => requireSession(context.queryClient, location.href),
  loader: ({ context }) => loadStaff(context.queryClient),
  pendingComponent: function ConsolePending() { const t = useShellT(); return <PageState title={`${brand.name} ${t('console')}`}><PageSkeleton /></PageState>; },
  errorComponent: function ConsoleError({ error, reset }) {
    const t = useShellT();
    const kind = failureKind(error);
    return <FailurePage product={t('console')} kind={kind} onRetry={reset} autoRetry retryKey={error} title={kind === 'error' ? t('loadError') : undefined} />;
  },
  component: () => <ConsoleLayout denied={<><DeniedBanner /><Overview /></>}><Outlet /></ConsoleLayout>,
});
