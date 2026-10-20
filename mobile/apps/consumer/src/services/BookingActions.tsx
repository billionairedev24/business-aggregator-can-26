import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { randomId, space } from '@northline/mobile-kit';

import { percent } from '../account/common';
import type { Booking } from '../api/services';
import { config } from '../config';
import { useI18n, type MessageKey } from '../i18n';
import { hire, useOpenThread } from '../messages/Messages';
import { Body, Button, Notice, type } from '../ui/primitives';
import { errorMessage, Skeleton } from '../ui/states';
import { money } from './format';
import { Panel } from './parts';

const CANCELLABLE: ReadonlySet<string> = new Set(['requested', 'confirmed', 'en_route']);

/**
 * S-153 on the day-of screen: the late-cancellation terms the booking was made under, "Message …" (the booking's
 * thread with the provider), "Cancel booking" (what it costs is asked first — `GET /me/bookings/{id}/cancellation`; the
 * late fee is kept from the hold, the rest released) and a deposit quote's balance (held automatically before the
 * visit; when that failed, it is paid on the web, where the card sheet is).
 */
export function BookingActions({ b }: { b: Booking }) {
  const { t, locale, day, time } = useI18n();
  const at = (iso: string) => `${day(iso, b.timeZone)} ${time(iso, b.timeZone)}`;
  const qc = useQueryClient();
  const message = useOpenThread();
  const [asking, setAsking] = useState(false);
  const key = useRef(randomId());
  const terms = useQuery({ queryKey: ['hire', 'cancellation', b.bookingId], queryFn: () => hire().cancellation(b.bookingId), enabled: asking, staleTime: 0 });
  const cancel = useMutation({
    mutationFn: () => hire().cancel(b.bookingId, key.current),
    onSuccess: () => {
      setAsking(false);
      void qc.invalidateQueries({ queryKey: ['services', 'booking', b.bookingId] });
      void qc.invalidateQueries({ queryKey: ['account'] });
    },
  });
  const who = b.memberFirstName ?? b.providerName;
  const p = b.cancellation;
  const fee = p ? (p.feeCents > 0 ? money(p.feeCents, locale) : p.feeBps > 0 ? percent(p.feeBps, locale) : null) : null;
  const bal = b.balance && b.balance.state !== 'none' ? b.balance : null;
  const balAmount = bal ? money(bal.cents + bal.taxCents, locale) : '';

  return (
    <View style={styles.stack} testID="booking-actions">
      {b.cancelled ? (
        <Notice
          tone="info"
          message={b.cancelled.feeCents > 0 ? t('hire.booking.cancelledFee', { time: at(b.cancelled.at), fee: money(b.cancelled.feeCents + b.cancelled.feeTaxCents, locale) }) : t('hire.booking.cancelled', { time: at(b.cancelled.at) })}
          testID="booking-cancelled"
        />
      ) : p && CANCELLABLE.has(b.state) ? (
        <Body tone="small">
          {p.windowHours > 0 && fee ? t('hire.booking.terms', { hours: p.windowHours, fee }) : t('hire.booking.freeAlways')}
        </Body>
      ) : null}

      {bal ? (
        <Panel testID="booking-balance">
          <Text style={[type.body, type.strong]}>{t('hire.booking.balance')}</Text>
          <Text style={type.small}>
            {bal.state === 'pending'
              ? bal.attempts > 0 && bal.nextAt
                ? t('hire.booking.balance_retry', { amount: balAmount, time: at(bal.nextAt) })
                : t('hire.booking.balance_pending', { amount: balAmount, time: bal.dueAt ? at(bal.dueAt) : '' })
              : t(`hire.booking.balance_${bal.state}` as MessageKey, { amount: balAmount })}
          </Text>
          {bal.state === 'failed' || (bal.state === 'pending' && bal.attempts > 0) ? (
            <Button
              label={t('hire.booking.payOnWeb', { amount: balAmount })}
              tone="secondary"
              hint={t('common.opensInBrowser')}
              onPress={() => void Linking.openURL(`${config.siteOrigin}/bookings/${encodeURIComponent(b.bookingId)}`)}
              testID="pay-balance"
            />
          ) : null}
        </Panel>
      ) : null}

      {message.isError ? <Notice message={t('hire.messages.openError')} /> : null}
      {b.state !== 'cancelled' ? (
        <Button label={t('hire.booking.message', { name: who })} tone="secondary" busy={message.isPending} onPress={() => message.mutate({ refType: 'booking', refId: b.bookingId })} testID="booking-message" />
      ) : null}

      {CANCELLABLE.has(b.state) && !b.cancelled ? (
        asking ? (
          <Panel testID="cancel-terms">
            <Text style={[type.body, type.strong]}>{t('hire.booking.cancelTitle')}</Text>
            {terms.data ? (
              <>
                <Text style={type.body}>
                  {!terms.data.cancellable
                    ? t('hire.booking.cannotCancel')
                    : terms.data.late
                      ? t('hire.booking.cancelLate', { hours: terms.data.windowHours, fee: money(terms.data.feeTotalCents, locale), released: money(terms.data.releasedCents, locale) })
                      : t('hire.booking.cancelFree', { amount: money(terms.data.releasedCents, locale) })}
                </Text>
                {cancel.isError ? <Notice message={errorMessage(cancel.error, t)} /> : null}
                {terms.data.cancellable ? (
                  <Button label={t('hire.booking.cancelConfirm')} tone="danger" busy={cancel.isPending} onPress={() => cancel.mutate()} testID="cancel-confirm" />
                ) : null}
              </>
            ) : terms.isError ? (
              <Notice message={errorMessage(terms.error, t)} />
            ) : (
              <Skeleton height={40} />
            )}
            <Button label={t('hire.booking.cancelKeep')} tone="ghost" disabled={cancel.isPending} onPress={() => setAsking(false)} testID="cancel-keep" />
          </Panel>
        ) : (
          <Button label={t('hire.booking.cancel')} tone="ghost" onPress={() => setAsking(true)} testID="booking-cancel" />
        )
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  stack: { gap: space[2] },
});
