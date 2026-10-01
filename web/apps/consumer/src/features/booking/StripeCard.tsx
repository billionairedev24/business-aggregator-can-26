import { useEffect, useRef, useState } from 'react';
import type { StripeJs } from '../cart/Payment';
import { useBookingT } from './messages';

/** Stripe.js from js.stripe.com (SAQ-A: card data only in Stripe's iframe), loaded once. */
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
 * The booking's one manual-capture PaymentIntent (the escrow hold, S-11) confirmed with the Payment Element — only
 * when the api answers `provider: stripe` (S-51's rule). 3-D Secure is Stripe's own sheet.
 */
export function StripeCard({ clientSecret, publishableKey, label, onAuthorized, onError }: {
  clientSecret: string; publishableKey: string; label: string; onAuthorized: () => void; onError: (message: string) => void;
}) {
  const t = useBookingT();
  const host = useRef<HTMLDivElement>(null);
  const [stripe, setStripe] = useState<{ js: StripeJs; elements: ReturnType<StripeJs['elements']> }>();
  const [working, setWorking] = useState(false);

  useEffect(() => {
    let element: { destroy: () => void } | undefined;
    let cancelled = false;
    void loadStripe(publishableKey).then(js => {
      if (cancelled || !host.current) return;
      const elements = js.elements({ clientSecret, locale: document.documentElement.lang.startsWith('fr') ? 'fr-CA' : 'en-CA' });
      const payment = elements.create('payment', { layout: 'tabs' });
      payment.mount(host.current);
      element = payment;
      setStripe({ js, elements });
    }).catch(() => onError(t('genericError')));
    return () => { cancelled = true; element?.destroy(); };
  }, [clientSecret, publishableKey]); // eslint-disable-line react-hooks/exhaustive-deps

  const confirm = async () => {
    if (!stripe) return;
    setWorking(true);
    try {
      const submitted = await stripe.elements.submit();
      if (submitted.error) { onError(t('payError', { message: submitted.error.message ?? '' })); return; }
      const result = await stripe.js.confirmPayment({ elements: stripe.elements, redirect: 'if_required' });
      if (result.error) { onError(t('payError', { message: result.error.message ?? '' })); return; }
      onAuthorized();
    } finally { setWorking(false); }
  };

  return (
    <div className="nl-bk-card">
      <div className="nl-bk-card-head"><span className="nl-bk-kicker">{t('stripeKicker')}</span><span className="nl-bk-muted">{t('stripeNote')}</span></div>
      <div ref={host} data-testid="stripe-payment-element" />
      <button type="button" className="btn btn-primary nl-bk-primary" disabled={!stripe || working} aria-busy={working} onClick={() => void confirm()}>
        {working ? t('paying') : label}
      </button>
    </div>
  );
}
