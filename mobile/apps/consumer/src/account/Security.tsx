import { useQuery, useQueryClient } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { router } from 'expo-router';
import { useState } from 'react';
import { Share, StyleSheet, Text, View } from 'react-native';

import { ApiError, colors, radius, space } from '@northline/mobile-kit';

import type { ActiveSession, Security as SecurityData } from '../api/account';
import { useAuth } from '../auth/AuthProvider';
import { config } from '../config';
import type { MessageKey } from '../i18n';
import { useShopFormat } from '../shop/common';
import { StepUpFailed, stepUpWithCode } from '../shop/stepUp';
import { Body, Button, Field, Notice, Tag, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { LoadingList, QueryView, SignInPrompt, errorMessage } from '../ui/states';
import { KEYS, account } from './common';
import { Heading, LineRow } from './parts';
import { securityApi } from './security';

const SITE_SECURITY = () => `${config.siteOrigin}/account?tab=security`;

/** "now", "12 min ago", "3 h ago", "2 d ago". */
export function ago(iso: string | null | undefined, t: (k: MessageKey, p?: Record<string, number>) => string, now = Date.now()): string {
  if (!iso) return t('account.sec.now');
  const min = Math.max(0, Math.round((now - Date.parse(iso)) / 60_000));
  if (min < 2) return t('account.sec.now');
  if (min < 60) return t('account.sec.minAgo', { n: min });
  if (min < 60 * 24) return t('account.sec.hAgo', { n: Math.round(min / 60) });
  return t('account.sec.dAgo', { n: Math.round(min / 1440) });
}

/**
 * D4 Security centre (design 01 `security`; signed in): how well the account is protected, the sign-in methods
 * (passkeys, the authenticator app, the SMS backup, a security key), devices & sessions with "Sign out" for each other
 * one, login alerts, "Download my data" (`GET /me/export` → the phone's share sheet) and "Sign out of all devices".
 * Methods and sessions are northline-auth's (`security.ts`), in the auth session with a recent second factor — the
 * authenticator code confirms it (S-51's step-up). Adding a passkey, a security key or the authenticator app needs
 * WebAuthn or a QR code the app can't show natively (S-98): those open the website's Security page. The design's
 * "Require Face ID for payments over $100" has no api (payments ask for a step-up when the api says so, S-51).
 */
export function Security() {
  const f = useShopFormat();
  const { t } = f;
  const { status, signOut } = useAuth();
  const qc = useQueryClient();
  const signedIn = status === 'signedIn';
  const security = useQuery({ queryKey: KEYS.security, queryFn: () => securityApi.overview(), enabled: signedIn, retry: false, staleTime: 30_000 });
  const profile = useQuery({ queryKey: KEYS.profile, queryFn: () => account().profile(), enabled: signedIn, staleTime: 300_000 });
  const [exporting, setExporting] = useState(false);
  const [exported, setExported] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [leaving, setLeaving] = useState(false);

  if (!signedIn) {
    return (
      <Screen title={t('title.security')} testID="security">
        <SignInPrompt message={t('account.sec.signIn')} />
      </Screen>
    );
  }

  const download = async () => {
    setError(null);
    setExported(false);
    setExporting(true);
    try {
      const data = await account().exportData();
      const r = await Share.share({ title: 'northline-my-data.json', message: JSON.stringify(data, null, 2) });
      if (r.action !== Share.dismissedAction) setExported(true);
    } catch (e) {
      setError(errorMessage(e, t));
    } finally {
      setExporting(false);
    }
  };

  const everywhere = async () => {
    setError(null);
    setLeaving(true);
    try {
      await securityApi.revokeOthers();
      await signOut();
      qc.removeQueries({ queryKey: KEYS.all });
      router.replace('/home');
    } catch (e) {
      setError(e instanceof ApiError && e.code === 'step_up_required' ? t('account.sec.confirmFirst') : errorMessage(e, t));
      if (e instanceof ApiError && e.code === 'step_up_required') qc.setQueryData(KEYS.security, null);
    } finally {
      setLeaving(false);
    }
  };

  return (
    <Screen
      title={t('title.security')}
      testID="security"
      footer={
        <>
          {error ? <Notice message={error} testID="security-error" /> : null}
          {exported ? <Notice tone="info" message={t('account.sec.exported')} /> : null}
          <Button label={t('account.sec.download')} tone="secondary" busy={exporting} onPress={() => void download()} testID="export" />
          {security.data ? (
            <Button label={t('account.sec.signOutAll')} tone="danger" busy={leaving} onPress={() => void everywhere()} testID="sign-out-all" />
          ) : null}
        </>
      }
    >
      <QueryView query={security} skeleton={<LoadingList rows={6} height={44} />}>
        {(s) => (s ? <Overview s={s} phone={profile.data?.phone} f={f} /> : <ConfirmItsYou onDone={() => void security.refetch()} />)}
      </QueryView>
    </Screen>
  );
}

/** The auth session needs a recent second factor: the authenticator app's code (or the website, without one here). */
function ConfirmItsYou({ onDone }: { onDone: () => void }) {
  const { t } = useShopFormat();
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [elsewhere, setElsewhere] = useState(false);
  const [busy, setBusy] = useState(false);
  const confirm = async () => {
    setError(null);
    if (!/^\d{6}$/.test(code.trim())) return setError(t('shop.stepUp.wrong'));
    setBusy(true);
    try {
      await stepUpWithCode(code);
      setCode('');
      onDone();
    } catch (e) {
      if (e instanceof StepUpFailed && e.reason === 'elsewhere') setElsewhere(true);
      setError(e instanceof StepUpFailed ? t(e.reason === 'wrong_code' ? 'shop.stepUp.wrong' : e.reason === 'locked' ? 'shop.stepUp.locked' : 'account.sec.elsewhere') : errorMessage(e, t));
    } finally {
      setBusy(false);
    }
  };
  return (
    <View style={styles.confirm} testID="security-confirm">
      <Text accessibilityRole="header" style={[type.body, type.strong]}>
        {t('account.sec.confirmTitle')}
      </Text>
      <Body tone="muted">{t('account.sec.confirmBody')}</Body>
      <Field
        label={t('shop.stepUp.code')}
        value={code}
        onChangeText={setCode}
        keyboardType="number-pad"
        textContentType="oneTimeCode"
        autoComplete="one-time-code"
        maxLength={6}
        error={error}
        testID="security-code"
      />
      <Button label={t('shop.stepUp.confirm')} busy={busy} onPress={() => void confirm()} testID="security-confirm-button" />
      <Button label={t('account.sec.onWebsite')} tone={elsewhere ? 'primary' : 'ghost'} hint={t('common.opensInBrowser')} onPress={() => void Linking.openURL(SITE_SECURITY())} testID="security-site" />
    </View>
  );
}

function Overview({ s, phone, f }: { s: SecurityData; phone?: string | null; f: ReturnType<typeof useShopFormat> }) {
  const { t } = f;
  const qc = useQueryClient();
  const [sessions, setSessions] = useState<ActiveSession[]>(s.sessions);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const strong = s.passkeys.length > 0 || s.authenticator;
  const methods = [s.passkeys.length > 0 ? t('account.sec.m.passkey') : null, s.authenticator ? t('account.sec.m.totp') : null, phone ? t('account.sec.m.sms') : null].filter(Boolean).join(' + ');
  const site = () => void Linking.openURL(SITE_SECURITY());

  const revoke = async (id: string) => {
    setError(null);
    setBusy(id);
    try {
      const r = await securityApi.revoke(id);
      setSessions(r.sessions ?? sessions.filter((x) => x.id !== id));
    } catch (e) {
      if (e instanceof ApiError && e.code === 'step_up_required') {
        qc.setQueryData(KEYS.security, null);
        return;
      }
      setError(e instanceof ApiError && e.code === 'current_session' ? t('account.sec.currentSession') : errorMessage(e, t));
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      <View style={[styles.summary, !strong && styles.summaryWeak]} testID="security-summary">
        <Text style={[type.body, styles.summaryText]}>
          <Text style={type.strong}>{t(strong ? 'account.sec.well' : 'account.sec.weak')}</Text>{' '}
          {strong ? t('account.sec.summary', { methods, n: sessions.length }) : t('account.sec.weakBody')}
        </Text>
      </View>

      <Heading>{t('account.sec.methods')}</Heading>
      {s.passkeys.map((p, i) => (
        <LineRow key={p.id} right={i === 0 ? <Tag label={t('account.sec.primary')} tone="accent" /> : null}>
          <Text style={type.body}>{t('account.sec.passkey', { label: p.label })}</Text>
        </LineRow>
      ))}
      <LineRow right={s.authenticator ? <Tag label={t('account.sec.enabled')} tone="accent" /> : <Button label={t('account.sec.setUp')} tone="ghost" hint={t('common.opensInBrowser')} onPress={site} testID="setup-totp" />}>
        <Text style={type.body}>{t('account.sec.totp')}</Text>
      </LineRow>
      {phone ? (
        <LineRow right={<Tag label={t('account.sec.backup')} />}>
          <Text style={type.body}>{t('account.sec.sms', { last: phone.replace(/\D/g, '').slice(-4) })}</Text>
        </LineRow>
      ) : null}
      <LineRow right={<Button label={t('account.sec.add')} tone="ghost" hint={t('common.opensInBrowser')} onPress={site} testID="add-key" />}>
        <Text style={type.body}>{t('account.sec.key')}</Text>
      </LineRow>

      <Heading>{t('account.sec.devices')}</Heading>
      {error ? <Notice message={error} /> : null}
      {sessions.map((x) => (
        <LineRow
          key={x.id}
          testID={`session-${x.id}`}
          right={
            x.current ? null : (
              <Button label={t('account.sec.signOut')} tone="ghost" hint={x.device ?? undefined} busy={busy === x.id} disabled={!!busy} onPress={() => void revoke(x.id)} testID={`revoke-${x.id}`} />
            )
          }
        >
          <Text style={type.body}>{[x.device ?? t('account.sec.unknownDevice'), x.city].filter(Boolean).join(' · ')}</Text>
          <Text style={type.small}>{x.current ? t('account.sec.thisDevice') : ago(x.lastSeenAt ?? x.signedInAt, t)}</Text>
        </LineRow>
      ))}

      <Heading>{t('account.sec.alerts')}</Heading>
      <LineRow right={<Tag label={t('account.sec.on')} tone="accent" />}>
        <Text style={type.body}>{t('account.sec.loginAlerts')}</Text>
        <Text style={type.small}>{t('account.sec.loginAlertsNote')}</Text>
      </LineRow>
    </>
  );
}

const styles = StyleSheet.create({
  confirm: { gap: space[3] },
  summary: { padding: space[4], borderRadius: radius.md, backgroundColor: colors.accent100 },
  summaryWeak: { backgroundColor: colors.highlight100 },
  summaryText: { color: colors.accent900 },
});
