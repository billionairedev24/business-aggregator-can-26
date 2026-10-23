import { forwardRef } from 'react';
import { StyleSheet, Text, TextInput, View, type TextInputProps } from 'react-native';

import { MIN_TARGET, radius, semantic, space } from '../../theme';
import { LINE, TYPE, textStyles } from './tokens';

export interface TextFieldProps extends Omit<TextInputProps, 'style'> {
  label: string;
  error?: string | null;
  hint?: string;
  testID?: string;
}

/**
 * A labelled text field: the label (13 / 600) above a 44 pt input with the 3:1 control edge and 3 pt corners (16 pt
 * text, so nothing zooms), the danger edge and the message under it when wrong (announced).
 */
export const TextField = forwardRef<TextInput, TextFieldProps>(function TextField({ label, error, hint, testID, ...input }, ref) {
  return (
    <View style={styles.field}>
      <Text style={textStyles.label} nativeID={testID ? `${testID}-label` : undefined}>
        {label}
      </Text>
      <TextInput
        ref={ref}
        accessibilityLabel={label}
        accessibilityHint={hint}
        aria-invalid={!!error}
        placeholderTextColor={semantic['text-subtle']}
        style={[styles.input, !!error && styles.inputError]}
        testID={testID}
        {...input}
      />
      {error ? (
        <Text accessibilityRole="alert" accessibilityLiveRegion="polite" style={styles.error}>
          {error}
        </Text>
      ) : null}
    </View>
  );
});

const styles = StyleSheet.create({
  field: { gap: space[1] + LINE.bold },
  input: {
    minHeight: MIN_TARGET,
    paddingHorizontal: space[3],
    paddingVertical: space[2],
    ...StyleSheet.flatten(textStyles.body),
    fontSize: TYPE.sub,
    lineHeight: undefined,
    backgroundColor: semantic.surface,
    borderWidth: LINE.hair,
    borderColor: semantic['border-control'],
    borderRadius: radius.control,
  },
  inputError: { borderColor: semantic['danger-border'], borderWidth: LINE.bold, paddingHorizontal: space[3] - LINE.hair },
  error: { ...StyleSheet.flatten(textStyles.label), fontFamily: textStyles.cardTitle.fontFamily, color: semantic.danger },
});
