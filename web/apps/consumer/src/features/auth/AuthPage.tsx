import { useEffect } from 'react';
import { useRouter } from '@tanstack/react-router';
import { Alert, SiteLink } from '@northline/ui';
import { appAuthorizationUrl, bffLoginUrl, safeNext, type AuthSession } from '@northline/auth-kit';
import { signInHref, useViewer } from '../session/api';
import { LegalLink } from './LegalLink';
import { useAuthT, type AuthKey } from './messages';
import { RegisterFlow } from './RegisterFlow';
import { SignInFlow } from './SignInFlow';

export type AuthMode = 'signin' | 'register';

/** Search params of /sign-in and /register (all optional). */
export interface AuthSearch {
  next?: string;
  /** Back from Google / Apple (S-18): `step=factor&identifier=…[&link=google]`, or register pre-filled. */
  step?: string;
  identifier?: string;
  error?: string;
  firstName?: string;
  lastName?: string;
  email?: string;
  provider?: string;
  link?: string;
  relay?: string;
}

const ERRORS: Record<string, AuthKey> = {
  rate_limited: 'rateLimitedLater', federation_cancelled: 'federationCancelled', federation_unavailable: 'federationUnavailable',
  federation: 'federationFailed', signin: 'signInFailed',
};
const PROVIDERS = { google: 'Google', apple: 'Apple' } as const;

/**
 * Design 06 `auth`: the pitch on the left (kicker, hero, three points, support line), the card on the right (title,
 * "Back to browsing", progress, the flow, the legal line). After the JSON flow against northline-auth, the browser
 * goes to the consumer-bff's hand-off (`/bff/login?next=`) — a new account to the Location screen, a sign-in back to
 * where the person was — or, for a mobile app's authorization request (S-29), back to it.
 */
export function AuthPage({ mode, search, navigate = url => window.location.assign(url) }: { mode: AuthMode; search: AuthSearch; navigate?: (url: string) => void }) {
  const t = useAuthT();
  const router = useRouter();
  const { user } = useViewer();
  const next = safeNext(search.next);
  const back = next ?? '/';
  const register = mode === 'register';

  // Already signed in: nothing to do here.
  useEffect(() => { if (user) void router.navigate({ href: back, replace: true }); }, [user]); // eslint-disable-line react-hooks/exhaustive-deps

  const provider = search.link ?? search.provider;
  const federated = provider === 'google' || provider === 'apple' ? PROVIDERS[provider] : undefined;
  const handOff = (s: AuthSession, to: string) => navigate(appAuthorizationUrl(s.continueTo) ?? bffLoginUrl(to));
  const points = register
    ? (['pointRegister1', 'pointRegister2', 'pointRegister3'] as const)
    : (['pointSignIn1', 'pointSignIn2', 'pointSignIn3'] as const);
  const switchTo = (m: AuthMode) => void router.navigate({ href: signInHref(next, m === 'register' ? 'register' : 'sign-in') });

  return (
    <div className="nl-auth">
      <div className="nl-auth-intro">
        <div className="nl-auth-kicker">{t(register ? 'kickerRegister' : 'kickerSignIn')}</div>
        <h1 className="nl-auth-hero">{t(register ? 'heroRegister' : 'heroSignIn')}</h1>
        <p className="nl-auth-lede">{t(register ? 'heroSubRegister' : 'heroSubSignIn')}</p>
        <ol className="nl-auth-points">
          {points.map((p, i) => (
            <li key={p} className="nl-auth-point">
              <span className="nl-auth-point-num" aria-hidden>{i + 1}</span>
              <span><strong>{t(p)}</strong><span className="nl-auth-point-body">{t(`${p}Body` as AuthKey)}</span></span>
            </li>
          ))}
        </ol>
        <div className="nl-auth-foot">{t('foot')}</div>
      </div>
      <section className="nl-auth-card" aria-labelledby="nl-auth-title">
        <div className="nl-auth-head">
          <h2 className="nl-auth-title" id="nl-auth-title">{t(register ? 'createAccount' : 'signIn')}</h2>
          <SiteLink href={back} className="btn btn-ghost nl-auth-cancel">{t('cancel')}</SiteLink>
        </div>
        {search.error && <Alert tone="error">{t(ERRORS[search.error] ?? 'federationFailed')}</Alert>}
        {!search.error && federated && (register || search.link) && (
          <Alert tone="neutral" role="status">
            {t(register ? 'federationRegister' : 'federationLink', { provider: federated })}
            {register && search.relay === '1' ? <> {t('federationRelay')}</> : null}
          </Alert>
        )}
        {register
          ? <RegisterFlow prefill={{ firstName: search.firstName, lastName: search.lastName, email: search.email }} onSignIn={() => switchTo('signin')}
              onFinished={s => handOff(s, '/location')} />
          : <SignInFlow resumeIdentifier={search.step === 'factor' ? search.identifier : undefined} onRegister={() => switchTo('register')}
              onFinished={s => handOff(s, back)} />}
        <p className="nl-auth-legal">
          {register
            ? <>{t('legalRegisterBefore')}<LegalLink doc="terms">{t('terms')}</LegalLink>{t('legalRegisterAnd')}<LegalLink doc="privacy">{t('privacy')}</LegalLink>{t('legalRegisterAfter')}</>
            : t('legalSignIn')}
        </p>
      </section>
    </div>
  );
}
