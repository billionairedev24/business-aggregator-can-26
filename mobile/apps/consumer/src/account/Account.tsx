import { useQuery, useQueryClient } from '@tanstack/react-query';
import * as Linking from 'expo-linking';
import { router } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { Icon, MIN_TARGET, colors, fonts, radius, semantic, space, type Locale } from '@northline/mobile-kit';

import { useAuth } from '../auth/AuthProvider';
import { config, fullNav, legalUrl } from '../config';
import { useI18n } from '../i18n';
import { useDeliveryLocation } from '../location/DeliveryLocation';
import { formatMoney } from '../shop/common';
import { Body, Button, Link, Notice, Row, Tag, Title, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { ErrorState, SignInPrompt, Skeleton } from '../ui/states';
import { KEYS, account, clock, count, decimal, monthYear, useAccountSummary, worded } from './common';
import { SettingsGroup, SettingsRow } from './parts';
import { activityTitle } from './Orders';
import { targetOf } from './routes';

/**
 * Account tab (redesign spec § 2.4 → MA:296 ACCOUNT): the 56 pt avatar with "Hi, {first}" and the email, "Your orders"
 * (the two latest of `GET /me/activity`, See all → Activity), then the settings in bordered groups (account, preferences,
 * help), the language, Sign out and the legal links. The dietary preferences are behind the full navigation flag
 * (EXPO_PUBLIC_FULL_NAV, thrash item 6); Wallet & points is back for everyone with S-152's points and Plus; Favourites is "Saved". D3 Profile (design 01 `account`): who you are (name, reliability, member since, where you are), the
 * wallet up top (points and Plus, → Wallet), then the design's rows, each leading somewhere real (`GET
 * /me/account-summary` gives their values): personal details, addresses & household, payment methods, security &
 * sign-in, notifications, dietary & accessibility, favourite providers, refunds & help, "Language / Langue" (here, in
 * place — the account's language follows, `PATCH /me/preferences`), becoming a seller (the website), sign-out and the
 * legal links, and S-152's Invite a neighbour. Guests get the sign-in
 * prompt, the language and the legal links.
 */
export function Account() {
  const { t, locale, setLocale } = useI18n();
  const { status, signOut, ended } = useAuth();
  const qc = useQueryClient();
  const signedIn = status === 'signedIn';
  const summary = useAccountSummary();
  const profile = useQuery({ queryKey: KEYS.profile, queryFn: () => account().profile(), enabled: signedIn, staleTime: 300_000 });
  const activity = useQuery({ queryKey: KEYS.activity, queryFn: () => account().activity(), enabled: signedIn, staleTime: 30_000 });
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

  const first = p?.firstName ?? '';
  const recent = (activity.data ?? []).slice(0, 2);
  return (
    <Screen testID="you">
      <Pressable accessibilityRole="button" accessibilityLabel={[first ? t('rd.account.hi', { name: first }) : t('you.signedInNoName'), p?.email].filter(Boolean).join(', ')} onPress={() => router.push('/account/profile')} style={styles.head} testID="you-head">
        <View style={styles.avatar}>
          <Text style={styles.initials}>{p ? `${p.firstName.charAt(0)}${p.lastName.charAt(0)}`.toUpperCase() : ''}</Text>
        </View>
        <View style={styles.headText}>
          {profile.isPending ? <Skeleton height={24} width="60%" /> : <Text accessibilityRole="header" style={styles.hi}>{first ? t('rd.account.hi', { name: first }) : name || t('you.signedInNoName')}</Text>}
          {p?.email ? <Text style={type.small} numberOfLines={1}>{p.email}</Text> : null}
          {meta ? <Text style={type.small}>{meta}</Text> : null}
        </View>
      </Pressable>
      {profile.isError && !p ? <ErrorState error={profile.error} onRetry={() => void profile.refetch()} /> : null}

      <View style={styles.section} testID="you-orders">
        <View style={styles.sectionHead}>
          <Text accessibilityRole="header" style={styles.h3}>
            {t('rd.account.yourOrders')}
          </Text>
          <Link label={t('rd.common.seeAll')} onPress={() => router.navigate('/orders')} testID="you-orders-all" />
        </View>
        {activity.isPending ? (
          <Skeleton height={56} />
        ) : recent.length === 0 ? (
          <Body tone="small">{t('rd.account.noOrders')}</Body>
        ) : (
          recent.map((i) => {
            const target = targetOf(i);
            return (
              <Pressable
                key={`${i.kind}-${i.id}`}
                accessibilityRole="button"
                accessibilityLabel={[activityTitle(i, t), worded(t, 'account.status', i.status)].join(', ')}
                disabled={!target || !('route' in target)}
                onPress={() => target && 'route' in target && router.push(target.route as never)}
                style={styles.orderRow}
              >
                <Icon name={i.kind === 'booking' || i.kind === 'quote' ? 'calendar-check' : 'package'} size={22} />
                <View style={styles.headText}>
                  <Text style={[type.body, type.strong]} numberOfLines={1}>
                    {activityTitle(i, t)}
                  </Text>
                  <Text style={type.small} numberOfLines={1}>
                    {[i.ref, worded(t, 'account.status', i.status)].filter(Boolean).join(' · ')}
                  </Text>
                </View>
                <Icon name="caret-right" size={16} color={colors.textMuted} />
              </Pressable>
            );
          })
        )}
      </View>

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

      <SettingsGroup title={t('rd.account.groupAccount')}>
        <SettingsRow icon="user" name={t('account.you.profile')} value={p?.email ?? undefined} onPress={() => router.push('/account/profile')} testID="row-profile" />
        <SettingsRow icon="map-pin" name={t('account.you.addresses')} value={value(s?.addresses ? String(s.addresses.count) : null)} onPress={() => router.push('/account/addresses')} testID="row-addresses" />
        <SettingsRow icon="credit-card" name={t('account.you.payments')} value={value(card)} onPress={() => router.push('/account/payments')} testID="row-payments" />
        <SettingsRow icon="lock-simple" name={t('account.you.security')} value={value(signIn)} onPress={() => router.push('/security')} testID="row-security" />
      </SettingsGroup>
      <SettingsGroup title={t('rd.account.groupPrefs')}>
        <SettingsRow icon="bell" name={t('account.you.notifications')} value={value(quiet)} onPress={() => router.push('/account/notifications')} testID="row-notifications" />
        {fullNav() ? (
          <SettingsRow icon="list-checks" name={t('account.you.preferences')} value={value(s?.dietary?.length ? s.dietary.join(', ') : null)} onPress={() => router.push('/account/preferences')} testID="row-preferences" />
        ) : null}
        <SettingsRow icon="chat-circle" name={t('hire.messages.row')} onPress={() => router.push('/messages' as never)} testID="row-messages" />
        <SettingsRow icon="hand-coins" name={t('loyalty.inviteRow')} onPress={() => router.push('/account/invite')} testID="row-invite" />
        <SettingsRow icon="basket" name={t('group.codeTitle')} onPress={() => router.push('/group')} testID="row-group" />
        <SettingsRow icon="heart" name={t('rd.account.saved')} value={value(s?.favourites != null ? String(s.favourites) : null)} onPress={() => router.push('/account/favourites')} testID="row-favourites" />
      </SettingsGroup>
      <SettingsGroup title={t('rd.account.groupHelp')}>
        <SettingsRow icon="question" name={t('account.you.help')} value={value(s?.openCases ? t('account.you.cases', { n: s.openCases }) : null)} onPress={() => router.push('/account/help')} testID="row-help" />
        <SettingsRow icon="storefront" name={t('account.you.sell')} hint={t('common.opensInBrowser')} onPress={() => void Linking.openURL(`${config.siteOrigin}/sell`)} testID="row-sell" />
        {/* S-159: deleting the account is one tap from Account (App Store 5.1.1(v)) */}
        <SettingsRow icon="trash" name={t('delete.row')} tone="danger" onPress={() => router.push('/account/delete')} testID="row-delete" />
      </SettingsGroup>

      {language}
      <Button label={t('you.signOut')} tone="danger" large busy={busy} onPress={() => void out()} testID="sign-out" />
      {legal}
    </Screen>
  );
}

const styles = StyleSheet.create({
  head: { flexDirection: 'row', alignItems: 'center', gap: space[3], minHeight: MIN_TARGET },
  headText: { flex: 1, minWidth: 0, gap: 2 },
  avatar: { width: 64, height: 64, borderRadius: 32, backgroundColor: semantic['action-primary'], alignItems: 'center', justifyContent: 'center' },
  initials: { fontFamily: fonts.display, fontSize: 24, color: semantic['action-primary-text'] },
  hi: { fontFamily: fonts.display, fontSize: 28, lineHeight: 34, color: colors.text },
  section: { gap: space[2] },
  sectionHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  h3: { fontFamily: fonts.display, fontSize: 20, lineHeight: 26, color: colors.text },
  orderRow: { flexDirection: 'row', alignItems: 'center', gap: space[3], minHeight: 56, paddingHorizontal: 14, paddingVertical: space[3], borderWidth: 1, borderColor: colors.border, borderRadius: radius.md },
  wallet: {
    marginTop: space[2],
    paddingHorizontal: space[4],
    paddingVertical: 14,
    minHeight: MIN_TARGET,
    borderRadius: radius.md,
    backgroundColor: semantic['surface-brand'],
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: space[3],
  },
  walletPressed: { backgroundColor: semantic['surface-hover'] },
  walletKicker: { ...StyleSheet.flatten(type.kicker), fontSize: 11, color: semantic['badge-brand-text'] },
  walletPoints: { fontFamily: fonts.bold, fontSize: 18, color: semantic['badge-brand-text'] },
  rows: { paddingTop: space[2] },
  language: { gap: space[2], paddingTop: space[3] },
  segmented: { flexDirection: 'row', backgroundColor: semantic['surface-muted'], borderRadius: radius.pill, padding: 3, gap: 2 },
  segment: { flex: 1, minHeight: MIN_TARGET, alignItems: 'center', justifyContent: 'center', borderRadius: radius.pill },
  segmentOn: { backgroundColor: colors.surface },
  segmentText: { fontFamily: fonts.medium, fontSize: 15, color: colors.text },
  segmentTextOn: { fontFamily: fonts.bold, color: colors.text },
  legal: { paddingTop: space[6] },
});
