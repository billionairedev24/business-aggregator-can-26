import { useEffect, useRef, useState } from 'react';
import { Button } from '@northline/ui';
import { startSetup, useConfirmCard, type Setup } from './settingsApi';
import { useSettingsT } from './settingsMessages';

/**
 * Stripe.js for a SetupIntent (no charge): the Payment Element in Stripe's iframe, `confirmSetup`, then the api keeps
 * the card (brand, last four, expiry — never the number). Loaded only when the api says Stripe is configured.
 */
interface StripeError { message?: string }
interface StripeElement { mount: (el: HTMLElement) => void; destroy: () => void }
interface StripeElements { create: (type: 'payment', options?: Record<string, unknown>) => StripeElement }
interface SetupStripe {
  elements: (options: { clientSecret: string; locale?: string }) => StripeElements;
  confirmSetup: (o: { elements: StripeElements; redirect: 'if_required' }) => Promise<{ error?: StripeError; setupIntent?: { id: string; status: string } }>;
}

async function loadStripe(key: string): Promise<SetupStripe> {
  const w = window as unknown as { Stripe?: (key: string) => SetupStripe };
  if (!w.Stripe) {
    await new Promise<void>((resolve, reject) => {
      const s = document.createElement('script');
      s.src = 'https://js.stripe.com/v3';
      s.onload = () => resolve();
      s.onerror = () => reject(new Error('Stripe.js failed to load'));
      document.head.appendChild(s);
    });
  }
  return w.Stripe!(key);
}

/** "Add payment method" → the card form; `onSaved` once the card is listed. */
export function CardForm({ onSaved }: { onSaved: () => void }) {
  const t = useSettingsT();
  const [setup, setSetup] = useState<Setup>();
  const [error, setError] = useState<string>();
  useEffect(() => {
    let cancelled = false;
    startSetup().then(s => { if (!cancelled) setSetup(s); }).catch(() => { if (!cancelled) setError(t('cardError')); });
    return () => { cancelled = true; };
  }, [t]);
  return (
    <div className="nl-card-form">
      <div className="nl-card-kicker">{setup?.provider === 'fake' ? t('fakeKicker') : t('setupKicker')}</div>
      {setup?.provider === 'stripe' && setup.publishableKey && setup.clientSecret
        ? <StripeSetup setup={setup} onSaved={onSaved} onError={setError} />
        : <FakeSetup setup={setup} onSaved={onSaved} onError={setError} />}
      {error ? <p className="nl-error" role="alert">{error}</p> : null}
    </div>
  );
}

function StripeSetup({ setup, onSaved, onError }: { setup: Setup; onSaved: () => void; onError: (m: string) => void }) {
  const t = useSettingsT();
  const host = useRef<HTMLDivElement>(null);
  const [stripe, setStripe] = useState<{ js: SetupStripe; elements: StripeElements }>();
  const confirm = useConfirmCard();
  useEffect(() => {
    let element: StripeElement | undefined;
    let cancelled = false;
    void loadStripe(setup.publishableKey!).then(js => {
      if (cancelled || !host.current) return;
      const elements = js.elements({ clientSecret: setup.clientSecret!, locale: document.documentElement.lang.startsWith('fr') ? 'fr-CA' : 'en-CA' });
      element = elements.create('payment', { layout: 'tabs' });
      element.mount(host.current);
      setStripe({ js, elements });
    }).catch(() => onError(t('cardError')));
    return () => { cancelled = true; element?.destroy(); };
  }, [setup.clientSecret]); // eslint-disable-line react-hooks/exhaustive-deps
  const save = async () => {
    if (!stripe) return;
    const result = await stripe.js.confirmSetup({ elements: stripe.elements, redirect: 'if_required' });
    if (result.error) { onError(result.error.message ?? t('cardError')); return; }
    confirm.mutate(setup.setupIntentId, { onSuccess: onSaved, onError: () => onError(t('cardError')) });
  };
  return (
    <>
      <div ref={host} className="nl-card-element" />
      <Button type="button" className="nl-card-save" disabled={!stripe || confirm.isPending} aria-busy={confirm.isPending} onClick={() => void save()}>{t('saveCard')}</Button>
    </>
  );
}

/** Without Stripe (local): the design's card form as a read-only stand-in; nothing is typed, nothing is collected. */
function FakeSetup({ setup, onSaved, onError }: { setup?: Setup; onSaved: () => void; onError: (m: string) => void }) {
  const t = useSettingsT();
  const confirm = useConfirmCard();
  return (
    <>
      <p className="nl-small nl-muted">{t('fakeNote')}</p>
      <dl className="nl-card-fake" aria-hidden>
        <div><dt>{t('cardNumber')}</dt><dd>4242 4242 4242 4242</dd></div>
        <div className="nl-card-fake-row"><div><dt>{t('expiry')}</dt><dd>12 / 29</dd></div><div><dt>{t('cvc')}</dt><dd>•••</dd></div></div>
      </dl>
      <Button type="button" className="nl-card-save" disabled={!setup || confirm.isPending} aria-busy={confirm.isPending}
        onClick={() => setup && confirm.mutate(setup.setupIntentId, { onSuccess: onSaved, onError: () => onError(t('cardError')) })}>{t('saveCard')}</Button>
    </>
  );
}
