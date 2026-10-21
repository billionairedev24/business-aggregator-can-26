import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { View } from 'react-native';

import { ApiError } from '@northline/mobile-kit';

import type { ErasureConsequence, PrivacyRequest } from '../api/account';
import { useAuth } from '../auth/AuthProvider';
import { useShopFormat } from '../shop/common';
import { Body, Button, Checkbox, Notice } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { LoadingList, QueryView, SignInPrompt, errorMessage } from '../ui/states';
import { KEYS, account } from './common';
import { Heading, accountStyles } from './parts';
import { Verify } from './YourData';

const OPEN = ['awaiting_verification', 'verified', 'in_progress'];
const PREVIEW = ['account', 'privacy', 'erasure-preview'] as const;

/**
 * Account › Delete account (S-159; App Store 5.1.1(v), Google Play's in-app deletion): what deleting the account means
 * and what it affects right now (`erasure-preview`: open orders, upcoming bookings, money held, points, Plus,
 * businesses only this person owns), "I understand", then S-105's erasure request — confirmed with the code texted to
 * the verified mobile or emailed to the verified address, or the authenticator app. A request already made shows when
 * it starts and can be cancelled until then. Confirmation emails follow (scheduled, done).
 */
export function DeleteAccount() {
  const f = useShopFormat();
  const { t } = f;
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const qc = useQueryClient();
  const requests = useQuery({ queryKey: KEYS.privacy, queryFn: () => account().privacyRequests(), enabled: signedIn, staleTime: 15_000 });
  const preview = useQuery({ queryKey: PREVIEW, queryFn: () => account().erasurePreview(), enabled: signedIn });
  const [agreed, setAgreed] = useState(false);
  const [tried, setTried] = useState(false);
  const [verifying, setVerifying] = useState<PrivacyRequest | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  if (!signedIn) {
    return (
      <Screen title={t('title.deleteAccount')} testID="delete-account">
        <SignInPrompt message={t('account.data.signIn')} />
      </Screen>
    );
  }
  const refresh = () => Promise.all([KEYS.privacy, KEYS.profile, KEYS.summary].map((k) => qc.invalidateQueries({ queryKey: k })));
  const run = async (work: () => Promise<unknown>) => {
    setError(null);
    setNotice(null);
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
  const confirm = () => {
    setTried(true);
    if (!agreed) return;
    void run(async () => {
      const r = await account().openPrivacyRequest({ type: 'erasure' });
      if (r.state === 'awaiting_verification') setVerifying(r);
    });
  };
  const pending = (requests.data ?? []).find((r) => r.type === 'erasure' && OPEN.includes(r.state));

  return (
    <Screen title={t('title.deleteAccount')} testID="delete-account">
      <View style={accountStyles.form}>
        {error ? <Notice message={error} testID="delete-error" /> : null}
        {notice ? <Notice tone="info" message={notice} /> : null}
        {verifying ? (
          <Verify request={verifying} onDone={() => { setVerifying(null); void refresh(); }} />
        ) : pending ? (
          <View style={accountStyles.actions} testID="delete-pending">
            <Body>{pending.state === 'awaiting_verification' ? t('delete.awaiting') : t('delete.pending', { date: f.date(pending.scheduledFor ?? pending.dueAt) })}</Body>
            {pending.state === 'awaiting_verification' ? <Button label={t('account.data.confirmIt')} tone="secondary" onPress={() => setVerifying(pending)} testID="delete-verify" /> : null}
            {pending.state !== 'in_progress' ? (
              <Button label={t('delete.cancel')} tone="ghost" busy={busy} onPress={() => void run(async () => { await account().withdrawPrivacyRequest(pending.id); setNotice(t('delete.cancelled')); })} testID="delete-withdraw" />
            ) : null}
          </View>
        ) : (
          <>
            <Body>{t('delete.lede')}</Body>
            <Heading>{t('delete.whatTitle')}</Heading>
            <View style={accountStyles.list}>
              {(['delete.what1', 'delete.what2', 'delete.what3', 'delete.what4'] as const).map((k) => <Body key={k}>{`• ${t(k)}`}</Body>)}
            </View>
            <Heading>{t('delete.now')}</Heading>
            <QueryView query={preview} skeleton={<LoadingList rows={2} height={32} />}>
              {(items) =>
                items.length ? (
                  <View style={accountStyles.list} testID="consequences">
                    {items.map((c) => <Body key={c.code}>{`• ${consequence(c, f)}`}</Body>)}
                  </View>
                ) : (
                  <Body tone="muted">{t('delete.nothing')}</Body>
                )
              }
            </QueryView>
            <Checkbox checked={agreed} onChange={(v) => { setAgreed(v); setTried(false); }} label={t('delete.agree')} error={tried && !agreed ? t('delete.agreeRequired') : null} testID="delete-agree">
              <Body>{t('delete.agree')}</Body>
            </Checkbox>
            <Button label={t('delete.confirm')} tone="danger" large busy={busy} onPress={confirm} testID="delete-confirm" />
            <Body tone="small">{t('delete.email')}</Body>
          </>
        )}
      </View>
    </Screen>
  );
}

function consequence(c: ErasureConsequence, f: ReturnType<typeof useShopFormat>): string {
  const { t } = f;
  switch (c.code) {
    case 'open_orders': return t('delete.c_open_orders', { count: c.count });
    case 'upcoming_bookings': return t('delete.c_upcoming_bookings', { count: c.count });
    case 'held_payments': return t('delete.c_held_payments', { amount: f.money(c.amountCents) });
    case 'open_refunds': return t('delete.c_open_refunds', { count: c.count });
    case 'open_disputes': return t('delete.c_open_disputes', { count: c.count });
    case 'points': return t('delete.c_points', { count: c.count });
    case 'plus': return t('delete.c_plus');
    case 'sole_owner': return t('delete.c_sole_owner', { count: c.count });
    default: return c.code;
  }
}
