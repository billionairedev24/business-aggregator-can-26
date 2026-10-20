import type { ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, Path } from 'react-native-svg';

import { Icon, colors, fonts, radius, semantic, shadow, space } from '@northline/mobile-kit';

/**
 * The stop's map strip (light direction): a quiet schematic of streets with the stop's pin — the app draws no real map
 * (no map SDK; the phone's own maps app navigates, through `action`) — and when it's due on a white pill.
 */
export function MapStrip({ label, kind, action }: { label: string; kind: 'pickup' | 'dropoff' | 'return' | 'collect'; action?: ReactNode }) {
  return (
    <View style={styles.box} testID="map-strip">
      <View style={styles.map} accessible={false} importantForAccessibility="no-hide-descendants">
        <Svg width="100%" height="100%" viewBox="0 0 400 150" preserveAspectRatio="xMidYMid slice">
          <Path d="M0 40 H400 M0 112 H400 M60 0 V150 M170 0 V150 M300 0 V150" stroke={colors.surface} strokeWidth={12} />
          <Path d="M0 76 H400 M236 0 V150" stroke={colors.surface} strokeWidth={6} />
          <Path d="M60 130 L60 76 L236 76 L236 52" fill="none" stroke={semantic['action-primary']} strokeWidth={4} strokeDasharray="8 6" />
          <Circle cx={60} cy={130} r={8} fill={semantic['action-primary']} stroke={colors.surface} strokeWidth={3} />
          <Circle cx={236} cy={40} r={20} fill={kind === 'pickup' ? semantic['action-accent'] : semantic['action-primary']} opacity={0.18} />
          <Circle cx={236} cy={40} r={10} fill={kind === 'pickup' ? semantic['action-accent'] : semantic['action-primary']} stroke={colors.surface} strokeWidth={3} />
        </Svg>
        <View style={styles.pill}>
          <Icon name="clock" size={16} color={semantic['action-primary']} />
          <Text style={styles.pillText} numberOfLines={1}>
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
  map: { height: 150, borderRadius: radius.lg, overflow: 'hidden', backgroundColor: semantic['surface-sunken'] },
  pill: { position: 'absolute', left: space[3], bottom: space[3], maxWidth: '90%', flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 12, paddingVertical: 6, borderRadius: radius.pill, backgroundColor: colors.surface, ...shadow.md },
  pillText: { flexShrink: 1, fontFamily: fonts.bold, fontSize: 14, color: colors.text },
});
