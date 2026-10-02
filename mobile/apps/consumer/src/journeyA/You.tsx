import { useQuery } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { router } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { MIN_TARGET, colors, fonts, radius, space } from '@northline/mobile-kit';

import { useAuth } from '../auth/AuthProvider';
import { legalUrl } from '../config';
import { useI18n } from '../i18n';
import { services } from '../services';
import { Body, Button, Link, Notice, Row, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { SignInPrompt } from '../ui/states';

interface Me {
  firstName: string;
  lastName: string;
}

/**
 * The You tab until S-101 builds design 01's Profile: who is signed in, "Language / Langue", Sign out (S-98: the
 * secure session ends — the push hook, `/oauth2/revoke`, the key and tokens deleted), and the legal links. Guests get
 * the sign-in prompt and the language.
 */
export function You() {
  const { t, locale, setLocale } = useI18n();
  const { status, signOut, ended } = useAuth();
  const [busy, setBusy] = useState(false);
  const me = useQuery({ queryKey: ['me'], queryFn: () => services().api.get<Me>('/me'), enabled: status === 'signedIn', staleTime: 300_000 });
  const name = me.data ? `${me.data.firstName} ${me.data.lastName}`.trim() : '';

  const out = async () => {
    setBusy(true);
    try {
      await signOut();
      router.replace('/home');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Screen testID="you">
      <Title>{t('screen.account')}</Title>
      {status === 'signedIn' ? (
        <Body tone="muted">{name ? t('you.signedIn', { name }) : t('you.signedInNoName')}</Body>
      ) : (
        <>
          {ended ? <Notice message={t('common.signedOut')} tone="info" /> : null}
          <SignInPrompt message={t('you.guest')} />
        </>
      )}

      <View style={styles.row}>
        <Body>{t('you.language')}</Body>
        <View accessibilityRole="radiogroup" accessibilityLabel={t('you.language')} style={styles.segmented}>
          {(
            [
              ['en', 'English'],
              ['fr-CA', 'Français'],
            ] as const
          ).map(([value, label]) => (
            <Pressable
              key={value}
              accessibilityRole="radio"
              accessibilityState={{ checked: locale === value }}
              accessibilityLabel={label}
              onPress={() => setLocale(value)}
              style={[styles.segment, locale === value && styles.segmentOn]}
              testID={`language-${value}`}
            >
              <Text style={[styles.segmentText, locale === value && styles.segmentTextOn]}>{label}</Text>
            </Pressable>
          ))}
        </View>
      </View>

      {status === 'signedIn' ? <Button label={t('you.signOut')} tone="danger" busy={busy} onPress={() => void out()} testID="sign-out" /> : null}

      <Row wrap style={styles.legal}>
        <Link label={t('you.privacy')} hint={t('common.opensInBrowser')} onPress={() => void Linking.openURL(legalUrl('privacy'))} />
        <Body tone="small">·</Body>
        <Link label={t('you.terms')} hint={t('common.opensInBrowser')} onPress={() => void Linking.openURL(legalUrl('terms'))} />
      </Row>
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { gap: space[2], paddingTop: space[3] },
  segmented: { flexDirection: 'row', backgroundColor: colors.surface2, borderRadius: radius.md, padding: 3, gap: 2 },
  segment: { flex: 1, minHeight: MIN_TARGET, alignItems: 'center', justifyContent: 'center', borderRadius: radius.sm },
  segmentOn: { backgroundColor: colors.surface },
  segmentText: { fontFamily: fonts.body, fontSize: 15, color: colors.text },
  segmentTextOn: { fontFamily: fonts.bodyStrong, color: colors.accent800 },
  legal: { paddingTop: space[6] },
});
