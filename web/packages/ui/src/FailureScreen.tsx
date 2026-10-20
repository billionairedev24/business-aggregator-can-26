import { Component, useEffect, useRef, useState, type ErrorInfo, type ReactNode } from 'react';
import { ArrowClockwise, CloudSlash, Compass, LockKey, Warning } from '@phosphor-icons/react';
import { defineMessages } from './i18n';
import { Logo } from './Logo';
import { SiteLink } from './SiteLink';

/** What failed (`failureKind()` of @northline/client): the screen's copy and actions follow it. */
export type FailureScreenKind = 'unreachable' | 'updated' | 'notFound' | 'forbidden' | 'unauthorized' | 'error';

const useT = defineMessages({
  en: {
    unreachableTitle: 'We can’t connect right now.',
    unreachableBody: 'Our servers aren’t answering at the moment. It’s not you — we’ll keep trying.',
    updatedTitle: 'A new version is available.',
    updatedBody: 'This page changed while you had it open. Reload to get the latest version.',
    notFoundTitle: 'We couldn’t find that page.',
    notFoundBody: 'The link may be old, or the page has moved.',
    forbiddenTitle: 'You don’t have access to this page.',
    forbiddenBody: 'Ask the account’s owner to give you access, or go back home.',
    errorTitle: 'Something went wrong on our side.',
    errorBody: 'It’s not you. Try again in a moment.',
    tryAgain: 'Try again', reload: 'Reload', goHome: 'Go to home', support: 'Contact support',
    retrying: 'Trying again in {seconds, plural, one {# second} other {# seconds}}…',
    trying: 'Trying again…',
    gaveUp: 'Still not answering. Try again in a few minutes.',
    devTitle: 'Developer details (development only)',
  },
  fr: {
    unreachableTitle: 'Connexion impossible pour le moment.',
    unreachableBody: 'Nos serveurs ne répondent pas en ce moment. Le problème ne vient pas de vous — nous réessayons.',
    updatedTitle: 'Une nouvelle version est disponible.',
    updatedBody: 'Cette page a changé pendant que vous l’aviez ouverte. Rechargez-la pour obtenir la dernière version.',
    notFoundTitle: 'Page introuvable.',
    notFoundBody: 'Le lien est peut-être ancien, ou la page a été déplacée.',
    forbiddenTitle: 'Vous n’avez pas accès à cette page.',
    forbiddenBody: 'Demandez l’accès au propriétaire du compte, ou retournez à l’accueil.',
    errorTitle: 'Un problème est survenu de notre côté.',
    errorBody: 'Le problème ne vient pas de vous. Réessayez dans un instant.',
    tryAgain: 'Réessayer', reload: 'Recharger', goHome: 'Aller à l’accueil', support: 'Contacter le soutien',
    retrying: 'Nouvel essai dans {seconds, plural, one {# seconde} other {# secondes}}…',
    trying: 'Nouvel essai…',
    gaveUp: 'Toujours pas de réponse. Réessayez dans quelques minutes.',
    devTitle: 'Détails pour les développeurs (développement seulement)',
  },
});

/** The automatic tries of an unreachable screen: 2, 4, 8, 16 and 30 s apart, then the visitor decides. */
export const RETRY_BACKOFF_SECONDS = [2, 4, 8, 16, 30] as const;

// Across re-renders of the error screen (each failed try renders it again): how many tries, and when the last one was.
// Ten quiet minutes start the count again.
const tries = { n: 0, at: 0 };
/** Tests: forget the tries made so far. */
export const resetFailureRetries = () => { tries.n = 0; tries.at = 0; };

export interface FailureScreenProps {
  kind: FailureScreenKind;
  /** Try again: re-runs what failed (the route's loaders, the queries). Without it, "Try again" reloads the page. */
  onRetry?: () => void;
  /** unreachable: try again on its own with {@link RETRY_BACKOFF_SECONDS} (needs `onRetry`). */
  autoRetry?: boolean;
  /** What failed (the error): a new value after a failed try schedules the next one (the screen may stay mounted). */
  retryKey?: unknown;
  homeHref?: string;
  /** The support address (`mailto:` or a help page); none = no support link. */
  supportHref?: string;
  /** Replace the kind's heading or sentence (the consumer site: "We can't reach the shop right now."). */
  title?: string;
  body?: string;
  /** A picture above the heading (decorative). */
  art?: ReactNode;
  /** Development only: the error behind the screen (shown after hydration, never in a production build's markup). */
  detail?: { name: string; message: string; stack?: string } | null;
}

/**
 * A whole screen that could not be shown (a route's errorComponent, the apps' top-level boundary): what happened in
 * plain words, Try again, Go to home and a support link; an unreachable server is tried again on its own with a
 * backoff, the countdown announced politely. The h1 is the screen's (S-142). Never shows a raw error message.
 */
export function FailureScreen({ kind, onRetry, autoRetry = false, retryKey, homeHref = '/', supportHref, title, body, art, detail }: FailureScreenProps) {
  const t = useT();
  const k = kind === 'unauthorized' ? 'forbidden' : kind;
  const [wait, setWait] = useState<number | 'trying' | 'gave-up' | null>(null);
  const [mounted, setMounted] = useState(false);
  const retryRef = useRef(onRetry);
  retryRef.current = onRetry;
  useEffect(() => setMounted(true), []);
  useEffect(() => {
    if (k !== 'unreachable' || !autoRetry || !retryRef.current) return;
    if (Date.now() - tries.at > 10 * 60_000) tries.n = 0;
    const seconds = RETRY_BACKOFF_SECONDS[tries.n];
    if (seconds === undefined) { setWait('gave-up'); return; }
    setWait(seconds);
    const timer = setTimeout(() => { tries.n += 1; tries.at = Date.now(); setWait('trying'); retryRef.current?.(); }, seconds * 1000);
    return () => clearTimeout(timer);
  }, [k, autoRetry, retryKey]);
  const retry = () => { if (onRetry) onRetry(); else window.location.reload(); };
  const Icon = k === 'unreachable' ? CloudSlash : k === 'updated' ? ArrowClockwise : k === 'notFound' ? Compass : k === 'forbidden' ? LockKey : Warning;
  return (
    <div className="nl-failure" data-kind={k}>
      <span className="nl-failure-art" aria-hidden>{art ?? <span className="nl-failure-icon"><Icon size={56} /></span>}</span>
      <h1 className="nl-failure-title">{title ?? t(`${k}Title`)}</h1>
      <p className="nl-failure-body">{body ?? t(`${k}Body`)}</p>
      {k === 'unreachable' && autoRetry ? <p className="nl-failure-retry" role="status">{wait === 'gave-up' ? t('gaveUp') : wait === 'trying' ? t('trying') : wait ? t('retrying', { seconds: wait }) : ''}</p> : null}
      <div className="nl-failure-actions">
        {k === 'updated'
          ? <button type="button" className="btn btn-primary btn-lg" onClick={() => window.location.reload()}>{t('reload')}</button>
          : k !== 'notFound' && k !== 'forbidden' ? <button type="button" className="btn btn-primary btn-lg" onClick={retry}>{t('tryAgain')}</button> : null}
        <SiteLink href={homeHref} className={`btn ${k === 'notFound' || k === 'forbidden' ? 'btn-primary' : 'btn-secondary'} btn-lg`}>{t('goHome')}</SiteLink>
      </div>
      {supportHref ? <p className="nl-failure-help"><a href={supportHref}>{t('support')}</a></p> : null}
      {mounted && detail ? (
        <details className="nl-failure-dev">
          <summary>{t('devTitle')}</summary>
          <p><strong>{detail.name}</strong>: {detail.message}</p>
          {detail.stack ? <pre>{detail.stack}</pre> : null}
        </details>
      ) : null}
    </div>
  );
}

/** A failure that replaces the whole app (no shell around it): the brand's logo above the {@link FailureScreen}. */
export function FailurePage({ product, ...props }: FailureScreenProps & { product?: string }) {
  return (
    <main id="main" className="nl-failure-page">
      <div className="nl-failure-brand"><Logo size={30} product={product} /></div>
      <FailureScreen {...props} />
    </main>
  );
}

/**
 * The last line of defence of a single-page app (around its RouterProvider): an error no route caught — a provider
 * failing, a chunk that a new deploy removed — shows the branded {@link FailureScreen} instead of a blank page.
 * `kindOf` maps the error (the apps pass @northline/client's failureKind).
 */
export class AppErrorBoundary extends Component<{ children: ReactNode; kindOf: (error: unknown) => FailureScreenKind; homeHref?: string; supportHref?: string; dev?: boolean; product?: string }, { error: unknown }> {
  state = { error: undefined as unknown };
  static getDerivedStateFromError(error: unknown) { return { error }; }
  componentDidCatch(error: unknown, info: ErrorInfo) { console.error('[app] unhandled error', error, info.componentStack); }
  render() {
    const { error } = this.state;
    if (error === undefined) return this.props.children;
    const e = error instanceof Error ? error : new Error(String(error));
    return (
      <FailurePage kind={this.props.kindOf(error)} onRetry={() => window.location.reload()} homeHref={this.props.homeHref} supportHref={this.props.supportHref}
        product={this.props.product} detail={this.props.dev ? { name: e.name, message: e.message, stack: e.stack } : null} />
    );
  }
}
