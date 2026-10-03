import { useMutation, useQuery } from '@tanstack/react-query';
import Constants from 'expo-constants';
import { router, useLocalSearchParams, usePathname } from 'expo-router';
import { useState } from 'react';
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';

import { MIN_TARGET, colors, fonts, radius, space } from '@northline/mobile-kit';

import { pilotApi, screenOf, type FeedbackCategory, type FeedbackSeverity } from '../api/pilot';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n';
import { services } from '../services';
import { Body, Button, Field, Notice, Option } from '../ui/primitives';
import { Screen } from '../ui/screen';

const CATEGORIES: readonly FeedbackCategory[] = ['bug', 'confusing', 'idea', 'praise'];
const SEVERITIES: readonly FeedbackSeverity[] = ['blocker', 'major', 'minor', 'cosmetic'];
const MAX = 4000;

/** S-121: is the signed-in person in the pilot? (`GET /me/pilot`; any failure = no). */
export function usePilot() {
  const { status } = useAuth();
  return useQuery({
    queryKey: ['pilot', 'status'],
    queryFn: async () => (await pilotApi(services().api).status().catch(() => null))?.participant === true,
    enabled: status === 'signedIn',
    staleTime: 5 * 60_000,
  });
}

/**
 * The floating "Feedback" button on every screen, for pilot participants only: it opens the feedback form with the
 * screen it was pressed on.
 */
export function PilotButton() {
  const { t } = useI18n();
  const pathname = usePathname();
  const pilot = usePilot();
  if (!pilot.data || pathname === '/feedback') return null;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={t('pilot.open')}
      accessibilityHint={t('pilot.openHint')}
      onPress={() => router.push({ pathname: '/feedback', params: { from: screenOf(pathname) } })}
      style={({ pressed }) => [styles.fab, pressed && styles.fabPressed]}
      testID="pilot-feedback"
    >
      <Text style={styles.fabText}>{t('pilot.open')}</Text>
    </Pressable>
  );
}

/** The feedback form: what it is, how much it got in the way, what happened. */
export function FeedbackScreen() {
  const { t, locale } = useI18n();
  const { from } = useLocalSearchParams<{ from?: string }>();
  const screen = screenOf(from);
  const [category, setCategory] = useState<FeedbackCategory>('bug');
  const [severity, setSeverity] = useState<FeedbackSeverity>('minor');
  const [body, setBody] = useState('');
  const [error, setError] = useState<string>();
  const send = useMutation({
    mutationFn: () =>
      pilotApi(services().api).send({
        category,
        severity,
        body: body.trim(),
        route: screen,
        appVersion: (Constants.expoConfig?.version ?? 'dev').replace(/[^A-Za-z0-9._+-]/g, '') || 'dev',
        locale,
        platform: `Northline app · ${Platform.OS} ${String(Platform.Version)}`,
      }),
  });
  const submit = () => {
    const text = body.trim();
    if (!text || text.length > MAX) return setError(t('pilot.bodyRequired'));
    setError(undefined);
    send.mutate();
  };
  return (
    <Screen
      title={t('pilot.title')}
      footer={send.data ? null : <Button label={t('pilot.send')} onPress={submit} busy={send.isPending} large testID="pilot-send" />}
    >
      {send.data ? (
        <Notice tone="info" message={t('pilot.sent', { reference: send.data.reference })} testID="pilot-sent" />
      ) : (
        <View style={styles.form}>
          <Body tone="lead">{t('pilot.lede')}</Body>
          <Text style={styles.legend}>{t('pilot.category')}</Text>
          <View accessibilityRole="radiogroup" style={styles.options}>
            {CATEGORIES.map((c) => (
              <Option key={c} selected={category === c} onPress={() => setCategory(c)} title={t(`pilot.c.${c}`)} testID={`pilot-c-${c}`} />
            ))}
          </View>
          <Text style={styles.legend}>{t('pilot.severity')}</Text>
          <View accessibilityRole="radiogroup" style={styles.options}>
            {SEVERITIES.map((s) => (
              <Option key={s} selected={severity === s} onPress={() => setSeverity(s)} title={t(`pilot.s.${s}`)} testID={`pilot-s-${s}`} />
            ))}
          </View>
          <Field label={t('pilot.body')} hint={t('pilot.bodyHint')} value={body} onChangeText={setBody} multiline maxLength={MAX} error={error} testID="pilot-body" />
          <Body tone="small">{t('pilot.context', { screen })}</Body>
          {send.isError ? <Notice message={t('pilot.error')} /> : null}
        </View>
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  fab: {
    position: 'absolute',
    right: space[4],
    bottom: 96,
    minHeight: MIN_TARGET,
    paddingHorizontal: space[4],
    justifyContent: 'center',
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.accent,
    backgroundColor: colors.bg,
  },
  fabPressed: { backgroundColor: colors.accent100 },
  fabText: { color: colors.accent, fontFamily: fonts.bodyStrong, fontSize: 15 },
  form: { gap: space[3] },
  legend: { fontFamily: fonts.bodyStrong, fontSize: 15, color: colors.neutral900 },
  options: { gap: space[2] },
});
