import { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import { MIN_TARGET, radius, semantic, space } from '../../theme';
import { Icon } from '../Icon';
import { BottomSheet } from './Feedback';
import { OptionCard } from './Choice';
import { useKit } from './KitProvider';
import { CAP, LINE, TYPE, textStyles } from './tokens';

export interface SearchScope<T extends string = string> {
  value: T;
  label: string;
}

/**
 * The search bar of the revamp's header (Amazon's pattern): the department scope on the left ("Shop ▾", opening a
 * bottom sheet of scopes), the field, and the violet search button (S-188). 44 pt tall, 3 pt corners, the 3:1 control
 * edge, the 2 pt violet focus edge while typing. `onChrome` (the consumer's violet header): the white field is the
 * edge, with a soft drop shadow. Without `onChangeText` it is a button that opens the search screen (`onPress`), as on
 * a tab's top bar.
 */
export function SearchBar<T extends string>({
  placeholder,
  value,
  onChangeText,
  onSubmit,
  onPress,
  scopes,
  scope,
  onScope,
  autoFocus,
  onChrome,
  testID,
}: {
  placeholder: string;
  value?: string;
  onChangeText?: (q: string) => void;
  onSubmit?: (q: string) => void;
  /** No field: the whole bar opens the search screen. */
  onPress?: () => void;
  scopes?: SearchScope<T>[];
  scope?: T;
  onScope?: (s: T) => void;
  autoFocus?: boolean;
  /** On the violet header: no edge (the white field is the edge), a soft shadow. */
  onChrome?: boolean;
  testID?: string;
}) {
  const { t } = useKit();
  const [picking, setPicking] = useState(false);
  const [focused, setFocused] = useState(false);
  const bar = [styles.bar, onChrome && styles.barChrome, focused && !onChrome && styles.barFocused];
  const current = scopes?.find((s) => s.value === scope) ?? scopes?.[0];
  const field = onChangeText !== undefined;
  if (!field && !(scopes && onScope)) {
    // a button that opens search: one target, the ink square drawn inside it
    return (
      <Pressable accessibilityRole="search" accessibilityLabel={placeholder} onPress={onPress} style={({ pressed }) => [...bar, pressed && styles.pressed]} testID={testID}>
        <View style={styles.fake}>
          <Text style={styles.placeholder} numberOfLines={1}>
            {value || placeholder}
          </Text>
        </View>
        <View style={styles.go}>
          <Icon name="magnifying-glass" size={TYPE.section} color={semantic['action-primary-text']} />
        </View>
      </Pressable>
    );
  }
  return (
    <View style={bar} testID={testID}>
      {scopes && current && onScope ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('kit.scope', { scope: current.label })}
          onPress={() => setPicking(true)}
          style={({ pressed }) => [styles.scope, pressed && styles.pressed]}
          testID={testID ? `${testID}-scope` : undefined}
        >
          <Text style={styles.scopeText} numberOfLines={1} maxFontSizeMultiplier={CAP.control}>
            {current.label}
          </Text>
          <Icon name="caret-down" size={TYPE.meta} color={semantic['text-muted']} />
        </Pressable>
      ) : null}
      {field ? (
        <TextInput
          accessibilityLabel={placeholder}
          value={value}
          onChangeText={onChangeText}
          onSubmitEditing={() => onSubmit?.(value ?? '')}
          placeholder={placeholder}
          placeholderTextColor={semantic['text-subtle']}
          returnKeyType="search"
          autoFocus={autoFocus}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          style={styles.input}
          testID={testID ? `${testID}-input` : undefined}
        />
      ) : (
        <Pressable accessibilityRole="search" accessibilityLabel={placeholder} onPress={onPress} style={({ pressed }) => [styles.fake, pressed && styles.pressed]} testID={testID ? `${testID}-open` : undefined}>
          <Text style={styles.placeholder} numberOfLines={1}>
            {value || placeholder}
          </Text>
        </Pressable>
      )}
      {field && value ? (
        <Pressable accessibilityRole="button" accessibilityLabel={t('kit.clear')} onPress={() => onChangeText?.('')} style={styles.clear}>
          <Icon name="x" size={TYPE.sub} color={semantic['text-muted']} />
        </Pressable>
      ) : null}
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={t('kit.search')}
        onPress={() => (field ? onSubmit?.(value ?? '') : onPress?.())}
        style={({ pressed }) => [styles.go, pressed && styles.goPressed]}
        testID={testID ? `${testID}-go` : undefined}
      >
        <Icon name="magnifying-glass" size={TYPE.section} color={semantic['action-primary-text']} />
      </Pressable>
      {scopes && onScope ? (
        <BottomSheet visible={picking} onClose={() => setPicking(false)} title={t('kit.scopeTitle')}>
          {scopes.map((s) => (
            <OptionCard
              key={s.value}
              title={s.label}
              selected={s.value === current?.value}
              onPress={() => {
                onScope(s.value);
                setPicking(false);
              }}
            />
          ))}
        </BottomSheet>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    alignItems: 'stretch',
    minHeight: MIN_TARGET,
    borderWidth: LINE.hair,
    borderColor: semantic['border-control'],
    borderRadius: radius.control,
    backgroundColor: semantic.surface,
    overflow: 'hidden',
  },
  barChrome: { borderWidth: 0, boxShadow: `0 ${LINE.hair}px ${LINE.bold}px ${semantic['hero-scrim']}` },
  barFocused: { borderWidth: LINE.bold, borderColor: semantic.focus },
  pressed: { backgroundColor: semantic['surface-hover'] },
  scope: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space[1],
    maxWidth: '40%',
    paddingHorizontal: space[3],
    backgroundColor: semantic['surface-sunken'],
    borderRightWidth: LINE.hair,
    borderRightColor: semantic['border-strong'],
  },
  scopeText: { ...StyleSheet.flatten(textStyles.label), fontFamily: textStyles.body.fontFamily, color: semantic['text-muted'], flexShrink: 1 },
  input: { flex: 1, minWidth: 0, paddingHorizontal: space[3], ...StyleSheet.flatten(textStyles.body), fontSize: TYPE.sub, lineHeight: undefined },
  fake: { flex: 1, minWidth: 0, justifyContent: 'center', paddingHorizontal: space[3] },
  placeholder: { ...StyleSheet.flatten(textStyles.body), fontSize: TYPE['price-sm'], color: semantic['text-subtle'] },
  clear: { width: MIN_TARGET, alignItems: 'center', justifyContent: 'center' },
  go: { width: MIN_TARGET + space[1], alignItems: 'center', justifyContent: 'center', backgroundColor: semantic['action-primary'] },
  goPressed: { backgroundColor: semantic['action-primary-hover'] },
});
