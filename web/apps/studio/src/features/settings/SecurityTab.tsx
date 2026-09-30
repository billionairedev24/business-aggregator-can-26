import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, DataTable, Dialog, Drawer, ErrorState, Field, PageSkeleton, Tag, TextInput, useFormatters, type DataTableColumn } from '@northline/ui';
import { useSession } from '../../lib/session';
import { authApi, createPasskey, getPasskey, PasskeyError } from '@northline/auth-kit';
import { useMerchantId, useRole } from '../shell/api';
import { StepUpFailed, stepUpWithCode, stepUpWithPasskey } from '../finance/stepUp';
import {
  addPasskey, auditLogQuery, passkeyOptions, removePasskey, revokeOtherSessions, revokeSession, securityChangeError, securityKey, securityQuery,
  useNewBackupCodes, type ActiveSession, type AuditEntry, type Security, type SecurityChangeError,
} from './api';
import { useSettingsT, type SettingsT } from './messages';

/** "Mozilla/5.0 (iPhone; …)" → "iPhone". */
export function deviceName(ua: string | null | undefined, t: SettingsT): string {
  if (!ua) return t('unknownDevice');
  if (/iPhone|iPad/i.test(ua)) return t('device_iphone');
  if (/Android/i.test(ua)) return t('device_android');
  if (/Macintosh|Mac OS X/i.test(ua)) return t('device_mac');
  if (/Windows/i.test(ua)) return t('device_windows');
  return t('device_other');
}

/**
 * Security (design 02 › st.security): the signed-in person's factors and sessions, from northline-auth. Removing a
 * passkey and signing sessions out (S-19) need a recent second factor: the step-up dialog asks for it when needed.
 */
export function SecurityTab() {
  const t = useSettingsT();
  const session = useSession().data;
  const devAuth = !!session?.devAuth;
  const q = useQuery({ ...securityQuery(session?.sid), enabled: !devAuth });
  return (
    <div className="nl-set-security">
      <div className="nl-set-banner"><strong>{t('securityBanner')}</strong>{t('securityBannerTail')}</div>
      {devAuth ? <Alert tone="neutral" title={t('securityDevAuthTitle')}>{t('securityDevAuth')}</Alert>
        : q.isPending ? <PageSkeleton kpis={0} rows={6} />
        : q.isError ? <ErrorState message={t('securityError')} onRetry={() => void q.refetch()} />
        : q.data === null ? <ConfirmItsYou />
        : <Factors security={q.data} />}
    </div>
  );
}

function Factors({ security }: { security: Security }) {
  const t = useSettingsT();
  const f = useFormatters();
  const qc = useQueryClient();
  const role = useRole();
  const sid = useSession().data?.sid ?? null;
  const codes = useNewBackupCodes();
  const change = useSecurityChange();
  const [shownCodes, setShownCodes] = useState<string[] | null>(null);
  const [adding, setAdding] = useState(false);
  const [keyMessage, setKeyMessage] = useState<{ text: string; error: boolean } | null>(null);
  const [removing, setRemoving] = useState<Security['passkeys'][number] | null>(null);
  const [sessionsOpen, setSessionsOpen] = useState(false);
  const [auditOpen, setAuditOpen] = useState(false);
  const devices = [...new Set(security.sessions.map(s => deviceName(s.device, t)))];
  const primary = security.mfaPrimary === 'passkey' ? security.passkeys[0] : undefined;
  // Never the last second factor (S-19): another passkey or the authenticator app must remain.
  const lastFactor = security.passkeys.length + (security.authenticator ? 1 : 0) <= 1;

  const addKey = async () => {
    setAdding(true);
    setKeyMessage(null);
    try {
      const options = await passkeyOptions();
      const credential = await createPasskey(options);
      await addPasskey(credential, t('securityKey'));
      setKeyMessage({ text: t('keyAdded'), error: false });
      await qc.invalidateQueries({ queryKey: securityKey });
    } catch (e) {
      setKeyMessage({ text: e instanceof PasskeyError ? (e.reason === 'unsupported' ? t('keyUnsupported') : t('keyCancelled')) : t('keyError'), error: !(e instanceof PasskeyError && e.reason === 'cancelled') });
    } finally {
      setAdding(false);
    }
  };

  const remove = (id: string) => change.run(async () => {
    await removePasskey(id);
    setRemoving(null);
    setKeyMessage({ text: t('keyRemoved'), error: false });
    await qc.invalidateQueries({ queryKey: securityKey });
  });

  return (
    <>
      <ul className="nl-set-factors">
        {security.passkeys.length === 0 && <li><span>{t('passkeyNone')}</span><Tag tone="neutral">{t('notSetUp')}</Tag></li>}
        {security.passkeys.map(p => (
          <li key={p.id}>
            <span>{t('passkeyRow', { label: p.label })}</span>
            <span className="nl-set-rowside">
              {p === primary ? <Tag tone="accent">{t('primary')}</Tag> : <Tag tone="outline">{p.createdAt ? `${t('added')} ${f.date(p.createdAt, 'full')}` : t('added')}</Tag>}
              <Button variant="ghost" aria-label={t('removeKeyLabel', { label: p.label })} title={lastFactor ? t('err_last_factor') : undefined}
                disabled={lastFactor} onClick={() => { change.reset(); setRemoving(p); }}>{t('removeKey')}</Button>
            </span>
          </li>
        ))}
        {lastFactor && security.passkeys.length > 0 && <li className="nl-set-note"><span className="nl-set-sub">{t('err_last_factor')}</span></li>}
        <li><span>{t('authenticator')}</span><Tag tone={security.authenticator ? 'accent' : 'neutral'}>{security.authenticator ? t('enabled') : t('notSetUp')}</Tag></li>
        <li>
          <span>{t('securityKey')}{keyMessage && <span role={keyMessage.error ? 'alert' : 'status'} className={keyMessage.error ? 'nl-error' : 'nl-set-sub'}>{keyMessage.text}</span>}</span>
          <Button variant="ghost" onClick={() => void addKey()} disabled={adding}>{adding ? t('adding') : t('add')}</Button>
        </li>
        <li>
          <span>{t('backupCodes', { n: security.backupCodesRemaining })}{codes.isError && <span role="alert" className="nl-error">{t('codesError')}</span>}</span>
          <Button variant="ghost" disabled={codes.isPending} onClick={() => codes.mutate(undefined, { onSuccess: r => { setShownCodes(r.codes); void qc.invalidateQueries({ queryKey: securityKey }); } })}>{t('newCodes')}</Button>
        </li>
        <li><span>{t('payoutReauth')}</span><Tag tone="accent">{t('on')}</Tag></li>
        <li><span>{security.email ? t('loginAlerts', { email: security.email }) : t('loginAlertsNone')}</span><Tag tone="accent">{t('on')}</Tag></li>
        <li>
          <span>{security.sessions.length ? t('sessions', { n: security.sessions.length, devices: devices.join(', ') }) : t('sessionsNone')}</span>
          <Button variant="ghost" onClick={() => { change.reset(); setSessionsOpen(true); }} disabled={!security.sessions.length && !security.signIns.length}>{t('review')}</Button>
        </li>
        {role === 'owner' && (
          <li><span>{t('auditLog')}</span><Button variant="ghost" onClick={() => setAuditOpen(true)}>{t('view')}</Button></li>
        )}
      </ul>
      {shownCodes && (
        <Dialog open onClose={() => setShownCodes(null)} title={t('codesTitle')} actions={<Button onClick={() => setShownCodes(null)}>{t('done')}</Button>}>
          <p className="nl-small">{t('codesNote')}</p>
          <ul className="nl-set-codes">{shownCodes.map(c => <li key={c}><code>{c}</code></li>)}</ul>
        </Dialog>
      )}
      {removing && !change.stepUp && (
        <Dialog open role="alertdialog" onClose={() => setRemoving(null)} title={t('removeKeyTitle')}
          actions={<>
            <Button variant="ghost" onClick={() => setRemoving(null)} disabled={change.busy}>{t('cancel')}</Button>
            <Button onClick={() => void remove(removing.id)} disabled={change.busy} aria-busy={change.busy || undefined}>{change.busy ? t('removing') : t('removeKey')}</Button>
          </>}>
          <p className="nl-small">{t('removeKeyBody', { label: removing.label })}</p>
          {change.error && <Alert tone="error" role="alert">{t(`err_${change.error}`)}</Alert>}
        </Dialog>
      )}
      <Drawer open={sessionsOpen} onClose={() => setSessionsOpen(false)} title={t('activeSessionsTitle')}>
        <Sessions security={security} sid={sid} change={change} />
      </Drawer>
      {change.stepUp && <StepUpDialog onDone={() => void change.retry()} onCancel={change.cancel} />}
      {auditOpen && <AuditDrawer onClose={() => setAuditOpen(false)} />}
    </>
  );
}

/**
 * Runs a Settings › Security change (S-19). A 403 `step_up_required` opens the step-up dialog; once the person has
 * confirmed with their passkey or authenticator code, the same change runs again.
 */
function useSecurityChange() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Exclude<SecurityChangeError, 'step_up_required'> | null>(null);
  const [pending, setPending] = useState<(() => Promise<void>) | null>(null);
  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
    } catch (e) {
      const why = securityChangeError(e);
      if (why === 'step_up_required') setPending(() => fn);
      else setError(why);
    } finally {
      setBusy(false);
    }
  };
  return {
    busy, error, stepUp: pending !== null, run,
    reset: () => setError(null),
    retry: async () => { const fn = pending; setPending(null); if (fn) await run(fn); },
    cancel: () => setPending(null),
  };
}
type SecurityChange = ReturnType<typeof useSecurityChange>;

function Sessions({ security, sid, change }: { security: Security; sid: string | null; change: SecurityChange }) {
  const t = useSettingsT();
  const f = useFormatters();
  const qc = useQueryClient();
  const [confirm, setConfirm] = useState<ActiveSession | 'others' | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const others = security.sessions.filter(s => !s.current);
  const refresh = () => qc.invalidateQueries({ queryKey: securityKey });
  const revoke = (target: ActiveSession | 'others') => change.run(async () => {
    if (target === 'others') {
      const r = await revokeOtherSessions(sid);
      setDone(t('revokedOthers', { n: r.revoked }));
    } else {
      await revokeSession(target.id, sid);
      setDone(t('revoked'));
    }
    setConfirm(null);
    await refresh();
  });
  const where = (s: ActiveSession) => [s.city, s.ipApprox].filter(Boolean).join(' · ');
  return (
    <>
      {done && <p role="status" className="nl-set-sub">{done}</p>}
      {change.error && !confirm && <Alert tone="error" role="alert">{t(`err_${change.error}`)}</Alert>}
      <ul className="nl-set-rows" aria-label={t('activeSessionsTitle')}>
        {security.sessions.map(s => (
          <li key={s.id} className="nl-set-row">
            <span>
              <strong>{deviceName(s.device, t)}</strong>{where(s) ? ` · ${where(s)}` : ''}
              <span className="nl-set-sub">
                {t('signedIn', { date: f.date(s.signedInAt, 'dateTime') })}{s.method ? ` · ${t(`method_${s.method}` as Parameters<SettingsT>[0])}` : ''}
                {s.lastSeenAt ? ` · ${t('lastActive', { date: f.date(s.lastSeenAt, 'dateTime') })}` : ''}
              </span>
              {s.apps.length > 0 && <span className="nl-set-sub">{t('viaApps', { apps: s.apps.join(', ') })}</span>}
            </span>
            {s.current ? <Tag tone="accent">{t('thisDevice')}</Tag>
              : <Button variant="ghost" aria-label={t('revokeSessionLabel', { device: deviceName(s.device, t) })} onClick={() => { change.reset(); setDone(null); setConfirm(s); }}>{t('revokeSession')}</Button>}
          </li>
        ))}
      </ul>
      {others.length > 0 && (
        <div className="nl-set-actions">
          <Button variant="secondary" onClick={() => { change.reset(); setDone(null); setConfirm('others'); }}>{t('revokeOthers')}</Button>
        </div>
      )}
      {security.signIns.length > 0 && (
        <>
          <h3 className="nl-set-h3">{t('recentSignIns')}</h3>
          <ul className="nl-set-rows">
            {security.signIns.map(s => (
              <li key={s.id} className="nl-set-row">
                <span><strong>{deviceName(s.device, t)}</strong>{s.city ? ` · ${s.city}` : ''}<span className="nl-set-sub">{t('signedIn', { date: f.date(s.at, 'dateTime') })}{s.method ? ` · ${t(`method_${s.method}` as Parameters<SettingsT>[0])}` : ''}</span></span>
              </li>
            ))}
          </ul>
        </>
      )}
      {confirm && !change.stepUp && (
        <Dialog open role="alertdialog" onClose={() => setConfirm(null)} title={confirm === 'others' ? t('revokeOthersTitle') : t('revokeTitle')}
          actions={<>
            <Button variant="ghost" onClick={() => setConfirm(null)} disabled={change.busy}>{t('cancel')}</Button>
            <Button onClick={() => void revoke(confirm)} disabled={change.busy} aria-busy={change.busy || undefined}>
              {change.busy ? t('signingOut') : confirm === 'others' ? t('revokeOthers') : t('revokeSession')}
            </Button>
          </>}>
          <p className="nl-small">{confirm === 'others' ? t('revokeOthersBody') : t('revokeBody', { device: deviceName(confirm.device, t) })}</p>
          {change.error && <Alert tone="error" role="alert">{t(`err_${change.error}`)}</Alert>}
        </Dialog>
      )}
    </>
  );
}

/** Step-up for a security change: the passkey, or a code from the authenticator app (northline-auth /step-up). */
function StepUpDialog({ onDone, onCancel }: { onDone: () => void; onCancel: () => void }) {
  const t = useSettingsT();
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async (get: () => Promise<string>) => {
    setBusy(true);
    setError(null);
    try {
      await get();
      onDone();
    } catch (e) {
      if (e instanceof StepUpFailed && e.reason === 'cancelled') setError(null);
      else if (e instanceof StepUpFailed && e.reason === 'locked') setError(t('err_rate_limited'));
      else if (e instanceof StepUpFailed && e.reason === 'signed_out') setError(t('err_signed_out'));
      else setError(t('confirmError'));
    } finally {
      setBusy(false);
    }
  };
  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!/^\d{6}$/.test(code.trim())) { setError(t('codeFormat')); return; }
    void run(() => stepUpWithCode(code.trim()));
  };
  return (
    <Dialog open onClose={onCancel} title={t('stepUpTitle')} actions={<Button variant="ghost" onClick={onCancel} disabled={busy}>{t('cancel')}</Button>}>
      <p className="nl-small">{t('stepUpBody')}</p>
      <div className="nl-set-confirmrow">
        <Button onClick={() => void run(stepUpWithPasskey)} disabled={busy}>{t('usePasskey')}</Button>
        <form onSubmit={submit} className="nl-set-confirmcode" noValidate>
          <Field label={t('codeLabel')}>
            <TextInput inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={e => setCode(e.target.value)} />
          </Field>
          <Button type="submit" variant="secondary" disabled={busy}>{busy ? t('confirming') : t('confirm')}</Button>
        </form>
      </div>
      {error && <Alert tone="error" role="alert">{error}</Alert>}
    </Dialog>
  );
}

interface AuditRow { id: string; at: number; who: string; action: string }

function AuditDrawer({ onClose }: { onClose: () => void }) {
  const t = useSettingsT();
  const f = useFormatters();
  const merchantId = useMerchantId();
  const q = useQuery(auditLogQuery(merchantId));
  const rows: AuditRow[] = (q.data ?? []).map((e: AuditEntry) => ({ id: e.id, at: new Date(e.at).getTime(), who: e.actorName ?? e.actorId ?? '—', action: e.action }));
  const columns: DataTableColumn<AuditRow>[] = [
    { key: 'at', label: t('colWhen'), format: v => f.date(Number(v), 'dateTime'), filter: false },
    { key: 'who', label: t('colWho'), filter: 'facet' },
    { key: 'action', label: t('colAction'), filter: 'facet', primary: true },
  ];
  return (
    <Drawer open onClose={onClose} title={t('auditTitle')} width={640}>
      <DataTable<AuditRow> entity={t('auditEntity')} plural={t('auditPlural')} columns={columns} rows={rows} loading={q.isPending}
        error={q.isError ? t('loadError') : null} onRetry={() => void q.refetch()} emptyText={t('auditEmpty')}
        can={{ create: false, update: false, delete: false, export: true }} reportName="audit-log" />
    </Drawer>
  );
}

/** The auth server session expired (or never had a second factor): step up with a passkey or authenticator code. */
function ConfirmItsYou() {
  const t = useSettingsT();
  const qc = useQueryClient();
  const session = useSession();
  const identifier = session.data?.user.email ?? session.data?.user.phone ?? '';
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const done = () => qc.invalidateQueries({ queryKey: securityKey });
  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try { await authApi.startSignIn(identifier); await fn(); await done(); } catch (e) { setError(e instanceof PasskeyError && e.reason === 'cancelled' ? null : t('confirmError')); } finally { setBusy(false); }
  };
  const passkey = () => run(async () => { const options = await authApi.passkeySignInOptions(); await authApi.signInPasskey(await getPasskey(options)); });
  const totp = (e: React.FormEvent) => {
    e.preventDefault();
    if (!/^\d{6}$/.test(code.trim())) { setError(t('codeFormat')); return; }
    void run(() => authApi.signInTotp(code));
  };
  return (
    <section className="nl-set-confirm" aria-labelledby="set-confirm">
      <h3 id="set-confirm" className="nl-set-h3">{t('confirmTitle')}</h3>
      <p className="nl-small nl-muted">{t('confirmBody')}</p>
      <div className="nl-set-confirmrow">
        <Button onClick={() => void passkey()} disabled={busy || !identifier}>{t('usePasskey')}</Button>
        <form onSubmit={totp} className="nl-set-confirmcode" noValidate>
          <Field label={t('codeLabel')}>
            <TextInput inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={e => setCode(e.target.value)} />
          </Field>
          <Button type="submit" variant="secondary" disabled={busy || !identifier}>{busy ? t('confirming') : t('confirm')}</Button>
        </form>
      </div>
      {error && <Alert tone="error" role="alert">{error}</Alert>}
    </section>
  );
}
