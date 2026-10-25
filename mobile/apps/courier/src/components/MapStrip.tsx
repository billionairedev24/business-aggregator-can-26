import type { ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, Path } from 'react-native-svg';

import { CAP, Icon, SIZE, TYPE, radius, semantic, space, textStyles } from '@northline/mobile-kit';

/**
 * The stop's map strip (S-179): a quiet schematic of streets on the well grey with the stop's pin — the app draws no
 * real map (no map SDK; the phone's own maps app navigates, through `action`) — and when it's due on a solid ink tag,
 * readable in sunlight.
 */
export function MapStrip({ label, kind, action }: { label: string; kind: 'pickup' | 'dropoff' | 'return' | 'collect'; action?: ReactNode }) {
  return (
    <View style={styles.box} testID="map-strip">
      <View style={styles.map} accessible={false} importantForAccessibility="no-hide-descendants">
        <Svg width="100%" height="100%" viewBox="0 0 400 150" preserveAspectRatio="xMidYMid slice">
          <Path d="M0 40 H400 M0 112 H400 M60 0 V150 M170 0 V150 M300 0 V150" stroke={semantic.surface} strokeWidth={12} />
          <Path d="M0 76 H400 M236 0 V150" stroke={semantic.surface} strokeWidth={6} />
          <Path d="M60 130 L60 76 L236 76 L236 52" fill="none" stroke={semantic.selected} strokeWidth={4} strokeDasharray="8 6" />
          <Circle cx={60} cy={130} r={8} fill={semantic.selected} stroke={semantic.surface} strokeWidth={3} />
          <Circle cx={236} cy={40} r={20} fill={kind === 'pickup' ? semantic.selected : semantic.selected} opacity={0.18} />
          <Circle cx={236} cy={40} r={10} fill={kind === 'pickup' ? semantic.selected : semantic.selected} stroke={semantic.surface} strokeWidth={3} />
        </Svg>
        <View style={styles.pill}>
          <Icon name="clock" size={TYPE.sub} color={semantic['selected-text']} />
          <Text style={styles.pillText} numberOfLines={1} maxFontSizeMultiplier={CAP.control}>
            {label}
          </Text>
        </View>
      </View>
      {action}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: space[3] },
  map: { height: SIZE.rail * 2, borderRadius: radius.panel, overflow: 'hidden', backgroundColor: semantic.well },
  pill: { position: 'absolute', left: space[3], bottom: space[3], maxWidth: '90%', flexDirection: 'row', alignItems: 'center', gap: space[1.5], paddingHorizontal: space[3], paddingVertical: space[1.5], borderRadius: radius.control, backgroundColor: semantic.selected },
  pillText: { ...StyleSheet.flatten(textStyles.bodyStrong), flexShrink: 1, color: semantic['selected-text'] },
});
