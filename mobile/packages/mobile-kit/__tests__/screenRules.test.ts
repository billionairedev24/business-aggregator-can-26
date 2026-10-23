import { screenRuleHits } from '../src/ui/kit/rules';
import { sourceFor } from '../src/ui/Photo';

/** S-178/S-179: the screens' rules catch what the revamp removed, and leave the kit's own idioms alone. */
describe('screen rules', () => {
  it.each([
    ['raw font size', 'title: { fontSize: 28, color },'],
    ['raw line height', 'body: { lineHeight: 21 },'],
    ['the retired display face', 'h: { fontFamily: fonts.display },'],
    ['raw spacing', 'row: { gap: 6 },'],
    ['raw corner radius', 'card: { borderRadius: 12 },'],
    ['an older radius name', 'card: { borderRadius: radius.lg },'],
    ['the retired linen', "panel: { backgroundColor: semantic['surface-sunken'] },"],
    ['a drawn scene', '<Scene slot="hero/courier" subject="courier" />'],
    ['a price formatted by hand', "new Intl.NumberFormat(tag, { style: 'currency', currency: 'CAD' })"],
    ['raw colour', "color: '#1747C9',"],
  ])('%s', (rule, line) => {
    expect(screenRuleHits('x.tsx', line).join('\n')).toContain(rule);
  });

  it('refuses the native switch, even imported over several lines', () => {
    expect(screenRuleHits('x.tsx', "import {\n  Pressable,\n  Switch,\n} from 'react-native';")).toEqual(['the native switch (use the kit’s Switch) in x.tsx']);
  });

  it('allows tokens, 0–2 pt nudges, zero corners and comments', () => {
    const ok = [
      'row: { gap: space[2], paddingTop: LINE.bold, marginTop: -space[3] },',
      'title: { ...StyleSheet.flatten(textStyles.pageTitle) },',
      'well: { borderRadius: 0 },',
      'nudge: { paddingVertical: 2 },',
      '// the old card used borderRadius: 12 and fontSize: 15',
      "import { Switch } from '@northline/mobile-kit';",
    ].join('\n');
    expect(screenRuleHits('x.tsx', ok)).toEqual([]);
  });
});

describe('a photo on the web (react-native-web takes one source)', () => {
  const sources = [{ width: 960 }, { width: 320 }, { width: 640 }];
  it('picks the narrowest variant at least as wide as the view in device pixels', () => {
    expect(sourceFor(sources, 150, 2)).toEqual({ width: 320 });
    expect(sourceFor(sources, 300, 2)).toEqual({ width: 640 });
  });
  it('takes the widest when none is wide enough', () => {
    expect(sourceFor(sources, 800, 2)).toEqual({ width: 960 });
  });
});
