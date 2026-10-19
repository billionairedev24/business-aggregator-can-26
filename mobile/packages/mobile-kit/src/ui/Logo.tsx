import theme from '@northline/tokens/theme.json';
import { StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';
import Svg, { Circle, Path, Rect } from 'react-native-svg';

import { brand, colors, fonts, semantic } from '../theme';

type Shape = { el: string; fill?: string; stroke?: string; [k: string]: unknown };
type Mark = { viewBox: string; shapes: Shape[] };

/** A role name of the mark ("action-primary") → its colour; "none" stays none. */
const role = (name: unknown) => (typeof name === 'string' && name !== 'none' ? ((semantic as Record<string, string>)[name] ?? name) : name === 'none' ? 'none' : undefined);

/** The palette's mark: SVG shapes whose colours are semantic roles (brand.json → palettes/<palette>.json → theme.json). */
export function LogoMark({ size = 30, onDark }: { size?: number; onDark?: boolean }) {
  const mark = ((onDark ? theme.logo.markOnDark : theme.logo.mark) ?? theme.logo.mark) as Mark;
  return (
    <Svg width={size} height={size} viewBox={mark.viewBox} accessible={false} importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
      {mark.shapes.map(({ el, fill, stroke, ...rest }, i) => {
        const common = { fill: role(fill), stroke: role(stroke), ...(rest as Record<string, string | number>) };
        if (el === 'rect') return <Rect key={i} {...common} />;
        if (el === 'circle') return <Circle key={i} {...common} />;
        return <Path key={i} {...(common as unknown as { d: string })} />;
      })}
    </Svg>
  );
}

/**
 * The mark and the wordmark (spec § 1.3 mobile: a 30 px mark beside a 21 px / 800 wordmark): the one place the
 * apps draw the product's name. `portal` adds a muted word after it ("Courier"). The whole logo is one image named
 * after the brand for screen readers.
 */
export function Logo({ size = 30, portal, onDark, style }: { size?: number; portal?: string; onDark?: boolean; style?: StyleProp<ViewStyle> }) {
  const label = portal ? `${brand.name} ${portal}` : brand.name;
  return (
    <View accessible accessibilityRole="image" accessibilityLabel={label} style={[styles.row, style]} testID="logo">
      <LogoMark size={size} onDark={onDark} />
      <Text style={[styles.word, { fontSize: Math.round(size * 0.7), color: onDark ? colors.headerText : colors.text }]} numberOfLines={1}>
        {theme.logo.wordmark}
      </Text>
      {portal ? <Text style={[styles.portal, { fontSize: Math.round(size * 0.5), color: onDark ? semantic['header-muted'] : colors.textMuted }]}>{portal}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  word: { fontFamily: fonts.black, letterSpacing: -0.3 },
  portal: { fontFamily: fonts.semibold },
});
