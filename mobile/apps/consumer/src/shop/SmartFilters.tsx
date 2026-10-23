import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { FilterChip, Icon, LINE, TYPE, radius, semantic, space, textStyles } from '@northline/mobile-kit';

import { Button } from '../ui/primitives';
import { shop, useShopFormat } from './common';
import { chipLabel, looksNatural, proposals, type Proposal } from './smartSearch';

/** Removable chips (the suggestions, or the applied filters): the kit's applied FilterChip — ink edge, ✓ and ×, one 44 pt target named "Remove filter: …". */
export function ProposalChips({ chips, onRemove, testID }: { chips: Proposal[]; onRemove: (c: Proposal) => void; testID: string }) {
  const f = useShopFormat();
  return (
    <View style={styles.chips} testID={testID}>
      {chips.map((c) => (
        <FilterChip key={c.id} label={chipLabel(c, f.t, f.money)} onRemove={() => onRemove(c)} testID={`chip-remove-${c.id}`} />
      ))}
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
  // removed chips, per reading: a new reading starts with all of its chips again
  const [removed, setRemoved] = useState<{ of: unknown; ids: string[] }>({ of: null, ids: [] });
  const gone = removed.of === reading.data ? removed.ids : [];
  const chips = reading.data ? proposals(reading.data).filter((c) => !gone.includes(c.id)) : [];
  if (!wanted || status.data?.available !== true) return null;
  if (reading.isPending) return <Text style={styles.quiet}>{t('ai.search.thinking')}</Text>;
  if (reading.isError || !reading.data) return <Text style={styles.quiet} accessibilityLiveRegion="polite" testID="smart-unavailable">{t('ai.search.unavailable')}</Text>;
  if (chips.length === 0) return null;
  return (
    <View style={styles.box} accessibilityLabel={t('ai.search.label')} testID="smart-filters">
      <View style={styles.head}>
        <Icon name="sparkle" size={TYPE.price} />
        <Text style={styles.headText} accessibilityLiveRegion="polite">{t('ai.search.understood', { explanation: reading.data.explanation })}</Text>
      </View>
      <ProposalChips chips={chips} onRemove={(c) => setRemoved({ of: reading.data, ids: [...gone, c.id] })} testID="smart-chips" />
      <Text style={styles.quiet}>{t('ai.search.hint')}</Text>
      <View style={styles.actions}>
        <Button label={t('ai.search.apply')} onPress={() => onApply(chips)} testID="smart-apply" />
        <Button label={t('ai.search.dismiss')} tone="secondary" onPress={() => setDismissed(text)} testID="smart-dismiss" />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: space[2], padding: space[3], borderRadius: radius.panel, borderWidth: LINE.hair, borderColor: semantic['border-strong'], backgroundColor: semantic.surface },
  head: { flexDirection: 'row', alignItems: 'center', gap: space[2] },
  headText: { ...StyleSheet.flatten(textStyles.bodyStrong), flex: 1 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2] },
  quiet: textStyles.meta,
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2] },
});
