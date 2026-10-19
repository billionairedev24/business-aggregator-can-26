import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { Icon, MIN_TARGET, colors, fonts, semantic, space } from '@northline/mobile-kit';

import { useI18n } from '../i18n';

export type StepNo = 1 | 2 | 3;

/**
 * One of the checkout's three numbered sections (redesign spec § 1.3 Checkout, the web's /checkout): a 28 pt circle
 * with the number (a tick once done), the title (20 / 700); the open step shows its content, a done step collapses to
 * its summary with "Change", a later one is a muted title. The open step's title is announced as the current step.
 */
export function CheckoutStep({
  n,
  title,
  state,
  summary,
  onChange,
  changeLabel,
  children,
  testID,
}: {
  n: StepNo;
  title: string;
  state: 'open' | 'done' | 'later';
  summary?: ReactNode;
  onChange?: () => void;
  changeLabel?: string;
  children?: ReactNode;
  testID?: string;
}) {
  const { t } = useI18n();
  const label = `${t('rd.checkout.stepOf', { n, total: 3 })}: ${title}, ${t(state === 'done' ? 'rd.checkout.done' : state === 'later' ? 'rd.checkout.later' : 'rd.checkout.current')}`;
  return (
    <View style={[styles.step, state === 'later' && styles.later]} testID={testID ?? `checkout-step-${n}`}>
      <View style={styles.head}>
        <View style={[styles.num, state === 'done' && styles.numDone, state === 'later' && styles.numLater]}>
          {state === 'done' ? <Icon name="check" size={16} color={colors.actionPrimaryText} /> : <Text style={[styles.numText, state === 'later' && styles.numTextLater]}>{n}</Text>}
        </View>
        <Text
          accessibilityRole="header"
          accessibilityLabel={label}
          style={[styles.title, state === 'later' && styles.titleLater]}
        >
          {title}
        </Text>
        {state === 'done' && onChange ? (
          <Pressable accessibilityRole="button" accessibilityLabel={changeLabel ?? `${t('rd.checkout.change')}: ${title}`} onPress={onChange} hitSlop={8} style={styles.change} testID={`checkout-change-${n}`}>
            <Text style={styles.changeText}>{t('rd.checkout.change')}</Text>
          </Pressable>
        ) : null}
      </View>
      {state === 'done' && summary ? <View style={styles.summary}>{summary}</View> : null}
      {state === 'open' ? <View style={styles.body}>{children}</View> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  step: { gap: space[3], paddingBottom: space[4], borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border },
  later: { paddingBottom: space[3] },
  head: { flexDirection: 'row', alignItems: 'center', gap: space[3], minHeight: MIN_TARGET },
  num: { width: 28, height: 28, borderRadius: 14, backgroundColor: colors.actionPrimary, alignItems: 'center', justifyContent: 'center' },
  numDone: { backgroundColor: semantic.success },
  numLater: { backgroundColor: semantic['surface-muted'] },
  numText: { fontFamily: fonts.bold, fontSize: 14, color: colors.actionPrimaryText },
  numTextLater: { color: colors.textMuted },
  title: { flex: 1, fontFamily: fonts.bold, fontSize: 20, lineHeight: 25, color: colors.text },
  titleLater: { color: colors.textMuted },
  change: { minHeight: MIN_TARGET, justifyContent: 'center' },
  changeText: { fontFamily: fonts.semibold, fontSize: 14, color: colors.link, textDecorationLine: 'underline' },
  summary: { paddingLeft: 28 + space[3], gap: 2 },
  body: { gap: space[3] },
});
