import { useState } from 'react';
import { Alert, UnderlineTabs } from '@northline/ui';
import { bffLoginUrl } from '../../lib/auth-server';
import { Brand } from '../shell/StudioLayout';
import type { RegisterValues } from './api';
import { useAuthT } from './messages';
import { RegisterFlow } from './RegisterFlow';
import { SignInFlow } from './SignInFlow';
import './auth.css';

export type AuthMode = 'signin' | 'register';

export interface SignedOutPageProps {
  mode: AuthMode;
  /** Switch tab (the routes /sign-in ↔ /register). `recover`: open sign-in on the backup-code factor. */
  onModeChange: (mode: AuthMode, opts?: { recover?: boolean }) => void;
  /** Arrived via "Recover with a backup code" from the other tab. */
  recoverOnLoad?: boolean;
  /** Where to land after signing in (`?next=`); registration always continues to /onboarding. */
  next?: string;
  /** Back from Google/Apple: known email → factor step; unknown → pre-filled registration. */
  resumeIdentifier?: string;
  prefill?: Partial<Pick<RegisterValues, 'firstName' | 'lastName' | 'email'>>;
  /** `?error=federation|signin` from the auth server / BFF. */
  error?: string;
  /** Navigates to the BFF hand-off (tests replace it). */
  navigate?: (url: string) => void;
}

/**
 * The Studio's signed-out page (design 02, lines 17–96): top bar "Signed out", the pitch on the left, and the
 * Sign in / Create account card. The Studio renders its own sign-in UI against northline-auth's JSON API, then hands
 * off to the BFF (`/bff/login?next=`), which gets its tokens without showing another page.
 */
export function SignedOutPage({ mode, onModeChange, recoverOnLoad, next, resumeIdentifier, prefill, error, navigate = url => window.location.assign(url) }: SignedOutPageProps) {
  const t = useAuthT();
  const [recover, setRecover] = useState(recoverOnLoad ? 1 : 0);
  const onboarding = (next ?? '').startsWith('/onboarding');
  const title = mode === 'register' ? t('titleRegister') : onboarding ? t('titleSignInOnboarding') : t('titleSignIn');
  const points = [[t('point1Title'), t('point1Body')], [t('point2Title'), t('point2Body')], [t('point3Title'), t('point3Body')]] as const;

  return (
    <div className="nl-auth-page">
      <nav className="nav nl-auth-nav" aria-label="Northline Studio">
        <Brand label={t('studio')} />
        <span className="nl-auth-signedout">{t('signedOut')}</span>
      </nav>
      <main className="nl-auth-main">
        <div className="nl-auth-intro">
          <div className="nl-auth-kicker">{t('kicker')}</div>
          <h1 className="nl-auth-hero">{t('heroTitle')}</h1>
          <p className="nl-auth-lede">{t('heroLede')}</p>
          <ol className="nl-auth-points">
            {points.map(([title, body], i) => (
              <li key={title} className="nl-auth-point">
                <span className="nl-auth-point-num" aria-hidden>{i + 1}</span>
                <span><strong>{title}</strong><span className="nl-auth-point-body">{body}</span></span>
              </li>
            ))}
          </ol>
          <div className="nl-auth-support">{t('support')}</div>
        </div>
        <section className="nl-auth-card" aria-labelledby="nl-auth-title">
          <UnderlineTabs aria-label={t('tabs')} value={mode} onChange={onModeChange}
            options={[{ value: 'signin', label: t('tabSignIn') }, { value: 'register', label: t('tabRegister') }]} />
          <h2 className="nl-auth-title" id="nl-auth-title">{title}</h2>
          {error && <Alert tone="error">{t('federationFailed')}</Alert>}
          {mode === 'register'
            ? <RegisterFlow prefill={prefill} onSignIn={() => onModeChange('signin')} onFinished={() => navigate(bffLoginUrl('/onboarding'))} />
            : <SignInFlow onboarding={onboarding} resumeIdentifier={resumeIdentifier} recover={recover}
                onRegister={() => onModeChange('register')} onFinished={() => navigate(bffLoginUrl(next ?? '/'))} />}
          <p className="nl-auth-footer">
            {t('footerTrouble')}
            <button type="button" className="nl-auth-link" onClick={() => (mode === 'signin' ? setRecover(r => r + 1) : onModeChange('signin', { recover: true }))}>{t('footerRecover')}</button>
            {t('footerRest')}
          </p>
        </section>
      </main>
    </div>
  );
}
