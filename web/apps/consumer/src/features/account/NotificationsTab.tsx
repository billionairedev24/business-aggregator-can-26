import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, ErrorState, Field, FormGrid, Select, Switch, TextInput, useLocale } from '@northline/ui';
import { useSession } from '../session/api';
import { FormSkeleton } from './ProfileTab';
import { NOTIFY_CHANNELS, NOTIFY_EVENTS, consentsQuery, notificationsQuery, useSaveNotifications, type Consents, type NotificationPrefs } from './settingsApi';
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
 *
 * S-108: the "Offers & rewards" row and "Marketing emails" are the person's CASL consents — never on by default; the
 * wording each one means and the consent history are shown under "Marketing messages", and a save sends the wording
 * versions shown. The offers row's email cell and "Marketing emails" are the same consent and move together.
 */
export function NotificationsTab() {
  const t = useSettingsT();
  const prefs = useQuery(notificationsQuery);
  const { locale } = useLocale();
  const consents = useQuery(consentsQuery(locale));
  return (
    <>
      <h1 id="acct-title" className="nl-acct-h1">{t('notifTitle')}</h1>
      <p className="nl-acct-lede">{t('notifLede')}</p>
      {prefs.isPending ? <FormSkeleton label={t('loading')} rows={7} />
        : prefs.isError ? <ErrorState message={t('loadError')} onRetry={() => void prefs.refetch()} />
          : <NotificationsForm initial={prefs.data} consents={consents.data} />}
      {consents.isError ? <ErrorState message={t('loadError')} onRetry={() => void consents.refetch()} /> : null}
    </>
  );
}

const CONSENT_CELL = (e: string) => e === 'offers';

function NotificationsForm({ initial, consents }: { initial: NotificationPrefs; consents?: Consents }) {
  const t = useSettingsT();
  const { locale } = useLocale();
  const session = useSession();
  const save = useSaveNotifications();
  const [p, setP] = useState(initial);
  const [saved, setSaved] = useState(false);
  useEffect(() => setP(initial), [initial]);
  const update = (next: Partial<NotificationPrefs>) => { setP(x => ({ ...x, ...next })); setSaved(false); };
  const toggle = (event: string, channel: string) => {
    const on = !p.matrix[event]?.[channel];
    const matrix = { ...p.matrix, [event]: { ...p.matrix[event], [channel]: on } };
    // the offers row's email cell is the marketing-email consent: the choice below follows it
    const marketing = CONSENT_CELL(event) && channel === 'email' ? (on ? (p.marketing === 'none' ? 'weekly' : p.marketing) : 'none') : p.marketing;
    update({ matrix, marketing });
  };
  const chooseMarketing = (marketing: NotificationPrefs['marketing']) =>
    update({ marketing, matrix: { ...p.matrix, offers: { ...p.matrix.offers, email: marketing !== 'none' } } });
  const changedCells = () => {
    const out: Record<string, Record<string, boolean>> = {};
    for (const e of NOTIFY_EVENTS) for (const c of NOTIFY_CHANNELS) {
      if (e !== 'security' && p.matrix[e]?.[c] !== initial.matrix[e]?.[c]) (out[e] ??= {})[c] = !!p.matrix[e]?.[c];
    }
    return out;
  };
  const submit = () => {
    const matrix = changedCells();
    const marketingChanged = p.marketing !== initial.marketing;
    const consentChanged = marketingChanged || Object.keys(matrix).some(CONSENT_CELL);
    save.mutate({
      matrix, quietOn: p.quietOn, quietFrom: hhmm(p.quietFrom), quietTo: hhmm(p.quietTo), language: p.language,
      ...(marketingChanged ? { marketing: p.marketing } : {}),
      ...(consentChanged ? {
        consentSource: 'web_settings' as const,
        consentWordings: Object.fromEntries((consents?.categories ?? []).map(c => [c.channel, c.wordingVersion])),
      } : {}),
    }, { onSuccess: () => setSaved(true) });
  };
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
          <Select value={p.marketing} onChange={e => chooseMarketing(e.target.value as NotificationPrefs['marketing'])}
            options={(['weekly', 'rewards', 'none'] as const).map(m => ({ value: m, label: t(`mk_${m}`) }))} />
        </Field>
      </FormGrid>
      {consents ? <MarketingConsents consents={consents} /> : null}
      {save.isError ? <p className="nl-error" role="alert">{t('saveError')}</p> : null}
      <div className="nl-acct-actions">
        <Button type="button" onClick={submit} disabled={save.isPending} aria-busy={save.isPending}>{t('savePrefs')}</Button>
        <span className="nl-small nl-muted" role="status">{saved ? t('notifSaved') : ''}</span>
      </div>
    </>
  );
}

/** S-108: what each marketing consent means (the wording the person agrees to), who asks, and its history. */
function MarketingConsents({ consents }: { consents: Consents }) {
  const t = useSettingsT();
  const { locale } = useLocale();
  const date = (iso: string) => new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
  return (
    <section aria-labelledby="nl-consents" className="nl-measure">
      <h2 id="nl-consents" className="nl-acct-h2">{t('consentTitle')}</h2>
      <p className="nl-small nl-muted">{t('consentLede')}</p>
      <ul className="nl-consents">
        {consents.categories.map(c => (
          <li key={c.category}>
            <strong>{t(`cat_${c.category}` as 'cat_marketing_email')}</strong>{' · '}
            <span>{c.granted && c.since ? t('consentGivenOn', { date: date(c.since) }) : t('consentNotGiven')}</span>
            <span className="nl-block nl-small">{c.wording}</span>
          </li>
        ))}
      </ul>
      <p className="nl-small nl-muted">{t('consentRequester', { requester: consents.requester })}</p>
      <h3 className="nl-acct-h3">{t('consentHistory')}</h3>
      {consents.history.length === 0 ? <p className="nl-small nl-muted">{t('consentHistoryEmpty')}</p> : (
        <div className="nl-acct-table">
          <table className="table">
            <thead><tr><th scope="col">{t('colDate')}</th><th scope="col">{t('consentWhat')}</th><th scope="col">{t('consentAction')}</th><th scope="col">{t('consentWhere')}</th></tr></thead>
            <tbody>
              {consents.history.map(h => (
                <tr key={h.id}>
                  <td>{date(h.at)}</td>
                  <td>{t(`cat_${h.category}` as 'cat_marketing_email')}</td>
                  <td>{t(h.action === 'granted' ? 'act_granted' : 'act_withdrawn')}</td>
                  <td>{t(`src_${h.source}` as 'src_web_settings')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
