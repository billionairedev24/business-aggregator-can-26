import { useQuery } from '@tanstack/react-query';
import { SiteLink, useFormatters } from '@northline/ui';
import { rewardsNearQuery, type NearbyReward } from './api';
import { useLoyaltyT } from './messages';

/**
 * S-152 "Rewards near you" (consumer home): businesses in the person's place paying extra points today (S-75's
 * provider-funded rewards, running while their budget lasts). Nothing renders when there are none.
 */
export function RewardsNearYou({ city, lat, lng }: { city?: string | null; lat?: number | null; lng?: number | null }) {
  const t = useLoyaltyT();
  const rewards = useQuery({ ...rewardsNearQuery({ city, lat, lng }), enabled: !!city || (lat != null && lng != null) });
  const items = rewards.data?.items ?? [];
  if (items.length === 0) return null;
  return (
    <section className="nl-section nl-loyal-near" aria-labelledby="loyal-near">
      <h2 id="loyal-near" className="nl-section-title">{t('nearTitle')}</h2>
      <p className="nl-muted">{t('nearLede')}</p>
      <ul className="nl-loyal-near-list">{items.map(r => <RewardCard key={r.merchantId} r={r} />)}</ul>
    </section>
  );
}

function RewardCard({ r }: { r: NearbyReward }) {
  const t = useLoyaltyT();
  const { date } = useFormatters();
  const href = r.slug ? (r.type === 'kitchen' ? `/food/${r.slug}` : `/providers/${r.slug}`) : null;
  const what = r.label ? t('nearOn', { multiplier: r.multiplier, label: r.label }) : t('nearAll', { multiplier: r.multiplier });
  const body = (
    <>
      <span className="nl-loyal-near-mult" aria-hidden>{r.multiplier}×</span>
      <span className="nl-loyal-near-text">
        <span className="nl-acct-strong">{r.businessName}</span>
        <span className="nl-small">{what} · {t('nearUntil', { date: date(`${r.endsOn}T12:00:00Z`, 'date') })}</span>
      </span>
    </>
  );
  return <li className="nl-loyal-near-card">{href ? <SiteLink href={href} className="nl-loyal-near-link">{body}</SiteLink> : <span className="nl-loyal-near-link">{body}</span>}</li>;
}
