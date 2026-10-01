import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { newIdempotencyKey, ValidationError } from '@northline/client';
import { Alert, Button, EmptyState, Field, OptionCard, Select, Skeleton, SiteLink, TextInput, useFormatters } from '@northline/ui';
import { StripePayment } from '../cart/Payment';
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
  const { money, date } = useFormatters();
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
  const [phase, setPhase] = useState<Phase>({ step: 'form' });
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const keys = useRef({ start: newIdempotencyKey(), confirm: newIdempotencyKey() });

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
    };
  }, [cart, data, pickup, when, slot, tip, hasAddress, location, dropoff, note, extras]);

  const quoteKey = JSON.stringify(body);
  const quote = useQuery({
    queryKey: ['me', 'food-quote', quoteKey],
    queryFn: () => quoteFood(body!),
    enabled: !!user && !!body && (pickup || hasAddress) && !(when === 'schedule' && !slot),
    retry: false,
    staleTime: 30_000,
  });

  if (loading) return <div className="nl-page"><Skeleton width={320} height={36} /><Skeleton height={200} style={{ marginTop: 20 }} /></div>;
  if (!cart || cart.lines.length === 0) {
    return <div className="nl-page"><EmptyState action={<Link to="/food" className="btn btn-primary">{t('browseFood')}</Link>}>{t('emptyCheckout')}</EmptyState></div>;
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
      clear();
      void navigate({ to: '/food/orders/$orderId', params: { orderId: placed.orderId } });
    } catch (e) { failed(e); } finally { setBusy(false); }
  };

  const totals = phase.step === 'pay' ? phase.started.totals : quote.data;
  const payable = !!body && (pickup || hasAddress) && !(when === 'schedule' && !slot) && !!quote.data;

  return (
    <div className="nl-fco">
      <div>
        <h1>{t('checkoutTitle', { name: cart.name })}</h1>
        <h2>{t('when')}</h2>
        <div className="nl-fco-when" role="radiogroup" aria-label={t('when')}>
          {data?.kitchen.open !== false && <OptionCard selected={when === 'asap'} role="radio" aria-checked={when === 'asap'} title={t('asap')} description={data ? t('etaRange', { from: data.kitchen.etaFromMin, to: data.kitchen.etaToMin }) : ''} onClick={() => setWhen('asap')} />}
          {data && data.slots.length > 0 && <OptionCard selected={when === 'schedule'} role="radio" aria-checked={when === 'schedule'} title={t('scheduleOpt')} description={t('scheduleDesc')} onClick={() => setWhen('schedule')} />}
          {data?.kitchen.fulfilment.includes('pickup') && <OptionCard selected={pickup} role="radio" aria-checked={pickup} title={t('pickupOpt')} description={t('pickupDesc', { from: data.kitchen.pickupFromMin, to: data.kitchen.pickupToMin })} onClick={() => setWhen('pickup')} />}
        </div>
        {when === 'schedule' && (
          <Field label={t('window')}>
            <Select value={slot} onChange={e => setSlot(e.target.value)} options={slots.map(s => ({ value: s, label: `${date(s, 'long')} · ${date(s, 'time')}–${date(new Date(new Date(s).getTime() + 30 * 60_000), 'time')}` }))} />
          </Field>
        )}

        {!pickup && (
          <>
            <h2>{t('deliverTo')}</h2>
            {hasAddress ? (
              <div className="nl-fco-address">
                <div><div className="nl-fco-strong">{location.street}{location.unit ? `, ${location.unit}` : ''}</div><div className="nl-fco-muted">{[location.city, location.postalCode].filter(Boolean).join(' · ')}</div></div>
                <Link to="/location" search={{ next: '/food/checkout' }} className="btn btn-ghost">{t('change')}</Link>
              </div>
            ) : (
              <Alert tone="info" role="status" actions={<Link to="/location" search={{ next: '/food/checkout' }} className="btn btn-secondary">{t('addAddress')}</Link>}>{t('noAddress')}</Alert>
            )}
            <fieldset className="nl-fco-chips"><legend>{t('dropoff')}</legend>
              {DROPOFFS.map(d => <button key={d} type="button" className="nl-chip" aria-pressed={dropoff === d} onClick={() => setDropoff(d)}>{t(d)}</button>)}
            </fieldset>
            <Field label={t('courierNote')}><TextInput value={note} onChange={e => setNote(e.target.value)} placeholder={t('courierNotePlaceholder')} maxLength={140} /></Field>
            <fieldset className="nl-fco-chips"><legend>{t('extras')}</legend>
              {EXTRAS.map(x => <button key={x} type="button" className="nl-chip" aria-pressed={extras.has(x)} onClick={() => setExtras(s => { const n = new Set(s); if (n.has(x)) n.delete(x); else n.add(x); return n; })}>{t(x)}</button>)}
            </fieldset>
            <fieldset className="nl-fco-chips"><legend>{t('tipTitle')}</legend>
              {TIPS.map((x, i) => <button key={i} type="button" className="nl-chip" aria-pressed={tip === i} onClick={() => setTip(i)}>{x.kind === 'none' ? t('noTip') : x.kind === 'percent' ? `${x.value}%` : money(x.value, { whole: true })}</button>)}
            </fieldset>
          </>
        )}
        <h2>{t('payment')}</h2>
        {phase.step === 'pay' && phase.started.mode === 'stripe'
          ? <StripePayment started={asCartStarted(phase.started)} busy={busy} onPaid={() => void place(phase.started)} onError={m => setError(m)} />
          : <p className="nl-fco-muted">{t('fakeKicker')}</p>}
      </div>

      <aside className="nl-rest-aside" aria-live="polite">
        <Summary t={t} totals={totals} loading={quote.isFetching && !totals} lines={cart.lines.map(l => ({ key: l.key, qty: l.qty, title: l.title, cents: l.unitCents * l.qty }))} />
        {error && <p className="nl-error" role="alert">{error}</p>}
        {quote.isError && phase.step === 'form' && <p className="nl-error" role="alert">{quote.error instanceof ValidationError ? quote.error.errors.map(x => x.message).join(' ') : problemCode(quote.error) === 'kitchen_closed' ? t('err_closed') : t('err_generic')}</p>}
        {phase.step === 'pay' && phase.started.mode === 'fake' ? (
          <BankStep t={t} total={money(phase.started.totals.totalCents)} busy={busy} onApprove={() => void place(phase.started)} />
        ) : phase.step !== 'pay' ? (
          <>
            <Button block disabled={!payable || busy} aria-busy={busy} onClick={() => void pay()}>{busy ? t('paying') : t('pay', { total: money(totals?.totalCents ?? 0) })}</Button>
            <p className="nl-fco-note">{t('payNote')}</p>
          </>
        ) : null}
      </aside>

      {phase.step === 'stepUp' && (
        <StepUpDialog mode={phase.mode} onClose={() => setPhase({ step: 'form' })} onProof={proof => { setPhase({ step: 'form' }); void pay(proof); }} />
      )}
    </div>
  );
}

/** S-51's Payment Element expects the cart's shape: one intent here. */
function asCartStarted(s: FoodStarted): CartStarted {
  return {
    checkoutId: s.orderId, orderId: s.orderId, ref: s.ref, totalCents: s.totals.totalCents, expiresAt: '',
    payment: { provider: s.mode, publishableKey: s.publishableKey ?? null },
    intents: [{ paymentIntent: s.paymentIntent, clientSecret: s.clientSecret ?? null, status: s.status === 'authorized' ? 'requires_confirmation' : s.status, amountCents: s.totals.totalCents }],
  };
}

function Summary({ t, totals, loading, lines }: { t: T; totals?: Totals; loading: boolean; lines: { key: string; qty: number; title: string; cents: number }[] }) {
  const { money } = useFormatters();
  return (
    <>
      <ul className="nl-rest-lines">
        {(totals?.lines.map((l, i) => ({ key: String(i), qty: l.qty, title: l.title, cents: l.totalCents })) ?? lines).map(l => (
          <li key={l.key}><span><strong>{l.qty}×</strong> {l.title}</span><span>{money(l.cents)}</span></li>
        ))}
      </ul>
      {loading ? <Skeleton height={120} /> : totals ? (
        <dl className="nl-rest-totals">
          <div><dt>{t('items')}</dt><dd>{money(totals.subtotalCents)}</dd></div>
          {totals.mode === 'delivery' && <div><dt>{t('delivery')}</dt><dd>{money(totals.deliveryFeeCents)}</dd></div>}
          <div><dt>{t('serviceFee')}</dt><dd>{money(totals.serviceFeeCents)}</dd></div>
          {totals.mode === 'delivery' && <div><dt>{t('tip')}</dt><dd>{money(totals.tipCents)}</dd></div>}
          <div><dt>{t('tax')}</dt><dd>{money(totals.taxCents)}</dd></div>
          <div className="nl-rest-total"><dt>{t('total')}</dt><dd>{money(totals.totalCents)}</dd></div>
        </dl>
      ) : null}
      {totals?.estimate && <p className="nl-fco-muted">{t('estimate')}</p>}
    </>
  );
}

/** The local fake gateway's stand-in for the bank's 3-D Secure screen (design 06 `pay3ds`). */
function BankStep({ t, total, busy, onApprove }: { t: T; total: string; busy: boolean; onApprove: () => void }) {
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
