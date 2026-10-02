import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { space } from '@northline/mobile-kit';

import type { Card } from '../api/account';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n';
import { Body, Button, Notice, Tag, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, LoadingList, QueryView, SignInPrompt, errorMessage } from '../ui/states';
import { cardSetupFor } from './cardSetup';
import { KEYS, account, useAccountMutation } from './common';

/**
 * Payment methods (design 01 You › "Payment methods", the consumer web's S-59 tab; signed in): the saved cards
 * (`GET /me/payment-methods`, default first), make one the default, remove one, add one with a SetupIntent (no charge;
 * Stripe's PaymentSheet in setup mode, or the api's stand-in — `cardSetup.ts`). Apple Pay / Google Pay aren't offered
 * (S-99: no merchant ids).
 */
/** Journey B's Payment screen keeps its own copy of the list: it reads it again after a change here. */
const SHOP_CARDS = [['shop', 'cards']];

export function Payments() {
  const { t } = useI18n();
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const cards = useQuery({ queryKey: KEYS.cards, queryFn: () => account().cards(), enabled: signedIn, staleTime: 60_000 });
  const makeDefault = useAccountMutation((id: string) => account().defaultCard(id), { set: KEYS.cards, refresh: SHOP_CARDS });
  const remove = useAccountMutation((id: string) => account().removeCard(id), { set: KEYS.cards, refresh: SHOP_CARDS });
  const save = useAccountMutation((setupIntentId: string) => account().saveCard(setupIntentId), { set: KEYS.cards, refresh: SHOP_CARDS });
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  if (!signedIn) {
    return (
      <Screen title={t('account.payments.title')} testID="payments">
        <SignInPrompt message={t('account.payments.signIn')} />
      </Screen>
    );
  }

  const add = async () => {
    setError(null);
    setSaved(false);
    setAdding(true);
    try {
      const setup = await account().startCardSetup();
      const collector = cardSetupFor(setup.provider);
      if (collector) {
        const r = await collector.collect(setup);
        if (r.status === 'cancelled') return;
        if (r.status === 'failed') return setError(r.message ? t('account.payments.cardError', { message: r.message }) : t('account.payments.failed'));
      }
      await save.mutateAsync(setup.setupIntentId);
      setSaved(true);
    } catch (e) {
      setError(errorMessage(e, t));
    } finally {
      setAdding(false);
    }
  };

  const failed = makeDefault.error ?? remove.error;
  return (
    <Screen
      title={t('account.payments.title')}
      testID="payments"
      footer={
        <>
          {error ? <Notice message={error} testID="card-error" /> : null}
          {saved ? <Notice tone="info" message={t('account.payments.saved')} /> : null}
          <Button label={t('account.payments.add')} tone="secondary" busy={adding} onPress={() => void add()} testID="card-add" />
          {cards.data?.provider === 'fake' ? <Body tone="small">{t('account.payments.fake')}</Body> : null}
        </>
      }
    >
      {failed ? <Notice message={errorMessage(failed, t)} /> : null}
      <QueryView
        query={cards}
        skeleton={<LoadingList rows={2} height={56} />}
        isEmpty={(c) => c.items.length === 0}
        empty={<EmptyState message={t('account.payments.empty')} />}
      >
        {(c) => (
          <View accessibilityRole="list">
            {[...c.items].sort((a, b) => Number(b.isDefault) - Number(a.isDefault)).map((card) => (
              <CardRow key={card.id} card={card} busy={makeDefault.isPending || remove.isPending} onDefault={() => makeDefault.mutate(card.id)} onRemove={() => remove.mutate(card.id)} />
            ))}
          </View>
        )}
      </QueryView>
    </Screen>
  );
}

function CardRow({ card, busy, onDefault, onRemove }: { card: Card; busy: boolean; onDefault: () => void; onRemove: () => void }) {
  const { t } = useI18n();
  const name = t('account.card', { brand: card.brand, last4: card.last4 });
  return (
    <View style={styles.card} testID={`card-${card.id}`}>
      <View style={styles.cardHead}>
        <View style={styles.flex}>
          <Text style={[type.body, type.strong]}>{name}</Text>
          <Text style={type.small}>{t('account.payments.expires', { date: `${String(card.expMonth).padStart(2, '0')}/${String(card.expYear % 100).padStart(2, '0')}` })}</Text>
        </View>
        {card.isDefault ? <Tag label={t('account.payments.default')} tone="accent" /> : null}
      </View>
      <View style={styles.actions}>
        {!card.isDefault ? <Button label={t('account.payments.makeDefault')} tone="ghost" disabled={busy} onPress={onDefault} testID={`card-default-${card.id}`} /> : null}
        <Button label={t('account.payments.remove')} tone="danger" disabled={busy} hint={name} onPress={onRemove} testID={`card-remove-${card.id}`} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, minWidth: 0 },
  card: { paddingVertical: space[2], gap: 4 },
  cardHead: { flexDirection: 'row', alignItems: 'center', gap: space[3] },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2] },
});
