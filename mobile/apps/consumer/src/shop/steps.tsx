import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { CAP, LINE, MIN_TARGET, SIZE, radius, semantic, space, textStyles } from '@northline/mobile-kit';

import { useI18n } from '../i18n';

/** The one-page checkout's sections (design/revamp/a.html "Cart and one-page checkout"). */
export const CHECKOUT_SECTIONS = 6;

/**
 * One numbered section of the one-page checkout (E-15 Plainsight): the number in a solid ink square (a 3 pt corner,
 * never a disc), the title (18 / 700) and an optional "Change" link; its content under it; a hairline between
 * sections. The title is read as "Step n of 6: …".
 */
export function CheckoutSection({ n, title, onChange, changeLabel, children, testID }: { n: number; title: string; onChange?: () => void; changeLabel?: string; children?: ReactNode; testID?: string }) {
  const { t } = useI18n();
  return (
    <View style={[styles.section, n > 1 && styles.rule]} testID={testID ?? `checkout-step-${n}`}>
      <View style={styles.head}>
        <View style={styles.num} accessible={false}>
          <Text style={styles.numText} maxFontSizeMultiplier={CAP.badge}>
            {n}
          </Text>
        </View>
        <Text accessibilityRole="header" accessibilityLabel={`${t('rd.checkout.stepOf', { n, total: CHECKOUT_SECTIONS })}: ${title}`} style={styles.title}>
          {title}
        </Text>
        {onChange ? (
          <Pressable accessibilityRole="button" accessibilityLabel={changeLabel ?? `${t('rd.checkout.change')}: ${title}`} onPress={onChange} style={styles.change} testID={`checkout-change-${n}`}>
            <Text style={[textStyles.label, textStyles.link]}>{t('rd.checkout.change')}</Text>
          </Pressable>
        ) : null}
      </View>
      <View style={styles.body}>{children}</View>
    </View>
  );
}

const NUM = SIZE['control-sm'] - space[1];

const styles = StyleSheet.create({
  section: { gap: space[3], paddingVertical: space[4] },
  rule: { borderTopWidth: LINE.hair, borderTopColor: semantic.border },
  head: { flexDirection: 'row', alignItems: 'center', gap: space[3], minHeight: MIN_TARGET },
  num: { width: NUM, height: NUM, borderRadius: radius.control, backgroundColor: semantic.selected, alignItems: 'center', justifyContent: 'center' },
  numText: { ...StyleSheet.flatten(textStyles.label), fontFamily: textStyles.section.fontFamily, color: semantic['selected-text'] },
  title: { ...StyleSheet.flatten(textStyles.section), flex: 1 },
  change: { minHeight: MIN_TARGET, justifyContent: 'center' },
  body: { gap: space[3] },
});
