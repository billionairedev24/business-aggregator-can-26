import { useQueryErrorResetBoundary } from '@tanstack/react-query';
import { useRouter, type ErrorComponentProps } from '@tanstack/react-router';
import { ErrorState, PageHeader } from '@northline/ui';
import { useShellT } from './messages';

/**
 * A loader or screen failed: rosehip inline error + Retry (re-runs the route's loaders and resets failed queries).
 * Screens show their own <ErrorState> for failures inside the page; this catches what escapes.
 */
export function RouteError({ error }: ErrorComponentProps) {
  const t = useShellT();
  const router = useRouter();
  const queries = useQueryErrorResetBoundary();
  const message = error instanceof Error && error.message ? error.message : t('errorTitle');
  return (
    <div className="nl-page">
      <PageHeader title={t('errorTitle')} />
      <ErrorState message={message} onRetry={() => { queries.reset(); void router.invalidate(); }} />
    </div>
  );
}
