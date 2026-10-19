import { useQueryErrorResetBoundary } from '@tanstack/react-query';
import { useRouter, type ErrorComponentProps } from '@tanstack/react-router';
import { CloudRain, Wrench } from '@phosphor-icons/react';
import { ErrorState } from '@northline/ui';
import { Art } from '../art/Art';
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
    <div className="nl-page nl-lost">
      <span className="nl-lost-pic" aria-hidden><Art subject="error" tone="stone" draw={{ main: CloudRain, side: Wrench }} fit="contain" /></span>
      <h1 className="nl-lost-title">{t('errorTitle')}</h1>
      <ErrorState message={message} onRetry={() => { queries.reset(); void router.invalidate(); }} />
    </div>
  );
}
