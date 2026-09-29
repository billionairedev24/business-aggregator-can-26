import { useQuery } from '@tanstack/react-query';
import { ErrorState, Skeleton, useFormatters, useLocale } from '@northline/ui';
import { statusQuery } from './api';
import { useHelpT } from './messages';

/** Platform status (design statusRows): "Operational" in spruce, anything else in rosehip with the note and time. */
export function PlatformStatus({ merchantId }: { merchantId: string }) {
  const t = useHelpT();
  const { locale } = useLocale();
  const { date } = useFormatters();
  const q = useQuery(statusQuery(merchantId, locale));
  return (
    <div className="nl-help-status">
      {q.isPending ? Array.from({ length: 6 }, (_, i) => <Skeleton key={i} height={24} style={{ margin: '12px 0' }} />)
        : q.isError ? <ErrorState message={t('statusError')} onRetry={() => void q.refetch()} />
          : (
            <ul className="nl-help-status-list">
              {q.data.map(c => (
                <li key={c.key} className="nl-help-status-row">
                  <span>{c.name}</span>
                  <span className={`tag ${c.state === 'operational' ? 'tag-accent' : 'tag-accent-2'}`}>
                    {[t(`state_${c.state}`), c.note, c.state !== 'operational' && c.since ? date(c.since, 'time') : null].filter(Boolean).join(' · ')}
                  </span>
                </li>
              ))}
            </ul>
          )}
      <p className="nl-help-note">{t('statusFooter')}</p>
    </div>
  );
}
