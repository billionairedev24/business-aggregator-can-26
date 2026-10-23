import { useRef } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import { colors, LINE, radius, semantic, space, textStyles } from '@northline/mobile-kit';

import { type } from './primitives';

/**
 * The design's six code boxes over one real text field: the platforms fill it from the SMS (iOS `oneTimeCode`,
 * Android `sms-otp`), screen readers read it as one "6-digit code" field, the box after the last digit is marked.
 */
export function CodeInput({
  value,
  onChange,
  label,
  error,
  autoFocus,
  testID = 'code-input',
}: {
  value: string;
  onChange: (v: string) => void;
  label: string;
  error?: string | null;
  autoFocus?: boolean;
  testID?: string;
}) {
  const input = useRef<TextInput>(null);
  const digits = value.padEnd(6, ' ').slice(0, 6).split('');
  return (
    <View style={styles.wrap}>
      <Pressable onPress={() => input.current?.focus()} accessible={false} style={styles.row}>
        {digits.map((d, i) => (
          <View key={i} style={[styles.box, i === Math.min(value.length, 5) && styles.boxNext, !!error && styles.boxError]}>
            <Text style={styles.digit}>{d.trim()}</Text>
          </View>
        ))}
      </Pressable>
      <TextInput
        ref={input}
        value={value}
        onChangeText={(v) => onChange(v.replace(/\D/g, '').slice(0, 6))}
        keyboardType="number-pad"
        textContentType="oneTimeCode"
        autoComplete="sms-otp"
        maxLength={6}
        autoFocus={autoFocus}
        accessibilityLabel={label}
        aria-invalid={!!error}
        caretHidden
        style={styles.hidden}
        testID={testID}
      />
      {error ? (
        <Text accessibilityRole="alert" accessibilityLiveRegion="polite" style={type.error}>
          {error}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: space[2] },
  row: { flexDirection: 'row', gap: space[2] },
  box: { flex: 1, height: 56, alignItems: 'center', justifyContent: 'center', borderRadius: radius.panel, backgroundColor: semantic.surface, borderWidth: LINE.hair, borderColor: colors.divider },
  boxNext: { borderColor: colors.accent },
  boxError: { borderColor: colors.accent2 },
  digit: { ...StyleSheet.flatten(textStyles.pageTitle) },
  // over the boxes, invisible, so a tap anywhere focuses it and the OS autofill bar targets it
  hidden: { position: 'absolute', left: 0, right: 0, top: 0, height: 56, opacity: 0.011, color: 'transparent' },
});
