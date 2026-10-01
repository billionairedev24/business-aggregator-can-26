import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import {
  addPasskey, createPasskey, passkeyOptions, PasskeyError, removePasskey, revokeOtherSessions, revokeSession, securityChangeError, securityKey,
  securityQuery, type Security,
} from '@northline/auth-kit';
import { Button, ErrorState, PageSkeleton, Segmented, Tag, UnderlineTabs, useFormatters, useLocale } from '@northline/ui';
import { useSession } from '../../lib/session';
import { useGrant } from '../shell/grant';
import { meQuery } from '../shell/api';
import { useShellT, type ShellKey } from '../shell/messages';
import { AuditLog } from '../team/AuditLog';
import { useProfileT, type ProfileKey } from './messages';
import '../team/team.css';

const TABS = ['security', 'sessions', 'audit', 'prefs'] as const;
type Tab = (typeof TABS)[number];

/**
 * My profile (S-96, design 03 `profile`; every staff member): second factor and passkeys and the devices signed in, at
 * northline-auth through `@northline/auth-kit` (the Studio's and the consumer site's security API, S-19), my own audit
 * trail, and the console's language.
 */
export function Profile() {
  const t = useProfileT();
  const shell = useShellT();
  const { tab } = useSearch({ strict: false }) as { tab?: Tab };
  const navigate = useNavigate();
  const session = useSession().data;
  const me = useQuery(meQuery).data;
  const { roleName } = useGrant();
  const current: Tab = tab ?? 'security';
  const user = session?.user;
  const roles = (me?.roles ?? []).map(r => shell(`role_${r.role}` as ShellKey)).join(', ');
  return (
    <div>
      <h1 className="nl-tm-title">{t('title')}</h1>
      {user ? (
        <div className="nl-pf-who">
          <span className="nl-pf-initials" aria-hidden>{user.initials}</span>
          <div>
            <strong>{`${user.firstName} ${user.lastName}`}</strong>
            <div className="nl-tm-sub">{t('meta', { email: user.email ?? '—', roles: roles || '—', view: roleName ?? '—' })}</div>
          </div>
        </div>
      ) : null}
      <UnderlineTabs aria-label={t('tabs')} value={current} options={TABS.map(v => ({ value: v, label: t(`tab_${v}` as ProfileKey) }))}
        onChange={v => void navigate({ to: '/profile', search: { tab: v === 'security' ? undefined : v } as never })} />
      <div className="nl-pf-panel">
        {current === 'audit' ? <AuditLog mine /> : current === 'prefs' ? <Prefs /> : <SecurityPanel tab={current} sid={session?.sid ?? null} />}
      </div>
    </div>
  );
}

function SecurityPanel({ tab, sid }: { tab: 'security' | 'sessions'; sid: string | null }) {
  const t = useProfileT();
  const security = useQuery(securityQuery(sid));
  if (security.isPending) return <PageSkeleton kpis={0} rows={4} />;
  if (security.isError) return <ErrorState message={t('loadError')} onRetry={() => void security.refetch()} />;
  if (security.data === null) return <p className="nl-tm-sub">{t('confirmFirst')}</p>;
  return tab === 'security' ? <Factors security={security.data} sid={sid} /> : <Sessions security={security.data} sid={sid} />;
}

function useChange() {
  const t = useProfileT();
  const qc = useQueryClient();
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const run = async (step: () => Promise<string | void>) => {
    setBusy(true); setMessage(null);
    try {
      const done = await step();
      if (done) setMessage({ text: done, error: false });
      await qc.invalidateQueries({ queryKey: securityKey });
    } catch (e) {
      if (e instanceof PasskeyError) setMessage({ text: e.reason === 'unsupported' ? t('keyUnsupported') : t('keyCancelled'), error: e.reason !== 'cancelled' });
      else setMessage({ text: t(`err_${securityChangeError(e)}` as ProfileKey), error: true });
    } finally {
      setBusy(false);
    }
  };
  return { run, busy, message };
}

function Factors({ security, sid }: { security: Security; sid: string | null }) {
  const t = useProfileT();
  const { run, busy, message } = useChange();
  return (
    <>
      <ul className="nl-tm-log nl-pf-rows">
        {security.passkeys.map((p, i) => (
          <li key={p.id}>
            <span>{t('passkey', { label: p.label })}</span>
            <span className="nl-tm-actions">
              <Tag tone={i === 0 ? 'accent' : 'neutral'}>{i === 0 ? t('primary') : t('backup')}</Tag>
              <Button variant="ghost" disabled={busy} onClick={() => void run(async () => { await removePasskey(p.id); })}>{t('removePasskey')}</Button>
            </span>
          </li>
        ))}
        <li><span>{t('authenticator')}</span><Tag tone={security.authenticator ? 'accent' : 'neutral'}>{security.authenticator ? t('on') : t('off')}</Tag></li>
        <li><span>{t('backupCodes')}</span><span className="nl-tm-sub">{t('codesLeft', { n: security.backupCodesRemaining })}</span></li>
      </ul>
      <div className="nl-tm-actions">
        <Button variant="secondary" disabled={busy} onClick={() => void run(async () => {
          const options = await passkeyOptions();
          const credential = await createPasskey(options);
          await addPasskey(credential, t('keyLabel'));
          return t('keyAdded');
        })}>{t('addPasskey')}</Button>
        <Button variant="ghost" className="nl-pf-danger" disabled={busy} onClick={() => void run(async () => {
          const r = await revokeOtherSessions(sid);
          return t('signedOutOthers', { n: r.revoked });
        })}>{t('signOutEverywhere')}</Button>
      </div>
      {message ? <p role={message.error ? 'alert' : 'status'} className={message.error ? 'nl-tm-error' : 'nl-tm-sub'}>{message.text}</p> : null}
    </>
  );
}

function Sessions({ security, sid }: { security: Security; sid: string | null }) {
  const t = useProfileT();
  const fmt = useFormatters();
  const { run, busy, message } = useChange();
  return (
    <>
      <ul className="nl-tm-log nl-pf-rows">
        {security.sessions.map(s => (
          <li key={s.id}>
            <span><strong>{s.device ?? t('unknown')}</strong><span className="nl-tm-sub"> · {s.city ?? '—'} · {s.current ? t('now') : fmt.date(s.lastSeenAt ?? s.signedInAt, 'dateTime')}</span></span>
            {s.current ? <Tag tone="accent">{t('thisDevice')}</Tag>
              : <Button variant="ghost" disabled={busy} onClick={() => void run(async () => { await revokeSession(s.id, sid); })}>{t('signOut')}</Button>}
          </li>
        ))}
      </ul>
      {message ? <p role={message.error ? 'alert' : 'status'} className={message.error ? 'nl-tm-error' : 'nl-tm-sub'}>{message.text}</p> : null}
    </>
  );
}

function Prefs() {
  const t = useProfileT();
  const { locale, setLocale } = useLocale();
  return (
    <div>
      <h2 className="nl-tm-h2">{t('language')}</h2>
      <Segmented name="console-language" aria-label={t('language')} value={locale}
        options={[{ value: 'en', label: t('english') }, { value: 'fr', label: t('french') }]} onChange={v => setLocale(v)} />
    </div>
  );
}
