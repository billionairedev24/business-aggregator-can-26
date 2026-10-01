import { createFileRoute, Outlet } from '@tanstack/react-router';
import { ErrorState, PageSkeleton } from '@northline/ui';
import { ConsoleLayout, DeniedBanner } from '../features/shell/ConsoleLayout';
import { loadStaff, requireSession } from '../features/shell/guards';
import { useShellT } from '../features/shell/messages';
import { Overview } from '../features/overview/Overview';

/** Every console screen: signed in (console-bff: staff with a second factor), roles loaded, the shell around it. */
export const Route = createFileRoute('/_console')({
  codeSplitGroupings: [['component'], ['pendingComponent'], ['errorComponent'], ['notFoundComponent']],
  beforeLoad: ({ context, location }) => requireSession(context.queryClient, location.href),
  loader: ({ context }) => loadStaff(context.queryClient),
  pendingComponent: () => <div style={{ padding: 32 }}><PageSkeleton /></div>,
  errorComponent: function ConsoleError({ reset }) {
    const t = useShellT();
    return <div style={{ padding: 32 }}><ErrorState message={t('loadError')} onRetry={reset} /></div>;
  },
  component: () => <ConsoleLayout denied={<><DeniedBanner /><Overview /></>}><Outlet /></ConsoleLayout>,
});
