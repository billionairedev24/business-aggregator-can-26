import { useQuery } from '@tanstack/react-query';
import { Navigate } from '@tanstack/react-router';
import { Alert, ErrorState, PageHeader, PageSkeleton } from '@northline/ui';
import { ApiError } from '../../lib/http';
import { pilotInviteQuery } from './api';
import { useOnboardingT } from './messages';

/**
 * `/pilot/$token` (S-120): a pilot invite link. Signed in (or just registered), the person lands on the Account step
 * with the invite's business type and province filled in; creating the business accepts the invite.
 */
export function PilotInviteScreen({ token }: { token: string }) {
  const t = useOnboardingT();
  const q = useQuery(pilotInviteQuery(token));
  if (q.isPending) return <main className="nl-invite"><PageSkeleton kpis={0} rows={3} /></main>;
  if (q.isError) {
    const missing = q.error instanceof ApiError && q.error.status === 404;
    return <main className="nl-invite"><PageHeader kicker={t('pilotKicker')} title={t('pilotInvalid')} /><ErrorState message={missing ? t('pilotInvalid') : t('loadError')} onRetry={missing ? undefined : () => void q.refetch()} /></main>;
  }
  const invite = q.data;
  if (invite.state === 'pending') {
    return <Navigate to="/onboarding/$step" params={{ step: 'account' }} search={{ type: invite.businessType, pilot: token }} replace />;
  }
  return (
    <main className="nl-invite">
      <PageHeader kicker={t('pilotKicker')} title={t('pilotTitle', { city: invite.city })} />
      <Alert tone="error" role="alert">{t(`pilot_${invite.state}`)}</Alert>
    </main>
  );
}
