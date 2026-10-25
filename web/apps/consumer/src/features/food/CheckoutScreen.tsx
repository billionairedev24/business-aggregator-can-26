import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { newIdempotencyKey, ValidationError } from '@northline/client';
import { Alert, Button, Chip, EmptyState, Field, OptionCard, Price, RadioGroup, Segmented, Select, Skeleton, SiteLink, TextInput, useFormatters } from '@northline/ui';
import { Section } from '../checkout/Section';
import { StripePayment } from '../cart/Payment';
import { CheckoutConsent, useCheckoutConsent } from '../consent/CheckoutConsent';
import { AgeCheck } from '../cart/AgeCheck';
import { ageCleared, NO_AGE } from '../cart/age';
import { PromoPoints } from '../promotions/PromoPoints';
import { usePromoT } from '../promotions/messages';
import { StepUpDialog } from '../cart/StepUpDialog';
import type { Started as CartStarted } from '../cart/api';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import { signInHref, useViewer } from '../session/api';
import { confirmFood, problemCode, quoteFood, restaurantQuery, startFood, type FoodStarted, type OrderBody, type Totals } from './api';
import { useFoodCart } from './foodCart';
import { useFoodT } from './messages';

type T = ReturnType<typeof useFoodT>;
type When = 'asap' | 'schedule' | 'pickup';
type Tip = { kind: 'none' | 'amount' | 'percent'; value: number };
const TIPS: readonly Tip[] = [{ kind: 'none', value: 0 }, { kind: 'amount', value: 200 }, { kind: 'amount', value: 400 }, { kind: 'percent', value: 15 }, { kind: 'amount', value: 600 }];
const DROPOFFS = ['hand', 'door', 'lobby'] as const;
const EXTRAS = ['utensils', 'contactless', 'ring'] as const;
type Phase = { step: 'form' } | { step: 'stepUp'; mode: 'required' | 'enrol' } | { step: 'pay'; started: FoodStarted };

/**
 * Food checkout (design 06 `foodCheckout`, S-57): when (as soon as possible · a scheduled window · pickup), where to
 * (the address saved on the Location screen), drop-off, extras, the courier's tip, then Pay: the api opens one card
 * payment held in escrow (released when the kitchen hands the order off), after S-51's step-up rule for phone-code
 * sign-ins; Stripe's Payment Element confirms it (the local fake shows the bank step), and the order goes to the
 * kitchen. Signed in only — guests see the guest banner and "Sign in to pay".
 */
export function CheckoutScreen() {
  const t = useFoodT();
  // tip amounts are choices in whole dollars (the checkout's); every price uses `price`
  const { money, price, date } = useFormatters();
  const navigate = useNavigate();
  const { user, loading } = useViewer();
  const { location } = useDeliveryLocation();
  const { cart, clear } = useFoodCart();
  const kitchen = useQuery({ ...restaurantQuery(cart?.slug ?? ''), enabled: !!cart?.slug });
  const [when, setWhen] = useState<When>('asap');
  const [slot, setSlot] = useState('');
  const [dropoff, setDropoff] = useState<(typeof DROPOFFS)[number]>('hand');
  const [note, setNote] = useState('');
  const [extras, setExtras] = useState<Set<string>>(new Set());
  const [tip, setTip] = useState(2);
  const [promoCode, setPromoCode] = useState<string>();
  const [usePoints, setUsePoints] = useState(false);
  const tp = usePromoT();
  const [phase, setPhase] = useState<Phase>({ step: 'form' });
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const keys = useRef({ start: newIdempotencyKey(), confirm: newIdempotencyKey() });
  const consent = useCheckoutConsent(!!user); // S-157: the optional offers box (CASL)

  const data = kitchen.data;
  const pickup = when === 'pickup';
  const hasAddress = location.status === 'saved' && !!location.street && location.lat != null && location.lng != null;
  const slots = data?.slots ?? [];
  useEffect(() => { if (when === 'schedule' && !slot && slots[0]) setSlot(slots[0]); }, [when, slot, slots]);

  const body: OrderBody | null = useMemo(() => {
    if (!cart || !data) return null;
    return {
      merchantId: cart.merchantId,
      mode: pickup ? 'pickup' : 'delivery',
      scheduledFor: when === 'schedule' && slot ? slot : null,
      items: cart.lines.filter(l => l.kind === 'item').map(l => ({ itemId: l.itemId!, qty: l.qty, optionIds: l.optionIds, note: l.note ?? null })),
      combos: cart.lines.filter(l => l.kind === 'combo').map(l => ({ comboId: l.comboId!, qty: l.qty, itemIds: l.itemIds })),
      tip: pickup ? { kind: 'none', value: 0 } : TIPS[tip]!,
      delivery: pickup || !hasAddress ? null : {
        street: location.street!, ...(location.unit ? { unit: location.unit } : {}), ...(location.city ? { city: location.city } : {}),
        province: location.province ?? data.province ?? '', ...(location.postalCode ? { postalCode: location.postalCode } : {}),
        lat: location.lat!, lng: location.lng!, ...(location.zoneId ? { zoneId: location.zoneId } : {}), ...(location.zone ? { zone: location.zone } : {}),
        dropoff, ...(note.trim() ? { note: note.trim() } : {}), extras: [...extras],
      },
      ...(promoCode ? { promoCode } : {}),
      ...(usePoints ? { usePoints } : {}),
    };
  }, [cart, data, pickup, when, slot, tip, hasAddress, location, dropoff, note, extras, promoCode, usePoints]);

  const quoteKey = JSON.stringify(body);
  const quote = useQuery({
    queryKey: ['me', 'food-quote', quoteKey],
    queryFn: () => quoteFood(body!),
    enabled: !!user && !!body && (pickup || hasAddress) && !(when === 'schedule' && !slot),
    retry: false,
    staleTime: 30_000,
  });

  if (loading) return <div className="nl-page nl-skel-stack"><Skeleton width={320} height={36} /><Skeleton height={200} /></div>;
  if (!cart || cart.lines.length === 0) {
    return (
      <div className="nl-page nl-fco-empty">
        <h1 className="nl-page-title">{t('emptyCheckoutTitle')}</h1>
        <EmptyState action={<Link to="/food" className="btn btn-primary">{t('browseFood')}</Link>}>{t('emptyCheckout')}</EmptyState>
      </div>
    );
  }
  if (!user) {
    return (
      <div className="nl-fco">
        <h1>{t('checkoutTitle', { name: cart.name })}</h1>
        <SiteLink href={signInHref('/food/checkout')} className="btn btn-primary">{t('signInToPay')}</SiteLink>
      </div>
    );
  }

  const failed = (e: unknown) => {
    const code = problemCode(e);
    if (code === 'step_up_required' || code === 'second_factor_required') { setPhase({ step: 'stepUp', mode: code === 'step_up_required' ? 'required' : 'enrol' }); return; }
    if (code === 'kitchen_closed') { setError(t('err_closed')); return; }
    if (code === 'age_verification_required' || code === 'age_under_minimum' || code === 'restricted_hours') { void quote.refetch(); return; }
    if (e instanceof ValidationError) { setError(e.errors.map(x => x.message).join(' ')); return; }
    setError(e instanceof Error && code ? e.message : t('err_generic'));
  };

  const pay = async (proof?: string) => {
    if (!body) return;
    setBusy(true); setError(undefined);
    try {
      const started = await startFood(body, keys.current.start, proof);
      keys.current.start = newIdempotencyKey();
      setPhase({ step: 'pay', started });
    } catch (e) { failed(e); } finally { setBusy(false); }
  };

  const place = async (started: FoodStarted) => {
    setBusy(true); setError(undefined);
    try {
      const placed = await confirmFood(started.orderId, keys.current.confirm);
      await consent.record(); // S-157: the ticked offers box, once the order went through
      clear();
      void navigate({ to: '/food/orders/$orderId', params: { orderId: placed.orderId } });
    } catch (e) { failed(e); } finally { setBusy(false); }
  };

  const totals = phase.step === 'pay' ? phase.started.totals : quote.data;
  const age = quote.data?.age ?? NO_AGE;
  const payable = !!body && (pickup || hasAddress) && !(when === 'schedule' && !slot) && !!quote.data && ageCleared(age);

  const tipKeys = TIPS.map((_, i) => String(i));
  return (
    <div className="nl-page co-page nl-fco">
      <div className="co-layout">
        <div className="co-head"><h1 className="co-title">{t('checkoutTitle', { name: cart.name })}</h1></div>
        <div className="co-main">
          <Section n={1} id="fco-when" title={t('when')}>
            <RadioGroup className="co-choices nl-fco-when" aria-labelledby="fco-when">
              {data?.kitchen.open !== false && <OptionCard mark="radio" selected={when === 'asap'} role="radio" title={t('asap')} description={data ? t('etaRange', { from: data.kitchen.etaFromMin, to: data.kitchen.etaToMin }) : ''} onClick={() => setWhen('asap')} />}
              {data && data.slots.length > 0 && <OptionCard mark="radio" selected={when === 'schedule'} role="radio" title={t('scheduleOpt')} description={t('scheduleDesc')} onClick={() => setWhen('schedule')} />}
              {data?.kitchen.fulfilment.includes('pickup') && <OptionCard mark="radio" selected={pickup} role="radio" title={t('pickupOpt')} description={t('pickupDesc', { from: data.kitchen.pickupFromMin, to: data.kitchen.pickupToMin })} onClick={() => setWhen('pickup')} />}
            </RadioGroup>
            {when === 'schedule' && (
              <Field label={t('window')} className="nl-fco-window">
                <Select value={slot} onChange={e => setSlot(e.target.value)} options={slots.map(s => ({ value: s, label: `${date(s, 'long')} · ${date(s, 'time')}–${date(new Date(new Date(s).getTime() + 30 * 60_000), 'time')}` }))} />
              </Field>
            )}
          </Section>

          {!pickup && (
            <Section n={2} id="fco-where" title={t('deliverTo')}
              action={hasAddress ? <Link to="/location" search={{ next: '/food/checkout' }} className="btn btn-link co-change">{t('change')}</Link> : null}>
              {hasAddress ? (
                <RadioGroup className="co-choices" aria-labelledby="fco-where">
                  <OptionCard mark="radio" role="radio" selected title={`${location.street}${location.unit ? `, ${location.unit}` : ''}`} description={[location.city, location.postalCode].filter(Boolean).join(' · ')} />
                </RadioGroup>
              ) : (
                <Alert tone="info" role="status" actions={<Link to="/location" search={{ next: '/food/checkout' }} className="btn btn-secondary">{t('addAddress')}</Link>}>{t('noAddress')}</Alert>
              )}
              <div className="nl-fco-group">
                <span className="nl-fco-label" id="fco-dropoff">{t('dropoff')}</span>
                <Segmented name="fco-dropoff" stack aria-label={t('dropoff')} value={dropoff} onChange={setDropoff} options={DROPOFFS.map(d => ({ value: d, label: t(d) }))} />
              </div>
              <Field label={t('courierNote')}><TextInput value={note} onChange={e => setNote(e.target.value)} placeholder={t('courierNotePlaceholder')} maxLength={140} /></Field>
              <div className="nl-fco-group" role="group" aria-labelledby="fco-extras">
                <span className="nl-fco-label" id="fco-extras">{t('extras')}</span>
                <div className="nl-chips">
                  {EXTRAS.map(x => <Chip key={x} selected={extras.has(x)} onClick={() => setExtras(s => { const n = new Set(s); if (n.has(x)) n.delete(x); else n.add(x); return n; })}>{t(x)}</Chip>)}
                </div>
              </div>
            </Section>
          )}

          {!pickup && (
            <Section n={3} id="fco-tip" title={t('tipTitle')} className="co-tip-seg-host">
              <Segmented name="fco-tip" aria-label={t('tipTitle')} value={String(tip)} onChange={v => setTip(Number(v))}
                options={tipKeys.map((k, i) => { const x = TIPS[i]!; return { value: k, label: x.kind === 'none' ? t('noTip') : x.kind === 'percent' ? `${x.value} %` : money(x.value, { whole: true }) }; })} />
            </Section>
          )}

          <Section n={pickup ? 2 : 4} id="fco-pay" title={t('payment')}>
            <AgeCheck age={age} returnTo="web_food" onChanged={() => void quote.refetch()} />
            {phase.step === 'pay' && phase.started.mode === 'stripe'
              ? <StripePayment started={asCartStarted(phase.started)} busy={busy} onPaid={() => void place(phase.started)} onError={m => setError(m)} />
              : <p className="co-muted">{t('fakeKicker')}</p>}
            <details className="co-promo">
              <summary>{tp('promoTitle')}</summary>
              <PromoPoints code={promoCode} onCode={setPromoCode} discountCents={quote.data?.discountCents}
                error={quote.error instanceof ValidationError ? quote.error.byField().promoCode : undefined}
                usePoints={usePoints} onUsePoints={setUsePoints} pointsAvailable={quote.data?.pointsAvailable}
                pointsCents={quote.data?.pointsCents} disabled={phase.step === 'pay'} />
            </details>
            <CheckoutConsent consent={consent} />
          </Section>
        </div>

        <aside className="co-summary" aria-label={t('summaryTitle')} aria-live="polite">
          {phase.step === 'pay' && phase.started.mode === 'fake' ? (
            <BankStep t={t} total={price(phase.started.totals.totalCents)} busy={busy} onApprove={() => void place(phase.started)} />
          ) : phase.step !== 'pay' ? (
            <Button block size="lg" disabled={!payable || busy} aria-busy={busy} onClick={() => void pay()}>{busy ? t('paying') : t('pay', { total: price(totals?.totalCents ?? 0) })}</Button>
          ) : null}
          <p className="co-fine">{t('payNote')}</p>
          <Summary t={t} totals={totals} loading={quote.isFetching && !totals} lines={cart.lines.map(l => ({ key: l.key, qty: l.qty, title: l.title, cents: l.unitCents * l.qty }))} />
          {error && <Alert tone="error" role="alert">{error}</Alert>}
          {quote.isError && phase.step === 'form' && <Alert tone="error" role="alert">{quote.error instanceof ValidationError ? quote.error.errors.map(x => x.message).join(' ') : problemCode(quote.error) === 'kitchen_closed' ? t('err_closed') : t('err_generic')}</Alert>}
        </aside>
      </div>

      {phase.step === 'stepUp' && (
        <StepUpDialog mode={phase.mode} onClose={() => setPhase({ step: 'form' })} onProof={proof => { setPhase({ step: 'form' }); void pay(proof); }} />
      )}
    </div>
  );
}

/** S-51's Payment Element expects the cart's shape: one intent here. */
export function asCartStarted(s: FoodStarted): CartStarted {
  return {
    checkoutId: s.orderId, orderId: s.orderId, ref: s.ref, totalCents: s.totals.totalCents, expiresAt: '',
    payment: { provider: s.mode, publishableKey: s.publishableKey ?? null },
    intents: [{ paymentIntent: s.paymentIntent, clientSecret: s.clientSecret ?? null, status: s.status === 'authorized' ? 'requires_confirmation' : s.status, amountCents: s.totals.totalCents }],
  };
}

function Summary({ t, totals, loading, lines }: { t: T; totals?: Totals; loading: boolean; lines: { key: string; qty: number; title: string; cents: number }[] }) {
  const { price: money } = useFormatters();
  const tp = usePromoT();
  return (
    <>
      <ul className="nl-rest-lines nl-fco-lines">
        {(totals?.lines.map((l, i) => ({ key: String(i), qty: l.qty, title: l.title, cents: l.totalCents })) ?? lines).map(l => (
          <li key={l.key}><span><strong>{l.qty}×</strong> {l.title}</span><span>{money(l.cents)}</span></li>
        ))}
      </ul>
      {loading ? <Skeleton height={120} /> : totals ? (
        <dl className="co-totals nl-fco-totals">
          <div><dt>{t('items')}</dt><dd>{money(totals.subtotalCents)}</dd></div>
          {totals.discountCents > 0 && <div><dt>{tp('discount', { code: totals.promoCode ?? '' })}</dt><dd>−{money(totals.discountCents)}</dd></div>}
          {totals.pointsCents > 0 && <div><dt>{tp('pointsLine')}</dt><dd>−{money(totals.pointsCents)}</dd></div>}
          {totals.mode === 'delivery' && <div><dt>{t('delivery')}</dt><dd>{money(totals.deliveryFeeCents)}</dd></div>}
          <div><dt>{t('serviceFee')}</dt><dd>{totals.plusServiceFee ? tp('plusFee') : money(totals.serviceFeeCents)}</dd></div>
          {totals.mode === 'delivery' && <div><dt>{t('tip')}</dt><dd>{money(totals.tipCents)}</dd></div>}
          <div><dt>{t('tax')}</dt><dd>{money(totals.taxCents)}</dd></div>
        </dl>
      ) : null}
      {totals && !loading ? (
        <div className="co-total"><span>{t('total')}</span><Price cents={totals.totalCents} size="xl" /></div>
      ) : null}
      {totals?.estimate && <p className="co-fine">{t('estimate')}</p>}
    </>
  );
}

/** The local fake gateway's stand-in for the bank's 3-D Secure screen (design 06 `pay3ds`). */
export function BankStep({ t, total, busy, onApprove }: { t: T; total: string; busy: boolean; onApprove: () => void }) {
  const [code, setCode] = useState('');
  return (
    <div className="nl-fco-bank">
      <div className="nl-fco-kicker">{t('bankKicker')}</div>
      <div className="nl-fco-strong">{t('bankConfirm', { total })}</div>
      <Field label={t('bankCode')}><TextInput value={code} onChange={e => setCode(e.target.value)} inputMode="numeric" maxLength={6} /></Field>
      <Button block disabled={busy} aria-busy={busy} onClick={onApprove}>{t('approve')}</Button>
      <p className="nl-fco-muted">{t('fakeKicker')}</p>
    </div>
  );
}
