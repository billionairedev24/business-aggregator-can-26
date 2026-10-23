import { useQuery } from '@tanstack/react-query';
import { useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { Share, StyleSheet, Text, View } from 'react-native';

import { ApiError, LINE, radius, semantic, space, textStyles } from '@northline/mobile-kit';

import type { Referral } from '../api/account';
import { useAuth } from '../auth/AuthProvider';
import { useShopFormat } from '../shop/common';
import { Body, Button, Field, Notice, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { LoadingList, QueryView, SignInPrompt, errorMessage } from '../ui/states';
import { KEYS, account, useAccountMutation } from './common';
import { Heading } from './parts';

/**
 * S-152 Invite a neighbour (`/account/invite`, `?code=` fills the form): the person's code and the phone's share sheet
 * with the consumer site's link, how their invites went, and — for a new account that hasn't used one — a neighbour's
 * code (`POST /me/referral/claim`; the api's 422 says why a code can't be used).
 */
export function Invite() {
  const f = useShopFormat();
  const { t } = f;
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const referral = useQuery({
    queryKey: KEYS.referral,
    queryFn: () => account().referral(),
    enabled: signedIn,
    staleTime: 60_000,
  });
  if (!signedIn) {
    return (
      <Screen title={t('title.invite')} testID="invite">
        <SignInPrompt message={t('loyalty.signIn')} />
      </Screen>
    );
  }
  return (
    <Screen title={t('title.invite')} testID="invite">
      <QueryView query={referral} skeleton={<LoadingList rows={3} />}>
        {(r) => <InviteBody r={r} f={f} />}
      </QueryView>
    </Screen>
  );
}

function InviteBody({ r, f }: { r: Referral; f: ReturnType<typeof useShopFormat> }) {
  const { t } = f;
  const params = useLocalSearchParams<{ code?: string }>();
  const [code, setCode] = useState(typeof params.code === 'string' ? params.code.toUpperCase() : '');
  const claim = useAccountMutation((c: string) => account().claimReferral(c), {
    set: KEYS.referral,
    refresh: [KEYS.points],
  });
  const share = () =>
    void Share.share({
      message: t('loyalty.shareText', { code: r.code, url: r.shareUrl }),
      url: r.shareUrl,
    }).catch(() => undefined);
  const fieldError = claim.error instanceof ApiError ? claim.error.fieldMessage('code') : undefined;
  return (
    <>
      {r.pointsEach > 0 ? (
        <Body>
          {t('loyalty.inviteLede', {
            min: f.money(r.minOrderCents),
            points: r.pointsEach,
            value: f.money(r.pointsEach),
          })}
        </Body>
      ) : (
        <Body tone="muted">{t('loyalty.inviteNone')}</Body>
      )}
      <View style={styles.code} accessible accessibilityLabel={`${t('loyalty.yourCode')}: ${r.code.split('').join(' ')}`} testID="invite-code">
        <Text style={type.kicker}>{t('loyalty.yourCode')}</Text>
        <Text style={styles.codeText} selectable>
          {r.code}
        </Text>
      </View>
      <Button label={t('loyalty.share')} onPress={share} testID="invite-share" />
      <Body tone="small">
        {t('loyalty.inviteStats', {
          pending: r.pending,
          rewarded: r.rewarded,
          points: r.pointsEarned,
        })}
      </Body>
      {r.claimed ? (
        <Notice
          tone="info"
          message={t(`loyalty.claimed.${r.claimed.state}`, {
            code: r.claimed.code,
            min: f.money(r.minOrderCents),
          })}
          testID="invite-claimed"
        />
      ) : null}
      {r.canClaim ? (
        <View style={styles.claim}>
          <Heading>{t('loyalty.haveCode')}</Heading>
          <Field
            label={t('loyalty.codeLabel')}
            value={code}
            onChangeText={(v) => setCode(v.toUpperCase())}
            autoCapitalize="characters"
            autoCorrect={false}
            error={fieldError}
            testID="invite-claim-code"
          />
          <Button label={t('loyalty.useCode')} busy={claim.isPending} onPress={() => claim.mutate(code.trim())} testID="invite-claim" />
          {claim.error && !fieldError ? <Notice message={errorMessage(claim.error, t)} /> : null}
        </View>
      ) : null}
    </>
  );
}

const styles = StyleSheet.create({
  code: { padding: space[4], borderRadius: radius.panel, backgroundColor: semantic.surface, gap: space[1], alignItems: 'flex-start' },
  codeText: { ...StyleSheet.flatten(textStyles.priceLg), letterSpacing: LINE.bold },
  claim: { gap: space[3], marginTop: space[4] },
});
