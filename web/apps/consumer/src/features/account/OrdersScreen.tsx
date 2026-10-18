import clsx from 'clsx';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { EmptyState, ErrorState, SiteLink, Skeleton, useFormatters, useLocale } from '@northline/ui';
import { useZone } from '../location/regions';
import { signInHref, useViewer } from '../session/api';
import { activityQuery, type ActivityItem } from './api';
import { statusText, titleText, whenText, withText } from './format';
import { useAccountT } from './messages';

export const ORDER_VIEWS = ['active', 'past', 'cases'] as const;
export type OrderView = (typeof ORDER_VIEWS)[number];
export const ORDER_TABS = ['orders', 'bookings'] as const;
export type OrderTab = (typeof ORDER_TABS)[number];

const TONES: Record<ActivityItem['tone'], string> = { accent: 'tag-brand', neutral: 'tag-neutral', 'accent-2': 'tag-danger' };

export const inView = (item: ActivityItem, view: OrderView) =>
  view === 'cases' ? !!item.caseRef : view === 'active' ? item.active : !item.active;
/** Orders = goods and food; bookings = jobs and quote requests. */
export const inTab = (item: ActivityItem, tab: OrderTab) => (tab === 'orders' ? item.kind === 'order' || item.kind === 'food' : item.kind === 'booking' || item.kind === 'quote');

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
  const tab: OrderTab = search.tab === 'bookings' ? 'bookings' : 'orders';
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

function OrdersList({ items, view, tab }: { items: ActivityItem[]; view: OrderView; tab: OrderTab }) {
  const t = useAccountT();
  const navigate = useNavigate();
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
      <nav aria-label={t('crumbs')} className="nl-acct-crumbs"><SiteLink href="/account">{t('crumbAccount')}</SiteLink> › <span aria-current="page">{t('ordersTitle')}</span></nav>
      <h1 className="nl-acct-h1">{t('ordersTitle')}</h1>
      <div className="nl-orders-tabs" role="group" aria-label={t('tabsKind')}>
        {ORDER_TABS.map(k => (
          <button key={k} type="button" className={clsx('nl-orders-tab', k === tab && 'is-current')} aria-pressed={k === tab} onClick={() => go({ tab: k })}>
            {t(`tabKind_${k}`, { count: items.filter(i => inTab(i, k)).length })}
          </button>
        ))}
      </div>
      <div className="nl-orders-grid">
        <div className="nl-orders-main">
          <div className="nl-orders-views" role="group" aria-label={t('viewsLabel')}>
            {ORDER_VIEWS.map(v => (
              <button key={v} type="button" className="nl-chip" aria-pressed={v === view} onClick={() => go({ tab, view: v })}>{label(v)}</button>
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
  const { money } = useFormatters();
  const navigate = useNavigate();
  const open = () => { if (i.href) void navigate({ href: i.href }); };
  return (
    <li className="nl-ocard">
      <dl className="nl-ocard-head">
        <div><dt>{t('colWhen')}</dt><dd>{whenText(i, t, locale, new Date(), zone)}</dd></div>
        <div><dt>{t('colAmount')}</dt><dd>{i.amountCents > 0 ? money(i.amountCents) : t('noAmount')}</dd></div>
        {i.ref ? <div className="nl-ocard-ref"><dt>{t('colRef')}</dt><dd>{i.ref}</dd></div> : null}
      </dl>
      <div className="nl-ocard-body">
        <div className="nl-ocard-what">
          <span className={`tag ${TONES[i.tone]}`}>{statusText(i, t)}</span>
          <h2 className="nl-ocard-title">{titleText(i, t)}</h2>
          <span className="nl-muted nl-small">{withText(i, t)}</span>
        </div>
        <div className="nl-ocard-actions">
          <button type="button" className="btn btn-accent btn-sm" disabled={!i.href} onClick={open}>{t(`action_${i.action}`)}</button>
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
      <Skeleton width="40%" height={40} />
      <div style={{ display: 'flex', gap: 6, marginTop: 14 }}>{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} width={110} height={32} radius={999} />)}</div>
      {Array.from({ length: 3 }, (_, i) => <Skeleton key={i} height={120} radius={8} style={{ marginTop: 12, maxWidth: 960 }} />)}
    </div>
  );
}
