import { useQuery, useQueryClient } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { router, useLocalSearchParams } from 'expo-router';
import { useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { ApiError, NetworkError, colors, fonts, radius, randomId, space } from '@northline/mobile-kit';

import type { Acceptance, BookingConfirmation, QuotePage, Visit } from '../api/account';
import type { Started } from '../api/shop';
import { useAuth } from '../auth/AuthProvider';
import { config } from '../config';
import type { MessageKey } from '../i18n';
import { useDeliveryLocation } from '../location/DeliveryLocation';
import { backToTab, problemCode, useShopFormat } from '../shop/common';
import { SumRow } from '../shop/parts';
import { cardPaymentsFor } from '../shop/payments';
import { StepUpFailed, stepUpWithCode } from '../shop/stepUp';
import { Body, Button, Field, Notice, Tag, Title, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { LoadingList, QueryView, SignInPrompt, errorMessage } from '../ui/states';
import { KEYS, account, decimal, percent, worded } from './common';
import { Heading } from './parts';

type Format = ReturnType<typeof useShopFormat>;
type Phase = 'view' | 'where' | 'stepUp' | 'enrol' | 'done';
const KNOWN = ['quote_revised', 'quote_expired', 'slot_taken', 'quote_no_time'];

/**
 * D2 Quote received (design 01 `quote`; signed in): one provider's itemized quote (`GET /me/quotes/{id}`; the
 * provider sees it was read) — the title, validity, the provider and its trust, the scope, every labour / part / fee
 * line, tax and the total held in escrow, what could change the price, deposit and timing, the warranty, the escrow
 * promise. "Accept · pay … to escrow": where the job is (the address goes to this provider only), then
 * `POST /me/quotes/{id}/accept` (Idempotency-Key kept for every retry; `X-Step-Up` when the api asks — the
 * authenticator code, as Journey B's payment), the card (Stripe's PaymentSheet through S-99's payments port, or the
 * api's stand-in) and `POST /me/quotes/{id}/accept/confirm` → the booking. "Decline" tells the provider. The design's
 * "Ask a question" needs consumer messaging the api doesn't have (MOBILE_PLAN § API gaps).
 */
export function Quote() {
  const f = useShopFormat();
  const { t, locale } = f;
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const { id = '' } = useLocalSearchParams<{ id: string }>();
  const quote = useQuery({ queryKey: KEYS.quote(id, locale), queryFn: () => account().quote(id, locale), enabled: signedIn && !!id, staleTime: 30_000 });
  const title = t('title.quote', { ref: quote.data?.quote.ref ?? '' }).trim();

  if (!signedIn) {
    return (
      <Screen title={t('screen.quote')} testID="quote">
        <SignInPrompt message={t('account.quote.signIn')} />
      </Screen>
    );
  }
  if (quote.data) return <QuoteView page={quote.data} f={f} title={title} />;
  return (
    <Screen title={t('screen.quote')} testID="quote">
      <QueryView query={quote} skeleton={<LoadingList rows={6} height={40} />}>
        {() => null}
      </QueryView>
    </Screen>
  );
}

function QuoteView({ page, f, title }: { page: QuotePage; f: Format; title: string }) {
  const { t, locale } = f;
  const qc = useQueryClient();
  const { location } = useDeliveryLocation();
  const { quote: q, provider: p } = page;
  const state = q.expired ? 'expired' : q.state;
  const open = !q.expired && (q.state === 'sent' || q.state === 'viewed');
  const held = q.depositCents > 0 ? q.depositCents : q.totalCents;
  const [phase, setPhase] = useState<Phase>('view');
  const [visit, setVisit] = useState<Visit>({ addressLine: location.street ? [location.street, location.city].filter(Boolean).join(', ') : (location.label ?? ''), unit: location.unit ?? '', accessNote: '', contactPhone: '' });
  const [addressError, setAddressError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState<string | null>(null);
  const [booking, setBooking] = useState<BookingConfirmation | null>(null);
  // one key per acceptance of this body, kept for every retry (MOBILE_PLAN § Data)
  const keys = useRef<{ body: string; key: string; confirm: string } | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: KEYS.all });

  const clean = (v: Visit): Visit => ({
    addressLine: v.addressLine?.trim() || undefined,
    unit: v.unit?.trim() || undefined,
    accessNote: v.accessNote?.trim() || undefined,
    contactPhone: v.contactPhone?.trim() || undefined,
  });

  const fail = (e: unknown) => {
    const c = problemCode(e);
    if (c === 'step_up_required') return setPhase('stepUp');
    if (c === 'second_factor_required') return setPhase('enrol');
    if (e instanceof ApiError && e.status === 422 && e.fieldMessage('addressLine')) setAddressError(e.fieldMessage('addressLine')!);
    setError(c && KNOWN.includes(c) ? t(`account.quote.err.${c}` as MessageKey) : e instanceof ApiError || e instanceof NetworkError ? errorMessage(e, t) : t('account.quote.err.generic'));
    if (c === 'quote_revised' || c === 'quote_expired') void refresh();
    // a refusal ends this attempt; no answer keeps the key so trying again can't hold twice
    if (e instanceof ApiError && !e.transient) keys.current = null;
  };

  const confirm = async (body: Visit, key: string) => {
    const b = await account().confirmQuote(q.id, body, key);
    setBooking(b);
    setPhase('done');
    void refresh();
  };

  const accept = async (proof?: string) => {
    setError(null);
    setAddressError(null);
    const body = clean(visit);
    if (!body.addressLine) return setAddressError(t('account.quote.v.address'));
    const text = JSON.stringify(body);
    if (!keys.current || keys.current.body !== text) keys.current = { body: text, key: randomId(), confirm: randomId() };
    const k = keys.current;
    setBusy(true);
    try {
      const a: Acceptance = await account().acceptQuote(q.id, body, k.key, proof);
      if (a.status === 'requires_action' || a.status === 'requires_payment_method') {
        const payments = cardPaymentsFor(a.provider);
        if (!payments || !a.clientSecret || !a.paymentIntent) {
          setError(t('account.quote.err.generic'));
          return;
        }
        const started: Started = {
          checkoutId: q.id,
          orderId: a.bookingId,
          ref: q.ref,
          totalCents: a.totalCents,
          expiresAt: '',
          payment: { provider: a.provider, publishableKey: a.publishableKey ?? null },
          intents: [{ paymentIntent: a.paymentIntent, clientSecret: a.clientSecret, status: a.status, amountCents: a.totalCents }],
        };
        const cards = await account().cards().catch(() => null);
        const saved = cards?.items.find((c) => c.isDefault) ?? cards?.items[0];
        const r = await payments.pay(started, saved ? { kind: 'saved', paymentMethodId: saved.id } : { kind: 'new' }).catch(() => ({ status: 'failed' as const, message: undefined }));
        if (r.status !== 'paid') {
          setError(r.status === 'cancelled' ? t('shop.pay.err.cancelled') : r.message ? t('account.quote.err.card', { message: r.message }) : t('account.quote.err.generic'));
          return;
        }
      }
      await confirm(body, k.confirm);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const decline = async () => {
    setError(null);
    setBusy(true);
    try {
      await account().declineQuote(q.id);
      await refresh();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const confirmCode = async () => {
    setCodeError(null);
    if (!/^\d{6}$/.test(code.trim())) return setCodeError(t('shop.stepUp.wrong'));
    setBusy(true);
    try {
      const proof = await stepUpWithCode(code);
      setCode('');
      setPhase('where');
      await accept(proof);
    } catch (e) {
      setBusy(false);
      setCodeError(e instanceof StepUpFailed ? t(`shop.stepUp.${e.reason}` as MessageKey) : errorMessage(e, t));
    }
  };

  if (phase === 'stepUp') {
    return (
      <Screen
        title={title}
        testID="quote-step-up"
        footer={
          <>
            <Button label={t('shop.stepUp.confirm')} large busy={busy} onPress={() => void confirmCode()} testID="step-up-confirm" />
            <Button label={t('shop.stepUp.cancel')} tone="ghost" onPress={() => setPhase('where')} />
          </>
        }
      >
        <Title>{t('shop.stepUp.title')}</Title>
        <Body tone="muted">{t('account.quote.stepUpBody')}</Body>
        <Field label={t('shop.stepUp.code')} value={code} onChangeText={setCode} keyboardType="number-pad" textContentType="oneTimeCode" autoComplete="one-time-code" maxLength={6} error={codeError} testID="step-up-code" />
      </Screen>
    );
  }
  if (phase === 'enrol') {
    return (
      <Screen
        title={title}
        testID="quote-enrol"
        footer={
          <>
            <Button label={t('shop.enrol.open')} hint={t('common.opensInBrowser')} onPress={() => void Linking.openURL(`${config.siteOrigin}/account?tab=security`)} />
            <Button label={t('shop.stepUp.cancel')} tone="ghost" onPress={() => setPhase('where')} />
          </>
        }
      >
        <Title>{t('shop.enrol.title')}</Title>
        <Body tone="muted">{t('shop.enrol.body')}</Body>
      </Screen>
    );
  }
  if (phase === 'where') {
    return (
      <Screen
        title={title}
        testID="quote-where"
        footer={
          <>
            {error ? <Notice message={error} testID="quote-error" /> : null}
            <Button label={t('account.quote.accept', { amount: f.money(held) })} large busy={busy} onPress={() => void accept()} testID="quote-accept-confirm" />
            <Button label={t('account.quote.back')} tone="ghost" disabled={busy} onPress={() => setPhase('view')} />
          </>
        }
      >
        <Title>{t('account.quote.whereTitle')}</Title>
        <Body tone="muted">{t('account.quote.whereBody', { name: p.name })}</Body>
        <Field label={t('account.quote.address')} value={visit.addressLine} onChangeText={(v) => setVisit({ ...visit, addressLine: v })} autoComplete="street-address" maxLength={200} error={addressError} testID="visit-address" />
        <Field label={t('account.quote.unit')} value={visit.unit} onChangeText={(v) => setVisit({ ...visit, unit: v })} maxLength={40} testID="visit-unit" />
        <Field label={t('account.quote.access')} hint={t('account.quote.accessPrivate')} value={visit.accessNote} onChangeText={(v) => setVisit({ ...visit, accessNote: v })} multiline maxLength={500} testID="visit-access" />
        <Body tone="small">{t('account.quote.accessPrivate')}</Body>
        <Field label={t('account.quote.phone')} value={visit.contactPhone} onChangeText={(v) => setVisit({ ...visit, contactPhone: v })} keyboardType="phone-pad" autoComplete="tel" maxLength={20} testID="visit-phone" />
      </Screen>
    );
  }

  const tone = state === 'accepted' ? 'accent' : state === 'sent' || state === 'viewed' ? 'accent2' : 'neutral';
  const validity = q.validUntil ? (q.expired ? t('account.quote.expiredOn', { date: f.date(q.validUntil) }) : validFor(q.validUntil, f)) : worded(t, 'account.quote.state', state);
  const meta = [
    t('account.quote.from', { ref: q.ref, name: p.name }),
    f.tier(p.tier),
    p.reviewCount > 0 ? `★ ${decimal(p.rating, locale)}` : null,
    ...p.verifiedFacts.slice(0, 1),
    q.proposedAt ? `${f.date(q.proposedAt)}, ${f.time(q.proposedAt)}` : null,
  ]
    .filter(Boolean)
    .join(' · ');
  const deposit =
    q.depositKind === 'pct' && q.depositBps
      ? t('account.quote.deposit.pct', { pct: percent(q.depositBps, locale), amount: f.money(q.depositCents) })
      : q.depositKind === 'parts_upfront' && q.depositCents > 0
        ? t('account.quote.deposit.parts', { amount: f.money(q.depositCents) })
        : t('account.quote.deposit.none');

  const actions = booking ? (
    <>
      <Notice tone="info" message={t('account.quote.accepted', { amount: f.money(booking.heldCents), ref: booking.ref, name: p.name })} testID="quote-accepted" />
      <Button label={t('account.quote.seeBookings')} large onPress={() => backToTab('/orders')} testID="quote-bookings" />
    </>
  ) : state === 'accepted' ? (
    <>
      <Notice tone="info" message={t('account.quote.acceptedShort')} />
      <Button label={t('account.quote.seeBookings')} large onPress={() => backToTab('/orders')} />
    </>
  ) : !open ? (
    <Notice tone="info" message={q.state === 'declined' ? t('account.quote.declined', { name: p.name }) : q.state === 'superseded' ? t('account.quote.revised') : t('account.quote.expired', { name: p.name })} testID="quote-closed" />
  ) : (
    <>
      {error ? <Notice message={error} testID="quote-error" /> : null}
      <Button label={t('account.quote.accept', { amount: f.money(held) })} large onPress={() => setPhase('where')} testID="quote-accept" />
      <Button label={t('account.quote.decline')} tone="ghost" busy={busy} onPress={() => void decline()} testID="quote-decline" />
    </>
  );

  return (
    <Screen title={title} testID="quote" footer={actions}>
      <View style={styles.titleRow}>
        <Text accessibilityRole="header" style={styles.h2}>
          {page.title}
        </Text>
        <Tag label={open ? validity : worded(t, 'account.quote.state', state)} tone={tone} />
      </View>
      <Text style={type.small}>{meta}</Text>
      {q.state === 'superseded' ? (
        <Button label={t('account.quote.seeNewest')} tone="ghost" onPress={() => router.replace(`/quotes/${encodeURIComponent(q.currentQuoteId)}` as never)} testID="quote-newest" />
      ) : null}

      <Text style={styles.kicker}>{t('account.quote.scope')}</Text>
      <Text style={type.body}>{q.scope}</Text>

      <Text style={styles.kicker}>{t('account.quote.breakdown')}</Text>
      <View accessibilityRole="list">
        {q.lines.map((l, i) => (
          <View key={i} style={styles.line}>
            <View style={styles.flex}>
              <Text style={type.body}>{l.qty !== 1 ? `${l.description} × ${l.qty.toLocaleString(locale === 'fr-CA' ? 'fr-CA' : 'en-CA')}` : l.description}</Text>
              <Text style={type.small}>{[worded(t, 'account.quote.kind', l.kind), l.note].filter(Boolean).join(' · ')}</Text>
            </View>
            <Text style={[type.body, type.strong]}>{f.money(l.amountCents)}</Text>
          </View>
        ))}
      </View>
      <SumRow label={t('account.quote.tax', { pct: percent(q.taxBps, locale) })} value={f.money(q.taxCents)} />
      <SumRow label={t('account.quote.total')} value={f.money(q.totalCents)} strong />

      {q.exclusions ? (
        <>
          <Text style={styles.kicker}>{t('account.quote.change')}</Text>
          <Text style={type.small}>{q.exclusions}</Text>
        </>
      ) : null}

      <View style={styles.terms}>
        <View style={styles.term}>
          <Text style={[type.small, type.strong]}>{t('account.quote.depositTitle')}</Text>
          <Text style={type.small}>{deposit}</Text>
        </View>
        <View style={styles.term}>
          <Text style={[type.small, type.strong]}>{t('account.quote.warrantyTitle')}</Text>
          <Text style={type.small}>{worded(t, 'account.quote.warranty', q.warranty)}</Text>
        </View>
        <View style={styles.term}>
          <Text style={[type.small, type.strong]}>{t('account.quote.when')}</Text>
          <Text style={type.small}>{q.proposedAt ? `${f.date(q.proposedAt)} ${f.time(q.proposedAt)}` : t('account.quote.noTime')}</Text>
        </View>
      </View>

      <View style={styles.protected}>
        <Text style={[type.small, styles.protectedText]}>
          <Text style={type.strong}>{t('account.quote.protected')}</Text> {t('account.quote.protectedBody')}
        </Text>
      </View>

      {page.others.length > 0 ? (
        <>
          <Heading>{t('account.quote.others', { n: page.others.length })}</Heading>
          {page.others.map((o) => (
            <Button key={o.quoteId} label={`${o.providerName} · ${f.money(o.totalCents)}`} tone="ghost" onPress={() => router.push(`/quotes/${encodeURIComponent(o.quoteId)}` as never)} />
          ))}
        </>
      ) : null}
    </Screen>
  );
}

/** "Valid 71 h" / "Valid 3 d" — how long the quote stays open. */
function validFor(until: string, f: Format, now = Date.now()): string {
  const h = Math.max(0, Math.floor((Date.parse(until) - now) / 3_600_000));
  return h >= 96 ? f.t('account.quote.validDays', { n: Math.floor(h / 24) }) : f.t('account.quote.validHours', { n: h });
}

const styles = StyleSheet.create({
  flex: { flex: 1, minWidth: 0 },
  titleRow: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'baseline', gap: space[3] },
  h2: { flex: 1, minWidth: 180, fontFamily: fonts.heading, fontSize: 24, lineHeight: 28, letterSpacing: -0.4, color: colors.text },
  kicker: { ...StyleSheet.flatten(type.kicker), fontSize: 11, marginTop: space[3] },
  line: { flexDirection: 'row', justifyContent: 'space-between', gap: space[3], paddingVertical: 9 },
  terms: { flexDirection: 'row', flexWrap: 'wrap', gap: space[4], marginTop: space[3] },
  term: { minWidth: 96, flexShrink: 1, gap: 2 },
  protected: { marginTop: space[3], padding: 14, borderRadius: radius.md, backgroundColor: colors.accent100 },
  protectedText: { color: colors.accent900, lineHeight: 19 },
});
