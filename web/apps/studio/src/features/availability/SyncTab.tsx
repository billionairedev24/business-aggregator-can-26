import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Checkbox, Dialog, ErrorState, PageSkeleton, Skeleton } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useMerchantId, useRole } from '../shell/api';
import { DAYS, goTo, sourcesQuery, syncQuery, useChooseSources, useSetBookable, useToggleCalendar, type Calendar, type Days } from './api';
import { useAvailabilityT } from './messages';

type T = ReturnType<typeof useAvailabilityT>;
type TwoWay = Exclude<Calendar['provider'], 'ical'>;

/** Where the OAuth callback lands: `/b/$id/availability?calendar=google&result=connected[&choose=1]`. */
export interface CalendarReturn { calendar?: TwoWay; result?: 'connected' | 'denied' | 'failed' | 'scopes' | 'expired'; choose?: boolean }

function ago(iso: string, t: T, now = Date.now()) {
  const min = Math.max(0, Math.round((now - new Date(iso).getTime()) / 60000));
  return min < 1 ? t('justNow') : min < 60 ? t('minutesAgo', { n: min }) : t('hoursAgo', { n: Math.round(min / 60) });
}

/** "Mon–Thu, Sat" from the bookable days. */
export function daysSummary(days: Days, t: T): string {
  const on = DAYS.map(d => days[d].length > 0);
  const out: string[] = [];
  for (let i = 0; i < 7; i++) {
    if (!on[i]) continue;
    let j = i;
    while (j + 1 < 7 && on[j + 1]) j++;
    out.push(j - i >= 2 ? `${t(`day_${DAYS[i]!}`)}–${t(`day_${DAYS[j]!}`)}` : DAYS.slice(i, j + 1).map(d => t(`day_${d}`)).join(', '));
    i = j;
  }
  return out.join(', ');
}

export function calSub(c: Calendar, t: T): string {
  if (c.provider === 'ical') return c.connected ? t('readOnlyFeed') : t('notConnected');
  if (c.state === 'reconnect') return t('reconnectNeeded');
  if (!c.connected) return c.available === false ? t('unavailable') : t('notConnected');
  const synced = c.lastSyncAt ? t('twoWaySynced', { ago: ago(c.lastSyncAt, t) }) : t('twoWay');
  return c.sources?.length ? `${synced} · ${t('sourcesLine', { names: c.sources.join(', ') })}` : synced;
}

export function SyncTab({ returned, onReturnSeen }: { returned?: CalendarReturn; onReturnSeen?: () => void }) {
  const t = useAvailabilityT();
  const merchantId = useMerchantId();
  const role = useRole();
  const q = useQuery(syncQuery(merchantId));
  const toggle = useToggleCalendar(merchantId);
  const bookable = useSetBookable(merchantId);
  const [choosing, setChoosing] = useState<TwoWay | null>(returned?.result === 'connected' && returned.choose && returned.calendar ? returned.calendar : null);
  const [banner, setBanner] = useState(returned?.calendar && returned.result ? returned : undefined);
  useEffect(() => { if (returned?.result) onReturnSeen?.(); }, [returned, onReturnSeen]);
  if (q.isPending) return <PageSkeleton kpis={0} rows={5} />;
  if (q.isError) return <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} />;
  const ical = q.data.calendars.find(c => c.provider === 'ical');
  const canEdit = role !== 'bookkeeper';
  const onClick = (c: Calendar) => {
    const connect = !c.connected || c.state === 'reconnect';
    toggle.mutate({ provider: c.provider, connect });
  };
  return (
    <div className="nl-av-rules">
      <div>
        {banner?.calendar && banner.result ? (
          <Alert tone={banner.result === 'connected' ? 'info' : 'error'} role="status"
            actions={<button type="button" className="btn btn-ghost" onClick={() => setBanner(undefined)}>{t('dismiss')}</button>}>
            {t(`result_${banner.result}`, { provider: t(`cal_${banner.calendar}`) })}
          </Alert>
        ) : null}
        <h3 className="nl-av-h3">{t('calendars')}</h3>
        <ul className="nl-av-list">
          {q.data.calendars.map(c => {
            const reconnect = c.state === 'reconnect';
            const unavailable = c.provider !== 'ical' && c.available === false && !c.connected;
            return (
              <li key={c.provider} className="nl-av-item nl-av-cal">
                <span><strong>{t(`cal_${c.provider}`)}{c.connected && c.provider !== 'ical' && c.accountLabel ? ` · ${c.accountLabel}` : ''}</strong><span className={reconnect ? 'nl-av-sub nl-av-warn' : 'nl-av-sub'}>{calSub(c, t)}</span></span>
                <span className="nl-av-itemactions">
                  {c.provider !== 'ical' && c.connected && !reconnect && canEdit ? (
                    <button type="button" className="btn btn-ghost nl-av-smallchip" onClick={() => setChoosing(c.provider as TwoWay)}>{t('chooseCalendars')}</button>
                  ) : null}
                  <button type="button" className={reconnect ? 'btn btn-primary nl-av-smallchip' : 'nl-chip nl-av-smallchip'} aria-pressed={reconnect ? undefined : c.connected}
                    disabled={!canEdit || toggle.isPending || unavailable} onClick={() => onClick(c)}>
                    {reconnect ? t('reconnect') : c.connected ? t('connected') : t('connect')}
                  </button>
                </span>
              </li>
            );
          })}
        </ul>
        {toggle.isError ? <Alert tone="error">{t('saveError')}</Alert> : null}
        <h3 className="nl-av-h3 nl-av-how">{t('howSync')}</h3>
        <ul className="nl-av-bullets">
          <li>{t('sync1')}</li><li>{t('sync2')}</li><li>{t('sync3')}</li>
          <li>{ical?.feedUrl ? <>{t('sync4')} <code className="nl-av-code">{ical.feedUrl}</code></> : t('sync4none')}</li>
        </ul>
      </div>
      <div>
        <h3 className="nl-av-h3">{t('team')}</h3>
        <ul className="nl-av-list">
          {q.data.team.map(m => (
            <li key={m.userId} className="nl-av-item">
              <span><strong>{m.name}</strong> · {t(`role_${m.role}` as Parameters<T>[0])}<span className="nl-av-sub">{m.days ? t('daysSummary', { days: daysSummary(m.days, t) || t('noHoursYet') }) : t('noHoursYet')}</span></span>
              <button type="button" className="nl-chip nl-av-smallchip" aria-pressed={m.bookable} disabled={role !== 'owner'} title={role !== 'owner' ? t('notAMember') : undefined}
                onClick={() => bookable.mutate({ userId: m.userId, bookable: !m.bookable })}>{m.bookable ? t('bookable') : t('hidden')}</button>
            </li>
          ))}
        </ul>
        <p className="nl-small nl-muted">{t('teamNote')}</p>
      </div>
      {choosing ? <ChooseCalendarsDialog provider={choosing} onClose={() => setChoosing(null)} /> : null}
    </div>
  );
}

/** "Choose calendars": which of the member's calendars block slots (asks the provider for the list first if needed). */
export function ChooseCalendarsDialog({ provider, onClose }: { provider: TwoWay; onClose: () => void }) {
  const t = useAvailabilityT();
  const merchantId = useMerchantId();
  const q = useQuery(sourcesQuery(merchantId, provider));
  const save = useChooseSources(merchantId, provider);
  const [picked, setPicked] = useState<string[] | null>(null);
  const [tried, setTried] = useState(false);
  const selected = picked ?? q.data?.items.filter(i => i.selected).map(i => i.id) ?? [];
  const name = t(`cal_${provider}`);
  const consent = q.data?.authorizationUrl;
  const serverError = save.error instanceof ValidationError ? save.error.errors[0]?.message : undefined;
  const error = serverError ?? (tried && selected.length === 0 ? t('chooseNone') : undefined);
  const submit = () => {
    setTried(true);
    if (selected.length === 0) return;
    save.mutate(selected, { onSuccess: onClose });
  };
  const actions = consent
    ? <><button type="button" className="btn btn-ghost" onClick={onClose}>{t('chooseCancel')}</button><button type="button" className="btn btn-primary" onClick={() => goTo.assign(consent)}>{t('chooseAllow', { provider: name })}</button></>
    : <><button type="button" className="btn btn-ghost" onClick={onClose}>{t('chooseCancel')}</button><button type="button" className="btn btn-primary" disabled={save.isPending || !q.data} onClick={submit}>{t('chooseSave')}</button></>;
  return (
    <Dialog open onClose={onClose} title={t('chooseTitle')} actions={actions}>
      {q.isPending ? <Skeleton height={96} /> : q.isError ? <ErrorState message={t('chooseLoadError')} onRetry={() => void q.refetch()} /> : consent ? (
        <p>{t('chooseConsent', { provider: name })}</p>
      ) : (
        <>
          <p className="nl-small nl-muted">{t('chooseHelp')}</p>
          <ul className="nl-av-list" aria-label={t('chooseTitle')}>
            {q.data.items.map(i => (
              <li key={i.id} className="nl-av-item">
                <Checkbox checked={selected.includes(i.id)} label={i.primary ? `${i.name} (${t('choosePrimary')})` : i.name}
                  onChange={on => setPicked(on ? [...selected, i.id] : selected.filter(id => id !== i.id))} />
              </li>
            ))}
          </ul>
          {error ? <p className="nl-av-error" role="alert">{error}</p> : null}
          {save.isError && !(save.error instanceof ValidationError) ? <Alert tone="error">{t('saveError')}</Alert> : null}
        </>
      )}
    </Dialog>
  );
}
