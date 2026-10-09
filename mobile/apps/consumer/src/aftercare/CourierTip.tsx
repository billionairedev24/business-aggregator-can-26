import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { ApiError, randomId, space } from '@northline/mobile-kit';

import { TIP_CHOICES, type TipChoice } from '../api/aftercare';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n';
import { money } from '../services/format';
import { Panel } from '../shop/parts';
import { cardPaymentsFor } from '../shop/payments';
import { Body, Button, Notice, type } from '../ui/primitives';
import { errorMessage } from '../ui/states';
import { TipPicker } from './PromoPoints';
import { aftercare } from './Review';

const AFTER: readonly TipChoice[] = TIP_CHOICES.filter((c) => c.kind !== 'none');

/**
 * The courier's tip after the delivery (mobile gaps part 2), up to 7 days: `POST /me/orders/{id}/tips` (one
 * Idempotency-Key per tip, kept for retries) opens a PaymentIntent; Stripe's sheet takes the card (the same port as the
 * checkout — the app never sees a card number), or the api's stand-in authorizes it; then `…/confirm`. The tip goes
 * 100 % to the courier and is not taxed. Hidden when the order can't take one (no courier, too late, already tipped).
 */
export function CourierTip({ orderId }: { orderId: string }) {
  const { t, locale } = useI18n();
  const { status } = useAuth();
  const qc = useQueryClient();
  const key = ['aftercare', 'tips', orderId];
  const tips = useQuery({ queryKey: key, queryFn: () => aftercare().tips(orderId), enabled: status === 'signedIn', retry: false });
  const [choice, setChoice] = useState<TipChoice>(AFTER[1]!);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const idem = useRef(randomId());
  const data = tips.data;
  if (!data) return null;
  const sent = data.items.filter((x) => x.source === 'after_delivery' && x.state !== 'canceled' && x.state !== 'pending');
  if (sent.length > 0) {
    return (
      <Panel tone="accent" testID="tip-thanks">
        <Text style={type.body}>{t('tip.after.thanks', { amount: money(sent[0]!.amountCents, locale) })}</Text>
      </Panel>
    );
  }
  if (!data.canTip) return null;

  const send = async () => {
    setBusy(true);
    setMessage(null);
    try {
      const started = await aftercare().startTip(orderId, { kind: choice.kind === 'percent' ? 'percent' : 'amount', value: choice.value }, idem.current);
      if (!started) throw new ApiError(502, undefined, undefined);
      const port = cardPaymentsFor(started.provider);
      if (port && started.tip.clientSecret) {
        const paid = await port.pay(
          {
            checkoutId: started.tip.id, orderId, ref: '', totalCents: started.tip.amountCents, expiresAt: '',
            payment: { provider: started.provider, publishableKey: started.publishableKey },
            intents: [{ paymentIntent: started.tip.id, clientSecret: started.tip.clientSecret, status: started.tip.state, amountCents: started.tip.amountCents }],
          },
          { kind: 'new' },
        );
        if (paid.status !== 'paid') {
          idem.current = randomId();
          return setMessage(paid.status === 'cancelled' ? t('tip.after.cancelled') : (paid.message ?? t('tip.after.failed')));
        }
      }
      await aftercare().confirmTip(orderId, started.tip.id);
      await qc.invalidateQueries({ queryKey: key });
    } catch (e) {
      if (e instanceof ApiError && !e.transient) idem.current = randomId();
      setMessage(e instanceof ApiError && e.errors.length ? e.errors.map((x) => x.message).join(' ') : errorMessage(e, t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <View style={styles.stack} testID="courier-tip">
      <Text accessibilityRole="header" style={[type.body, type.strong]}>
        {data.courierFirstName ? t('tip.after.title', { name: data.courierFirstName }) : t('tip.after.titleNoName')}
      </Text>
      <Body tone="small">{t('tip.after.body')}</Body>
      <TipPicker value={choice} onChange={setChoice} choices={AFTER} />
      {message ? <Notice message={message} testID="tip-error" /> : null}
      <Button label={t('tip.after.send')} tone="secondary" busy={busy} onPress={() => void send()} testID="tip-send" />
    </View>
  );
}

const styles = StyleSheet.create({
  stack: { gap: space[2] },
});
