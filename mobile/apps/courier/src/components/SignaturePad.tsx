import { useEffect, useState } from 'react';
import { PanResponder, StyleSheet, View } from 'react-native';
import Svg, { Path } from 'react-native-svg';

import { colors, radius } from '@northline/mobile-kit';

import type { Stroke } from '../signature/png';

const pathOf = (s: Stroke) => s.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ');

/**
 * A box to sign in with a finger. The pad keeps the strokes and reports them on every change; the parent clears it by
 * remounting it (a new `key`). The PNG for the api is drawn from the strokes (src/signature/png.ts), so no screenshot
 * module is needed.
 */
export function SignaturePad({
  label,
  onChange,
  onSize,
  height = 200,
}: {
  label: string;
  onChange: (strokes: Stroke[]) => void;
  onSize: (size: { w: number; h: number }) => void;
  height?: number;
}) {
  const [strokes, setStrokes] = useState<Stroke[]>([]);
  useEffect(() => onChange(strokes), [strokes, onChange]);
  const [responder] = useState(() =>
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderTerminationRequest: () => false,
      onPanResponderGrant: (e) => {
        const p = { x: e.nativeEvent.locationX, y: e.nativeEvent.locationY };
        setStrokes((all) => [...all, [p]]);
      },
      onPanResponderMove: (e) => {
        const p = { x: e.nativeEvent.locationX, y: e.nativeEvent.locationY };
        setStrokes((all) => [...all.slice(0, -1), [...(all[all.length - 1] ?? []), p]]);
      },
    }),
  );

  return (
    <View
      accessible
      accessibilityLabel={label}
      accessibilityRole="image"
      style={[styles.box, { height }]}
      onLayout={(e) => onSize({ w: e.nativeEvent.layout.width, h: e.nativeEvent.layout.height })}
      testID="signature-pad"
      {...responder.panHandlers}
    >
      <Svg width="100%" height="100%">
        {strokes.map((s, i) => (
          <Path key={i} d={pathOf(s)} stroke={colors.text} strokeWidth={3} strokeLinecap="round" strokeLinejoin="round" fill="none" />
        ))}
      </Svg>
      <View style={styles.baseline} pointerEvents="none" />
    </View>
  );
}

const styles = StyleSheet.create({
  box: { backgroundColor: colors.onAccent, borderRadius: radius.md, borderWidth: 1, borderColor: colors.neutral300, overflow: 'hidden' },
  baseline: { position: 'absolute', left: 24, right: 24, bottom: 40, height: 1, backgroundColor: colors.neutral300 },
});
