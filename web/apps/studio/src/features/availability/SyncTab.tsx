import { useQuery } from '@tanstack/react-query';
import { ErrorState, PageSkeleton } from '@northline/ui';
import { useMerchantId, useRole } from '../shell/api';
import { DAYS, syncQuery, useSetBookable, useToggleCalendar, type Calendar, type Days } from './api';
import { useAvailabilityT } from './messages';

type T = ReturnType<typeof useAvailabilityT>;

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

function calSub(c: Calendar, t: T): string {
  if (c.provider === 'ical') return c.connected ? t('readOnlyFeed') : t('notConnected');
  if (!c.connected) return t('notConnected');
  return c.lastSyncAt ? t('twoWaySynced', { ago: ago(c.lastSyncAt, t) }) : t('twoWay');
}

export function SyncTab() {
  const t = useAvailabilityT();
  const merchantId = useMerchantId();
  const role = useRole();
  const q = useQuery(syncQuery(merchantId));
  const toggle = useToggleCalendar(merchantId);
  const bookable = useSetBookable(merchantId);
  if (q.isPending) return <PageSkeleton kpis={0} rows={5} />;
  if (q.isError) return <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} />;
  const ical = q.data.calendars.find(c => c.provider === 'ical');
  const canEdit = role !== 'bookkeeper';
  return (
    <div className="nl-av-rules">
      <div>
        <h3 className="nl-av-h3">{t('calendars')}</h3>
        <ul className="nl-av-list">
          {q.data.calendars.map(c => (
            <li key={c.provider} className="nl-av-item nl-av-cal">
              <span><strong>{t(`cal_${c.provider}`)}{c.connected && c.provider !== 'ical' && c.accountLabel ? ` · ${c.accountLabel}` : ''}</strong><span className="nl-av-sub">{calSub(c, t)}</span></span>
              <button type="button" className="nl-chip nl-av-smallchip" aria-pressed={c.connected} disabled={!canEdit || toggle.isPending} onClick={() => toggle.mutate({ provider: c.provider, connect: !c.connected })}>{c.connected ? t('connected') : t('connect')}</button>
            </li>
          ))}
        </ul>
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
    </div>
  );
}
