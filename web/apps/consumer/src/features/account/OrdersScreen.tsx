import { useMemo } from 'react';
import clsx from 'clsx';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { DataTable, EmptyState, ErrorState, SiteLink, Skeleton, useFormatters, useLocale, type DataTableAction, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { useZone } from '../location/regions';
import { signInHref, useViewer } from '../session/api';
import { activityQuery, type ActivityItem } from './api';
import { statusText, titleText, whenText, withText } from './format';
import { useAccountT } from './messages';

export const ORDER_VIEWS = ['active', 'past', 'cases'] as const;
export type OrderView = (typeof ORDER_VIEWS)[number];

interface Row {
  id: string;
  what: string;
  ref: string;
  with: string;
  when: string;
  amount: number;
  status: string;
  action: ActivityItem['action'];
  tone: DataTableTone;
  href: string | null;
}

const TONES: Record<ActivityItem['tone'], DataTableTone> = { accent: 'tag-accent', neutral: 'tag-neutral', 'accent-2': 'tag-accent-2' };
const ACTIONS = ['track', 'details', 'view_quote', 'rebook', 'view_case'] as const;

export const inView = (item: ActivityItem, view: OrderView) =>
  view === 'cases' ? !!item.caseRef : view === 'active' ? item.active : !item.active;

/**
 * Orders & bookings (design 06 `orders`) — reached from the account menu only (CLAUDE.md: never the top navigation).
 * "Active · N" / "Past" / "Refunds & cases · N" filter one Data Table of the caller's orders, bookings and open
 * quote requests; a row (or its action) opens the order's tracking, the booking, the quote or the case.
 */
export function OrdersScreen() {
  const t = useAccountT();
  const { user, loading } = useViewer();
  const search = useSearch({ strict: false }) as { view?: unknown };
  const view: OrderView = (ORDER_VIEWS as readonly unknown[]).includes(search.view) ? search.view as OrderView : 'active';
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
  return <OrdersList items={activity.data ?? []} view={view} />;
}

function OrdersList({ items, view }: { items: ActivityItem[]; view: OrderView }) {
  const t = useAccountT();
  const { locale } = useLocale();
  const zone = useZone();
  const { money } = useFormatters();
  const navigate = useNavigate();
  const counts = { active: items.filter(i => i.active).length, cases: items.filter(i => !!i.caseRef).length };
  const shown = items.filter(i => inView(i, view));
  const rows: Row[] = shown.map(i => ({
    id: `${i.kind}:${i.id}`,
    what: titleText(i, t),
    ref: i.ref ?? '',
    with: withText(i, t),
    when: whenText(i, t, locale, new Date(), zone),
    amount: i.amountCents,
    status: statusText(i, t),
    action: i.action,
    tone: TONES[i.tone],
    href: i.href ?? null,
  }));
  const columns = useMemo<DataTableColumn<Row>[]>(() => [
    { key: 'what', label: t('colWhat'), sub: 'ref', primary: true, filter: false },
    { key: 'with', label: t('colWith'), filter: false },
    { key: 'when', label: t('colWhen'), filter: false },
    { key: 'amount', label: t('colAmount'), type: 'num', filter: false, format: v => (Number(v) > 0 ? money(Number(v)) : t('noAmount')) },
    { key: 'status', label: t('colStatus'), type: 'tag' },
  ], [t, money]);
  const actions = useMemo<DataTableAction<Row>[]>(() => ACTIONS.map(a => ({ id: a, label: t(`action_${a}`), inline: true, bulk: false, when: { key: 'action', in: [a] } })), [t]);
  const go = (row: Row) => { if (row.href) void navigate({ href: row.href }); };
  const label = (v: OrderView) => v === 'active' ? t('viewActive', { count: counts.active })
    : v === 'past' ? t('viewPast') : counts.cases ? t('viewCases', { count: counts.cases }) : t('viewCasesNone');

  return (
    <div className="nl-page nl-orders">
      <h1 className="nl-acct-h1">{t('ordersTitle')}</h1>
      <div className="nl-orders-views" role="group" aria-label={t('viewsLabel')}>
        {ORDER_VIEWS.map(v => (
          <button key={v} type="button" className={clsx('nl-orders-view', v === view && 'is-current')} aria-pressed={v === view}
            onClick={() => void navigate({ to: '/account/orders', search: (v === 'active' ? {} : { view: v }) as never, replace: true })}>
            {label(v)}
          </button>
        ))}
      </div>
      {rows.length === 0 ? (
        <EmptyState action={view === 'cases' ? undefined : <SiteLink href={view === 'active' ? '/shop' : '/services'} className="btn btn-primary">{t(view === 'active' ? 'startShopping' : 'findProvider')}</SiteLink>}>
          {t(view === 'active' ? 'emptyActive' : view === 'past' ? 'emptyPast' : 'emptyCases')}
        </EmptyState>
      ) : (
        <div className="nl-orders-table">
          <DataTable<Row> entity={t('orderEntity')} plural={t('orderPlural')} aria-label={t('ordersTitle')} columns={columns} rows={rows}
            rowTones={r => ({ status: r.tone })} can={{ create: false, update: false, delete: false, export: true }}
            actions={actions} onAction={(_a, list) => { if (list[0]) go(list[0]); }} onOpen={go} openLabel={t('action_details')} />
        </div>
      )}
    </div>
  );
}

export function OrdersSkeleton() {
  const t = useAccountT();
  return (
    <div className="nl-page nl-orders" aria-busy="true">
      <span className="nl-sr-only">{t('loading')}</span>
      <Skeleton width="40%" height={40} />
      <div style={{ display: 'flex', gap: 6, marginTop: 14 }}>{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} width={110} height={28} radius={999} />)}</div>
      {Array.from({ length: 5 }, (_, i) => <Skeleton key={i} height={44} style={{ marginTop: 10, maxWidth: 960 }} />)}
    </div>
  );
}

