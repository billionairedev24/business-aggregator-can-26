import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, DataTable, Dialog, Drawer, ErrorState, Field, PageSkeleton, Tag, TextInput, useFormatters, type DataTableColumn } from '@northline/ui';
import { useSession } from '../../lib/session';
import { authApi } from '../auth/api';
import { createPasskey, getPasskey, PasskeyError } from '../auth/webauthn';
import { useMerchantId, useRole } from '../shell/api';
import { addPasskey, auditLogQuery, passkeyOptions, securityQuery, useNewBackupCodes, type AuditEntry, type Security } from './api';
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

/** Security (design 02 › st.security): the signed-in person's factors and sessions, from northline-auth. */
export function SecurityTab() {
  const t = useSettingsT();
  const q = useQuery(securityQuery);
  return (
    <div className="nl-set-security">
      <div className="nl-set-banner"><strong>{t('securityBanner')}</strong>{t('securityBannerTail')}</div>
      {q.isPending ? <PageSkeleton kpis={0} rows={6} />
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
  const codes = useNewBackupCodes();
  const [shownCodes, setShownCodes] = useState<string[] | null>(null);
  const [adding, setAdding] = useState(false);
  const [keyMessage, setKeyMessage] = useState<{ text: string; error: boolean } | null>(null);
  const [sessionsOpen, setSessionsOpen] = useState(false);
  const [auditOpen, setAuditOpen] = useState(false);
  const devices = [...new Set(security.signIns.map(s => deviceName(s.device, t)))];
  const primary = security.mfaPrimary === 'passkey' ? security.passkeys[0] : undefined;

  const addKey = async () => {
    setAdding(true);
    setKeyMessage(null);
    try {
      const options = await passkeyOptions();
      const credential = await createPasskey(options);
      await addPasskey(credential, t('securityKey'));
      setKeyMessage({ text: t('keyAdded'), error: false });
      await qc.invalidateQueries({ queryKey: securityQuery.queryKey });
    } catch (e) {
      setKeyMessage({ text: e instanceof PasskeyError ? (e.reason === 'unsupported' ? t('keyUnsupported') : t('keyCancelled')) : t('keyError'), error: !(e instanceof PasskeyError && e.reason === 'cancelled') });
    } finally {
      setAdding(false);
    }
  };

  return (
    <>
      <ul className="nl-set-factors">
        {security.passkeys.length === 0 && <li><span>{t('passkeyNone')}</span><Tag tone="neutral">{t('notSetUp')}</Tag></li>}
        {security.passkeys.map(p => (
          <li key={p.id}><span>{t('passkeyRow', { label: p.label })}</span>{p === primary ? <Tag tone="accent">{t('primary')}</Tag> : <Tag tone="outline">{p.createdAt ? `${t('added')} ${f.date(p.createdAt, 'full')}` : t('added')}</Tag>}</li>
        ))}
        <li><span>{t('authenticator')}</span><Tag tone={security.authenticator ? 'accent' : 'neutral'}>{security.authenticator ? t('enabled') : t('notSetUp')}</Tag></li>
        <li>
          <span>{t('securityKey')}{keyMessage && <span role={keyMessage.error ? 'alert' : 'status'} className={keyMessage.error ? 'nl-error' : 'nl-set-sub'}>{keyMessage.text}</span>}</span>
          <Button variant="ghost" onClick={() => void addKey()} disabled={adding}>{adding ? t('adding') : t('add')}</Button>
        </li>
        <li>
          <span>{t('backupCodes', { n: security.backupCodesRemaining })}{codes.isError && <span role="alert" className="nl-error">{t('codesError')}</span>}</span>
          <Button variant="ghost" disabled={codes.isPending} onClick={() => codes.mutate(undefined, { onSuccess: r => { setShownCodes(r.codes); void qc.invalidateQueries({ queryKey: securityQuery.queryKey }); } })}>{t('newCodes')}</Button>
        </li>
        <li><span>{t('payoutReauth')}</span><Tag tone="accent">{t('on')}</Tag></li>
        <li><span>{security.email ? t('loginAlerts', { email: security.email }) : t('loginAlertsNone')}</span><Tag tone="accent">{t('on')}</Tag></li>
        <li>
          <span>{security.signIns.length ? t('sessions', { n: security.signIns.length, devices: devices.join(', ') }) : t('sessionsNone')}</span>
          <Button variant="ghost" onClick={() => setSessionsOpen(true)} disabled={!security.signIns.length}>{t('review')}</Button>
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
      <Drawer open={sessionsOpen} onClose={() => setSessionsOpen(false)} title={t('sessionsTitle')}>
        <ul className="nl-set-rows">
          {security.signIns.map(s => (
            <li key={s.id} className="nl-set-row">
              <span><strong>{deviceName(s.device, t)}</strong>{s.city ? ` · ${s.city}` : ''}<span className="nl-set-sub">{t('signedIn', { date: f.date(s.at, 'dateTime') })}{s.method ? ` · ${t(`method_${s.method}` as Parameters<SettingsT>[0])}` : ''}</span></span>
            </li>
          ))}
        </ul>
      </Drawer>
      {auditOpen && <AuditDrawer onClose={() => setAuditOpen(false)} />}
    </>
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
  const done = () => qc.invalidateQueries({ queryKey: securityQuery.queryKey });
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
