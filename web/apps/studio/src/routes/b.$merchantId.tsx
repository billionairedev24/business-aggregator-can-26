import { createFileRoute, Navigate, Outlet, useRouterState } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { ErrorState, PageSkeleton, useLocale } from '@northline/ui';
import { ApiError } from '../lib/http';
import { merchantQuery } from '../features/shell/api';
import { requireSession } from '../features/shell/guards';
import { useShellT } from '../features/shell/messages';
import { homeScreen, screenFromPath, screenHref, screensFor } from '../features/shell/nav';
import { StudioLayout } from '../features/shell/StudioLayout';
import { PlaceValues } from '../features/shell/place';

export const Route = createFileRoute('/b/$merchantId')({
  // S-69: the business is requested with the session check, not after the layout's chunk (see vite.config.ts).
  codeSplitGroupings: [['component'], ['pendingComponent'], ['errorComponent'], ['notFoundComponent']],
  beforeLoad: ({ context, location }) => requireSession(context.queryClient, location.href),
  loader: ({ context, params }) => context.queryClient.ensureQueryData(merchantQuery(params.merchantId)),
  pendingComponent: () => <div style={{ padding: 32 }}><PageSkeleton /></div>,
  errorComponent: MerchantError,
  component: StudioRoute,
});

function StudioRoute() {
  const { merchantId } = Route.useParams();
  const merchant = useQuery(merchantQuery(merchantId)).data!;
  const { locale } = useLocale();
  const pathname = useRouterState({ select: s => s.location.pathname });
  const screen = screenFromPath(pathname);
  if (!screensFor(merchant.type).includes(screen)) return <Navigate to={screenHref(merchant.id, homeScreen(merchant.type))} replace />;
  const region = merchant.region;
  return (
    <PlaceValues
      province={region ? region.provinceName[locale] : ''}
      provinceIn={region?.provinceIn?.[locale]}
      provinceOf={region?.provinceOf?.[locale]}
      city={merchant.city}
      privacyLaw={region?.privacyLaw}
      timeZone={region?.timeZone}
    >
      <StudioLayout merchant={merchant}><Outlet /></StudioLayout>
    </PlaceValues>
  );
}

function MerchantError({ error, reset }: { error: unknown; reset: () => void }) {
  const t = useShellT();
  const missing = error instanceof ApiError && (error.status === 403 || error.status === 404);
  return <div style={{ padding: 32 }}><ErrorState message={missing ? t('notFound') : t('loadError')} onRetry={missing ? undefined : reset} /></div>;
}
