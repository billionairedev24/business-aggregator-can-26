import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { router } from 'expo-router';
import { useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { ApiError, colors, randomId, space } from '@northline/mobile-kit';

import { PromoPoints } from '../aftercare/PromoPoints';
import { aftercare } from '../aftercare/Review';
import type { Booking, BookingRequest, Checkout, ProviderPage, SavedCard } from '../api/services';
import { useAuth } from '../auth/AuthProvider';
import { config } from '../config';
import { useI18n } from '../i18n';
import { useDeliveryLocation, type DeliveryLocation } from '../location/DeliveryLocation';
import { Body, Button, Checkbox, Field, Notice, Option, Title, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, errorMessage, QueryView, SignInPrompt, Skeleton } from '../ui/states';
import { clearDraft, parseVehicle, useDraft, type BookingDraft } from './draft';
import { money } from './format';
import { Line, Panel, useServicesApi, WizardSteps } from './parts';
import { bookingPaymentsFor, type PayWith } from './payments';
import { stepUpProof } from './stepUp';

type Phase = 'form' | 'paying' | 'stepUp' | 'enrol';
const SPOT_VALUES = { vehicle: ['Driveway', 'Street parking', 'Underground parkade', 'Workplace lot'], home: ['Front door', 'Side or back door', 'Concierge / buzzer', 'Lockbox'] };

/**
 * The job site on the map (mobile gaps part 2, for the live ETA): the saved delivery address's point when the booking's
 * address is that one; nothing otherwise (no ETA minutes then — the address isn't geocoded here).
 */
export function siteOf(d: Pick<BookingDraft, 'address'>, location: DeliveryLocation): { siteLat: number; siteLng: number } | undefined {
  const saved = location.street ?? location.label;
  if (location.status !== 'saved' || location.lat == null || location.lng == null || !saved || !d.address.trim().startsWith(saved)) return undefined;
  return { siteLat: location.lat, siteLng: location.lng };
}

/** The promo code and points the customer chose on the review step. */
export interface Discounts { promoCode?: string; usePoints?: boolean; site?: { siteLat: number; siteLng: number } }

/** The checkout request from the wizard's answers (the api's BookingRequest). */
export function bookingRequest(p: ProviderPage, d: BookingDraft, agree: { policies: boolean; terms: boolean }, area?: string, extra: Discounts = {}): BookingRequest {
  const v = parseVehicle(d.vehicle);
  const service = p.services.find((s) => s.id === d.serviceId);
  const where = service?.kind === 'appointment' ? {} : {
    addressLine: d.address.trim(),
    spot: d.spot === undefined ? undefined : (p.vehicle ? SPOT_VALUES.vehicle : SPOT_VALUES.home)[d.spot],
    accessNote: d.access.trim(),
    ...(area ? { area } : {}),
  };
  return {
    holdId: d.hold!.holdId,
    serviceId: d.serviceId!,
    description: d.note.trim(),
    ...(p.vehicle && v ? { vehicle: v } : {}),
    ...where,
    agreePolicies: agree.policies,
    agreeTerms: agree.terms,
    ...(extra.promoCode ? { promoCode: extra.promoCode } : {}),
    ...(extra.usePoints ? { usePoints: true } : {}),
    ...(service?.kind === 'appointment' ? {} : (extra.site ?? {})),
  };
}

/**
 * C6 Review & escrow (design 01 `book_review`): the price, the time in the business's zone, tax, the total held in
 * escrow and how escrow works; the card (a saved one, or a new one in Stripe's own sheet — the app never sees card
 * numbers); a promo code and points (mobile gaps part 2: `POST /me/bookings/price` — tax on the price after the code,
 * points pay like money); the cancellation policy and terms. "Hold $… in escrow" prices it on the api (`Idempotency-Key`, S-51
 * step-up with the authenticator app when the sign-in had no second factor), authorizes the card and confirms the
 * booking.
 */
export function BookReview({ slug }: { slug: string }) {
  const { t, locale } = useI18n();
  const { status } = useAuth();
  const api = useServicesApi();
  const lang = locale === 'fr-CA' ? 'fr' : 'en';
  const [draft] = useDraft(slug);
  const page = useQuery({ queryKey: ['services', 'provider', slug, lang], queryFn: () => api.provider(slug, lang), staleTime: 60_000 });
  return (
    <Screen title={t('title.book_review')} testID="book-review">
      <WizardSteps at={3} />
      {status !== 'signedIn' ? (
        <SignInPrompt message={t('services.book.signInToPay')} />
      ) : !draft.hold || !draft.serviceId ? (
        <EmptyState message={t('services.book.noHold')} action={t('services.book.pickTimeAgain')} onAction={() => router.replace(`/book/${slug}/time`)} />
      ) : (
        <QueryView query={page} skeleton={<ReviewSkeleton />}>
          {(p) => <Pay p={p} draft={draft} />}
        </QueryView>
      )}
    </Screen>
  );
}

function Pay({ p, draft }: { p: ProviderPage; draft: BookingDraft }) {
  const { t, locale, time, day } = useI18n();
  const api = useServicesApi();
  const qc = useQueryClient();
  const service = p.services.find((s) => s.id === draft.serviceId)!;
  const hold = draft.hold!;
  const cards = useQuery({ queryKey: ['services', 'payment-methods'], queryFn: () => api.paymentMethods(), staleTime: 60_000 });
  const [phase, setPhase] = useState<Phase>('form');
  const [agree, setAgree] = useState({ policies: false, terms: false });
  const [errors, setErrors] = useState<{ policies?: string; terms?: string; code?: string }>({});
  const [failure, setFailure] = useState<{ message: string; expired?: boolean } | null>(null);
  const [code, setCode] = useState('');
  const [method, setMethod] = useState<string | null>(null);
  const [checkout, setCheckout] = useState<Checkout | null>(null);
  const keys = useRef(new Map<string, string>());
  const confirmKey = useRef(randomId());
  const proof = useRef<string | undefined>(undefined);
  const [promoCode, setPromoCode] = useState<string>();
  const [usePoints, setUsePoints] = useState(false);
  const { location } = useDeliveryLocation();
  const ask = { holdId: hold.holdId, serviceId: service.id, ...(promoCode ? { promoCode } : {}), usePoints };
  const price = useQuery({
    queryKey: ['aftercare', 'booking-price', ask],
    queryFn: () => aftercare().bookingPrice(ask),
    placeholderData: keepPreviousData,
    retry: false,
    staleTime: 30_000,
  });
  const priced = price.data ?? undefined;
  const promoError = price.error instanceof ApiError ? (price.error.fieldMessage('promoCode') ?? null) : null;

  const saved: SavedCard[] = cards.data?.items ?? [];
  const chosenCard = method ?? saved.find((c) => c.isDefault)?.id ?? saved[0]?.id ?? 'new';
  const subtotal = checkout?.priceCents ?? priced?.priceCents ?? service.priceCents ?? 0;
  const discount = checkout?.discountCents ?? priced?.discountCents ?? 0;
  const pointsCents = checkout?.pointsCents ?? priced?.pointsCents ?? 0;
  const tax = checkout?.taxCents ?? priced?.taxCents ?? Math.round(((subtotal - discount) * p.taxBps) / 10_000);
  const total = checkout?.totalCents ?? priced?.totalCents ?? subtotal - discount + tax - pointsCents;
  const when = `${day(hold.startsAt, p.timeZone)} · ${time(hold.startsAt, p.timeZone)}`;
  const cancelBy = new Date(Date.parse(hold.startsAt) - 12 * 3_600_000).toISOString();
  const provider = cards.data?.provider ?? 'fake';

  const done = (b: Booking) => {
    qc.setQueryData(['services', 'booking', b.bookingId], b);
    void qc.invalidateQueries({ queryKey: ['services', 'slots', p.slug] });
    clearDraft(p.slug);
    router.replace(`/bookings/${b.bookingId}/booked`);
  };

  const fail = (e: unknown) => {
    setPhase('form');
    if (e instanceof ApiError) {
      if (e.status === 403 && e.code === 'step_up_required') return setPhase('stepUp');
      if (e.status === 403 && e.code === 'second_factor_required') return setPhase('enrol');
      if (e.status === 409 && (e.code === 'hold_expired' || e.code === 'slot_taken')) return setFailure({ message: e.message, expired: true });
      if (e.status === 422) {
        const fields = { policies: e.fieldMessage('agreePolicies'), terms: e.fieldMessage('agreeTerms') };
        setErrors(fields);
        const other = e.errors.find((x) => x.field !== 'agreePolicies' && x.field !== 'agreeTerms');
        if (other) setFailure({ message: t('services.book.fixEarlier', { message: other.message }) });
        return;
      }
    }
    setFailure({ message: errorMessage(e, t) });
  };

  const pay = async () => {
    setFailure(null);
    const found = {
      ...(agree.policies ? {} : { policies: t('services.err.policies') }),
      ...(agree.terms ? {} : { terms: t('services.err.terms') }),
    };
    setErrors(found);
    if (Object.keys(found).length) return;
    setPhase('paying');
    try {
      const body = bookingRequest(p, draft, agree, undefined, { promoCode: promoError ? undefined : promoCode, usePoints, site: siteOf(draft, location) });
      const json = JSON.stringify(body);
      if (!keys.current.has(json)) keys.current.set(json, randomId());
      const started = await api.checkout(body, keys.current.get(json)!, proof.current);
      setCheckout(started);
      if (started.status === 'confirmed' && started.booking) return done(started.booking);
      if (started.status !== 'authorized') {
        const port = bookingPaymentsFor(started.provider);
        if (!port) throw new Error('No card payment for this provider.');
        const how: PayWith = chosenCard === 'new' ? { kind: 'new' } : { kind: 'saved', paymentMethodId: chosenCard };
        const paid = await port.pay(started, how);
        if (paid.status === 'cancelled') return setPhase('form');
        if (paid.status === 'failed') {
          setPhase('form');
          return setFailure({ message: paid.message ?? t('services.book.cardFailed') });
        }
      }
      const booked = await api.confirm(hold.holdId, confirmKey.current);
      done(booked);
    } catch (e) {
      fail(e);
    }
  };

  const confirmIdentity = async () => {
    if (!/^\d{6}$/.test(code.trim())) return setErrors({ code: t('services.err.code') });
    setErrors({});
    try {
      proof.current = await stepUpProof(code);
      setCode('');
      setPhase('form');
      await pay();
    } catch (e) {
      if (e instanceof ApiError && (e.status === 401 || e.status === 403)) return setFailure({ message: t('services.stepUp.elsewhere') });
      if (e instanceof ApiError && e.status === 422) return setErrors({ code: e.fieldMessage('code') ?? t('services.err.code') });
      setFailure({ message: errorMessage(e, t) });
    }
  };

  if (phase === 'stepUp') {
    return (
      <View style={styles.form} testID="step-up">
        <Title>{t('services.stepUp.title')}</Title>
        <Body tone="muted">{t('services.stepUp.body')}</Body>
        <Field label={t('services.stepUp.code')} value={code} onChangeText={setCode} error={errors.code} keyboardType="number-pad" textContentType="oneTimeCode" maxLength={6} testID="field-step-up" />
        {failure ? <Notice message={failure.message} /> : null}
        <Button label={t('services.stepUp.confirm', { total: money(total, locale) })} large onPress={() => void confirmIdentity()} testID="step-up-confirm" />
        <Button label={t('services.stepUp.cancel')} tone="ghost" onPress={() => setPhase('form')} />
      </View>
    );
  }
  if (phase === 'enrol') {
    return (
      <View style={styles.form} testID="enrol">
        <Title>{t('services.enrol.title')}</Title>
        <Body tone="muted">{t('services.enrol.body')}</Body>
        <Button label={t('services.enrol.open')} hint={t('common.opensInBrowser')} large onPress={() => void Linking.openURL(`${config.siteOrigin}/account?tab=security`)} />
        <Button label={t('services.stepUp.cancel')} tone="ghost" onPress={() => setPhase('form')} />
      </View>
    );
  }

  return (
    <View style={styles.form}>
      <Title>{t('services.book.reviewTitle')}</Title>
      <View style={styles.lines}>
        <Line left={service.name} right={money(subtotal, locale)} />
        {discount > 0 ? <Line left={t('promo.sum.discount', { code: priced?.promoCode ?? promoCode ?? '' })} right={`−${money(discount, locale)}`} testID="review-discount" /> : null}
        <Line left={when} right={p.name} muted testID="review-when" />
        {service.kind === 'appointment' ? null : <Line left={t('services.book.travel')} right={t('services.book.included')} />}
        <Line left={t('services.book.tax', { pct: (p.taxBps / 100).toLocaleString(locale === 'fr-CA' ? 'fr-CA' : 'en-CA') })} right={money(tax, locale)} />
        {pointsCents > 0 ? <Line left={t('promo.sum.points')} right={`−${money(pointsCents, locale)}`} testID="review-points" /> : null}
        <Line left={t('services.book.held')} right={money(total, locale)} strong testID="review-total" />
      </View>
      <PromoPoints
        code={promoCode}
        onCode={setPromoCode}
        error={promoError}
        discountCents={priced?.discountCents}
        usePoints={usePoints}
        onUsePoints={setUsePoints}
        pointsAvailable={priced?.pointsAvailable}
        pointsCents={priced?.pointsCents}
        disabled={phase === 'paying'}
      />
      <Panel tone="accent">
        <Text style={[type.small, styles.escrow]}>
          <Text style={type.strong}>{t('services.book.escrowTitle')}</Text> {t('services.book.escrowBody', { name: p.name })}
        </Text>
      </Panel>
      {provider === 'fake' ? (
        <Body tone="small">{t('services.book.fakePayments')}</Body>
      ) : (
        <View style={styles.cards} accessibilityRole="radiogroup" accessibilityLabel={t('services.book.payWith')}>
          <Text style={type.small}>{t('services.book.payWith')}</Text>
          {saved.map((c) => (
            <Option key={c.id} selected={chosenCard === c.id} title={t('services.book.card', { brand: c.brand.charAt(0).toUpperCase() + c.brand.slice(1), last4: c.last4 })} description={t('services.book.expires', { mm: String(c.expMonth).padStart(2, '0'), yy: String(c.expYear).slice(-2) })} onPress={() => setMethod(c.id)} testID={`card-${c.id}`} />
          ))}
          <Option selected={chosenCard === 'new'} title={t('services.book.newCard')} description={t('services.book.newCardNote')} onPress={() => setMethod('new')} testID="card-new" />
        </View>
      )}
      <Body tone="small">{t('services.book.cancelUntil', { when: `${day(cancelBy, p.timeZone)} ${time(cancelBy, p.timeZone)}` })}</Body>
      <Checkbox checked={agree.policies} onChange={(v) => setAgree((a) => ({ ...a, policies: v }))} label={t('services.book.agreePolicies')} error={errors.policies} testID="agree-policies">
        <Body tone="small">{t('services.book.agreePolicies')}</Body>
      </Checkbox>
      <Checkbox checked={agree.terms} onChange={(v) => setAgree((a) => ({ ...a, terms: v }))} label={t('services.book.agreeTerms')} error={errors.terms} testID="agree-terms">
        <Body tone="small">{t('services.book.agreeTerms')}</Body>
      </Checkbox>
      {failure ? (
        <View style={styles.cards}>
          <Notice message={failure.message} testID="pay-error" />
          {failure.expired ? <Button label={t('services.book.pickTimeAgain')} tone="secondary" onPress={() => router.replace(`/book/${p.slug}/time`)} /> : null}
        </View>
      ) : null}
      <Button label={t('services.book.hold', { total: money(total, locale) })} large busy={phase === 'paying'} onPress={() => void pay()} testID="pay" />
    </View>
  );
}

function ReviewSkeleton() {
  const { t } = useI18n();
  return (
    <View accessibilityRole="progressbar" accessibilityLabel={t('common.loading')} style={styles.form} testID="loading">
      <Skeleton height={28} width="70%" />
      {[0, 1, 2, 3].map((i) => (
        <Skeleton key={i} height={20} />
      ))}
      <Skeleton height={80} />
    </View>
  );
}

const styles = StyleSheet.create({
  form: { gap: space[3], paddingTop: space[3] },
  lines: { gap: 10 },
  escrow: { color: colors.accent900 },
  cards: { gap: space[2] },
});
