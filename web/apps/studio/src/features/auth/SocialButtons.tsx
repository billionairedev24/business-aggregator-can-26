import { AppleMark, federationUrl, GoogleMark, PasskeyMark } from '@northline/auth-kit';
import { useAuthT } from './messages';

/** "or continue with" — Passkey (sign-in only), Google, Apple. */
export function SocialButtons({ onPasskey, passkeyBusy }: { onPasskey?: () => void; passkeyBusy?: boolean }) {
  const t = useAuthT();
  return (
    <>
      <div className="nl-auth-divider">{t('orContinue')}</div>
      <div className="nl-auth-social">
        {onPasskey && (
          <button type="button" className="btn btn-secondary" onClick={onPasskey} disabled={passkeyBusy} aria-busy={passkeyBusy || undefined}>
            <PasskeyMark />{t('passkey')}
          </button>
        )}
        <a className="btn btn-secondary" href={federationUrl('google')}><GoogleMark />Google</a>
        <a className="btn btn-secondary" href={federationUrl('apple')}><AppleMark />Apple</a>
      </div>
    </>
  );
}
