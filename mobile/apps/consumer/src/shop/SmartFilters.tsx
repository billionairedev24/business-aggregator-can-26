import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { Icon, MIN_TARGET, colors, fonts, radius, space } from '@northline/mobile-kit';

import { Button } from '../ui/primitives';
import { shop, useShopFormat } from './common';
import { chipLabel, looksNatural, proposals, type Proposal } from './smartSearch';

/** Removable chips (the suggestions, or the applied filters): × on each, at least 44 pt. */
export function ProposalChips({ chips, onRemove, testID }: { chips: Proposal[]; onRemove: (c: Proposal) => void; testID: string }) {
  const f = useShopFormat();
  return (
    <View style={styles.chips} testID={testID}>
      {chips.map((c) => {
        const label = chipLabel(c, f.t, f.money);
        return (
          <View key={c.id} style={styles.chip}>
            <Text style={styles.chipText}>{label}</Text>
            <Pressable accessibilityRole="button" accessibilityLabel={f.t('ai.search.remove', { chip: label })} onPress={() => onRemove(c)} style={styles.x} testID={`chip-remove-${c.id}`}>
              <Icon name="x" size={14} />
            </Pressable>
          </View>
        );
      })}
    </View>
  );
}

/**
 * S-158 natural-language search (S-132's `POST /search/interpret`): a typed sentence gets the AI's suggested filters
 * as removable chips under "Searching for: …". Nothing is applied until the person taps Apply; the results meanwhile
 * are those of the words. Hidden when AI is off (`/ai/status`); a failure (503, 429) says so in one line.
 */
export function SmartFilters({ text, located, onApply }: { text: string; located: boolean; onApply: (chips: Proposal[]) => void }) {
  const f = useShopFormat();
  const { t, locale } = f;
  const [dismissed, setDismissed] = useState<string | null>(null);
  const wanted = looksNatural(text) && dismissed !== text;
  const status = useQuery({ queryKey: ['ai', 'status'], queryFn: () => shop().aiStatus(), enabled: wanted, staleTime: 300_000 });
  const reading = useQuery({
    queryKey: ['search', 'interpret', text.toLowerCase(), located, locale],
    queryFn: () => shop().interpret(text, located, locale),
    enabled: wanted && status.data?.available === true,
    staleTime: 600_000,
    retry: false,
  });
  const [chips, setChips] = useState<Proposal[]>([]);
  useEffect(() => setChips(reading.data ? proposals(reading.data) : []), [reading.data]);
  if (!wanted || status.data?.available !== true) return null;
  if (reading.isPending) return <Text style={styles.quiet}>{t('ai.search.thinking')}</Text>;
  if (reading.isError || !reading.data) return <Text style={styles.quiet} accessibilityLiveRegion="polite" testID="smart-unavailable">{t('ai.search.unavailable')}</Text>;
  if (chips.length === 0) return null;
  return (
    <View style={styles.box} accessibilityLabel={t('ai.search.label')} testID="smart-filters">
      <View style={styles.head}>
        <Icon name="sparkle" size={18} />
        <Text style={styles.headText} accessibilityLiveRegion="polite">{t('ai.search.understood', { explanation: reading.data.explanation })}</Text>
      </View>
      <ProposalChips chips={chips} onRemove={(c) => setChips((list) => list.filter((x) => x.id !== c.id))} testID="smart-chips" />
      <Text style={styles.quiet}>{t('ai.search.hint')}</Text>
      <View style={styles.actions}>
        <Button label={t('ai.search.apply')} onPress={() => onApply(chips)} testID="smart-apply" />
        <Button label={t('ai.search.dismiss')} tone="secondary" onPress={() => setDismissed(text)} testID="smart-dismiss" />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: space[2], padding: space[3], borderRadius: radius.lg, borderWidth: StyleSheet.hairlineWidth, borderColor: colors.border, backgroundColor: colors.surface },
  head: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  headText: { flex: 1, fontFamily: fonts.semibold, fontSize: 15, color: colors.text },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2] },
  chip: { flexDirection: 'row', alignItems: 'center', paddingLeft: space[3], borderRadius: radius.pill, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.canvas },
  chipText: { fontFamily: fonts.medium, fontSize: 14, color: colors.text },
  x: { minWidth: MIN_TARGET, minHeight: MIN_TARGET, alignItems: 'center', justifyContent: 'center' },
  quiet: { fontFamily: fonts.regular, fontSize: 13, color: colors.textMuted },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2] },
});
