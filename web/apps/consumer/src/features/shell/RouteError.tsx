import { useQueryErrorResetBoundary } from '@tanstack/react-query';
import { useRouter, type ErrorComponentProps } from '@tanstack/react-router';
import { ArrowClockwise, CloudRain, CloudSlash, Wrench } from '@phosphor-icons/react';
import { failureKind } from '@northline/client';
import { FailureScreen } from '@northline/ui';
import { Scene } from '../art/Scene';
import { useSiteConfig } from '../../lib/config';
import { NotFound } from './NotFound';
import { useShellT } from './messages';

/**
 * A loader or screen failed (inside the page shell: header, footer). What it means decides the screen
 * (@northline/client failureKind): the consumer-bff or the api not answering → "We can't reach the shop right now",
 * tried again on its own with a backoff; a 404 → the not-found page; a new deploy → Reload; anything else → "Something
 * went wrong on our side". Try again re-runs the route's loaders and resets failed queries. Never the raw message;
 * development builds add the error under "Developer details". Screens show their own <ErrorState> for failures
 * inside the page; this catches what escapes. (The server answers 503 for the unreachable case: lib/serverErrors.ts.)
 */
export function RouteError({ error }: ErrorComponentProps) {
  const t = useShellT();
  const router = useRouter();
  const queries = useQueryErrorResetBoundary();
  const { supportEmail } = useSiteConfig();
  const kind = failureKind(error);
  if (kind === 'notFound') return <NotFound />;
  const retry = () => { queries.reset(); void router.invalidate(); };
  const e = error instanceof Error ? error : new Error(String(error));
  const draw = kind === 'unreachable' ? { main: CloudSlash, side: ArrowClockwise } : { main: CloudRain, side: Wrench };
  return (
    <div className="nl-page nl-lost">
      <FailureScreen
        kind={kind}
        onRetry={retry}
        autoRetry={kind === 'unreachable'}
        retryKey={error}
        art={<span className="nl-lost-pic"><Scene slot="empty/error" subject="error" tone="stone" draw={draw} fit="contain" sizes="(min-width: 768px) 560px, 100vw" /></span>}
        title={kind === 'unreachable' ? t('unreachableTitle') : kind === 'error' ? t('errorTitle') : undefined}
        body={kind === 'unreachable' ? t('unreachableBody') : undefined}
        supportHref={supportEmail ? `mailto:${supportEmail}` : '/help'}
        detail={import.meta.env.DEV ? { name: e.name, message: e.message, stack: e.stack } : null}
      />
    </div>
  );
}
