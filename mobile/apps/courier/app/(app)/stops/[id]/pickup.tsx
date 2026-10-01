import { useLocalSearchParams, useRouter } from 'expo-router';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Switch, View, StyleSheet } from 'react-native';

import { colors, space } from '@northline/mobile-kit';

import { Banner, Body, Button, Card, Heading, Screen } from '../../../../src/components/ui';
import { useRun } from '../../../../src/hooks';
import { useI18n } from '../../../../src/i18n';
import { RUN_KEY, services } from '../../../../src/services';
import { stopName } from '../../../../src/stops';

/**
 * Pickup confirm: the courier confirms the sealed, labelled bag (recorded as the scan). The shop must have packed: the
 * api refuses otherwise (409 not_packed, shown from the outbox) — the run read here may be older than the packing.
 * Saved on the phone first; sent when there is a connection.
 */
export default function PickupScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { t } = useI18n();
  const router = useRouter();
  const { run } = useRun();
  const stop = run?.stops.find((s) => s.id === id);
  const [sealed, setSealed] = useState(false);
  const qc = useQueryClient();
  // the shop may have packed since the run was read
  useEffect(() => void qc.invalidateQueries({ queryKey: RUN_KEY }), [qc]);

  if (!stop || stop.kind !== 'pickup') {
    return (
      <Screen>
        <Banner tone="warn">{t('stop.notFound')}</Banner>
      </Screen>
    );
  }

  const confirm = async () => {
    await services().outbox.enqueue({ kind: 'pickup', stopId: stop.id, scanOk: sealed });
    router.dismissTo('/run');
  };

  return (
    <Screen testID="pickup-screen">
      <Heading>{stopName(stop)}</Heading>
      {!stop.packed ? <Banner tone="warn">{t('pickup.waitPacked')}</Banner> : null}
      <Card>
        <View style={styles.switchRow}>
          <Body style={styles.label}>{t('pickup.sealed', { ref: stop.orderRef ?? '' })}</Body>
          <Switch
            value={sealed}
            onValueChange={setSealed}
            accessibilityLabel={t('pickup.sealed', { ref: stop.orderRef ?? '' })}
            trackColor={{ true: colors.accent, false: colors.neutral300 }}
            testID="sealed"
          />
        </View>
      </Card>
      <Button label={t('pickup.confirm')} disabled={!sealed} onPress={() => void confirm()} testID="confirm-pickup" />
    </Screen>
  );
}

const styles = StyleSheet.create({
  switchRow: { flexDirection: 'row', alignItems: 'center', gap: space[3], minHeight: 48 },
  label: { flex: 1 },
});
