import { useQuery } from '@tanstack/react-query';
import { router } from 'expo-router';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { colors, space } from '@northline/mobile-kit';

import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n';
import { useShopFormat } from '../shop/common';
import { Button, Notice, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { EmptyState, LoadingList, QueryView, SignInPrompt, errorMessage } from '../ui/states';
import { KEYS, account, useAccountMutation } from './common';

/**
 * Favourite providers & shops (design 01 You › "Favourite providers"; S-58; signed in): newest first
 * (`GET /me/favourites`), each opening the provider's profile (Journey C, `/providers/<slug>`) to re-book, or removed
 * (`DELETE /me/favourites/{id}`). Hearts are added on the provider's profile.
 */
export function Favourites() {
  const f = useShopFormat();
  const { t } = useI18n();
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const list = useQuery({ queryKey: KEYS.favourites, queryFn: () => account().favourites(), enabled: signedIn, staleTime: 60_000 });
  const remove = useAccountMutation((id: string) => account().removeFavourite(id), { refresh: [KEYS.favourites] });
  if (!signedIn) {
    return (
      <Screen title={t('account.fav.title')} testID="favourites">
        <SignInPrompt message={t('account.fav.signIn')} />
      </Screen>
    );
  }
  return (
    <Screen title={t('account.fav.title')} testID="favourites">
      {remove.error ? <Notice message={errorMessage(remove.error, t)} /> : null}
      <QueryView
        query={list}
        skeleton={<LoadingList rows={3} height={56} />}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState message={t('account.fav.empty')} action={t('account.fav.find')} onAction={() => router.navigate('/services')} />}
      >
        {(items) => (
          <View accessibilityRole="list">
            {items.map((x) => (
              <View key={x.merchantId} style={styles.row} testID={`fav-${x.merchantId}`}>
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={x.name}
                  disabled={!x.slug}
                  onPress={() => x.slug && router.push(`/providers/${encodeURIComponent(x.slug)}` as never)}
                  style={styles.text}
                >
                  <Text style={[type.body, type.strong]}>{x.name}</Text>
                  <Text style={type.small}>
                    {[f.tier(x.tier), x.visits > 0 ? t('account.fav.visits', { n: x.visits }) : null, x.lastAt ? t('account.fav.last', { date: f.date(x.lastAt) }) : null].filter(Boolean).join(' · ')}
                  </Text>
                </Pressable>
                <Button label={t('account.fav.remove')} tone="ghost" hint={x.name} disabled={remove.isPending} onPress={() => remove.mutate(x.merchantId)} testID={`fav-remove-${x.merchantId}`} />
              </View>
            ))}
          </View>
        )}
      </QueryView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: space[2], paddingVertical: space[2], borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.divider },
  text: { flex: 1, minWidth: 0, gap: 2, minHeight: 48, justifyContent: 'center' },
});
