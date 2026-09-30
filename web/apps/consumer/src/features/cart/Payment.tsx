import { useEffect, useRef, useState } from 'react';
import { useFormatters } from '@northline/ui';
import type { Started } from './api';
import { useCartT } from './messages';

/**
 * Stripe.js, loaded only when the api says Stripe is configured (never under the local profile). Card data goes to
 * Stripe's iframe only (SAQ-A): Northline sees PaymentIntent ids and a PaymentMethod id.
 */
interface StripeError { message?: string }
interface StripeElement { mount: (el: HTMLElement) => void; destroy: () => void }
interface StripeElements { create: (type: 'payment', options?: Record<string, unknown>) => StripeElement; submit: () => Promise<{ error?: StripeError }> }
interface ConfirmResult { error?: StripeError; paymentIntent?: { id: string; status: string; payment_method?: string | { id: string } | null } }
export interface StripeJs {
  elements: (options: { clientSecret: string; locale?: string; appearance?: Record<string, unknown> }) => StripeElements;
  confirmPayment: (o: { elements: StripeElements; redirect: 'if_required'; confirmParams?: Record<string, unknown> }) => Promise<ConfirmResult>;
  confirmCardPayment: (clientSecret: string, data: { payment_method: string }) => Promise<ConfirmResult>;
}
declare global { interface Window { Stripe?: (key: string) => StripeJs } }

async function loadStripe(key: string): Promise<StripeJs> {
  if (!window.Stripe) {
    await new Promise<void>((resolve, reject) => {
      const s = document.createElement('script');
      s.src = 'https://js.stripe.com/v3';
      s.onload = () => resolve();
      s.onerror = () => reject(new Error('Stripe.js failed to load'));
      document.head.appendChild(s);
    });
  }
  return window.Stripe!(key);
}

/**
 * The Payment Element for the first PaymentIntent (the card is saved to the customer, `setup_future_usage`), then the
 * order's other PaymentIntents — one per order line and one for the delivery fee (S-11 escrow model) — confirmed with
 * the same PaymentMethod. Stripe shows 3-D Secure itself when a bank asks for it.
 */
export function StripePayment({ started, busy, onPaid, onError }: { started: Started; busy: boolean; onPaid: () => void; onError: (message: string) => void }) {
  const t = useCartT();
  const { money } = useFormatters();
  const host = useRef<HTMLDivElement>(null);
  const [stripe, setStripe] = useState<{ js: StripeJs; elements: StripeElements }>();
  const [working, setWorking] = useState(false);
  const pending = started.intents.filter(i => i.status !== 'authorized' && i.clientSecret);
  const first = pending[0];

  useEffect(() => {
    if (!first || !started.payment.publishableKey || !host.current) return;
    let element: StripeElement | undefined;
    let cancelled = false;
    void loadStripe(started.payment.publishableKey).then(js => {
      if (cancelled || !host.current) return;
      const elements = js.elements({ clientSecret: first.clientSecret!, locale: document.documentElement.lang.startsWith('fr') ? 'fr-CA' : 'en-CA' });
      element = elements.create('payment', { layout: 'tabs' });
      element.mount(host.current);
      setStripe({ js, elements });
    }).catch(() => onError(t('err_generic')));
    return () => { cancelled = true; element?.destroy(); };
  }, [first?.clientSecret]); // eslint-disable-line react-hooks/exhaustive-deps

  const confirm = async () => {
    if (!stripe || !first) return;
    setWorking(true);
    try {
      const submitted = await stripe.elements.submit();
      if (submitted.error) { onError(t('err_card', { message: submitted.error.message ?? '' })); return; }
      const lead = await stripe.js.confirmPayment({ elements: stripe.elements, redirect: 'if_required' });
      if (lead.error) { onError(t('err_card', { message: lead.error.message ?? '' })); return; }
      const pm = lead.paymentIntent?.payment_method;
      const pmId = typeof pm === 'string' ? pm : pm?.id;
      for (const intent of pending.slice(1)) {
        if (!pmId) { onError(t('err_generic')); return; }
        const r = await stripe.js.confirmCardPayment(intent.clientSecret!, { payment_method: pmId });
        if (r.error) { onError(t('err_card', { message: r.error.message ?? '' })); return; }
      }
      onPaid();
    } finally { setWorking(false); }
  };

  return (
    <div className="cart-pay-box">
      <div className="cart-pay-head"><span className="cart-kicker">{t('stripeKicker')}</span><span className="cart-muted">{t('stripeNote')}</span></div>
      <div ref={host} className="cart-stripe" data-testid="stripe-payment-element" />
      <button type="button" className="btn btn-primary cart-pay" disabled={!stripe || working || busy} aria-busy={working || busy} onClick={() => void confirm()}>
        {working || busy ? t('paying') : t('confirm', { total: money(started.totalCents) })}
      </button>
    </div>
  );
}

/**
 * Without Stripe (local profile, the fake gateway) nothing is collected: a read-only stand-in of the card form, and
 * after Pay the design's bank step (BankApproval) places the order. Under Stripe, the Payment Element appears once
 * Pay has opened the payments.
 */
export function FakePayment({ provider }: { provider: 'stripe' | 'fake' }) {
  const t = useCartT();
  if (provider === 'stripe') return <p className="cart-muted">{t('stripeNote')}</p>;
  return (
    <div className="cart-pay-box">
      <div className="cart-pay-head"><span className="cart-kicker">{t('fakeKicker')}</span><span className="cart-muted">{t('fakeNote')}</span></div>
      <dl className="cart-fake-card">
        <div className="cart-span-2"><dt>{t('cardNumber')}</dt><dd>4242 4242 4242 4242</dd></div>
        <div><dt>{t('expiry')}</dt><dd>12 / 30</dd></div>
        <div><dt>{t('cvc')}</dt><dd>123</dd></div>
        <div className="cart-span-2"><dt>{t('country')}</dt><dd>Canada · T2R 0K3</dd></div>
      </dl>
    </div>
  );
}
