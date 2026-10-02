import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  addPasskey, createPasskey, passkeyOptions, PasskeyError, removePasskey, revokeOtherSessions, revokeSession, securityChangeError,
  securityKey, securityQuery, type ActiveSession, type Security, type SecurityChangeError,
} from '@northline/auth-kit';
import { Button, Dialog, ErrorState, Field, Tag, TextInput, codeValue } from '@northline/ui';
import { enrolPasskey, StepUpFailed, stepUpWithCode, stepUpWithPasskey } from '../cart/stepUp';
import { signInHref, useSession, useSignOut } from '../session/api';
import { accountSummaryQuery, useAccountSummary } from './api';
import { FormSkeleton } from './ProfileTab';
import { useSettingsT, type SettingsT } from './settingsMessages';
import { tabHref } from './tabs';

export function ago(iso: string | null | undefined, t: SettingsT, now = Date.now()): string {
  if (!iso) return t('justNow');
  const min = Math.max(0, Math.round((now - new Date(iso).getTime()) / 60_000));
  if (min < 1) return t('justNow');
  if (min < 60) return t('minAgo', { n: min });
  if (min < 60 * 24) return t('hAgo', { n: Math.round(min / 60) });
  return t('dAgo', { n: Math.round(min / 1440) });
}

/**
 * Security & sign-in (design 06 `at.security`) on northline-auth's security API (S-19, shared with the Studio through
 * `@northline/auth-kit`): passkeys, the authenticator app, the SMS backup, a security key, active sessions, "Download my
 * data" and "Sign out everywhere". Seeing it needs a recent second factor: a phone-code session confirms with its
 * passkey or authenticator first — or, with none, adds a passkey on this device (S-51's enrolment).
 */
export function SecurityTab() {
  const t = useSettingsT();
  const session = useSession();
  const sid = session.data?.sid ?? null;
  const security = useQuery({ ...securityQuery(sid), enabled: session.isSuccess });
  return (
    <>
      <h1 id="acct-title" className="nl-acct-h1">{t('secTitle')}</h1>
      {session.isPending || security.isPending ? <FormSkeleton label={t('loading')} rows={5} />
        : security.isError ? <ErrorState message={t('loadError')} onRetry={() => void security.refetch()} />
          : security.data === null ? <ConfirmItsYou onDone={() => void security.refetch()} />
            : <SecurityView security={security.data} sid={sid} phone={session.data?.user?.phone ?? null} />}
    </>
  );
}

/** No second-factor session yet: confirm (passkey / code) or, without a second factor, add a passkey here. */
function ConfirmItsYou({ onDone }: { onDone: () => void }) {
  const t = useSettingsT();
  const summary = useAccountSummary(true);
  const hasFactor = summary.data?.signIn === 'passkey' || summary.data?.signIn === 'totp';
  const [useCode, setUseCode] = useState(summary.data?.signIn === 'totp');
  const [code, setCode] = useState('');
  const [error, setError] = useState<string>();
  const [signInAgain, setSignInAgain] = useState(false);
  const [busy, setBusy] = useState(false);
  const run = async (step: () => Promise<string>) => {
    setBusy(true); setError(undefined);
    try { await step(); onDone(); } catch (e) {
      const reason = e instanceof StepUpFailed ? e.reason : 'rejected';
      if (reason === 'signed_out' || (reason === 'rejected' && !hasFactor)) setSignInAgain(true);
      setError(reason === 'wrong_code' ? t('wrongCode') : reason === 'cancelled' ? t('passkeyCancelled') : reason === 'unsupported' ? t('passkeyUnsupported')
        : reason === 'locked' ? t('err_rate_limited') : t('err_failed'));
    } finally { setBusy(false); }
  };
  return (
    <div className="nl-acct-panel nl-sec-confirm">
      <div>
        <div className="nl-acct-strong">{t('confirmTitle')}</div>
        <p className="nl-small nl-muted">{hasFactor ? t('confirmBody') : t('enrolBody')}</p>
        {hasFactor && useCode ? (
          <Field label={t('code')} error={error}>
            <TextInput value={code} onChange={e => setCode(codeValue(e.target.value))} inputMode="numeric" autoComplete="one-time-code" />
          </Field>
        ) : error ? <p className="nl-error" role="alert">{error}</p> : null}
        {signInAgain ? <p className="nl-small">{t('signInAgain')} <a href={signInHref(tabHref('security'))}>{t('signInAgainAction')}</a></p> : null}
      </div>
      <div className="nl-acct-actions">
        {!hasFactor ? <Button type="button" disabled={busy} aria-busy={busy} onClick={() => void run(enrolPasskey)}>{t('addPasskey')}</Button>
          : useCode ? <Button type="button" disabled={busy || !/^\d{6}$/.test(code.trim())} onClick={() => void run(() => stepUpWithCode(code))}>{t('confirm')}</Button>
            : <>
              <Button type="button" disabled={busy} aria-busy={busy} onClick={() => void run(stepUpWithPasskey)}>{t('confirmPasskey')}</Button>
              <Button type="button" variant="ghost" onClick={() => setUseCode(true)}>{t('confirmCode')}</Button>
            </>}
      </div>
    </div>
  );
}

function SecurityView({ security, sid, phone }: { security: Security; sid: string | null; phone: string | null }) {
  const t = useSettingsT();
  const qc = useQueryClient();
  const signOut = useSignOut();
  const [error, setError] = useState<SecurityChangeError>();
  const [stepUp, setStepUp] = useState<() => Promise<unknown>>();
  const [confirm, setConfirm] = useState<{ title: string; body: string; run: () => Promise<unknown> }>();
  const [busy, setBusy] = useState(false);
  const refresh = () => Promise.all([qc.invalidateQueries({ queryKey: securityKey }), qc.invalidateQueries({ queryKey: accountSummaryQuery.queryKey })]);

  /** Runs a change; 403 step_up_required asks to confirm and retries. */
  const change = async (run: () => Promise<unknown>) => {
    setBusy(true); setError(undefined);
    try { await run(); await refresh(); } catch (e) {
      const reason = securityChangeError(e);
      if (reason === 'step_up_required') setStepUp(() => run); else setError(reason);
    } finally { setBusy(false); }
  };
  const addKey = (label: string) => change(async () => {
    try { await addPasskey(await createPasskey(await passkeyOptions()), label); } catch (e) {
      if (e instanceof PasskeyError) { setError('failed'); return; }
      throw e;
    }
  });

  const factors = [security.passkeys.length ? t('f_passkey') : null, security.authenticator ? t('authApp') : null, phone ? t('f_sms') : null].filter(Boolean).join(' + ');
  const strong = security.passkeys.length > 0 || security.authenticator;
  const others = security.sessions.filter(s => !s.current);
  const sessionText = (s: ActiveSession) => {
    const device = s.device ?? t('unknownDevice');
    const city = s.city ? `${s.city} · ` : '';
    return s.current ? t('thisSession', { device, city }) : t('sessionAgo', { device, city, ago: ago(s.lastSeenAt ?? s.signedInAt, t) });
  };

  return (
    <>
      <div className={strong ? 'nl-sec-banner' : 'nl-sec-banner is-weak'} role="status">
        <strong>{strong ? t('secWell') : t('secWeak')}</strong> {strong ? t('secSummary', { factors, devices: security.sessions.length }) : t('secWeakBody')}
      </div>
      <ul className="nl-acct-rows" aria-label={t('secTitle')}>
        {security.passkeys.map((p, i) => (
          <li key={p.id} className="nl-acct-row">
            <span>{t('passkeyRow', { label: p.label })}</span>
            {i === 0 && security.mfaPrimary === 'passkey' ? <Tag tone="accent">{t('primary')}</Tag>
              : <Button type="button" variant="ghost" disabled={busy} onClick={() => setConfirm({ title: t('removePasskeyTitle'), body: t('removePasskeyBody', { label: p.label }), run: () => removePasskey(p.id) })}>{t('remove')}</Button>}
          </li>
        ))}
        {security.passkeys.length === 0 ? (
          <li className="nl-acct-row"><span>{t('addPasskey')}</span><Button type="button" variant="ghost" disabled={busy} onClick={() => void addKey('Passkey')}>{t('add')}</Button></li>
        ) : null}
        <li className="nl-acct-row"><span>{t('authApp')}</span><Tag tone={security.authenticator ? 'accent' : 'neutral'}>{security.authenticator ? t('enabled') : t('notSetUp')}</Tag></li>
        {phone ? <li className="nl-acct-row"><span>{t('smsBackup', { last4: phone.replace(/\D/g, '').slice(-4) })}</span><Tag tone="neutral">{t('backup')}</Tag></li> : null}
        <li className="nl-acct-row"><span>{t('securityKey')}</span><Button type="button" variant="ghost" disabled={busy} onClick={() => void addKey('Security key')}>{t('add')}</Button></li>
        <li className="nl-acct-row"><span>{t('payRule')}</span><strong>{t('payRuleValue')}</strong></li>
        {security.sessions.map(s => (
          <li key={s.id} className="nl-acct-row">
            <span>{sessionText(s)}</span>
            {s.current ? <Tag tone="neutral">{t('now')}</Tag>
              : <Button type="button" variant="ghost" disabled={busy} onClick={() => void change(() => revokeSession(s.id, sid))}>{t('signOut')}</Button>}
          </li>
        ))}
      </ul>
      {error ? <p className="nl-error" role="alert">{t(`err_${error}`)}</p> : null}
      <div className="nl-acct-actions">
        <a className="btn btn-secondary" href={`${tabHref('profile')}#your-data`}>{t('download')}</a>
        <Button type="button" variant="ghost" className="nl-danger" disabled={busy}
          onClick={() => setConfirm({ title: t('everywhereTitle'), body: t('everywhereBody'), run: async () => { if (others.length) await revokeOtherSessions(sid); await signOut('/'); } })}>
          {t('signOutEverywhere')}
        </Button>
      </div>
      <Dialog open={!!confirm} onClose={() => setConfirm(undefined)} title={confirm?.title ?? ''} role="alertdialog"
        actions={<>
          <Button type="button" variant="ghost" onClick={() => setConfirm(undefined)}>{t('cancel')}</Button>
          <Button type="button" disabled={busy} onClick={() => { const c = confirm; setConfirm(undefined); if (c) void change(c.run); }}>{t('confirm')}</Button>
        </>}>
        <p>{confirm?.body}</p>
      </Dialog>
      {stepUp ? (
        <Dialog open onClose={() => setStepUp(undefined)} title={t('confirmTitle')} actions={<Button type="button" variant="ghost" onClick={() => setStepUp(undefined)}>{t('cancel')}</Button>}>
          <ConfirmItsYou onDone={() => { const retry = stepUp; setStepUp(undefined); void change(retry); }} />
        </Dialog>
      ) : null}
    </>
  );
}
