import { useQuery } from '@tanstack/react-query';
import { ErrorState, PageSkeleton, useLocale } from '@northline/ui';
import { useMerchant, useMerchantId } from '../shell/api';
import { CHANNELS, NOTIFICATION_EVENTS, notificationsQuery, useToggleNotification, type NotificationEvent } from './api';
import { useSettingsT } from './messages';

/** Rows that make sense for the portal: quotes are provider-only, stock is for sellers. */
export function eventsFor(type: string): NotificationEvent[] {
  return NOTIFICATION_EVENTS.filter(e =>
    e === 'quote_request' ? type === 'provider' || type === 'both' : e === 'low_stock' ? type === 'seller' || type === 'both' : true);
}

/** "21:00:00" → "9 pm" / « 21 h ». */
function hour(time: string, locale: string): string {
  const [h = 0, m = 0] = time.split(':').map(Number);
  if (locale === 'fr') return m ? `${h} h ${String(m).padStart(2, '0')}` : `${h} h`;
  const suffix = h < 12 ? 'am' : 'pm';
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return m ? `${h12}:${String(m).padStart(2, '0')} ${suffix}` : `${h12} ${suffix}`;
}

/** Notifications (design st.notifications): a plain event × channel matrix; each cell toggles and saves at once. */
export function NotificationsTab() {
  const t = useSettingsT();
  const { locale } = useLocale();
  const merchantId = useMerchantId();
  const merchant = useMerchant();
  const q = useQuery(notificationsQuery(merchantId));
  const toggle = useToggleNotification(merchantId);
  if (q.isPending) return <PageSkeleton kpis={0} rows={7} />;
  if (q.isError) return <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} />;
  const events = eventsFor(merchant.type);
  return (
    <div className="nl-set-notifications">
      <div className="nl-set-matrixwrap">
        <table className="table nl-set-matrix">
          <thead><tr><th scope="col">{t('colEvent')}</th>{CHANNELS.map(c => <th key={c} scope="col">{t(`ch_${c}`)}</th>)}</tr></thead>
          <tbody>
            {events.map(ev => (
              <tr key={ev}>
                <th scope="row">{t(`ev_${ev}`)}</th>
                {CHANNELS.map(ch => {
                  const on = !!q.data.matrix[ev]?.[ch];
                  return (
                    <td key={ch}>
                      <button type="button" className="nl-set-cell" aria-pressed={on} aria-label={t('cellLabel', { event: t(`ev_${ev}`), channel: t(`ch_${ch}`) })}
                        onClick={() => toggle.mutate({ event: ev, channel: ch, on: !on })}>
                        <span aria-hidden="true">{on ? '✓' : '—'}</span>
                      </button>
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {toggle.isError && <p role="alert" className="nl-error">{t('matrixError')}</p>}
      <p className="nl-set-quiet">{t('quietHours', { from: hour(q.data.quietFrom, locale), to: hour(q.data.quietTo, locale) })}</p>
    </div>
  );
}
