import { useMutation, useQuery } from '@tanstack/react-query';
import Constants from 'expo-constants';
import * as ImagePicker from 'expo-image-picker';
import { useLocalSearchParams, usePathname, useRouter } from 'expo-router';
import { useState } from 'react';
import { Image, Platform, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import {
  MIN_TARGET,
  SCREENSHOT_MAX_BYTES,
  SCREENSHOT_TYPES,
  checkPhoto,
  colors,
  fonts,
  pilotApi,
  radius,
  screenOf,
  space,
  type FeedbackCategory,
  type FeedbackSeverity,
  type PickedImage,
} from '@northline/mobile-kit';

import { Banner, Body, Button, Heading, Screen } from '../components/ui';
import { useI18n } from '../i18n';
import { services } from '../services';

const CATEGORIES: readonly FeedbackCategory[] = ['bug', 'confusing', 'idea', 'praise'];
const SEVERITIES: readonly FeedbackSeverity[] = ['blocker', 'major', 'minor', 'cosmetic'];
const MAX = 4000;

/** S-121 for couriers (mobile gaps part 1): is the signed-in courier a pilot courier? (`GET /me/pilot?app=courier`) */
export function usePilotCourier() {
  return useQuery({
    queryKey: ['pilot', 'courier'],
    queryFn: async () => (await pilotApi(services().api, 'courier').status().catch(() => null))?.participant === true,
    staleTime: 5 * 60_000,
  });
}

/** "Feedback" in the header of every screen, for pilot couriers only: opens the form with the screen it came from. */
export function FeedbackButton() {
  const { t } = useI18n();
  const router = useRouter();
  const pathname = usePathname();
  const pilot = usePilotCourier();
  if (!pilot.data || pathname === '/feedback') return null;
  return <Button tone="ghost" label={t('pilot.open')} hint={t('pilot.openHint')} onPress={() => router.push({ pathname: '/feedback', params: { from: screenOf(pathname) } })} testID="pilot-feedback" />;
}

/** A screenshot the courier took with the phone's buttons, from the system photo picker (no library permission). */
async function pickScreenshot(): Promise<PickedImage | null> {
  const result = await ImagePicker.launchImageLibraryAsync({
    mediaTypes: ['images'],
    quality: 1,
    exif: false,
    allowsMultipleSelection: false,
    preferredAssetRepresentationMode: ImagePicker.UIImagePickerPreferredAssetRepresentationMode.Compatible,
  });
  const a = result.canceled ? null : result.assets?.[0];
  return a ? { uri: a.uri, mimeType: a.mimeType ?? null, fileSize: a.fileSize ?? null, fileName: a.fileName ?? null } : null;
}

/**
 * The pilot courier's feedback (the same UAT api as the web and the consumer app, `app: courier` → persona courier):
 * what it is, how much it got in the way, what happened, an optional screenshot (PNG/JPEG ≤ 5 MB; the api checks it
 * again, S-104). Sent with the screen, app version, language and phone system — no run, stop or address.
 */
export function FeedbackScreen() {
  const { t, locale } = useI18n();
  const { from } = useLocalSearchParams<{ from?: string }>();
  const screen = screenOf(from);
  const [category, setCategory] = useState<FeedbackCategory>('bug');
  const [severity, setSeverity] = useState<FeedbackSeverity>('minor');
  const [body, setBody] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [shot, setShot] = useState<PickedImage | null>(null);
  const send = useMutation({
    mutationFn: async () => {
      const api = pilotApi(services().api, 'courier');
      const screenshotId = shot ? (await api.screenshot(shot))?.id : undefined;
      return api.send({
        category,
        severity,
        body: body.trim(),
        route: screen,
        appVersion: (Constants.expoConfig?.version ?? 'dev').replace(/[^A-Za-z0-9._+-]/g, '') || 'dev',
        locale,
        platform: `Northline Courier · ${Platform.OS} ${String(Platform.Version)}`,
        ...(screenshotId ? { screenshotId } : {}),
      });
    },
  });
  const attach = async () => {
    setError(null);
    const picked = await pickScreenshot().catch(() => null);
    if (!picked) return;
    const problem = checkPhoto(picked, SCREENSHOT_TYPES, SCREENSHOT_MAX_BYTES);
    if (problem) return setError(t(problem === 'size' ? 'pilot.shotSize' : 'pilot.shotType'));
    setShot(picked);
  };
  const submit = () => {
    const text = body.trim();
    if (!text || text.length > MAX) return setError(t('pilot.bodyRequired'));
    setError(null);
    send.mutate();
  };
  if (send.data) {
    return (
      <Screen testID="pilot-sent">
        <Banner tone="info" role="text">{t('pilot.sent', { reference: send.data.reference })}</Banner>
      </Screen>
    );
  }
  return (
    <Screen testID="pilot-form">
      <Heading>{t('pilot.title')}</Heading>
      <Body>{t('pilot.lede')}</Body>
      <Choices label={t('pilot.category')} options={CATEGORIES.map((c) => ({ value: c, label: t(`pilot.c.${c}`) }))} value={category} onChange={setCategory} testID="pilot-c" />
      <Choices label={t('pilot.severity')} options={SEVERITIES.map((s) => ({ value: s, label: t(`pilot.s.${s}`) }))} value={severity} onChange={setSeverity} testID="pilot-s" />
      <Body strong>{t('pilot.body')}</Body>
      <TextInput
        accessibilityLabel={t('pilot.body')}
        accessibilityHint={t('pilot.bodyHint')}
        value={body}
        onChangeText={setBody}
        multiline
        maxLength={MAX}
        placeholder={t('pilot.bodyHint')}
        placeholderTextColor={colors.neutral500}
        style={styles.input}
        testID="pilot-body"
      />
      <Body strong>{t('pilot.shot')}</Body>
      <Body muted>{t('pilot.shotHint')}</Body>
      {shot ? (
        <View style={styles.shotRow}>
          <Image source={{ uri: shot.uri }} style={styles.shot} accessibilityLabel={t('pilot.shotAttached')} testID="pilot-shot" />
          <Button tone="ghost" label={t('pilot.shotRemove')} onPress={() => setShot(null)} testID="pilot-shot-remove" />
        </View>
      ) : (
        <Button tone="secondary" label={t('pilot.shotAdd')} onPress={() => void attach()} testID="pilot-shot-add" />
      )}
      {error ? <Banner tone="error">{error}</Banner> : null}
      {send.isError ? <Banner tone="error">{t('pilot.error')}</Banner> : null}
      <Body muted>{t(shot ? 'pilot.contextShot' : 'pilot.context', { screen })}</Body>
      <Button label={t('pilot.send')} busy={send.isPending} onPress={submit} testID="pilot-send" />
    </Screen>
  );
}

/** One answer of a few, as radio buttons (48 dp rows, the label read out with its state). */
function Choices<T extends string>({ label, options, value, onChange, testID }: { label: string; options: Array<{ value: T; label: string }>; value: T; onChange: (v: T) => void; testID: string }) {
  return (
    <View accessibilityRole="radiogroup" accessibilityLabel={label} style={styles.choices}>
      <Body strong>{label}</Body>
      {options.map((o) => {
        const on = o.value === value;
        return (
          <Pressable
            key={o.value}
            accessibilityRole="radio"
            accessibilityState={{ checked: on }}
            accessibilityLabel={o.label}
            onPress={() => onChange(o.value)}
            style={[styles.choice, on && styles.choiceOn]}
            testID={`${testID}-${o.value}`}
          >
            <View style={[styles.dot, on && styles.dotOn]} />
            <Text style={styles.choiceText}>{o.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  choices: { gap: space[2] },
  choice: { flexDirection: 'row', alignItems: 'center', gap: space[3], minHeight: MIN_TARGET, paddingHorizontal: space[3], borderRadius: radius.md, borderWidth: 1, borderColor: colors.divider },
  choiceOn: { borderColor: colors.accent, backgroundColor: colors.accent100 },
  dot: { width: 18, height: 18, borderRadius: 9, borderWidth: 2, borderColor: colors.neutral500 },
  dotOn: { borderColor: colors.accent, backgroundColor: colors.accent },
  choiceText: { flex: 1, fontFamily: fonts.body, fontSize: 16, color: colors.text },
  input: {
    minHeight: MIN_TARGET * 3,
    borderWidth: 1,
    borderColor: colors.divider,
    borderRadius: radius.md,
    padding: space[3],
    fontFamily: fonts.body,
    fontSize: 16,
    color: colors.text,
    textAlignVertical: 'top',
  },
  shotRow: { flexDirection: 'row', alignItems: 'center', gap: space[3] },
  shot: { width: 72, height: 128, borderRadius: radius.sm, borderWidth: 1, borderColor: colors.divider },
});
