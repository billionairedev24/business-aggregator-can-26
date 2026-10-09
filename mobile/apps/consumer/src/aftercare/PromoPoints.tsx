import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { space } from '@northline/mobile-kit';

import { TIP_CHOICES, type TipChoice } from '../api/aftercare';
import { useI18n } from '../i18n';
import { money } from '../services/format';
import { Chip } from '../shop/parts';
import { Body, Button, Checkbox, Field, Notice } from '../ui/primitives';

/**
 * Checkout's promo code and points (mobile gaps part 2), shared by the shop checkout and the booking review — the
 * consumer web's PromoPoints. The code is applied by the next quote: the api checks it and answers 422 on `promoCode`
 * with the reason, already in the app's language. Points are a switch: the api spends what the configured share of the
 * order allows (`northline.points.*`) and says how much.
 */
export function PromoPoints({
  code,
  onCode,
  error,
  discountCents,
  usePoints,
  onUsePoints,
  pointsAvailable,
  pointsCents,
  disabled,
}: {
  code: string | undefined;
  onCode: (code: string | undefined) => void;
  error?: string | null;
  discountCents?: number;
  usePoints: boolean;
  onUsePoints: (on: boolean) => void;
  pointsAvailable?: number;
  pointsCents?: number;
  disabled?: boolean;
}) {
  const { t, locale } = useI18n();
  const [draft, setDraft] = useState(code ?? '');
  const applied = !!code && !error && (discountCents ?? 0) > 0;
  const available = pointsAvailable ?? 0;
  return (
    <View style={styles.stack} testID="promo-points">
      {applied ? (
        <View style={styles.row}>
          <Notice tone="info" message={t('promo.applied', { code: code!, amount: money(discountCents, locale) })} testID="promo-applied" />
          <Button
            label={t('promo.remove')}
            tone="ghost"
            disabled={disabled}
            onPress={() => {
              setDraft('');
              onCode(undefined);
            }}
            testID="promo-remove"
          />
        </View>
      ) : (
        <View style={styles.stack}>
          <Field
            label={t('promo.label')}
            value={draft}
            onChangeText={setDraft}
            autoCapitalize="characters"
            autoCorrect={false}
            autoComplete="off"
            editable={!disabled}
            error={error}
            testID="promo-code"
          />
          <Button label={t('promo.apply')} tone="secondary" disabled={disabled || !draft.trim()} onPress={() => onCode(draft.trim() ? draft.trim().toUpperCase() : undefined)} testID="promo-apply" />
        </View>
      )}
      {available > 0 ? (
        <Checkbox checked={usePoints} onChange={onUsePoints} label={t('promo.points', { points: available })} testID="use-points">
          <Body tone="small">{t('promo.points', { points: available })}</Body>
        </Checkbox>
      ) : null}
      {usePoints && (pointsCents ?? 0) > 0 ? <Body tone="small">{t('promo.pointsUsed', { amount: money(pointsCents, locale) })}</Body> : null}
    </View>
  );
}

export const tipKey = (tip: TipChoice) => `${tip.kind}-${tip.value}`;

/** The courier's tip (as food's, S-57): no tip · $2 · $4 · 15 % · $6 — 100 % to the courier, never taxed. */
export function TipPicker({ value, onChange, choices = TIP_CHOICES }: { value: TipChoice; onChange: (tip: TipChoice) => void; choices?: readonly TipChoice[] }) {
  const { t, locale } = useI18n();
  const label = (tip: TipChoice) => (tip.kind === 'none' ? t('tip.none') : tip.kind === 'percent' ? t('tip.percent', { n: tip.value }) : money(tip.value, locale));
  return (
    <View style={styles.chips} accessibilityRole="radiogroup" accessibilityLabel={t('tip.title')}>
      {choices.map((tip) => (
        <Chip key={tipKey(tip)} role="radio" label={label(tip)} on={tip.kind === value.kind && tip.value === value.value} onPress={() => onChange(tip)} testID={`tip-${tipKey(tip)}`} />
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  stack: { gap: space[2] },
  row: { gap: space[1] },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
});
