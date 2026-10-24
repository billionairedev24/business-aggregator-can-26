import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { Chip, EmptyState, ErrorState, SiteLink, Skeleton, StatusTag, UnderlineTabs, useFormatters, useLocale, type StatusTone } from '@northline/ui';
import { useZone } from '../location/regions';
import { signInHref, useViewer } from '../session/api';
import { activityQuery, myRequestsQuery, type ActivityItem } from './api';
import { statusText, titleText, whenText, withText } from './format';
import { Crumbs } from '../pages/Crumbs';
import { useAccountT } from './messages';
import { useRenderNow } from '../../lib/renderClock';

export const ORDER_VIEWS = ['active', 'past', 'cases'] as const;
export type OrderView = (typeof ORDER_VIEWS)[number];
export const ORDER_TABS = ['orders', 'bookings', 'quotes'] as const;
export type OrderTab = (typeof ORDER_TABS)[number];

/**
 * E-15 S-173: a row's status is a semantic tag (one role per colour): what went wrong or a case is danger, waiting on
 * someone warning, under way info, done success, the rest neutral. The api's own `tone` breaks ties.
 */
const STATUS_TONE: Record<string, StatusTone> = {
  delivery_failed: 'danger', case: 'danger', declined: 'danger',
  requested: 'warning', waiting: 'warning', quote_ready: 'warning', deposit_held: 'warning', escrow: 'warning',
  packing: 'info', cooking: 'info', on_the_way: 'info', on_site: 'info', ready: 'info', booked: 'info', paid: 'info',
  delivered: 'success', completed: 'success', done: 'success', refunded: 'success',
  cancelled: 'neutral', expired: 'neutral',
};
export const statusTone = (item: Pick<ActivityItem, 'status' | 'tone'>): StatusTone =>
  STATUS_TONE[item.status] ?? (item.tone === 'accent-2' ? 'danger' : item.tone === 'accent' ? 'info' : 'neutral');
const REQUEST_TONE: Record<string, StatusTone> = { waiting: 'warning', quoted: 'info', accepted: 'success', declined: 'danger', expired: 'neutral' };

export const inView = (item: ActivityItem, view: OrderView) =>
  view === 'cases' ? !!item.caseRef : view === 'active' ? item.active : !item.active;
/** Orders = goods and food; bookings = jobs and quote requests. */
export const inTab = (item: ActivityItem, tab: OrderTab) => (tab === 'orders' ? item.kind === 'order' || item.kind === 'food' : tab === 'bookings' ? item.kind === 'booking' || item.kind === 'quote' : false);

/**
 * Orders & bookings (redesign § 2.1, AT Shop:688 OrdersView) — reached from the account menu only (CLAUDE.md: never
 * the top navigation). Tabs for Orders and Bookings, "Active · N" / "Past" / "Refunds & cases · N", one card per
 * order or job (the head row: when, total, reference; then what, with whom, the status and its action), beside a
 * 320px column with help and "order again" (the owner asked that it never stays empty, chat1:775).
 */
export function OrdersScreen() {
  const t = useAccountT();
  const { user, loading } = useViewer();
  const search = useSearch({ strict: false }) as { view?: unknown; tab?: unknown };
  const view: OrderView = (ORDER_VIEWS as readonly unknown[]).includes(search.view) ? search.view as OrderView : 'active';
  const tab: OrderTab = search.tab === 'bookings' || search.tab === 'quotes' ? search.tab : 'orders';
  const activity = useQuery({ ...activityQuery, enabled: !!user });
  if (loading || (user && activity.isPending)) return <OrdersSkeleton />;
  if (!user) {
    return (
      <div className="nl-page nl-orders">
        <h1 className="nl-acct-h1">{t('ordersTitle')}</h1>
        <EmptyState action={<SiteLink href={signInHref('/account/orders')} className="btn btn-primary">{t('signInAction')}</SiteLink>}>{t('signInOrders')}</EmptyState>
      </div>
    );
  }
  if (activity.isError) {
    return (
      <div className="nl-page nl-orders">
        <h1 className="nl-acct-h1">{t('ordersTitle')}</h1>
        <ErrorState message={t('loadError')} onRetry={() => void activity.refetch()} />
      </div>
    );
  }
  return <OrdersList items={activity.data ?? []} view={view} tab={tab} />;
}

/** S-153, Account › Quote requests: every request, whatever became of it (waiting, quoted, accepted…). */
function RequestsList() {
  const t = useAccountT();
  const { date, price } = useFormatters();
  const q = useQuery(myRequestsQuery);
  if (q.isPending) return <Skeleton height={120} radius={8} />;
  if (q.isError) return <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} />;
  if (q.data.length === 0) return <EmptyState action={<SiteLink href="/services" className="btn btn-primary">{t('findProvider')}</SiteLink>}>{t('requestsEmpty')}</EmptyState>;
  return (
    <ul className="nl-ocards" aria-label={t('tabKind_quotes', { count: q.data.length })}>
      {q.data.map(r => (
        <li key={r.id} className="nl-ocard">
          <dl className="nl-ocard-head">
            <div><dt>{t('colWhen')}</dt><dd>{date(r.createdAt, 'date')}</dd></div>
            <div><dt>{t('colAmount')}</dt><dd>{r.lowestCents != null ? t('requestFrom', { amount: price(r.lowestCents) }) : t('noAmount')}</dd></div>
            <div className="nl-ocard-ref"><dt>{t('colRef')}</dt><dd>{r.ref}</dd></div>
          </dl>
          <div className="nl-ocard-body">
            <div className="nl-ocard-what">
              <StatusTag tone={REQUEST_TONE[r.status] ?? 'neutral'}>{t(`request_${r.status}`)}</StatusTag>
              <h2 className="nl-ocard-title">{r.title}</h2>
              <span className="nl-muted nl-small">{t('requestQuotes', { quotes: r.quotes, providers: r.providers })}{r.photoCount ? ` · ${t('requestPhotos', { count: r.photoCount })}` : ''}</span>
            </div>
            <div className="nl-ocard-actions">
              <SiteLink href={r.href} className="btn btn-secondary btn-sm">{t(r.bookingId ? 'action_details' : 'action_view_quote')}</SiteLink>
            </div>
          </div>
        </li>
      ))}
    </ul>
  );
}

function OrdersList({ items, view, tab }: { items: ActivityItem[]; view: OrderView; tab: OrderTab }) {
  const t = useAccountT();
  const navigate = useNavigate();
  const requests = useQuery(myRequestsQuery);
  const mine = items.filter(i => inTab(i, tab));
  const counts = { active: mine.filter(i => i.active).length, cases: mine.filter(i => !!i.caseRef).length };
  const shown = mine.filter(i => inView(i, view));
  const label = (v: OrderView) => v === 'active' ? t('viewActive', { count: counts.active })
    : v === 'past' ? t('viewPast') : counts.cases ? t('viewCases', { count: counts.cases }) : t('viewCasesNone');
  const go = (next: { tab?: OrderTab; view?: OrderView }) => {
    const s = { ...(next.tab && next.tab !== 'orders' ? { tab: next.tab } : {}), ...(next.view && next.view !== 'active' ? { view: next.view } : {}) };
    void navigate({ to: '/account/orders', search: s as never, replace: true });
  };
  const again = items.filter(i => !i.active && i.href).slice(0, 3);

  return (
    <div className="nl-page nl-orders">
      <Crumbs label={t('crumbs')} items={[{ label: t('crumbAccount'), href: '/account' }, { label: t('ordersTitle') }]} />
      <h1 className="nl-acct-h1">{t('ordersTitle')}</h1>
      <div className="nl-orders-tabs">
        <UnderlineTabs aria-label={t('tabsKind')} value={tab} onChange={k => go({ tab: k })}
          options={ORDER_TABS.map(k => ({ value: k, label: t(`tabKind_${k}_label`), count: k === 'quotes' ? requests.data?.length ?? 0 : items.filter(i => inTab(i, k)).length }))} />
      </div>
      <div className="nl-orders-grid">
        <div className="nl-orders-main">
          {tab === 'quotes' ? <RequestsList /> : <>
          <div className="nl-orders-views" role="group" aria-label={t('viewsLabel')}>
            {ORDER_VIEWS.map(v => (
              <Chip key={v} selected={v === view} onClick={() => go({ tab, view: v })}>{label(v)}</Chip>
            ))}
          </div>
          {shown.length === 0 ? (
            <EmptyState action={view === 'cases' ? undefined : <SiteLink href={tab === 'orders' ? '/shop' : '/services'} className="btn btn-primary">{t(tab === 'orders' ? 'startShopping' : 'findProvider')}</SiteLink>}>
              {t(view === 'active' ? 'emptyActive' : view === 'past' ? 'emptyPast' : 'emptyCases')}
            </EmptyState>
          ) : (
            <ul className="nl-ocards" aria-label={t('ordersTitle')}>
              {shown.map(i => <OrderCard key={`${i.kind}:${i.id}`} item={i} />)}
            </ul>
          )}
          </>}
        </div>
        <aside className="nl-orders-side" aria-label={t('sideLabel')}>
          <section className="nl-orders-panel" aria-labelledby="orders-help">
            <h2 id="orders-help" className="nl-orders-h2">{t('helpTitle')}</h2>
            <SiteLink href="/account?tab=help" className="nl-orders-link">{t('helpCases')}</SiteLink>
            <SiteLink href="/help" className="nl-orders-link">{t('helpCentre')}</SiteLink>
          </section>
          <section className="nl-orders-panel" aria-labelledby="orders-again">
            <h2 id="orders-again" className="nl-orders-h2">{t('againTitle')}</h2>
            {again.length ? (
              <ul className="nl-orders-again">
                {again.map(i => <li key={`${i.kind}:${i.id}`}><SiteLink href={i.href!} className="nl-orders-link">{titleText(i, t)}</SiteLink></li>)}
              </ul>
            ) : <p className="nl-muted nl-small">{t('againEmpty')}</p>}
            <SiteLink href={tab === 'orders' ? '/shop' : '/services'} className="btn btn-secondary btn-sm">{t(tab === 'orders' ? 'startShopping' : 'findProvider')}</SiteLink>
          </section>
        </aside>
      </div>
    </div>
  );
}

function OrderCard({ item: i }: { item: ActivityItem }) {
  const t = useAccountT();
  const { locale } = useLocale();
  const zone = useZone();
  const now = useRenderNow();
  const { price } = useFormatters();
  const navigate = useNavigate();
  const open = () => { if (i.href) void navigate({ href: i.href }); };
  return (
    <li className="nl-ocard">
      <dl className="nl-ocard-head">
        <div><dt>{t('colWhen')}</dt><dd>{whenText(i, t, locale, now, zone)}</dd></div>
        <div><dt>{t('colAmount')}</dt><dd>{i.amountCents > 0 ? price(i.amountCents) : t('noAmount')}</dd></div>
        {i.ref ? <div className="nl-ocard-ref"><dt>{t('colRef')}</dt><dd>{i.ref}</dd></div> : null}
      </dl>
      <div className="nl-ocard-body">
        <div className="nl-ocard-what">
          <StatusTag tone={statusTone(i)}>{statusText(i, t)}</StatusTag>
          <h2 className="nl-ocard-title">{titleText(i, t)}</h2>
          <span className="nl-muted nl-small">{withText(i, t)}</span>
        </div>
        <div className="nl-ocard-actions">
          <button type="button" className={`btn btn-sm ${i.active ? 'btn-primary' : 'btn-secondary'}`} disabled={!i.href} onClick={open}>{t(`action_${i.action}`)}</button>
        </div>
      </div>
    </li>
  );
}

export function OrdersSkeleton() {
  const t = useAccountT();
  return (
    <div className="nl-page nl-orders" aria-busy="true">
      <span className="nl-sr-only">{t('loading')}</span>
      <Skeleton width="40%" height={32} />
      <div className="nl-orders-main nl-orders-skeleton">{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} height={120} />)}</div>
    </div>
  );
}
