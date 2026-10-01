import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Minus, Plus, Trash } from '@phosphor-icons/react';
import { Alert, EmptyState, ErrorState, Field, Select, SiteLink, Skeleton, TextInput, useFormatters, useLocale, type Locale } from '@northline/ui';
import { newIdempotencyKey, ValidationError } from '@northline/client';
import { signInHref, useViewer } from '../session/api';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import { clock, runWhen, weekday, windowRange } from '../shop/format';
import {
  cartQuery, placeOrder, problemCode, quoteQuery, setupQuery, startCheckout, useChangeCartLine,
  type AddressInput, type Cart, type CartLine, type CheckoutBody, type DeliveryOption, type Setup, type Started,
} from './api';
import { SERVER_FR, useCartT } from './messages';
import { FakePayment, StripePayment } from './Payment';
import { StepUpDialog } from './StepUpDialog';

const PROVINCES = ['AB', 'BC', 'MB', 'NB', 'NL', 'NS', 'NT', 'NU', 'ON', 'PE', 'QC', 'SK', 'YT'] as const;
type Substitution = CheckoutBody['substitution'];

/** Server 422 messages in the page's language (orders.domain.CheckoutMessages are English). */
export function localizeServer(message: string, locale: Locale): string {
  if (locale !== 'fr') return message;
  if (SERVER_FR[message]) return SERVER_FR[message]!;
  const notServed = /^We don't deliver to (.+) yet\.$/.exec(message);
  if (notServed) return `Nous ne livrons pas encore à ${notServed[1]}.`;
  const elsewhere = /^(.+) doesn't deliver to (.+)\.$/.exec(message);
  if (elsewhere) return `${elsewhere[1]} ne livre pas à ${elsewhere[2]}.`;
  const left = /^Only (\d+) left\.$/.exec(message);
  if (left) return `Plus que ${left[1]}.`;
  return message;
}

/** Client checks with the server's messages (orders.domain.CheckoutMessages). */
export function addressErrors(a: AddressInput): Record<string, string> {
  const e: Record<string, string> = {};
  if (a.addressId) return e;
  if (!a.street?.trim() || a.street.trim().length > 120) e['address.street'] = 'Enter the street address.';
  if (a.unit && a.unit.trim().length > 20) e['address.unit'] = 'Keep the unit under 20 characters.';
  if (!a.city?.trim() || a.city.trim().length > 60) e['address.city'] = 'Enter the city.';
  if (!a.province || !(PROVINCES as readonly string[]).includes(a.province)) e['address.province'] = "Choose a Canadian province or territory.";
  if (!/^[A-Z]\d[A-Z] ?\d[A-Z]\d$/.test((a.postal ?? '').trim().toUpperCase().replace('-', ' '))) e['address.postal'] = 'Enter a Canadian postal code, like T2P 1B5.';
  if (a.note && a.note.trim().length > 200) e['address.note'] = 'Keep delivery notes under 200 characters.';
  return e;
}

/**
 * Cart and checkout (S-51, design 06 `cart`): the multi-shop cart grouped by shop with quantity steppers; signed in,
 * the delivery window, the address, what to do if something is out of stock, the payment (Stripe's Payment Element,
 * or the local stand-in) and the summary with the tax from the api. Guests see their cart and "Sign in to pay".
 */
export function CartPage() {
  const t = useCartT();
  const { user, loading: sessionLoading } = useViewer();
  const cart = useQuery(cartQuery);
  if (cart.isPending || sessionLoading) return <CartSkeleton />;
  if (cart.isError) return <div className="nl-page"><ErrorState message={t('loadError')} onRetry={() => void cart.refetch()} /></div>;
  if (cart.data.itemCount === 0) {
    return (
      <div className="nl-page cart-page">
        <h1 className="cart-title">{t('title')}</h1>
        <div className="cart-empty"><EmptyState action={<SiteLink href="/shop" className="btn btn-primary">{t('browse')}</SiteLink>}>{t('empty')}</EmptyState></div>
      </div>
    );
  }
  return user ? <Checkout cart={cart.data} /> : <GuestCart cart={cart.data} />;
}

function GuestCart({ cart }: { cart: Cart }) {
  const t = useCartT();
  const { money } = useFormatters();
  return (
    <div className="nl-page cart-page">
      <div className="cart-grid">
        <div>
          <CartHead cart={cart} />
          <CartGroups cart={cart} packBy={null} />
        </div>
        <aside className="cart-summary" aria-label={t('total')}>
          <div className="cart-sum-row"><span>{t('items')}</span><span>{money(cart.subtotalCents)}</span></div>
          <SiteLink href={signInHref('/cart')} className="btn btn-primary cart-pay">{t('signInToPay')}</SiteLink>
          <p className="cart-note">{t('payNote')}</p>
        </aside>
      </div>
    </div>
  );
}

function CartHead({ cart }: { cart: Cart }) {
  const t = useCartT();
  return (
    <>
      <h1 className="cart-title">{t('title')}</h1>
      <div className="cart-sub">{t('sub', { items: cart.itemCount, shops: cart.shopCount })}</div>
    </>
  );
}

function CartGroups({ cart, packBy }: { cart: Cart; packBy: string | null }) {
  const t = useCartT();
  const { locale } = useLocale();
  return (
    <>
      {cart.groups.map(g => (
        <section key={g.merchantId || 'gone'} className="cart-group" aria-label={g.shopName || t('unavailableGroup')}>
          <div className="cart-group-head">
            <h2 className="cart-shop">{g.shopName || t('unavailableGroup')}</h2>
            {packBy && g.shopName ? <span className="cart-group-note">{t('closesToRun', { time: clock(packBy, locale) })}</span> : null}
          </div>
          <ul className="cart-lines">{g.items.map(item => <CartItem key={item.itemId} item={item} />)}</ul>
        </section>
      ))}
    </>
  );
}

function CartItem({ item }: { item: CartLine }) {
  const t = useCartT();
  const { money } = useFormatters();
  const change = useChangeCartLine();
  const name = item.name || '—';
  const meta = item.option && item.unit ? t('itemMeta', { option: item.option, unit: item.unit }) : item.option ?? item.unit ?? '';
  const problem = !item.available ? (item.stock > 0 && item.name ? t('notEnough', { count: item.stock }) : t('unavailable')) : null;
  return (
    <li className="cart-line">
      <span className="cart-line-media halftone" aria-hidden>{item.imageUrl ? <img src={item.imageUrl} alt="" /> : null}</span>
      <span className="cart-line-body">
        {item.productId ? <SiteLink href={`/products/${item.productId}`} className="cart-line-name">{name}</SiteLink> : <span className="cart-line-name">{name}</span>}
        {meta ? <span className="cart-line-meta">{meta}</span> : null}
        {problem ? <span className="cart-line-problem" role="note">{problem}</span> : null}
      </span>
      <span className="cart-qty" role="group" aria-label={t('qty', { name })}>
        <button type="button" aria-label={item.qty === 1 ? t('remove', { name }) : t('dec', { name })} disabled={change.isPending}
          onClick={() => change.mutate({ itemId: item.itemId, qty: item.qty - 1 })}>{item.qty === 1 ? <Trash size={16} aria-hidden /> : <Minus size={16} aria-hidden />}</button>
        <span aria-live="polite">{item.qty}</span>
        <button type="button" aria-label={t('inc', { name })} disabled={change.isPending || !item.available || item.qty >= Math.min(99, item.stock)}
          onClick={() => change.mutate({ itemId: item.itemId, qty: item.qty + 1 })}><Plus size={16} aria-hidden /></button>
      </span>
      <span className="cart-line-total">{money(item.lineCents)}</span>
    </li>
  );
}

// ── signed in: checkout ──────────────────────────────────────────────────────────────────────────────────────────

type Phase = { step: 'form' } | { step: 'stepUp'; mode: 'required' | 'enrol' } | { step: 'pay'; started: Started };

function Checkout({ cart }: { cart: Cart }) {
  const t = useCartT();
  const { locale } = useLocale();
  const { money } = useFormatters();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { location } = useDeliveryLocation();
  const market = location.status === 'locating' ? 'Calgary' : location.city ?? 'Calgary';
  const setup = useQuery(setupQuery(market, locale, true));

  const [optionId, setOptionId] = useState<string>();
  const [substitution, setSubstitution] = useState<Substitution>('similar');
  const [addressId, setAddressId] = useState<string>();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<AddressInput>({ province: 'AB' });
  const [touched, setTouched] = useState(false);
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [phase, setPhase] = useState<Phase>({ step: 'form' });
  const key = useRef<string>(newIdempotencyKey());

  const data = setup.data;
  useEffect(() => {
    if (!data) return;
    setOptionId(id => id && data.options.some(o => o.id === id) ? id : data.options[0]?.id);
    const saved = data.addresses.find(a => a.isDefault) ?? data.addresses[0];
    setAddressId(id => id ?? saved?.id);
    if (!saved) setEditing(true);
    setDraft(d => (d.city ? d : { ...d, city: location.city ?? data.market }));
  }, [data]); // eslint-disable-line react-hooks/exhaustive-deps

  const option = data?.options.find(o => o.id === optionId);
  const address: AddressInput = !editing && addressId ? { addressId } : trimAddress(draft);
  const clientErrors = addressErrors(address);
  const body: CheckoutBody = { kind: option?.kind ?? '', windowId: option?.windowId ?? null, address, substitution };
  const complete = !!option && Object.keys(clientErrors).length === 0;
  const quote = useQuery(quoteQuery(body, locale, complete));
  const shownErrors = { ...(touched ? clientErrors : {}), ...serverErrors };
  const packBy = option?.kind === 'pooled' ? option.packBy ?? null : null;
  const total = quote.data?.totalCents;

  const fail = (e: unknown) => {
    if (e instanceof ValidationError) {
      setServerErrors(Object.fromEntries(Object.entries(e.byField()).map(([f, m]) => [f, localizeServer(m, locale)])));
      setError(t('fixFields', { count: e.errors.length }));
      return;
    }
    const code = problemCode(e);
    setError(code && ['out_of_stock', 'window_closed', 'cart_empty', 'checkout_expired', 'payment_not_authorized'].includes(code)
      ? t(`err_${code as 'out_of_stock'}`) : t('err_generic'));
    if (code === 'out_of_stock' || code === 'cart_empty' || code === 'window_closed') {
      void qc.invalidateQueries({ queryKey: cartQuery.queryKey });
      void qc.invalidateQueries({ queryKey: ['checkout'] });
    }
  };

  const pay = async (proof?: string) => {
    setTouched(true);
    setError(undefined);
    setServerErrors({});
    if (!complete) { setError(t('fixFields', { count: Math.max(1, Object.keys(clientErrors).length) })); return; }
    setBusy(true);
    try {
      const started = await startCheckout(body, key.current, locale, proof);
      setPhase({ step: 'pay', started });
    } catch (e) {
      const code = problemCode(e);
      if (code === 'step_up_required' || code === 'second_factor_required') setPhase({ step: 'stepUp', mode: code === 'step_up_required' ? 'required' : 'enrol' });
      else { fail(e); key.current = newIdempotencyKey(); }
    } finally { setBusy(false); }
  };

  const place = async (started: Started) => {
    setBusy(true);
    try {
      const placed = await placeOrder(started.checkoutId, newIdempotencyKey());
      key.current = newIdempotencyKey();
      await qc.invalidateQueries({ queryKey: cartQuery.queryKey });
      void qc.invalidateQueries({ queryKey: ['checkout'] });
      await navigate({ to: '/orders/$orderId', params: { orderId: placed.orderId } });
    } catch (e) {
      fail(e);
      key.current = newIdempotencyKey();
      setPhase({ step: 'form' });
    } finally { setBusy(false); }
  };

  if (setup.isPending) return <CartSkeleton />;
  if (setup.isError || !data) return <div className="nl-page"><ErrorState message={t('loadError')} onRetry={() => void setup.refetch()} /></div>;
  const view = data.cart.itemCount > 0 ? data.cart : cart;
  const started = phase.step === 'pay' ? phase.started : null;

  return (
    <div className="nl-page cart-page">
      <div className="cart-grid">
        <div>
          <CartHead cart={view} />
          <CartGroups cart={view} packBy={packBy} />

          <section className="cart-section" aria-labelledby="cart-window">
            <h2 id="cart-window" className="cart-h2">{t('window')}</h2>
            {data.options.length === 0 ? <p className="cart-muted">{t('noWindow')}</p> : (
              <div className="cart-options" role="radiogroup" aria-labelledby="cart-window">
                {data.options.map(o => <WindowOption key={o.id} option={o} selected={o.id === optionId} onPick={() => setOptionId(o.id)} disabled={!!started} />)}
              </div>
            )}
          </section>

          <section className="cart-section" aria-labelledby="cart-address">
            <h2 id="cart-address" className="cart-h2">{t('deliverTo')}</h2>
            {!editing && addressId ? (
              <SavedAddress setup={data} addressId={addressId} onPick={setAddressId} onChange={() => setEditing(true)} disabled={!!started} />
            ) : (
              <AddressForm value={draft} onChange={v => { setDraft(v); setServerErrors({}); }} errors={shownErrors} locale={locale}
                onCancel={data.addresses.length > 0 ? () => setEditing(false) : undefined} disabled={!!started} />
            )}
            {serverErrors['address.city'] && !editing ? <p className="cart-field-error" role="alert">{serverErrors['address.city']}</p> : null}
            {serverErrors.items ? <p className="cart-field-error" role="alert">{serverErrors.items}</p> : null}
          </section>

          <section className="cart-section" aria-labelledby="cart-subs">
            <h2 id="cart-subs" className="cart-h2">{t('substitution')}</h2>
            <div className="cart-chips" role="radiogroup" aria-labelledby="cart-subs">
              {(['similar', 'refund', 'ask'] as const).map(s => (
                <button key={s} type="button" role="radio" aria-checked={substitution === s} className="shop-chip cart-chip" disabled={!!started} onClick={() => setSubstitution(s)}>{t(s)}</button>
              ))}
            </div>
          </section>

          <section className="cart-section" aria-labelledby="cart-payment">
            <h2 id="cart-payment" className="cart-h2">{t('payment')}</h2>
            {started && started.payment.provider === 'stripe' && started.intents.some(i => i.status !== 'authorized')
              ? <StripePayment started={started} busy={busy} onPaid={() => void place(started)} onError={m => setError(m)} />
              : <FakePayment provider={data.payment.provider} />}
          </section>
        </div>

        <aside className="cart-summary" aria-label={t('total')}>
          <div className="cart-sums">
            <div className="cart-sum-row"><span>{t('items')}</span><span>{money(view.subtotalCents)}</span></div>
            <div className="cart-sum-row"><span>{t('delivery')}</span><span>{option ? (option.feeCents === 0 ? t('free') : money(option.feeCents)) : '—'}</span></div>
            {quote.data
              ? quote.data.taxes.map(tax => <div key={`${tax.type}${tax.percent}`} className="cart-sum-row"><span>{taxLabel(t, tax.type, tax.percent, locale)}</span><span>{money(tax.cents)}</span></div>)
              : <div className="cart-sum-row"><span>{t('taxPending')}</span><span className="cart-muted">{quote.isFetching ? <Skeleton width={48} height={14} /> : t('taxLater')}</span></div>}
            <div className="cart-sum-row cart-total"><span>{t('total')}</span><span>{total === undefined ? '—' : money(total)}</span></div>
          </div>
          {error ? <div className="cart-alert"><Alert tone="error" role="alert">{error}</Alert></div> : null}
          {started && started.payment.provider !== 'stripe' ? (
            <BankApproval total={money(started.totalCents)} busy={busy} onApprove={() => void place(started)} />
          ) : started ? null : (
            <>
              <button type="button" className="btn btn-primary cart-pay" disabled={busy || view.itemCount === 0} aria-busy={busy} onClick={() => void pay()}>
                {busy ? t('paying') : t('pay', { total: total === undefined ? money(view.subtotalCents + (option?.feeCents ?? 0)) : money(total) })}
              </button>
              {data.stepUp !== 'none' ? <p className="cart-note">{t('stepUpNeeded')}</p> : null}
            </>
          )}
          <p className="cart-note">{t('payNote')}</p>
        </aside>
      </div>
      {phase.step === 'stepUp' ? (
        <StepUpDialog mode={phase.mode} onClose={() => setPhase({ step: 'form' })}
          onProof={proof => { setPhase({ step: 'form' }); void pay(proof); }} />
      ) : null}
    </div>
  );
}

const trimAddress = (a: AddressInput): AddressInput => ({
  street: a.street?.trim(), unit: a.unit?.trim() || undefined, city: a.city?.trim(), province: a.province,
  postal: a.postal?.trim().toUpperCase(), note: a.note?.trim() || undefined,
});

function taxLabel(t: ReturnType<typeof useCartT>, type: string, percent: number, locale: Locale) {
  const p = new Intl.NumberFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { maximumFractionDigits: 3 }).format(percent);
  return ['gst', 'hst', 'pst', 'qst', 'rst'].includes(type) ? t(`tax_${type as 'gst'}`, { percent: p }) : `${type.toUpperCase()} ${p}%`;
}

function WindowOption({ option, selected, onPick, disabled }: { option: DeliveryOption; selected: boolean; onPick: () => void; disabled: boolean }) {
  const t = useCartT();
  const { locale } = useLocale();
  const { money } = useFormatters();
  const name = option.kind === 'direct'
    ? t('now', { eta: option.etaMinutes ?? 45 })
    : t(`win_${runWhen({ day: option.day ?? 'later', startsAt: option.startsAt! })}`, { range: windowRange(option.startsAt!, option.endsAt!, locale), weekday: weekday(option.startsAt!, locale) });
  const desc = option.kind === 'direct' ? t('direct') : option.households > 0 ? t('pooledWith', { count: option.households }) : t('pooled', { time: clock(option.orderBy!, locale) });
  return (
    <button type="button" role="radio" aria-checked={selected} className="nl-option cart-option" onClick={onPick} disabled={disabled}>
      <span className="cart-option-text"><span className="cart-option-name">{name}</span><span className="cart-option-desc">{desc}</span></span>
      <span className="cart-option-price">{option.feeCents === 0 ? t('free') : money(option.feeCents)}</span>
    </button>
  );
}

function SavedAddress({ setup, addressId, onPick, onChange, disabled }: { setup: Setup; addressId: string; onPick: (id: string) => void; onChange: () => void; disabled: boolean }) {
  const t = useCartT();
  const current = setup.addresses.find(a => a.id === addressId) ?? setup.addresses[0]!;
  return (
    <div className="cart-address">
      <div>
        <div className="cart-address-line">{current.unit ? `${current.street}, ${current.unit}` : current.street}</div>
        <div className="cart-muted">{[`${current.city} ${current.province} ${current.postal}`, current.note].filter(Boolean).join(' · ')}</div>
        {setup.addresses.length > 1 ? (
          <Field label={t('deliverTo')} className="cart-address-pick">
            <Select value={current.id} disabled={disabled} onChange={e => onPick(e.target.value)} options={setup.addresses.map(a => ({ value: a.id, label: `${a.street}${a.unit ? `, ${a.unit}` : ''} · ${a.city}` }))} />
          </Field>
        ) : null}
      </div>
      <button type="button" className="btn btn-ghost" onClick={onChange} disabled={disabled}>{t('change')}</button>
    </div>
  );
}

function AddressForm({ value, onChange, errors, locale, onCancel, disabled }: {
  value: AddressInput; onChange: (v: AddressInput) => void; errors: Record<string, string>; locale: Locale; onCancel?: () => void; disabled: boolean;
}) {
  const t = useCartT();
  const set = (k: keyof AddressInput) => (e: { target: { value: string } }) => onChange({ ...value, [k]: e.target.value });
  const err = (f: string) => (errors[`address.${f}`] ? localizeServer(errors[`address.${f}`]!, locale) : undefined);
  return (
    <div className="cart-address-form">
      <div className="cart-form-grid">
        <Field label={t('street')} error={err('street')} className="cart-span-2"><TextInput value={value.street ?? ''} onChange={set('street')} autoComplete="address-line1" disabled={disabled} aria-invalid={!!err('street')} /></Field>
        <Field label={t('unit')} error={err('unit')}><TextInput value={value.unit ?? ''} onChange={set('unit')} autoComplete="address-line2" disabled={disabled} aria-invalid={!!err('unit')} /></Field>
        <Field label={t('city')} error={err('city')}><TextInput value={value.city ?? ''} onChange={set('city')} autoComplete="address-level2" disabled={disabled} aria-invalid={!!err('city')} /></Field>
        <Field label={t('province')} error={err('province')}><Select value={value.province ?? ''} onChange={set('province')} options={[...PROVINCES]} disabled={disabled} /></Field>
        <Field label={t('postal')} error={err('postal')}><TextInput value={value.postal ?? ''} onChange={set('postal')} autoComplete="postal-code" disabled={disabled} aria-invalid={!!err('postal')} /></Field>
        <Field label={t('note')} error={err('note')} className="cart-span-2"><TextInput value={value.note ?? ''} onChange={set('note')} placeholder={t('notePlaceholder')} disabled={disabled} aria-invalid={!!err('note')} /></Field>
      </div>
      {onCancel ? <button type="button" className="btn btn-ghost" onClick={onCancel} disabled={disabled}>{t('cancel')}</button> : null}
    </div>
  );
}

/** The local stand-in's bank step (design 06 `pay3ds`): approve, then the order is placed. */
function BankApproval({ total, busy, onApprove }: { total: string; busy: boolean; onApprove: () => void }) {
  const t = useCartT();
  const [code, setCode] = useState('482 913');
  return (
    <div className="cart-bank" role="group" aria-label={t('bankKicker')}>
      <div className="cart-kicker">{t('bankKicker')}</div>
      <div className="cart-bank-title">{t('bankTitle', { total })}</div>
      <Field label={t('bankCode')}><TextInput value={code} onChange={e => setCode(e.target.value)} className="cart-bank-code" inputMode="numeric" /></Field>
      <button type="button" className="btn btn-primary cart-pay" disabled={busy} aria-busy={busy} onClick={onApprove}>{t('approve')}</button>
    </div>
  );
}

export function CartSkeleton() {
  const t = useCartT();
  return (
    <div className="nl-page cart-page" aria-busy="true">
      <span className="nl-sr-only">{t('loading')}</span>
      <div className="cart-grid">
        <div>
          <Skeleton width={200} height={36} /><Skeleton width={260} height={14} style={{ marginTop: 10 }} />
          {Array.from({ length: 3 }, (_, i) => <Skeleton key={i} height={56} style={{ marginTop: 18 }} />)}
          <Skeleton width={180} height={22} style={{ marginTop: 32 }} /><Skeleton height={60} style={{ marginTop: 10 }} />
        </div>
        <Skeleton height={220} radius={12} />
      </div>
    </div>
  );
}

