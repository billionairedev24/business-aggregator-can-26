import { useQuery } from '@tanstack/react-query';
import { ErrorState, SiteLink, Skeleton, useFormatters } from '@northline/ui';
import { walletQuery } from './api';
import { useAccountT } from './messages';
import { tabHref } from './tabs';

/**
 * Wallet & points (design 06 `at.wallet`): the balance and what it's worth (100 points = $1), eight weeks of earning,
 * Northline Plus, then the payment methods (S-59 fills that section; until then a link to the tab).
 */
export function WalletTab({ cards }: { cards?: React.ReactNode }) {
  const t = useAccountT();
  const { money, number } = useFormatters();
  const wallet = useQuery(walletQuery);
  if (wallet.isPending) return <WalletSkeleton />;
  if (wallet.isError) return <><h1 id="acct-title" className="nl-acct-h1">{t('walletTitle')}</h1><ErrorState message={t('loadError')} onRetry={() => void wallet.refetch()} /></>;
  const { points, plus } = wallet.data;
  const max = Math.max(1, ...points.weekly);
  return (
    <>
      <h1 id="acct-title" className="nl-acct-h1">{t('walletTitle')}</h1>
      <div className="nl-wallet-balance">
        <span className="nl-wallet-points">{t('points', { count: number(points.balance) })}</span>
        <span className="nl-muted">{t('pointsValue', { value: money(points.valueCents), tag: t(plus ? 'plusTagOn' : 'plusTagOff') })}</span>
      </div>
      <figure className="nl-wallet-chart">
        <div className="nl-wallet-bars" role="img" aria-label={`${t('pointsChart')}: ${points.weekly.map((p, i) => t('pointsWeek', { count: number(p), weeks: points.weekly.length - 1 - i })).join(', ')}`}>
          {points.weekly.map((p, i) => <span key={i} className="nl-wallet-bar"><span style={{ height: `${Math.round((p / max) * 100)}%` }} /></span>)}
        </div>
        <figcaption className="nl-small nl-muted">{t('pointsChart')}</figcaption>
      </figure>
      {points.balance === 0 && points.weekly.every(p => p === 0)
        ? <p className="nl-muted">{t('noPoints')} <SiteLink href="/shop">{t('startShopping')}</SiteLink></p>
        : null}
      <div className="nl-acct-panel nl-wallet-plus">
        <div>
          <div className="nl-acct-strong">{t(plus ? 'plusHeadlineOn' : 'plusHeadlineOff')}</div>
          <div className="nl-small nl-muted">{t(plus ? 'plusSubOn' : 'plusSubOff')}</div>
        </div>
        <SiteLink href={tabHref('plus')} className="btn btn-ghost">{t(plus ? 'plusCtaOn' : 'plusCtaOff')}</SiteLink>
      </div>
      <h2 className="nl-acct-h2">{t('paymentMethods')}</h2>
      {cards ?? <SiteLink href={tabHref('payments')} className="btn btn-secondary">{t('managePayments')}</SiteLink>}
    </>
  );
}

function WalletSkeleton() {
  const t = useAccountT();
  return (
    <div aria-busy="true">
      <span className="nl-sr-only">{t('loading')}</span>
      <Skeleton width="45%" height={40} />
      <Skeleton width="60%" height={52} style={{ marginTop: 14 }} />
      <Skeleton height={60} style={{ marginTop: 14, maxWidth: 520 }} />
      <Skeleton height={64} style={{ marginTop: 22, maxWidth: 640 }} />
    </div>
  );
}
