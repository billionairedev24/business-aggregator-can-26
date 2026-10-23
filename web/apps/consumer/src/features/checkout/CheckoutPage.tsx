import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { LockSimple } from '@phosphor-icons/react';
import { newIdempotencyKey, ValidationError } from '@northline/client';
import { Alert, EmptyState, ErrorState, Field, RadioGroup, Select, SiteLink, Skeleton, TextInput, useFormatters, useLocale, type Locale } from '@northline/ui';
import { MarketZone, useRegions, useZone } from '../location/regions';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import { signInHref, useViewer } from '../session/api';
import { clock, runWhen, weekday, windowRange } from '../shop/format';
import { addressErrors, localizeServer, trimAddress } from '../cart/address';
import {
  cartQuery, placeOrder, problemCode, quoteQuery, setupQuery, startCheckout,
  type AddressInput, type Cart, type CheckoutBody, type DeliveryOption, type Started,
} from '../cart/api';
import { useCartT } from '../cart/messages';
import { FakePayment, useStripePayment } from '../cart/Payment';
import { CheckoutConsent, useCheckoutConsent } from '../consent/CheckoutConsent';
import { StepUpDialog } from '../cart/StepUpDialog';
import { AgeCheck } from '../cart/AgeCheck';
import { ageCleared, NO_AGE } from '../cart/age';
import { useAgeT } from '../cart/ageMessages';
import { PromoPoints, TipPicker, type TipChoice } from '../promotions/PromoPoints';
import { usePromoT } from '../promotions/messages';
import { legalHref } from '../../lib/legal';
import { PhotoWell } from '../art/PhotoWell';
import { useCheckoutT } from './messages';

type Substitution = CheckoutBody['substitution'];
type StepKey = 'delivery' | 'payment' | 'review';
const ORDER: StepKey[] = ['delivery', 'payment', 'review'];

/**
 * The 3-step checkout (redesign § 1.3 "Checkout", AT Shop:502–594; the payment that left the cart, § 3.8): "Secure
 * checkout" over three numbered sections — 1 the delivery address and time (the window, what to do when something's
 * out of stock, the courier's tip, the age check when the cart needs one), 2 the payment (promo code and points, then
 * Stripe's Payment Element, or the local stand-in), 3 the review (each shop's items beside the delivery) — and a sticky
 * summary with "Place your order". A finished step folds to one line with "Change". The same APIs as before: the
 * setup and the live quote, `POST /me/checkouts` when the payment step opens (it fixes the total; the step-up and
 * Idempotency-Key rules are unchanged), the card authorized on Place, then `…/place`. Signed in only.
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
  return <MarketZone city={city}><Checkout cart={cart.data!} /></MarketZone>;
}

function Checkout({ cart }: { cart: Cart }) {
  const t = useCheckoutT();
  const c = useCartT();
  const tp = usePromoT();
  const tAge = useAgeT();
  const { locale } = useLocale();
  const { money } = useFormatters();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { location } = useDeliveryLocation();
  // the visitor's market; none known = the api's fallback market (region configuration)
  const market = location.status === 'locating' ? undefined : location.city ?? undefined;
  const regions = useRegions();
  const provinceCodes = regions?.provinces.map(p => p.code) ?? [];
  const setup = useQuery(setupQuery(market, locale, true));

  const [step, setStep] = useState<StepKey>('delivery');
  const [optionId, setOptionId] = useState<string>();
  const [substitution, setSubstitution] = useState<Substitution>('similar');
  const [addressId, setAddressId] = useState<string>();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<AddressInput>({ province: location.status !== 'locating' ? location.province : undefined });
  useEffect(() => { if (!draft.province && regions?.defaultProvince) setDraft(d => ({ ...d, province: d.province ?? regions.defaultProvince ?? undefined })); }, [draft.province, regions?.defaultProvince]);
  const [touched, setTouched] = useState(false);
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  // once placed, the cart is empty: nothing may ask for a quote again (it answered 409 after a successful order)
  const [placed, setPlaced] = useState(false);
  const [started, setStarted] = useState<Started | null>(null);
  const [stepUp, setStepUp] = useState<'required' | 'enrol' | null>(null);
  const key = useRef<string>(newIdempotencyKey());
  const [promoCode, setPromoCode] = useState<string>();
  const [usePoints, setUsePoints] = useState(false);
  const [tip, setTip] = useState<TipChoice>({ kind: 'none', value: 0 });
  const pay = useStripePayment(started, m => setError(m));
  const consent = useCheckoutConsent(); // S-157: the optional offers box (CASL)
  const headings = useRef<Partial<Record<StepKey, HTMLHeadingElement | null>>>({});
  const moved = useRef(false);

  const data = setup.data;
  useEffect(() => {
    if (!data) return;
    setOptionId(id => id && data.options.some(o => o.id === id) ? id : data.options[0]?.id);
    const saved = data.addresses.find(a => a.isDefault) ?? data.addresses[0];
    setAddressId(id => id ?? saved?.id);
    if (!saved) setEditing(true);
    setDraft(d => (d.city ? d : { ...d, city: location.city ?? data.market }));
  }, [data]); // eslint-disable-line react-hooks/exhaustive-deps
  // moving to a step puts the keyboard and the screen reader on its heading
  useEffect(() => { if (moved.current) headings.current[step]?.focus(); moved.current = true; }, [step]);

  const option = data?.options.find(o => o.id === optionId);
  const address: AddressInput = !editing && addressId ? { addressId } : trimAddress(draft);
  const clientErrors = addressErrors(address, provinceCodes);
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

  /** A changed delivery or code needs a new checkout: the one opened is left to expire (its total is fixed). */
  const reopen = () => { if (started) { setStarted(null); key.current = newIdempotencyKey(); } };
  const go = (next: StepKey) => { setError(undefined); setStep(next); };

  const fail = (e: unknown) => {
    if (e instanceof ValidationError) {
      setServerErrors(Object.fromEntries(Object.entries(e.byField()).map(([f, m]) => [f, localizeServer(m, locale)])));
      setError(c('fixFields', { count: e.errors.length }));
      if (e.errors.some(x => x.field.startsWith('address') || x.field === 'items')) setStep('delivery');
      return;
    }
    const code = problemCode(e);
    if (code === 'age_verification_required' || code === 'age_under_minimum' || code === 'restricted_hours') {
      setError(code === 'restricted_hours' ? c('err_restricted_hours') : code === 'age_under_minimum' ? tAge('underAge', { age: age.minimumAge }) : tAge('payBlocked'));
      refreshAge();
      setStep('delivery');
      return;
    }
    setError(code && ['out_of_stock', 'window_closed', 'cart_empty', 'checkout_expired', 'payment_not_authorized'].includes(code)
      ? c(`err_${code as 'out_of_stock'}`) : c('err_generic'));
    if (code === 'out_of_stock' || code === 'cart_empty' || code === 'window_closed' || code === 'checkout_expired') {
      void qc.invalidateQueries({ queryKey: cartQuery.queryKey });
      void qc.invalidateQueries({ queryKey: ['checkout'] });
      setStarted(null);
      setStep(code === 'window_closed' ? 'delivery' : 'payment');
    }
  };

  const continueDelivery = () => {
    setTouched(true);
    setServerErrors({});
    if (!complete) { setError(c('fixFields', { count: Math.max(1, Object.keys(clientErrors).length) })); return; }
    go('payment');
  };

  /** Opens the payments (`POST /me/checkouts`, step-up when asked); the local stand-in goes straight to the review. */
  const openPayment = async (proof?: string) => {
    setError(undefined);
    setServerErrors({});
    setBusy(true);
    try {
      const s = await startCheckout(body, key.current, locale, proof);
      setStarted(s);
      if (s.payment.provider !== 'stripe' || s.intents.every(i => i.status === 'authorized')) go('review');
    } catch (e) {
      const code = problemCode(e);
      if (code === 'step_up_required' || code === 'second_factor_required') setStepUp(code === 'step_up_required' ? 'required' : 'enrol');
      else { fail(e); key.current = newIdempotencyKey(); }
    } finally { setBusy(false); }
  };

  const useMethod = async () => {
    setError(undefined);
    if (await pay.check()) go('review');
  };

  const place = async () => {
    if (!started) return;
    setBusy(true);
    setError(undefined);
    try {
      if (pay.needed && !(await pay.confirm())) return;
      const order = await placeOrder(started.checkoutId, newIdempotencyKey());
      key.current = newIdempotencyKey();
      setPlaced(true);
      await consent.record(); // S-157: the ticked offers box, once the order went through
      await qc.cancelQueries({ queryKey: ['checkout'] });
      await navigate({ to: '/checkout/complete', search: { order: order.orderId, ref: order.ref } });
      // after leaving: the cart is refetched (now empty) and the next checkout starts from a fresh set-up and quote
      qc.removeQueries({ queryKey: ['checkout'] });
      void qc.invalidateQueries({ queryKey: cartQuery.queryKey });
    } catch (e) {
      fail(e);
      key.current = newIdempotencyKey();
    } finally { setBusy(false); }
  };

  if (setup.isPending) return <CheckoutSkeleton />;
  if (setup.isError || !data) return <div className="nl-page co-page"><h1 className="co-title">{t('title')}</h1><ErrorState message={c('loadError')} onRetry={() => void setup.refetch()} /></div>;
  const view = data.cart.itemCount > 0 ? data.cart : cart;
  const state = (k: StepKey): 'open' | 'done' | 'todo' => (k === step ? 'open' : ORDER.indexOf(k) < ORDER.indexOf(step) ? 'done' : 'todo');
  const savedAddress = data.addresses.find(a => a.id === addressId);
  const addressLine = !editing && savedAddress ? oneLine(savedAddress) : oneLine(trimAddress(draft));
  const canPlace = step === 'review' && !!started && !busy;
  const stripe = started?.payment.provider === 'stripe' && pay.needed;

  return (
    <div className="nl-page co-page">
      <div className="co-layout">
        <div className="co-head">
          <h1 className="co-title"><LockSimple size={26} weight="fill" aria-hidden /> {t('title')}</h1>
          <span className="co-head-meta">{t('items', { count: view.itemCount })}</span>
        </div>

        <ol className="co-main" aria-label={t('steps')}>
          <Step n={1} id="co-delivery" title={t('step1')} state={state('delivery')} headingRef={el => { headings.current.delivery = el; }}
            onChange={() => { reopen(); go('delivery'); }} changeLabel={t('changeNamed', { step: t('step1') })}
            summary={<><span>{addressLine}</span><span className="co-muted">{option ? <WindowName option={option} /> : null}</span></>}>
            <section aria-labelledby="co-address" className="co-block">
              <h3 id="co-address" className="co-h3">{c('deliverTo')}</h3>
              {!editing && data.addresses.length > 0 ? (
                <>
                  <RadioGroup className="co-choices" aria-labelledby="co-address">
                    {data.addresses.map(a => (
                      <button key={a.id} type="button" role="radio" aria-checked={a.id === addressId} className="co-choice" onClick={() => setAddressId(a.id)}>
                        <span className="co-radio" aria-hidden />
                        <span className="co-choice-text"><strong>{a.unit ? `${a.street}, ${a.unit}` : a.street}</strong><span className="co-muted">{[`${a.city} ${a.province} ${a.postal}`, a.note].filter(Boolean).join(' · ')}</span></span>
                      </button>
                    ))}
                  </RadioGroup>
                  <button type="button" className="btn btn-link co-add" onClick={() => setEditing(true)}>{t('addAddress')}</button>
                </>
              ) : (
                <AddressForm value={draft} provinces={provinceCodes} onChange={v => { setDraft(v); setServerErrors({}); }} errors={shownErrors} locale={locale}
                  onCancel={data.addresses.length > 0 ? () => setEditing(false) : undefined} />
              )}
              {serverErrors['address.city'] && !editing ? <p className="cart-field-error" role="alert">{serverErrors['address.city']}</p> : null}
              {serverErrors.items ? <p className="cart-field-error" role="alert">{serverErrors.items}</p> : null}
            </section>

            <section aria-labelledby="co-window" className="co-block">
              <h3 id="co-window" className="co-h3">{c('window')}</h3>
              {data.options.length === 0 ? <p className="co-muted">{c('noWindow')}</p> : (
                <RadioGroup className="co-options" aria-labelledby="co-window">
                  {data.options.map(o => <WindowOption key={o.id} option={o} selected={o.id === optionId} onPick={() => setOptionId(o.id)} />)}
                </RadioGroup>
              )}
            </section>

            <section aria-labelledby="co-subs" className="co-block">
              <h3 id="co-subs" className="co-h3">{c('substitution')}</h3>
              <RadioGroup className="nl-chips" aria-labelledby="co-subs">
                {(['similar', 'refund', 'ask'] as const).map(s => (
                  <button key={s} type="button" role="radio" aria-checked={substitution === s} className="nl-chip" onClick={() => setSubstitution(s)}>{c(s)}</button>
                ))}
              </RadioGroup>
            </section>

            <section aria-labelledby="co-tip" className="co-block">
              <h3 id="co-tip" className="co-h3">{tp('tipTitle')}</h3>
              <TipPicker value={tip} onChange={setTip} />
            </section>

            <AgeCheck age={age} onChanged={refreshAge} />

            <div className="co-actions">
              <button type="button" className="btn btn-primary" disabled={!ageCleared(age) || data.options.length === 0} onClick={continueDelivery}>{editing ? t('useAddress') : t('continue')}</button>
              {!ageCleared(age) ? <span className="co-muted">{tAge('payBlocked')}</span> : null}
            </div>
          </Step>

          <Step n={2} id="co-payment" title={t('step2')} state={state('payment')} headingRef={el => { headings.current.payment = el; }}
            onChange={() => go('payment')} changeLabel={t('changeNamed', { step: t('step2') })} keepMounted={!!stripe}
            summary={<span>{started?.payment.provider === 'stripe' ? t('methodStripe') : t('methodFake')}</span>}>
            <section aria-labelledby="co-promo" className="co-block">
              <h3 id="co-promo" className="co-h3">{tp('promoTitle')}</h3>
              <PromoPoints code={promoCode} onCode={code => { setPromoCode(code); setServerErrors(({ promoCode: _, ...rest }) => rest); }}
                error={promoError} discountCents={quote.data?.discountCents} usePoints={usePoints} onUsePoints={setUsePoints}
                pointsAvailable={quote.data?.pointsAvailable} pointsCents={quote.data?.pointsCents} disabled={!!started} />
              {started ? <button type="button" className="btn btn-link co-add" onClick={reopen}>{t('changePromo')}</button> : null}
            </section>
            <section aria-labelledby="co-method" className="co-block">
              <h3 id="co-method" className="co-h3">{t('methodTitle')}</h3>
              {stripe ? (
                <div className="cart-pay-box">
                  <div className="cart-pay-head"><span className="cart-kicker">{c('stripeKicker')}</span></div>
                  {!pay.ready ? <p className="co-muted" role="status">{t('paymentLoading')}</p> : null}
                  <div ref={pay.host} className="cart-stripe" data-testid="stripe-payment-element" />
                </div>
              ) : <FakePayment provider={data.payment.provider} />}
              <p className="co-trust"><LockSimple size={16} aria-hidden /> {t('trust')}</p>
            </section>
            {data.stepUp !== 'none' && !started ? <p className="co-muted">{c('stepUpNeeded')}</p> : null}
            <div className="co-actions">
              {!started
                ? <button type="button" className="btn btn-primary" disabled={busy || !complete} aria-busy={busy} onClick={() => void openPayment()}>{busy ? c('paying') : t('toPayment')}</button>
                : stripe
                  ? <button type="button" className="btn btn-primary" disabled={!pay.ready || busy} onClick={() => void useMethod()}>{t('useMethod')}</button>
                  : <button type="button" className="btn btn-primary" onClick={() => go('review')}>{t('useMethod')}</button>}
            </div>
          </Step>

          <Step n={3} id="co-review" title={t('step3')} state={state('review')} headingRef={el => { headings.current.review = el; }}>
            <p className="co-muted">{t('shopCount', { count: view.shopCount })}</p>
            <ul className="co-packages">
              {view.groups.map(g => (
                <li key={g.merchantId || 'gone'} className="co-package">
                  <div className="co-package-items">
                    <h3 className="co-h3">{g.shopName || c('unavailableGroup')}</h3>
                    <ul className="co-items">
                      {g.items.map(i => (
                        <li key={i.itemId} className="co-item">
                          <span className="co-thumb" aria-hidden>{i.imageUrl ? <img src={i.imageUrl} alt="" /> : <PhotoWell subject={i.name} />}</span>
                          <span className="co-item-text"><span>{i.name}</span><span className="co-muted">{[i.option, c('qtyValue', { qty: i.qty })].filter(Boolean).join(' · ')}</span></span>
                          <span className="co-item-price">{money(i.lineCents)}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                  <div className="co-package-delivery">
                    <span className="co-label">{t('deliveryFor', { shop: g.shopName })}</span>
                    {option ? <span className="co-arriving"><WindowName option={option} /></span> : null}
                    <span className="co-muted">{addressLine}</span>
                  </div>
                </li>
              ))}
            </ul>
            <CheckoutConsent consent={consent} />
            <div className="co-actions">
              <button type="button" className="btn btn-primary btn-lg" disabled={!canPlace} aria-busy={busy} onClick={() => void place()}>{busy ? t('placing') : t('place')}</button>
            </div>
          </Step>
        </ol>

        <aside className="co-summary" aria-label={c('total')}>
          <dl className="co-totals">
            <Row label={c('items')} value={money(view.subtotalCents)} />
            <Row label={c('delivery')} value={option ? (option.feeCents === 0 ? c('free') : money(option.feeCents)) : '—'} />
            {view.plusSavingsCents > 0 ? <Row label={tp('plusSavings')} value={`−${money(view.plusSavingsCents)}`} /> : null}
            {quote.data && quote.data.discountCents > 0 ? <Row label={tp('discount', { code: quote.data.promoCode ?? '' })} value={`−${money(quote.data.discountCents)}`} /> : null}
            {/* S-152: points are a discount before tax, like the code */}
            {quote.data && quote.data.pointsCents > 0 ? <Row label={tp('pointsLine')} value={`−${money(quote.data.pointsCents)}`} /> : null}
            {quote.data
              ? quote.data.taxes.map(tax => <Row key={`${tax.type}${tax.percent}`} label={taxLabel(c, tax.type, tax.percent, locale)} value={money(tax.cents)} />)
              : <Row label={c('taxPending')} value={quote.isFetching ? <Skeleton width={48} height={14} /> : <span className="co-muted">{c('taxLater')}</span>} />}
            {quote.data && quote.data.tipCents > 0 ? <Row label={tp('tip')} value={money(quote.data.tipCents)} /> : null}
          </dl>
          <div className="co-total"><span>{c('total')}</span><span className="co-total-amount">{started ? money(started.totalCents) : quote.data ? money(quote.data.totalCents) : '—'}</span></div>
          {error ? <Alert tone="error" role="alert">{error}</Alert> : null}
          <button type="button" className="btn btn-primary btn-block btn-lg" disabled={!canPlace} aria-busy={busy} onClick={() => void place()}>{busy && step === 'review' ? t('placing') : t('place')}</button>
          {step !== 'review' ? <p className="co-fine">{t('placeFirst')}</p> : null}
          <p className="co-fine">{t('terms')} <a href={legalHref('terms', locale)} target="_blank" rel="noopener" hrefLang="en-CA">{t('termsLink')}</a> · <a href={legalHref('privacy', locale)} target="_blank" rel="noopener" hrefLang="en-CA">{t('privacyLink')}</a></p>
          <p className="co-fine">{c('payNote')}</p>
        </aside>
      </div>
      {stepUp ? (
        <StepUpDialog mode={stepUp} onClose={() => setStepUp(null)} onProof={proof => { setStepUp(null); void openPayment(proof); }} />
      ) : null}
    </div>
  );
}

/** A numbered section (28px circle, xl H2); done = one summary line and "Change". */
function Step({ n, id, title, state, summary, onChange, changeLabel, keepMounted, headingRef, children }: {
  n: number; id: string; title: string; state: 'open' | 'done' | 'todo'; summary?: ReactNode; onChange?: () => void; changeLabel?: string;
  keepMounted?: boolean; headingRef: (el: HTMLHeadingElement | null) => void; children: ReactNode;
}) {
  const t = useCheckoutT();
  return (
    <li className={`co-step co-step-${state}`} aria-current={state === 'open' ? 'step' : undefined}>
      <section aria-labelledby={id}>
        <div className="co-step-head">
          <span className="co-num" aria-hidden>{n}</span>
          <h2 id={id} ref={headingRef} tabIndex={-1} className="co-step-title">{title}</h2>
          {state === 'done' && onChange ? <button type="button" className="btn btn-link" aria-label={changeLabel} onClick={onChange}>{t('change')}</button> : null}
        </div>
        {state === 'done' && summary ? <div className="co-step-summary">{summary}</div> : null}
        {/* the same element open or folded, so a kept-mounted body (Stripe's iframe) never remounts */}
        {state === 'open' || (keepMounted && state === 'done') ? <div className="co-step-body" hidden={state !== 'open'}>{children}</div> : null}
      </section>
    </li>
  );
}

const Row = ({ label, value }: { label: ReactNode; value: ReactNode }) => <div className="co-row"><dt>{label}</dt><dd>{value}</dd></div>;

const oneLine = (a: { street?: string; unit?: string | null; city?: string; province?: string; postal?: string }) =>
  [a.unit ? `${a.street}, ${a.unit}` : a.street, [a.city, a.province, a.postal].filter(Boolean).join(' ')].filter(Boolean).join(' · ');

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
  const { money } = useFormatters();
  const desc = option.kind === 'direct' ? c('direct') : option.households > 0 ? c('pooledWith', { count: option.households }) : c('pooled', { time: clock(option.orderBy!, locale, zone) });
  return (
    <button type="button" role="radio" aria-checked={selected} className="co-choice co-option" onClick={onPick}>
      <span className="co-radio" aria-hidden />
      <span className="co-choice-text"><strong><WindowName option={option} /></strong><span className="co-eta">{desc}</span></span>
      <span className="co-option-fee">{option.feeCents === 0 ? c('free') : money(option.feeCents)}</span>
    </button>
  );
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
        <div className="co-main">{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} height={i === 0 ? 260 : 64} radius={8} style={{ marginBottom: 16 }} />)}</div>
        <Skeleton height={280} radius={12} />
      </div>
    </div>
  );
}

