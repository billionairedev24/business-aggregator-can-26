import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Text, View } from 'react-native';

import type { PushPermission } from '@northline/mobile-kit';

import { NOTIFY_CHANNELS, NOTIFY_EVENTS, type NotificationPrefs, type Matrix } from '../api/account';
import { useAuth } from '../auth/AuthProvider';
import { useI18n } from '../i18n';
import { Chip } from '../shop/parts';
import { Body, Button, Notice, Tag, type } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { LoadingList, QueryView, SignInPrompt, errorMessage } from '../ui/states';
import { KEYS, account, clock, useAccountMutation } from './common';
import { Heading, LineRow, Toggle, accountStyles } from './parts';
import { phonePush } from './phonePush';

const FROM = ['21:00', '22:00', '23:00'] as const;
const TO = ['06:00', '07:00', '08:00'] as const;
const hhmm = (v: string) => v.slice(0, 5);

/**
 * Notification settings (design 01 You › Notifications; the consumer web's S-59 matrix; signed in): for each kind of
 * update — booking reminders, orders, sign-off, quotes, refunds & cases, offers, security — push, SMS and email
 * (security alerts locked on), quiet hours (push and SMS held, never email), the notification language and marketing
 * email (CASL). `GET`/`PUT /me/notifications`, only the changed cells sent. Push is S-102's: the server sends to every
 * installation of the app that allows notifications; "This phone" asks this phone's permission (`phonePush.ts`).
 * Changes stay on screen until "Save preferences" (a failed save keeps them).
 */
export function NotificationSettings() {
  const { t } = useI18n();
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const prefs = useQuery({ queryKey: KEYS.notifications, queryFn: () => account().notifications(), enabled: signedIn, staleTime: 60_000 });
  if (!signedIn) {
    return (
      <Screen title={t('account.notif.title')} testID="notification-settings">
        <SignInPrompt message={t('account.notif.signIn')} />
      </Screen>
    );
  }
  return (
    <Screen title={t('account.notif.title')} testID="notification-settings">
      <Body tone="muted">{t('account.notif.lede')}</Body>
      <ThisPhone />
      <QueryView query={prefs} skeleton={<LoadingList rows={7} height={48} />}>
        {(p) => <Form initial={p} />}
      </QueryView>
    </Screen>
  );
}

/** "This phone": whether the system lets Northline notify, and the way to turn it on. */
function ThisPhone() {
  const { t } = useI18n();
  const push = phonePush();
  const [permission, setPermission] = useState<PushPermission | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!push) return;
    void push.permission().then(setPermission).catch(() => setPermission(null));
  }, [push]);
  if (!push || !permission) return null;
  const on = permission === 'granted' || permission === 'provisional';
  const enable = async () => {
    setBusy(true);
    setFailed(false);
    try {
      setPermission(await push.registration.enable());
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };
  return (
    <View testID="this-phone">
      <LineRow right={<Tag label={t(on ? 'account.notif.phoneOn' : 'account.notif.phoneOff')} tone={on ? 'accent' : 'neutral'} />}>
        <Text style={[type.body, type.strong]}>{t('account.notif.phone')}</Text>
        <Text style={type.small}>{t(on ? 'account.notif.phoneOnBody' : permission === 'denied' ? 'account.notif.phoneDenied' : 'account.notif.phoneOffBody')}</Text>
      </LineRow>
      {!on && permission === 'undetermined' ? <Button label={t('account.notif.turnOn')} busy={busy} onPress={() => void enable()} testID="push-enable" /> : null}
      {!on && permission === 'denied' ? <Button label={t('account.notif.openSettings')} tone="secondary" onPress={() => void push.openSettings()} testID="push-settings" /> : null}
      {failed ? <Notice message={t('account.notif.phoneFailed')} /> : null}
    </View>
  );
}

function Form({ initial }: { initial: NotificationPrefs }) {
  const { t, locale } = useI18n();
  const [p, setP] = useState(initial);
  const [saved, setSaved] = useState(false);
  // S-100's inbox shows the quiet hours from its own copy of /me/notifications: read it again after a save
  const save = useAccountMutation((c: Parameters<ReturnType<typeof account>['saveNotifications']>[0]) => account().saveNotifications(c), {
    set: KEYS.notifications,
    refresh: [['services', 'notification-prefs']],
  });
  const update = (next: Partial<NotificationPrefs>) => {
    setP((x) => ({ ...x, ...next }));
    setSaved(false);
  };
  const toggle = (event: string, channel: string) => update({ matrix: { ...p.matrix, [event]: { ...p.matrix[event], [channel]: !p.matrix[event]?.[channel] } } });
  const changed = (): Matrix => {
    const out: Matrix = {};
    for (const e of NOTIFY_EVENTS)
      for (const c of NOTIFY_CHANNELS) {
        if (e !== 'security' && !!p.matrix[e]?.[c] !== !!initial.matrix[e]?.[c]) (out[e] ??= {})[c] = !!p.matrix[e]?.[c];
      }
    return out;
  };
  const submit = () =>
    save.mutate(
      { matrix: changed(), quietOn: p.quietOn, quietFrom: hhmm(p.quietFrom), quietTo: hhmm(p.quietTo), language: p.language, marketing: p.marketing },
      { onSuccess: () => setSaved(true) },
    );

  return (
    <View style={accountStyles.form}>
      {NOTIFY_EVENTS.map((e) => (
        <View key={e} testID={`event-${e}`}>
          <Heading>{t(`account.notif.ev.${e}`)}</Heading>
          <Text style={type.small}>{t(`account.notif.evd.${e}`)}</Text>
          {NOTIFY_CHANNELS.map((c) => (
            <Toggle
              key={c}
              label={t('account.notif.cell', { channel: t(`account.notif.ch.${c}`), event: t(`account.notif.ev.${e}`) })}
              value={!!p.matrix[e]?.[c]}
              onChange={() => toggle(e, c)}
              disabled={e === 'security'}
              testID={`cell-${e}-${c}`}
            />
          ))}
          {e === 'security' ? <Text style={type.small}>{t('account.notif.securityLocked')}</Text> : null}
        </View>
      ))}

      <Heading>{t('account.notif.quiet')}</Heading>
      <Toggle label={t('account.notif.quietOn')} value={p.quietOn} onChange={(v) => update({ quietOn: v })} testID="quiet-on" />
      {p.quietOn ? (
        <>
          <Body tone="small">{t('account.notif.quietFrom')}</Body>
          <View style={accountStyles.chips} accessibilityRole="radiogroup" accessibilityLabel={t('account.notif.quietFrom')}>
            {FROM.map((v) => (
              <Chip key={v} role="radio" label={clock(v, locale)} on={hhmm(p.quietFrom) === v} onPress={() => update({ quietFrom: v })} testID={`quiet-from-${v}`} />
            ))}
          </View>
          <Body tone="small">{t('account.notif.quietTo')}</Body>
          <View style={accountStyles.chips} accessibilityRole="radiogroup" accessibilityLabel={t('account.notif.quietTo')}>
            {TO.map((v) => (
              <Chip key={v} role="radio" label={clock(v, locale)} on={hhmm(p.quietTo) === v} onPress={() => update({ quietTo: v })} testID={`quiet-to-${v}`} />
            ))}
          </View>
        </>
      ) : null}
      <Body tone="small">{t('account.notif.quietNote')}</Body>

      <Heading>{t('account.notif.language')}</Heading>
      <View style={accountStyles.chips} accessibilityRole="radiogroup" accessibilityLabel={t('account.notif.language')}>
        {(['app', 'en', 'fr'] as const).map((l) => (
          <Chip key={l} role="radio" label={t(`account.notif.lang.${l}`)} on={p.language === l} onPress={() => update({ language: l })} testID={`notif-lang-${l}`} />
        ))}
      </View>
      <Heading>{t('account.notif.marketing')}</Heading>
      <View style={accountStyles.chips} accessibilityRole="radiogroup" accessibilityLabel={t('account.notif.marketing')}>
        {(['weekly', 'rewards', 'none'] as const).map((m) => (
          <Chip key={m} role="radio" label={t(`account.notif.mk.${m}`)} on={p.marketing === m} onPress={() => update({ marketing: m })} testID={`marketing-${m}`} />
        ))}
      </View>

      {save.error ? <Notice message={errorMessage(save.error, t)} testID="notif-error" /> : null}
      {saved ? <Notice tone="info" message={t('account.notif.saved')} testID="notif-saved" /> : null}
      <Button label={t('account.notif.save')} busy={save.isPending} onPress={submit} testID="notif-save" />
    </View>
  );
}
