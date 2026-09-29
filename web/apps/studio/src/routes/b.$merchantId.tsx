import { createFileRoute, Navigate, Outlet, useRouterState } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { ErrorState, PageSkeleton } from '@northline/ui';
import { ApiError } from '../lib/http';
import { merchantQuery } from '../features/shell/api';
import { requireSession } from '../features/shell/guards';
import { useShellT } from '../features/shell/messages';
import { homeScreen, screenFromPath, screenHref, screensFor } from '../features/shell/nav';
import { StudioLayout } from '../features/shell/StudioLayout';

export const Route = createFileRoute('/b/$merchantId')({
  beforeLoad: ({ context, location }) => requireSession(context.queryClient, location.href),
  loader: ({ context, params }) => context.queryClient.ensureQueryData(merchantQuery(params.merchantId)),
  pendingComponent: () => <div style={{ padding: 32 }}><PageSkeleton /></div>,
  errorComponent: MerchantError,
  component: StudioRoute,
});

function StudioRoute() {
  const { merchantId } = Route.useParams();
  const merchant = useQuery(merchantQuery(merchantId)).data!;
  const pathname = useRouterState({ select: s => s.location.pathname });
  const screen = screenFromPath(pathname);
  if (!screensFor(merchant.type).includes(screen)) return <Navigate to={screenHref(merchant.id, homeScreen(merchant.type))} replace />;
  return <StudioLayout merchant={merchant}><Outlet /></StudioLayout>;
}

function MerchantError({ error, reset }: { error: unknown; reset: () => void }) {
  const t = useShellT();
  const missing = error instanceof ApiError && (error.status === 403 || error.status === 404);
  return <div style={{ padding: 32 }}><ErrorState message={missing ? t('notFound') : t('loadError')} onRetry={missing ? undefined : reset} /></div>;
}
