import { useQuery, useQueryClient } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { router } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { MIN_TARGET, colors, fonts, radius, space, type Locale } from '@northline/mobile-kit';

import { useAuth } from '../auth/AuthProvider';
import { config, legalUrl } from '../config';
import { useI18n } from '../i18n';
import { useDeliveryLocation } from '../location/DeliveryLocation';
import { formatMoney } from '../shop/common';
import { Body, Button, Link, Notice, Row, Tag, Title, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { ErrorState, SignInPrompt, Skeleton } from '../ui/states';
import { KEYS, account, clock, count, decimal, monthYear, useAccountSummary } from './common';
import { SettingsRow } from './parts';

/**
 * D3 Profile — the You tab (design 01 `account`): who you are (name, reliability, member since, where you are), the
 * wallet up top (points and Plus, → Wallet), then the design's rows, each leading somewhere real (`GET
 * /me/account-summary` gives their values): personal details, addresses & household, payment methods, security &
 * sign-in, notifications, dietary & accessibility, favourite providers, refunds & help, "Language / Langue" (here, in
 * place — the account's language follows, `PATCH /me/preferences`), becoming a seller (the website), sign-out and the
 * legal links. "Invite a neighbour" has no api yet (MOBILE_PLAN § API gaps) and is left out. Guests get the sign-in
 * prompt, the language and the legal links.
 */
export function Account() {
  const { t, locale, setLocale } = useI18n();
  const { status, signOut, ended } = useAuth();
  const qc = useQueryClient();
  const signedIn = status === 'signedIn';
  const summary = useAccountSummary();
  const profile = useQuery({ queryKey: KEYS.profile, queryFn: () => account().profile(), enabled: signedIn, staleTime: 300_000 });
  const { location } = useDeliveryLocation();
  const [busy, setBusy] = useState(false);
  const [langError, setLangError] = useState(false);

  const out = async () => {
    setBusy(true);
    try {
      await signOut();
      qc.removeQueries({ queryKey: KEYS.all });
      router.replace('/home');
    } finally {
      setBusy(false);
    }
  };

  const changeLanguage = (l: Locale) => {
    setLocale(l);
    setLangError(false);
    // the account's language too (receipts, emails, "same as app" notifications); the phone's choice stands either way
    if (signedIn) {
      account()
        .savePrefs({ language: l === 'fr-CA' ? 'fr' : 'en' })
        .then(() => qc.invalidateQueries({ queryKey: KEYS.prefs }))
        .catch(() => setLangError(true));
    }
  };

  const s = summary.data;
  const p = profile.data;
  const name = p ? `${p.firstName} ${p.lastName}`.trim() : '';
  const reliability = p?.reliability ?? s?.reliability;
  const meta = [
    reliability != null ? t('account.you.reliability', { score: decimal(reliability, locale) }) : null,
    p ? t('account.you.memberSince', { date: monthYear(p.memberSince, locale) }) : null,
    location.city ?? null,
  ]
    .filter(Boolean)
    .join(' · ');
  const value = (v: string | null | undefined) => (s ? v : undefined);
  const card = s?.paymentMethod ? t('account.card', { brand: s.paymentMethod.brand, last4: s.paymentMethod.last4 }) : null;
  const signIn = s?.signIn ? t(`account.you.signIn.${s.signIn === 'passkey' || s.signIn === 'totp' ? s.signIn : 'sms'}`) : null;
  const quiet = s?.quietHours ? t('account.you.quiet', { from: clock(s.quietHours.from, locale), to: clock(s.quietHours.to, locale) }) : s ? t('account.you.quietOff') : null;

  const language = (
    <View style={styles.language}>
      <Body>{t('you.language')}</Body>
      <View accessibilityRole="radiogroup" accessibilityLabel={t('you.language')} style={styles.segmented}>
        {(
          [
            ['en', 'English'],
            ['fr-CA', 'Français'],
          ] as const
        ).map(([v, label]) => (
          <Pressable
            key={v}
            accessibilityRole="radio"
            accessibilityState={{ checked: locale === v }}
            accessibilityLabel={label}
            onPress={() => changeLanguage(v)}
            style={[styles.segment, locale === v && styles.segmentOn]}
            testID={`language-${v}`}
          >
            <Text style={[styles.segmentText, locale === v && styles.segmentTextOn]}>{label}</Text>
          </Pressable>
        ))}
      </View>
      {langError ? <Body tone="small">{t('account.you.languageNotSaved')}</Body> : null}
    </View>
  );

  const legal = (
    <Row wrap style={styles.legal}>
      <Body tone="small">{t('account.you.company')}</Body>
      <Body tone="small">·</Body>
      <Link label={t('you.privacy')} hint={t('common.opensInBrowser')} onPress={() => void Linking.openURL(legalUrl('privacy'))} />
      <Body tone="small">·</Body>
      <Link label={t('you.terms')} hint={t('common.opensInBrowser')} onPress={() => void Linking.openURL(legalUrl('terms'))} />
    </Row>
  );

  if (!signedIn) {
    return (
      <Screen testID="you">
        <Title>{t('screen.account')}</Title>
        {ended ? <Notice message={t('common.signedOut')} tone="info" /> : null}
        <SignInPrompt message={t('you.guest')} />
        {language}
        {legal}
      </Screen>
    );
  }

  return (
    <Screen testID="you">
      <View style={styles.head}>
        <View style={styles.avatar} accessible={false}>
          <Text style={styles.initials}>{p ? `${p.firstName.charAt(0)}${p.lastName.charAt(0)}`.toUpperCase() : ''}</Text>
        </View>
        <View style={styles.headText}>
          {profile.isPending ? <Skeleton height={28} width="70%" /> : <Title>{name || t('you.signedInNoName')}</Title>}
          {meta ? <Text style={type.small}>{meta}</Text> : null}
        </View>
      </View>
      {profile.isError && !p ? <ErrorState error={profile.error} onRetry={() => void profile.refetch()} /> : null}

      <Pressable
        accessibilityRole="button"
        accessibilityLabel={[t('account.you.wallet'), s?.points ? t('account.you.points', { points: count(s.points.balance, locale), value: formatMoney(s.points.valueCents, locale) }) : '', s ? t(s.plus ? 'account.you.plus' : 'account.you.standard') : ''].filter(Boolean).join(', ')}
        onPress={() => router.push('/wallet')}
        style={({ pressed }) => [styles.wallet, pressed && styles.walletPressed]}
        testID="you-wallet"
      >
        <View style={styles.headText}>
          <Text style={styles.walletKicker}>{t('account.you.wallet')}</Text>
          {summary.isPending ? (
            <Skeleton height={22} width="60%" />
          ) : (
            <Text style={styles.walletPoints}>
              {s?.points ? t('account.you.points', { points: count(s.points.balance, locale), value: formatMoney(s.points.valueCents, locale) }) : t('account.you.walletOpen')}
            </Text>
          )}
        </View>
        {s ? <Tag label={t(s.plus ? 'account.you.plus' : 'account.you.standard')} tone="accent2" /> : null}
      </Pressable>
      {summary.isError ? <ErrorState error={summary.error} onRetry={() => void summary.refetch()} /> : null}

      <View style={styles.rows}>
        <SettingsRow name={t('account.you.profile')} value={p?.email ?? undefined} onPress={() => router.push('/account/profile')} testID="row-profile" />
        <SettingsRow
          name={t('account.you.addresses')}
          value={value(s?.addresses ? String(s.addresses.count) : null)}
          onPress={() => router.push('/account/addresses')}
          testID="row-addresses"
        />
        <SettingsRow name={t('account.you.payments')} value={value(card)} onPress={() => router.push('/account/payments')} testID="row-payments" />
        <SettingsRow name={t('account.you.security')} value={value(signIn)} onPress={() => router.push('/security')} testID="row-security" />
        <SettingsRow name={t('account.you.notifications')} value={value(quiet)} onPress={() => router.push('/account/notifications')} testID="row-notifications" />
        <SettingsRow
          name={t('account.you.preferences')}
          value={value(s?.dietary?.length ? s.dietary.join(', ') : null)}
          onPress={() => router.push('/account/preferences')}
          testID="row-preferences"
        />
        <SettingsRow name={t('account.you.favourites')} value={value(s?.favourites != null ? String(s.favourites) : null)} onPress={() => router.push('/account/favourites')} testID="row-favourites" />
        <SettingsRow
          name={t('account.you.help')}
          value={value(s?.openCases ? t('account.you.cases', { n: s.openCases }) : null)}
          onPress={() => router.push('/account/help')}
          testID="row-help"
        />
        <SettingsRow name={t('account.you.sell')} hint={t('common.opensInBrowser')} onPress={() => void Linking.openURL(`${config.siteOrigin}/sell`)} testID="row-sell" />
      </View>

      {language}
      <Button label={t('you.signOut')} tone="danger" busy={busy} onPress={() => void out()} testID="sign-out" />
      {legal}
    </Screen>
  );
}

const styles = StyleSheet.create({
  head: { flexDirection: 'row', alignItems: 'center', gap: 14 },
  headText: { flex: 1, minWidth: 0, gap: 2 },
  avatar: { width: 56, height: 56, borderRadius: 28, backgroundColor: colors.neutral400, alignItems: 'center', justifyContent: 'center' },
  initials: { fontFamily: fonts.bodyStrong, fontSize: 18, color: colors.text },
  wallet: {
    marginTop: space[2],
    paddingHorizontal: space[4],
    paddingVertical: 14,
    minHeight: MIN_TARGET,
    borderRadius: radius.md,
    backgroundColor: colors.accent100,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: space[3],
  },
  walletPressed: { backgroundColor: colors.accent200 },
  walletKicker: { ...StyleSheet.flatten(type.kicker), fontSize: 11, color: colors.accent800 },
  walletPoints: { fontFamily: fonts.bodyStrong, fontSize: 18, color: colors.accent900 },
  rows: { paddingTop: space[2] },
  language: { gap: space[2], paddingTop: space[3] },
  segmented: { flexDirection: 'row', backgroundColor: colors.surface2, borderRadius: radius.md, padding: 3, gap: 2 },
  segment: { flex: 1, minHeight: MIN_TARGET, alignItems: 'center', justifyContent: 'center', borderRadius: radius.sm },
  segmentOn: { backgroundColor: colors.surface },
  segmentText: { fontFamily: fonts.body, fontSize: 15, color: colors.text },
  segmentTextOn: { fontFamily: fonts.bodyStrong, color: colors.accent800 },
  legal: { paddingTop: space[6] },
});
