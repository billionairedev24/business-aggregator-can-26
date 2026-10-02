import { useQuery, useQueryClient } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { useState } from 'react';
import { Share, View } from 'react-native';

import { ApiError } from '@northline/mobile-kit';

import type { PrivacyRequest, PrivacyType } from '../api/account';
import { useAuth } from '../auth/AuthProvider';
import type { MessageKey } from '../i18n';
import { stepUpProof } from '../services/stepUp';
import { useShopFormat } from '../shop/common';
import { Chip } from '../shop/parts';
import { Body, Button, Field, Notice } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { LoadingList, QueryView, SignInPrompt, errorMessage } from '../ui/states';
import { KEYS, account } from './common';
import { Heading, accountStyles } from './parts';

const OPEN = ['awaiting_verification', 'verified', 'in_progress'];
const FIELDS = ['phone', 'receiptName', 'reviewName', 'legalName'] as const;

/**
 * "Your data" (S-105): a copy of one's data, a correction, or deleting the account — in the app, as the App Store
 * (5.1.1(v)) and Google Play require for an app that creates accounts. Each is a privacy request handled under the
 * person's province's law and its deadline (the api says which). The person confirms it is them with the code texted
 * to their verified mobile, or their authenticator app's code (northline-auth's step-up proof). A ready copy goes to
 * the share sheet through a short-lived link.
 */
export function YourData() {
  const f = useShopFormat();
  const { t } = f;
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const qc = useQueryClient();
  const requests = useQuery({ queryKey: KEYS.privacy, queryFn: () => account().privacyRequests(), enabled: signedIn, staleTime: 15_000 });
  const [mode, setMode] = useState<'delete' | 'correct' | null>(null);
  const [verifying, setVerifying] = useState<PrivacyRequest | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [shared, setShared] = useState(false);

  if (!signedIn) {
    return (
      <Screen title={t('account.data.title')} testID="your-data">
        <SignInPrompt message={t('account.data.signIn')} />
      </Screen>
    );
  }
  const refresh = () => Promise.all([KEYS.privacy, KEYS.profile, KEYS.summary].map((k) => qc.invalidateQueries({ queryKey: k })));
  const run = async (work: () => Promise<unknown>) => {
    setError(null);
    setBusy(true);
    try {
      await work();
      await refresh();
    } catch (e) {
      setError(e instanceof ApiError && e.code === 'request_open' ? t('account.data.alreadyOpen') : errorMessage(e, t));
    } finally {
      setBusy(false);
    }
  };
  const ask = (type: PrivacyType, extra: { corrections?: { field: string; value: string }[]; note?: string } = {}) =>
    run(async () => {
      const r = await account().openPrivacyRequest({ type, ...extra });
      setMode(null);
      if (r.state === 'awaiting_verification') setVerifying(r);
    });
  const share = (r: PrivacyRequest) =>
    run(async () => {
      setShared(false);
      const link = await account().downloadLink(r.id);
      const data = await account().exportData(link);
      const res = await Share.share({ title: `northline-${r.reference}.json`, message: JSON.stringify(data, null, 2) });
      if (res.action !== Share.dismissedAction) setShared(true);
    });
  const has = (type: PrivacyType) => (requests.data ?? []).some((r) => r.type === type && OPEN.includes(r.state));

  return (
    <Screen title={t('account.data.title')} testID="your-data">
      <View style={accountStyles.form}>
        <Body>{t('account.data.lede')}</Body>
        {error ? <Notice message={error} testID="data-error" /> : null}
        {shared ? <Notice tone="info" message={t('account.data.shared')} /> : null}
        {verifying ? (
          <Verify request={verifying} onDone={() => { setVerifying(null); void refresh(); }} />
        ) : mode === 'delete' ? (
          <View style={accountStyles.actions}>
            <Body>{t('account.data.deleteBody')}</Body>
            <Button label={t('account.data.deleteConfirm')} tone="danger" busy={busy} onPress={() => void ask('erasure')} testID="erasure-confirm" />
            <Button label={t('account.data.keep')} tone="ghost" onPress={() => setMode(null)} />
          </View>
        ) : mode === 'correct' ? (
          <Correction busy={busy} onSend={(c, note) => void ask('correction', { corrections: [c], note })} onCancel={() => setMode(null)} />
        ) : (
          <View style={accountStyles.actions}>
            <Button label={t('account.data.download')} tone="secondary" busy={busy} disabled={has('access')} onPress={() => void ask('access')} testID="export" />
            <Button label={t('account.data.correct')} tone="ghost" disabled={has('correction')} onPress={() => setMode('correct')} testID="correction-start" />
            <Button label={t('account.data.delete')} tone="danger" disabled={has('erasure')} onPress={() => setMode('delete')} testID="erasure-start" />
          </View>
        )}
        <Heading>{t('account.data.requests')}</Heading>
        <QueryView query={requests} skeleton={<LoadingList rows={2} height={44} />}>
          {(items) =>
            items.length ? (
              <View style={accountStyles.list}>
                {items.map((r) => (
                  <View key={r.id} style={accountStyles.actions} testID={`request-${r.id}`}>
                    <Body>{`${t(`account.data.type.${r.type}` as MessageKey)} · ${r.reference}`}</Body>
                    <Body tone="small">{statusText(r, t, f.date)}</Body>
                    {OPEN.includes(r.state) ? <Body tone="muted">{t('account.data.due', { date: f.date(r.extendedTo ?? r.dueAt), law: r.law.shortName })}</Body> : null}
                    {r.state === 'awaiting_verification' ? <Button label={t('account.data.confirmIt')} tone="secondary" onPress={() => setVerifying(r)} testID={`verify-${r.id}`} /> : null}
                    {r.type === 'erasure' && (r.state === 'verified' || r.state === 'awaiting_verification') ? (
                      <Button label={t('account.data.cancelDeletion')} tone="ghost" busy={busy} onPress={() => void run(() => account().withdrawPrivacyRequest(r.id))} testID={`withdraw-${r.id}`} />
                    ) : null}
                    {r.type === 'access' && r.state === 'completed' && r.export?.ready ? (
                      <Button label={t('account.data.share')} tone="secondary" busy={busy} onPress={() => void share(r)} testID={`share-${r.id}`} />
                    ) : null}
                    {r.state === 'rejected' ? (
                      <Button label={r.law.authority} tone="ghost" hint={t('common.opensInBrowser')} onPress={() => void Linking.openURL(r.law.authorityUrl)} />
                    ) : null}
                  </View>
                ))}
              </View>
            ) : (
              <Body tone="muted">{t('account.data.none')}</Body>
            )
          }
        </QueryView>
      </View>
    </Screen>
  );
}

function statusText(r: PrivacyRequest, t: ReturnType<typeof useShopFormat>['t'], date: (iso: string) => string): string {
  switch (r.state) {
    case 'awaiting_verification':
      return t('account.data.s.awaiting');
    case 'rejected':
      return t('account.data.s.rejected', { authority: r.law.authority });
    case 'withdrawn':
      return t('account.data.s.withdrawn');
    case 'completed':
      if (r.type === 'access') return r.export?.ready ? t('account.data.s.ready', { date: date(r.export.expiresAt ?? r.dueAt) }) : t('account.data.s.expired');
      return t(r.type === 'correction' ? 'account.data.s.corrected' : 'account.data.s.deleted');
    default:
      if (r.type === 'erasure' && r.state === 'verified') return t('account.data.s.scheduled', { date: date(r.scheduledFor ?? r.dueAt) });
      return t(r.type === 'access' ? 'account.data.s.preparing' : r.type === 'erasure' ? 'account.data.s.deleting' : 'account.data.s.reviewing');
  }
}

/** The texted code, or the authenticator app's code (a step-up proof) — the app has no passkey module (S-98). */
function Verify({ request, onDone }: { request: PrivacyRequest; onDone: () => void }) {
  const { t } = useShopFormat();
  const [code, setCode] = useState('');
  const [authenticator, setAuthenticator] = useState(!request.codeSentTo);
  const [sentTo, setSentTo] = useState(request.codeSentTo ?? null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const confirm = async () => {
    if (!/^\d{6}$/.test(code.trim())) {
      setError(t('account.data.v.code'));
      return;
    }
    setError(null);
    setBusy(true);
    try {
      if (authenticator) await account().verifyPrivacyRequest(request.id, { stepUp: await stepUpProof(code) });
      else await account().verifyPrivacyRequest(request.id, { code: code.trim() });
      onDone();
    } catch (e) {
      setError(e instanceof ApiError && e.status === 422 ? (e.fieldMessage('code') ?? t('account.data.v.wrong')) : errorMessage(e, t));
    } finally {
      setBusy(false);
    }
  };
  return (
    <View style={accountStyles.actions} testID="privacy-verify">
      <Heading>{t('account.data.confirmIt')}</Heading>
      <Body>{sentTo && !authenticator ? t('account.data.codeSent', { to: sentTo }) : t('account.data.useAuthenticatorBody')}</Body>
      <Field
        label={sentTo && !authenticator ? t('account.data.code') : t('account.data.authenticatorCode')}
        value={code}
        onChangeText={setCode}
        keyboardType="number-pad"
        maxLength={6}
        autoComplete="one-time-code"
        textContentType="oneTimeCode"
        error={error}
        testID="privacy-code"
      />
      <Button label={t('account.data.confirm')} busy={busy} onPress={() => void confirm()} testID="privacy-confirm" />
      {sentTo && !authenticator ? (
        <>
          <Button
            label={t('account.data.newCode')}
            tone="ghost"
            onPress={() => void account().resendPrivacyCode(request.id).then((r) => setSentTo(r.codeSentTo ?? null), (e: unknown) => setError(errorMessage(e, t)))}
          />
          <Button label={t('account.data.useAuthenticator')} tone="ghost" onPress={() => setAuthenticator(true)} testID="privacy-authenticator" />
        </>
      ) : null}
    </View>
  );
}

function Correction({ busy, onSend, onCancel }: { busy: boolean; onSend: (c: { field: string; value: string }, note?: string) => void; onCancel: () => void }) {
  const { t } = useShopFormat();
  const [field, setField] = useState<(typeof FIELDS)[number]>('phone');
  const [value, setValue] = useState('');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const send = () => {
    if (!value.trim() || value.trim().length > 200) {
      setError(t('account.data.v.value'));
      return;
    }
    setError(null);
    onSend({ field, value: value.trim() }, note.trim() || undefined);
  };
  return (
    <View style={accountStyles.actions}>
      <Body>{t('account.data.correctBody')}</Body>
      <View style={accountStyles.chips} accessibilityRole="radiogroup" accessibilityLabel={t('account.data.field')}>
        {FIELDS.map((x) => (
          <Chip key={x} role="radio" label={t(`account.data.f.${x}` as MessageKey)} on={field === x} onPress={() => setField(x)} testID={`field-${x}`} />
        ))}
      </View>
      <Field label={t('account.data.value')} value={value} onChangeText={setValue} maxLength={200} error={error} testID="correction-value" />
      <Field label={t('account.data.note')} value={note} onChangeText={setNote} maxLength={1000} multiline testID="correction-note" />
      <Button label={t('account.data.send')} busy={busy} onPress={send} testID="correction-send" />
      <Button label={t('account.data.keep')} tone="ghost" onPress={onCancel} />
    </View>
  );
}
