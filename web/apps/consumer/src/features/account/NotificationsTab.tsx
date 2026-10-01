import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, ErrorState, Field, FormGrid, Select, Switch, TextInput, useLocale } from '@northline/ui';
import { useSession } from '../session/api';
import { FormSkeleton } from './ProfileTab';
import { NOTIFY_CHANNELS, NOTIFY_EVENTS, notificationsQuery, useSaveNotifications, type NotificationPrefs } from './settingsApi';
import { useSettingsT } from './settingsMessages';

const FROM = ['21:00', '22:00', '23:00'] as const;
const TO = ['06:00', '07:00', '08:00'] as const;
const hhmm = (v: string) => v.slice(0, 5);
const hourLabel = (v: string, locale: 'en' | 'fr') => {
  const h = Number(v.slice(0, 2));
  return locale === 'fr' ? `${h} h` : `${h % 12 === 0 ? 12 : h % 12}:00 ${h < 12 ? 'am' : 'pm'}`;
};

/**
 * Notifications (design 06 `at.notifications`): the event × channel matrix (security alerts locked on), quiet hours,
 * the notification language, the contact the messages go to (edited under Profile) and marketing email (CASL). Changes
 * are kept on the page until "Save preferences".
 */
export function NotificationsTab() {
  const t = useSettingsT();
  const prefs = useQuery(notificationsQuery);
  return (
    <>
      <h1 id="acct-title" className="nl-acct-h1">{t('notifTitle')}</h1>
      <p className="nl-acct-lede">{t('notifLede')}</p>
      {prefs.isPending ? <FormSkeleton label={t('loading')} rows={7} />
        : prefs.isError ? <ErrorState message={t('loadError')} onRetry={() => void prefs.refetch()} />
          : <NotificationsForm initial={prefs.data} />}
    </>
  );
}

function NotificationsForm({ initial }: { initial: NotificationPrefs }) {
  const t = useSettingsT();
  const { locale } = useLocale();
  const session = useSession();
  const save = useSaveNotifications();
  const [p, setP] = useState(initial);
  const [saved, setSaved] = useState(false);
  useEffect(() => setP(initial), [initial]);
  const update = (next: Partial<NotificationPrefs>) => { setP(x => ({ ...x, ...next })); setSaved(false); };
  const toggle = (event: string, channel: string) =>
    update({ matrix: { ...p.matrix, [event]: { ...p.matrix[event], [channel]: !p.matrix[event]?.[channel] } } });
  const changedCells = () => {
    const out: Record<string, Record<string, boolean>> = {};
    for (const e of NOTIFY_EVENTS) for (const c of NOTIFY_CHANNELS) {
      if (e !== 'security' && p.matrix[e]?.[c] !== initial.matrix[e]?.[c]) (out[e] ??= {})[c] = !!p.matrix[e]?.[c];
    }
    return out;
  };
  const submit = () => save.mutate({
    matrix: changedCells(), quietOn: p.quietOn, quietFrom: hhmm(p.quietFrom), quietTo: hhmm(p.quietTo), language: p.language, marketing: p.marketing,
  }, { onSuccess: () => setSaved(true) });
  const user = session.data?.user;
  return (
    <>
      <div className="nl-acct-table">
        <table className="table nl-notif-table">
          <thead><tr><th scope="col">{t('colEvent')}</th>{NOTIFY_CHANNELS.map(c => <th key={c} scope="col" className="nl-center">{t(`ch_${c}`)}</th>)}</tr></thead>
          <tbody>
            {NOTIFY_EVENTS.map(e => (
              <tr key={e}>
                <th scope="row"><strong>{t(`ev_${e}`)}</strong><span className="nl-block nl-small nl-muted">{t(`evd_${e}`)}</span></th>
                {NOTIFY_CHANNELS.map(c => {
                  const on = !!p.matrix[e]?.[c];
                  const locked = e === 'security';
                  return (
                    <td key={c} className="nl-center">
                      <button type="button" role="switch" aria-checked={on} className="nl-toggle" disabled={locked}
                        aria-label={t('cell', { channel: t(`ch_${c}`), event: t(`ev_${e}`), state: t(on ? 'on' : 'off') })}
                        onClick={() => toggle(e, c)} />
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <h2 className="nl-acct-h2">{t('quietTitle')}</h2>
      <div className="nl-quiet">
        <Switch checked={p.quietOn} onChange={on => update({ quietOn: on })} label={t(p.quietOn ? 'quietOn' : 'quietOff')} />
        {p.quietOn ? (
          <>
            <Select aria-label={t('quietFrom')} value={hhmm(p.quietFrom)} onChange={e => update({ quietFrom: e.target.value })} options={FROM.map(v => ({ value: v, label: hourLabel(v, locale) }))} />
            <span className="nl-muted">{t('quietTo')}</span>
            <Select aria-label={t('quietTo')} value={hhmm(p.quietTo)} onChange={e => update({ quietTo: e.target.value })} options={TO.map(v => ({ value: v, label: hourLabel(v, locale) }))} />
          </>
        ) : null}
      </div>
      <p className="nl-small nl-muted nl-measure">{t('quietNote')}</p>
      <h2 className="nl-acct-h2">{t('deliveryLang')}</h2>
      <FormGrid min={220} className="nl-acct-form">
        <Field label={t('notifLang')}>
          <Select value={p.language} onChange={e => update({ language: e.target.value as NotificationPrefs['language'] })}
            options={[{ value: 'app', label: t('lang_app', { language: t(locale === 'fr' ? 'lang_fr' : 'lang_en') }) }, { value: 'en', label: t('lang_en') }, { value: 'fr', label: t('lang_fr') }]} />
        </Field>
        <Field label={t('smsNumber')} hint={t('contactHint')}><TextInput value={user?.phone ?? ''} readOnly /></Field>
        <Field label={t('emailAddr')}><TextInput value={user?.email ?? ''} readOnly /></Field>
        <Field label={t('marketing')}>
          <Select value={p.marketing} onChange={e => update({ marketing: e.target.value as NotificationPrefs['marketing'] })}
            options={(['weekly', 'rewards', 'none'] as const).map(m => ({ value: m, label: t(`mk_${m}`) }))} />
        </Field>
      </FormGrid>
      {save.isError ? <p className="nl-error" role="alert">{t('saveError')}</p> : null}
      <div className="nl-acct-actions">
        <Button type="button" onClick={submit} disabled={save.isPending} aria-busy={save.isPending}>{t('savePrefs')}</Button>
        <span className="nl-small nl-muted" role="status">{saved ? t('notifSaved') : ''}</span>
      </div>
    </>
  );
}
