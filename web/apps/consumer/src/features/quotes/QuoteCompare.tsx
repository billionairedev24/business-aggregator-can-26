import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { isNotFound } from '@northline/client';
import { ErrorState, Skeleton, useFormatters, useLocale } from '@northline/ui';
import { NotFound } from '../shell/NotFound';
import { percent, rating } from '../services/format';
import { comparisonQuery, type Offer } from './api';
import { useQuotesT } from './messages';

/** design 06 `book` quote mode, "N quotes received": every provider asked, cheapest quote first, then the waiting ones. */
export function QuoteCompare({ requestId }: { requestId: string }) {
  const t = useQuotesT();
  const { locale } = useLocale();
  const { date } = useFormatters();
  const q = useQuery(comparisonQuery(requestId, locale));
  if (q.isPending) return <div className="nl-page nl-qt" aria-busy="true"><Skeleton width="50%" height={36} style={{ marginTop: 28 }} /><Skeleton height={220} style={{ marginTop: 16 }} /></div>;
  if (q.isError) return isNotFound(q.error) ? <NotFound /> : <div className="nl-page nl-qt"><ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /></div>;
  const c = q.data;
  const quoted = c.offers.filter(o => o.status === 'quoted').length;
  return (
    <div className="nl-page nl-qt">
      <nav aria-label={t('breadcrumb')} className="nl-qt-crumbs">
        <Link to="/services">{t('crumbServices')}</Link> › <span aria-current="page">{c.ref}</span>
      </nav>
      <h1 className="nl-qt-title">{t('compareTitle', { count: quoted })}</h1>
      <p className="nl-qt-lede">{t('compareLede')}</p>
      <p className="nl-qt-muted">{t('sentTo', { ref: c.ref, count: c.offers.length, time: c.respondBy ? date(c.respondBy, 'time') : '—' })}</p>
      <div className="nl-qt-tablewrap">
        <table className="table nl-qt-table">
          <caption className="nl-sr-only">{c.title}</caption>
          <thead><tr><th scope="col">{t('thProvider')}</th><th scope="col">{t('thQuote')}</th><th scope="col">{t('thEarliest')}</th><th scope="col">{t('thIncludes')}</th><th scope="col">{t('thTrust')}</th><th scope="col"><span className="nl-sr-only">{t('viewQuote')}</span></th></tr></thead>
          <tbody>{c.offers.map(o => <Row key={o.provider.merchantId} offer={o} />)}</tbody>
        </table>
      </div>
    </div>
  );
}

function Row({ offer: o }: { offer: Offer }) {
  const t = useQuotesT();
  const { locale } = useLocale();
  const { money, date } = useFormatters();
  const p = o.provider;
  const tier = t(`tier_${p.tier === 'master' || p.tier === 'trusted' ? p.tier : 'registered'}`);
  const who = <td><strong>{p.name}</strong><br /><span className="nl-qt-muted">{p.reviewCount > 0 ? `${tier} · ★ ${rating(p.rating, locale)}` : tier}</span></td>;
  const trust = p.onTimePct != null ? t('trust', { onTime: percent(p.onTimePct, locale), disputes: percent(p.disputePct ?? 0, locale) }) : t('noScore');
  const q = o.quote;
  if (!q) {
    return <tr>{who}<td colSpan={3} className="nl-qt-muted">{o.status === 'declined' ? t('declined') : t('waiting')}</td><td>{trust}</td><td /></tr>;
  }
  const includes = q.lines.filter(l => l.kind !== 'discount').slice(0, 2).map(l => l.description).join(', ');
  return (
    <tr data-state={q.state}>
      {who}
      <td className="nl-qt-price">{money(q.totalCents)}{q.version > 1 ? <span className="tag nl-qt-vtag">{t('versionTag', { version: q.version })}</span> : null}<br /><span className="nl-qt-muted">{q.depositCents > 0 ? t('depositOf', { amount: money(q.depositCents) }) : t('noDeposit')}</span></td>
      <td>{q.proposedAt ? date(q.proposedAt, 'dateTime') : '—'}</td>
      <td className="nl-qt-small">{includes}{q.expired ? <> · <span className="tag">{t('expiredTag')}</span></> : null}</td>
      <td className="nl-qt-small">{trust}</td>
      <td><Link to="/quotes/$quoteId" params={{ quoteId: q.id }} className="btn btn-secondary nl-qt-view">{t('viewQuote')}</Link></td>
    </tr>
  );
}
