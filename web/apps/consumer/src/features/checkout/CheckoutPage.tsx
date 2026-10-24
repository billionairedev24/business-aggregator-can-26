import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { CreditCard, LockSimple, Plus, ShieldCheck, Truck } from '@phosphor-icons/react';
import { newIdempotencyKey, ValidationError } from '@northline/client';
import {
  Alert, EmptyState, ErrorState, Field, OptionCard, Price, RadioGroup, Segmented, Select, SiteLink, Skeleton, TextInput,
  useFormatters, useLocale, type Locale,
} from '@northline/ui';
import { MarketZone, useRegions, useZone } from '../location/regions';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import { signInHref, useViewer } from '../session/api';
import { clock, runWhen, weekday, windowRange } from '../shop/format';
import { addressErrors, localizeServer, trimAddress } from '../cart/address';
import {
  cartQuery, placeOrder, problemCode, quoteQuery, setupQuery, startCheckout, useChangeCartLine,
  type AddressInput, type AddressView, type Cart, type CartLine, type CheckoutBody, type DeliveryOption, type Started,
} from '../cart/api';
import { useCartT } from '../cart/messages';
import { useStripePayment } from '../cart/Payment';
import { CheckoutConsent, useCheckoutConsent } from '../consent/CheckoutConsent';
import { StepUpDialog } from '../cart/StepUpDialog';
import { AgeCheck } from '../cart/AgeCheck';
import { ageCleared, NO_AGE } from '../cart/age';
import { useAgeT } from '../cart/ageMessages';
import { PromoPoints, type TipChoice } from '../promotions/PromoPoints';
import { usePromoT } from '../promotions/messages';
import { legalHref } from '../../lib/legal';
import { PhotoWell } from '../art/PhotoWell';
import { useCheckoutT } from './messages';
import { Section } from './Section';

type Substitution = CheckoutBody['substitution'];
/** The tip choices drawn in design/revamp/a.html: no tip, $2, $3, $5 or another amount (100 % to the courier). */
const TIP_CHOICES = ['none', '200', '300', '500', 'other'] as const;
type TipKey = (typeof TIP_CHOICES)[number];
const SUBSTITUTIONS = ['similar', 'refund', 'ask'] as const;

/**
 * The one-page checkout (E-15, design/revamp/a.html "Cart and one-page checkout" and its fr-CA frame): every choice on
 * one page, each with its selected state — 1 the address, 2 the delivery window, 3 what to do when something is out of
 * stock, 4 the courier's tip, 5 the payment, 6 the items grouped by seller — beside a sticky summary with "Place your
 * order", the tax lines and the total (on phones a sticky bar keeps the total and the button in reach). The same calls
 * as before: the set-up and the live quote; "Place your order" opens the checkout (`POST /me/checkouts`, which fixes
 * the total; step-up and Idempotency-Key unchanged) and, with the local stand-in, places it at once. Under Stripe the
 * first press opens the Payment Element in section 5 and the next one authorizes the card and places the order. Any
 * change after the checkout opened (address, window, tip, code) opens a new one on the next press. Signed in only.
 */
export function CheckoutPage() {
  const t = useCheckoutT();
  const c = useCartT();
  const { user, loading } = useViewer();
  const cart = useQuery({ ...cartQuery, enabled: !!user });
  const { location } = useDeliveryLocation();
  const city = location.status === 'locating' ? undefined : location.city;
  if (loading || (user && cart.isPending)) return <CheckoutSkeleton />;
  if (!user) {
    return (
      <div className="nl-page co-page">
        <h1 className="co-title">{t('title')}</h1>
        <EmptyState action={<SiteLink href={signInHref('/checkout')} className="btn btn-primary">{t('signInAction')}</SiteLink>}>{t('signIn')}</EmptyState>
      </div>
    );
  }
  if (cart.isError) return <div className="nl-page co-page"><h1 className="co-title">{t('title')}</h1><ErrorState message={c('loadError')} onRetry={() => void cart.refetch()} /></div>;
  if (cart.data!.itemCount === 0) {
    return (
      <div className="nl-page co-page">
        <h1 className="co-title">{t('title')}</h1>
        <EmptyState title={t('emptyTitle')} action={<SiteLink href="/shop" className="btn btn-primary">{t('browse')}</SiteLink>}>{t('emptyBody')}</EmptyState>
      </div>
    );
  }
  // times on this page read in the visitor's market's zone (region model), else the platform zone
  return <MarketZone city={city}><Checkout cart={cart.data!} name={`${user.firstName} ${user.lastName}`.trim()} /></MarketZone>;
}

function Checkout({ cart, name }: { cart: Cart; name: string }) {
  const t = useCheckoutT();
  const c = useCartT();
  const tp = usePromoT();
  const tAge = useAgeT();
  const { locale } = useLocale();
  // tip amounts are choices drawn in whole dollars ($2 · $3 · $5, the mockup's); every price uses `price`
  const { price, money } = useFormatters();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { location } = useDeliveryLocation();
  // the visitor's market; none known = the api's fallback market (region configuration)
  const market = location.status === 'locating' ? undefined : location.city ?? undefined;
  const regions = useRegions();
  const provinceCodes = regions?.provinces.map(p => p.code) ?? [];
  const setup = useQuery(setupQuery(market, locale, true));

  const [optionId, setOptionId] = useState<string>();
  const [substitution, setSubstitution] = useState<Substitution>('similar');
  const [addressId, setAddressId] = useState<string>();
  const [editing, setEditing] = useState(false);
  const [choosing, setChoosing] = useState(false);
  const [draft, setDraft] = useState<AddressInput>({ province: location.status !== 'locating' ? location.province : undefined });
  useEffect(() => { if (!draft.province && regions?.defaultProvince) setDraft(d => ({ ...d, province: d.province ?? regions.defaultProvince ?? undefined })); }, [draft.province, regions?.defaultProvince]);
  const [touched, setTouched] = useState(false);
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string>();
  const [notice, setNotice] = useState<string>();
  const [busy, setBusy] = useState(false);
  // once placed, the cart is empty: nothing may ask for a quote again (it answered 409 after a successful order)
  const [placed, setPlaced] = useState(false);
  const [started, setStarted] = useState<Started | null>(null);
  const [stepUp, setStepUp] = useState<'required' | 'enrol' | null>(null);
  const key = useRef<string>(newIdempotencyKey());
  const [promoCode, setPromoCode] = useState<string>();
  const [usePoints, setUsePoints] = useState(false);
  const [tipKey, setTipKey] = useState<TipKey>('none');
  const [otherTip, setOtherTip] = useState('');
  const pay = useStripePayment(started, m => setError(m));
  const consent = useCheckoutConsent(); // S-157: the optional offers box (CASL)
  const addressHeading = useRef<HTMLHeadingElement>(null);
  const paymentHeading = useRef<HTMLHeadingElement>(null);

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
  const clientErrors = addressErrors(address, provinceCodes);
  const otherCents = Math.round(Number(otherTip.replace(',', '.')) * 100);
  const tip: TipChoice = tipKey === 'none' ? { kind: 'none', value: 0 }
    : tipKey === 'other' ? (otherCents > 0 ? { kind: 'amount', value: otherCents } : { kind: 'none', value: 0 })
      : { kind: 'amount', value: Number(tipKey) };
  const body: CheckoutBody = {
    kind: option?.kind ?? '', windowId: option?.windowId ?? null, address, substitution,
    ...(promoCode ? { promoCode } : {}), ...(usePoints ? { usePoints } : {}), ...(tip.kind !== 'none' ? { tip } : {}),
  };
  const complete = !!option && Object.keys(clientErrors).length === 0;
  const quote = useQuery(quoteQuery(body, locale, complete && !placed));
  const shownErrors = { ...(touched ? clientErrors : {}), ...serverErrors };
  const promoError = serverErrors.promoCode ?? (quote.error instanceof ValidationError ? quote.error.byField().promoCode : undefined);
  const age = quote.data?.age ?? setup.data?.age ?? NO_AGE;
  const refreshAge = () => { void qc.invalidateQueries({ queryKey: ['checkout'] }); };

  // A changed choice after the checkout opened needs a new one (its total is fixed): the open one is left to expire.
  const bodyKey = JSON.stringify(body);
  const startedFor = useRef<string>('');
  useEffect(() => {
    if (started && startedFor.current !== bodyKey) { setStarted(null); setNotice(undefined); key.current = newIdempotencyKey(); }
  }, [bodyKey]); // eslint-disable-line react-hooks/exhaustive-deps

  const fail = (e: unknown) => {
    if (e instanceof ValidationError) {
      setServerErrors(Object.fromEntries(Object.entries(e.byField()).map(([f, m]) => [f, localizeServer(m, locale)])));
      setError(c('fixFields', { count: e.errors.length }));
      if (e.errors.some(x => x.field.startsWith('address'))) addressHeading.current?.focus();
      return;
    }
    const code = problemCode(e);
    if (code === 'age_verification_required' || code === 'age_under_minimum' || code === 'restricted_hours') {
      setError(code === 'restricted_hours' ? c('err_restricted_hours') : code === 'age_under_minimum' ? tAge('underAge', { age: age.minimumAge }) : tAge('payBlocked'));
      refreshAge();
      return;
    }
    setError(code && ['out_of_stock', 'window_closed', 'cart_empty', 'checkout_expired', 'payment_not_authorized'].includes(code)
      ? c(`err_${code as 'out_of_stock'}`) : c('err_generic'));
    if (code === 'out_of_stock' || code === 'cart_empty' || code === 'window_closed' || code === 'checkout_expired') {
      void qc.invalidateQueries({ queryKey: cartQuery.queryKey });
      void qc.invalidateQueries({ queryKey: ['checkout'] });
      setStarted(null);
    }
  };

  /** The card authorized (Stripe), then `…/place`, then the order-placed page. */
  const finish = async (s: Started) => {
    if (pay.needed && !(await pay.confirm())) return;
    const order = await placeOrder(s.checkoutId, newIdempotencyKey());
    key.current = newIdempotencyKey();
    setPlaced(true);
    await consent.record(); // S-157: the ticked offers box, once the order went through
    await qc.cancelQueries({ queryKey: ['checkout'] });
    await navigate({ to: '/checkout/complete', search: { order: order.orderId, ref: order.ref } });
    // after leaving: the cart is refetched (now empty) and the next checkout starts from a fresh set-up and quote
    qc.removeQueries({ queryKey: ['checkout'] });
    void qc.invalidateQueries({ queryKey: cartQuery.queryKey });
  };

  /**
   * "Place your order": checks the page, opens the checkout when none is open (`POST /me/checkouts`, step-up when
   * asked), then places it — except under Stripe the first time, when the card form opens in section 5 first.
   */
  const place = async (proof?: string) => {
    setTouched(true);
    setServerErrors({});
    setError(undefined);
    if (!complete) {
      setError(c('fixFields', { count: Math.max(1, Object.keys(clientErrors).length) }));
      addressHeading.current?.focus();
      return;
    }
    if (!ageCleared(age)) { setError(tAge('payBlocked')); return; }
    setBusy(true);
    try {
      if (!started) {
        let s: Started;
        try { s = await startCheckout(body, key.current, locale, proof); } catch (e) {
          const code = problemCode(e);
          if (code === 'step_up_required' || code === 'second_factor_required') { setStepUp(code === 'step_up_required' ? 'required' : 'enrol'); return; }
          throw e;
        }
        startedFor.current = bodyKey;
        setStarted(s);
        if (s.payment.provider === 'stripe' && s.intents.some(i => i.status !== 'authorized' && i.clientSecret)) {
          setNotice(t('enterCard'));
          paymentHeading.current?.focus();
          return;
        }
        await finish(s);
        return;
      }
      await finish(started);
    } catch (e) {
      fail(e);
      key.current = newIdempotencyKey();
    } finally { setBusy(false); }
  };

  if (setup.isPending) return <CheckoutSkeleton />;
  if (setup.isError || !data) return <div className="nl-page co-page"><h1 className="co-title">{t('title')}</h1><ErrorState message={c('loadError')} onRetry={() => void setup.refetch()} /></div>;
  const view = data.cart.itemCount > 0 ? data.cart : cart;
  const stripe = data.payment.provider === 'stripe';
  const total = started ? started.totalCents : quote.data?.totalCents;
  const placeLabel = busy ? t('placing') : t('place');
  const taxes = quote.data?.taxes ?? [];

  return (
    <div className="nl-page co-page">
      <div className="co-layout">
        <div className="co-head">
          <h1 className="co-title"><LockSimple size={22} aria-hidden /> {t('title')}</h1>
          <span className="co-head-meta">{t('fromShops', { items: view.itemCount, shops: view.shopCount })}</span>
        </div>

        <div className="co-main">
          <Section n={1} id="co-address" title={t('s_address')} headingRef={addressHeading}
            action={!editing && data.addresses.length > 0 ? <button type="button" className="btn btn-link co-change" aria-expanded={choosing} onClick={() => setChoosing(v => !v)}>{t('change')}</button> : null}>
            {!editing && data.addresses.length > 0 ? (
              <>
                <RadioGroup className="co-choices" aria-labelledby="co-address">
                  {(choosing ? data.addresses : data.addresses.filter(a => a.id === addressId)).map(a => (
                    <OptionCard key={a.id} role="radio" mark="radio" selected={a.id === addressId} className="co-choice"
                      onClick={() => { setAddressId(a.id); setChoosing(false); }}
                      title={name || a.street} description={<AddressLines address={a} />} />
                  ))}
                </RadioGroup>
                {choosing ? <button type="button" className="btn btn-link co-add" onClick={() => { setEditing(true); setChoosing(false); }}><Plus size={16} aria-hidden /> {t('addAddress')}</button> : null}
              </>
            ) : (
              <AddressForm value={draft} provinces={provinceCodes} onChange={v => { setDraft(v); setServerErrors({}); }} errors={shownErrors} locale={locale}
                onCancel={data.addresses.length > 0 ? () => setEditing(false) : undefined} />
            )}
            {serverErrors['address.city'] && !editing ? <p className="cart-field-error" role="alert">{serverErrors['address.city']}</p> : null}
            {serverErrors.items ? <p className="cart-field-error" role="alert">{serverErrors.items}</p> : null}
            <AgeCheck age={age} onChanged={refreshAge} />
          </Section>

          <Section n={2} id="co-window" title={t('s_window')}>
            {data.options.length === 0 ? <p className="co-muted">{c('noWindow')}</p> : (
              <RadioGroup className="co-choices" aria-labelledby="co-window">
                {data.options.map(o => <WindowOption key={o.id} option={o} selected={o.id === optionId} onPick={() => setOptionId(o.id)} />)}
              </RadioGroup>
            )}
            {option?.kind === 'pooled' && option.packBy ? <PackNote option={option} shops={view.shopCount} /> : null}
          </Section>

          <Section n={3} id="co-subs" title={t('s_subs')}>
            <Segmented name="co-substitution" stack aria-label={t('s_subs')} value={substitution} onChange={setSubstitution}
              options={SUBSTITUTIONS.map(s => ({ value: s, label: t(`subs_${s}`) }))} />
          </Section>

          <Section n={4} id="co-tip" title={t('s_tip')} className="co-tip-seg-host">
            <p className="co-muted co-lede">{t('tipLede')}</p>
            <Segmented name="co-tip" aria-label={t('s_tip')} value={tipKey} onChange={setTipKey}
              options={TIP_CHOICES.map(k => ({ value: k, label: k === 'none' ? t('tipNone') : k === 'other' ? t('tipOther') : money(Number(k), { whole: true }) }))} />
            {tipKey === 'other' ? (
              <Field label={t('tipAmount')} className="co-tip-other">
                <TextInput inputMode="decimal" value={otherTip} onChange={e => setOtherTip(e.target.value.replace(/[^\d.,]/g, ''))} />
              </Field>
            ) : null}
          </Section>

          <Section n={5} id="co-payment" title={t('s_payment')} headingRef={paymentHeading}>
            <RadioGroup className="co-choices" aria-labelledby="co-payment">
              <OptionCard role="radio" mark="radio" selected className="co-choice"
                title={<span className="co-pay-title"><CreditCard size={18} aria-hidden />{stripe ? t('methodStripe') : t('methodTest')}</span>}
                description={stripe ? c('stripeNote') : t('methodTestNote')} />
            </RadioGroup>
            {stripe && started && pay.needed ? (
              <div className="cart-pay-box">
                {!pay.ready ? <p className="co-muted" role="status">{t('paymentLoading')}</p> : null}
                <div ref={pay.host} className="cart-stripe" data-testid="stripe-payment-element" />
              </div>
            ) : null}
            {notice ? <p className="co-notice" role="status">{notice}</p> : null}
            {data.stepUp !== 'none' && !started ? <p className="co-muted">{c('stepUpNeeded')}</p> : null}
            <p className="co-trust"><ShieldCheck size={16} aria-hidden /> {t('charged')}</p>
          </Section>

          <Section n={6} id="co-review" title={t('s_review')}>
            {view.groups.map(g => <SellerGroup key={g.merchantId || 'gone'} name={g.shopName} items={g.items} />)}
          </Section>
        </div>

        <aside className="co-summary" aria-label={t('summary')}>
          <button type="button" className="btn btn-primary btn-block btn-lg" disabled={busy || data.options.length === 0} aria-busy={busy} onClick={() => void place()}>{placeLabel}</button>
          <p className="co-fine co-terms">{t('termsBefore')}<a href={legalHref('terms', locale)} target="_blank" rel="noopener" hrefLang="en-CA">{t('termsLink')}</a>{t('termsAnd')}<a href={legalHref('privacy', locale)} target="_blank" rel="noopener" hrefLang="en-CA">{t('privacyLink')}</a>{t('termsAfter')}</p>
          <dl className="co-totals">
            <Row label={t('itemsCount', { count: view.itemCount })} value={price(view.subtotalCents)} />
            <Row label={c('delivery')} value={option ? (option.feeCents === 0 ? c('free') : price(option.feeCents)) : '—'} />
            {quote.data && quote.data.tipCents > 0 ? <Row label={t('s_tip')} value={price(quote.data.tipCents)} /> : null}
            {view.plusSavingsCents > 0 ? <Row label={tp('plusSavings')} value={`−${price(view.plusSavingsCents)}`} /> : null}
            {quote.data && quote.data.discountCents > 0 ? <Row label={tp('discount', { code: quote.data.promoCode ?? '' })} value={`−${price(quote.data.discountCents)}`} /> : null}
            {/* S-152: points are a discount before tax, like the code */}
            {quote.data && quote.data.pointsCents > 0 ? <Row label={tp('pointsLine')} value={`−${price(quote.data.pointsCents)}`} /> : null}
            {quote.data
              ? taxes.map(tax => <Row key={`${tax.type}${tax.percent}`} label={taxLabel(c, tax.type, tax.percent, locale)} value={price(tax.cents)} />)
              : <Row label={c('taxPending')} value={quote.isFetching ? <Skeleton width={48} height={14} /> : <span className="co-muted">{c('taxLater')}</span>} />}
          </dl>
          {taxes.length ? <p className="co-fine">{t('taxNote')}</p> : null}
          <div className="co-total"><span>{t('orderTotal')}</span>{total != null ? <Price cents={total} size="lg" className="co-total-amount" /> : <span className="co-total-amount">—</span>}</div>
          {error ? <Alert tone="error" role="alert">{error}</Alert> : null}
          <details className="co-promo">
            <summary>{t('promoToggle')}</summary>
            <PromoPoints code={promoCode} onCode={code => { setPromoCode(code); setServerErrors(({ promoCode: _, ...rest }) => rest); }}
              error={promoError} discountCents={quote.data?.discountCents} usePoints={usePoints} onUsePoints={setUsePoints}
              pointsAvailable={quote.data?.pointsAvailable} pointsCents={quote.data?.pointsCents} />
          </details>
          <CheckoutConsent consent={consent} />
        </aside>
      </div>
      {/* phones: the total and the button stay in reach (design/revamp/a.html, 390 frame) */}
      <div className="co-bar">
        <span className="co-bar-total"><span className="co-bar-label">{t('orderTotal')}</span>{total != null ? <Price cents={total} size="md" /> : <span>—</span>}</span>
        <button type="button" className="btn btn-primary" disabled={busy || data.options.length === 0} aria-busy={busy} onClick={() => void place()}>{placeLabel}</button>
      </div>
      {stepUp ? (
        <StepUpDialog mode={stepUp} onClose={() => setStepUp(null)} onProof={proof => { setStepUp(null); void place(proof); }} />
      ) : null}
    </div>
  );
}

function AddressLines({ address: a }: { address: AddressView }) {
  return (
    <>
      <span className="co-line">{a.unit ? `${a.street}, ${a.unit}` : a.street}</span>
      <span className="co-line">{`${a.city} ${a.province} ${a.postal}`}</span>
      {a.note ? <span className="co-line co-muted">{a.note}</span> : null}
    </>
  );
}

/** Review: one block per seller ("Sold by …"), each line with its photo well, the quantity and the line's price. */
function SellerGroup({ name, items }: { name: string; items: CartLine[] }) {
  const c = useCartT();
  const t = useCheckoutT();
  return (
    <div className="co-seller">
      <h3 className="co-seller-name">{name ? t('soldBy', { shop: name }) : c('unavailableGroup')}</h3>
      <ul className="co-items">{items.map(i => <ReviewLine key={i.itemId} item={i} />)}</ul>
    </div>
  );
}

function ReviewLine({ item: i }: { item: CartLine }) {
  const c = useCartT();
  const t = useCheckoutT();
  const { price } = useFormatters();
  const change = useChangeCartLine();
  const max = Math.min(99, Math.max(i.qty, i.stock, 1));
  const qtys = Array.from({ length: Math.min(max, Math.max(10, i.qty)) }, (_, k) => String(k + 1));
  const label = [i.name, i.option].filter(Boolean).join(', ');
  return (
    <li className="co-item">
      <span className="co-thumb" aria-hidden>{i.imageUrl ? <img src={i.imageUrl} alt="" /> : <PhotoWell subject={i.name} />}</span>
      <span className="co-item-text"><span className="co-item-name">{label}</span>{i.unit ? <span className="co-muted">{i.unit}</span> : null}</span>
      <label className="co-qty">
        <span aria-hidden>{t('qty')}</span>
        <Select aria-label={c('qty', { name: i.name })} value={String(i.qty)} disabled={change.isPending} options={qtys}
          onChange={e => change.mutate({ itemId: i.itemId, qty: Number(e.target.value) })} />
      </label>
      <span className="co-item-price">{price(i.lineCents)}</span>
    </li>
  );
}

const Row = ({ label, value }: { label: ReactNode; value: ReactNode }) => <div className="co-row"><dt>{label}</dt><dd>{value}</dd></div>;

function taxLabel(c: ReturnType<typeof useCartT>, type: string, percent: number, locale: Locale) {
  const p = new Intl.NumberFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { maximumFractionDigits: 3 }).format(percent);
  return ['gst', 'hst', 'pst', 'qst', 'rst'].includes(type) ? c(`tax_${type as 'gst'}`, { percent: p }) : `${type.toUpperCase()} ${p}%`;
}

function WindowName({ option }: { option: DeliveryOption }) {
  const c = useCartT();
  const { locale } = useLocale();
  const zone = useZone();
  return <>{option.kind === 'direct'
    ? c('now', { eta: option.etaMinutes ?? 45 })
    : c(`win_${runWhen({ day: option.day ?? 'later', startsAt: option.startsAt! }, zone)}`, { range: windowRange(option.startsAt!, option.endsAt!, locale, zone), weekday: weekday(option.startsAt!, locale, zone) })}</>;
}

function WindowOption({ option, selected, onPick }: { option: DeliveryOption; selected: boolean; onPick: () => void }) {
  const c = useCartT();
  const { locale } = useLocale();
  const zone = useZone();
  const { price } = useFormatters();
  const desc = option.kind === 'direct' ? c('direct') : option.households > 0 ? c('pooledWith', { count: option.households }) : c('pooled', { time: clock(option.orderBy!, locale, zone) });
  return (
    <OptionCard role="radio" mark="radio" selected={selected} className="co-choice" onClick={onPick}
      title={<WindowName option={option} />} description={desc}
      trailing={<span className="co-option-fee">{option.feeCents === 0 ? c('free') : price(option.feeCents)}</span>} />
  );
}

function PackNote({ option, shops }: { option: DeliveryOption; shops: number }) {
  const t = useCheckoutT();
  const { locale } = useLocale();
  const zone = useZone();
  return <p className="co-pack"><Truck size={16} aria-hidden /> {t('packNote', { time: clock(option.packBy!, locale, zone), count: shops })}</p>;
}

function AddressForm({ value, provinces, onChange, errors, locale, onCancel }: {
  value: AddressInput; provinces: readonly string[]; onChange: (v: AddressInput) => void; errors: Record<string, string>; locale: Locale; onCancel?: () => void;
}) {
  const c = useCartT();
  const set = (k: keyof AddressInput) => (e: { target: { value: string } }) => onChange({ ...value, [k]: e.target.value });
  const err = (f: string) => (errors[`address.${f}`] ? localizeServer(errors[`address.${f}`]!, locale) : undefined);
  return (
    <div className="co-address-form">
      <div className="co-form-grid">
        <Field label={c('street')} error={err('street')} className="co-span-2"><TextInput value={value.street ?? ''} onChange={set('street')} autoComplete="address-line1" aria-invalid={!!err('street')} /></Field>
        <Field label={c('unit')} error={err('unit')}><TextInput value={value.unit ?? ''} onChange={set('unit')} autoComplete="address-line2" aria-invalid={!!err('unit')} /></Field>
        <Field label={c('city')} error={err('city')}><TextInput value={value.city ?? ''} onChange={set('city')} autoComplete="address-level2" aria-invalid={!!err('city')} /></Field>
        <Field label={c('province')} error={err('province')}><Select value={value.province ?? ''} onChange={set('province')} options={[...provinces]} autoComplete="address-level1" /></Field>
        <Field label={c('postal')} error={err('postal')}><TextInput value={value.postal ?? ''} onChange={set('postal')} autoComplete="postal-code" aria-invalid={!!err('postal')} /></Field>
        <Field label={c('note')} error={err('note')} className="co-span-2"><TextInput value={value.note ?? ''} onChange={set('note')} placeholder={c('notePlaceholder')} aria-invalid={!!err('note')} /></Field>
      </div>
      {onCancel ? <button type="button" className="btn btn-link co-add" onClick={onCancel}>{c('cancel')}</button> : null}
    </div>
  );
}

export function CheckoutSkeleton() {
  const t = useCheckoutT();
  const c = useCartT();
  return (
    <div className="nl-page co-page" aria-busy="true">
      <span className="nl-sr-only">{c('loading')}</span>
      <div className="co-layout">
        <div className="co-head"><h1 className="co-title">{t('title')}</h1></div>
        <div className="co-main co-skeleton">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} height={i === 0 ? 120 : 80} />)}</div>
        <Skeleton height={280} />
      </div>
    </div>
  );
}
