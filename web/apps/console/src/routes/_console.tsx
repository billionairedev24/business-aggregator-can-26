import { createFileRoute, Outlet } from '@tanstack/react-router';
import { ErrorState, PageSkeleton, PageState } from '@northline/ui';
import { ConsoleLayout, DeniedBanner } from '../features/shell/ConsoleLayout';
import { loadStaff, requireSession } from '../features/shell/guards';
import { useShellT } from '../features/shell/messages';
import { Overview } from '../features/overview/Overview';

/** Every console screen: signed in (console-bff: staff with a second factor), roles loaded, the shell around it. */
export const Route = createFileRoute('/_console')({
  codeSplitGroupings: [['component'], ['pendingComponent'], ['errorComponent'], ['notFoundComponent']],
  beforeLoad: ({ context, location }) => requireSession(context.queryClient, location.href),
  loader: ({ context }) => loadStaff(context.queryClient),
  pendingComponent: function ConsolePending() { const t = useShellT(); return <PageState title={`Northline ${t('console')}`}><PageSkeleton /></PageState>; },
  errorComponent: function ConsoleError({ reset }) {
    const t = useShellT();
    return <PageState title={`Northline ${t('console')}`}><ErrorState message={t('loadError')} onRetry={reset} /></PageState>;
  },
  component: () => <ConsoleLayout denied={<><DeniedBanner /><Overview /></>}><Outlet /></ConsoleLayout>,
});
