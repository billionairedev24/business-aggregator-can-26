import { useQuery } from '@tanstack/react-query';
import { ErrorState, Skeleton, useFormatters } from '@northline/ui';
import { pointsQuery, type Activity } from './api';
import { useLoyaltyT } from './messages';

const KNOWN = new Set(['earn', 'reward', 'referral', 'redemption', 'redemption_return', 'refund_return', 'earn_reversal', 'reward_reversal', 'expiry']);

/**
 * S-152: how points work where the person shops (the region's rule), when the next ones expire, and the activity —
 * earned on completed orders and bookings, business bonuses, referrals, spent at checkout, refunds, expiry.
 */
export function PointsPanel() {
  const t = useLoyaltyT();
  const { date, number } = useFormatters();
  const points = useQuery(pointsQuery);
  if (points.isPending) return <div aria-busy="true"><span className="nl-sr-only">{t('loading')}</span><Skeleton height={120} style={{ maxWidth: 640 }} /></div>;
  if (points.isError) return <ErrorState message={t('loadError')} onRetry={() => void points.refetch()} />;
  const p = points.data;
  return (
    <>
      {p.nextExpiryAt && p.nextExpiryPoints > 0
        ? <p className="nl-loyal-expiry" role="note">{t('nextExpiry', { points: p.nextExpiryPoints, date: date(p.nextExpiryAt, 'date') })}</p>
        : null}
      <section className="nl-acct-panel nl-loyal-how" aria-labelledby="loyal-how">
        <h2 id="loyal-how" className="nl-acct-h2">{t('howTitle')}</h2>
        <ul>
          <li>{t('howEarn', { points: p.pointsPerDollarSpent })}</li>
          <li>{t('howPlus', { multiplier: number(p.plusMultiplierPct > 100 ? p.plusMultiplierPct / 100 : 2) })}</li>
          <li>{t('howRedeem', { points: p.redeemPointsPerDollar })}</li>
          <li>{p.expiryMonths ? t('howExpiry', { months: p.expiryMonths }) : t('howNever')}</li>
        </ul>
      </section>
      <h2 className="nl-acct-h2">{t('activityTitle')}</h2>
      {p.activity.length === 0 ? <p className="nl-muted">{t('activityEmpty')}</p> : (
        <ul className="nl-loyal-activity">
          {p.activity.map(a => <ActivityRow key={a.id} a={a} />)}
        </ul>
      )}
    </>
  );
}

function ActivityRow({ a }: { a: Activity }) {
  const t = useLoyaltyT();
  const { date, number } = useFormatters();
  const kind = KNOWN.has(a.type) ? `a_${a.type}` : a.type === 'order' || a.type === 'booking' ? 'a_earn' : 'a_other';
  return (
    <li className="nl-loyal-row">
      <span className="nl-loyal-row-what">
        <span className="nl-acct-strong">{t(kind as 'a_earn')}</span>
        <span className="nl-small nl-muted">{[a.note, date(a.at, 'date'), a.expiresAt && a.points > 0 ? t('expires', { date: date(a.expiresAt, 'date') }) : null].filter(Boolean).join(' · ')}</span>
      </span>
      <span className={a.points < 0 ? 'nl-loyal-pts nl-loyal-pts-out' : 'nl-loyal-pts'}>{a.points > 0 ? '+' : '−'}{number(Math.abs(a.points))}</span>
    </li>
  );
}
