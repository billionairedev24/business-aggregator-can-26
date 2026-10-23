import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ChartCard, ColumnChart, DataTable, PageSkeleton, Skeleton, useFormatters, useLocale, type DataTableColumn, type DataTableView, type StatusTone, BoHeader, KpiStrip } from '@northline/ui';
import { useMerchant, useMerchantId, useRole, type MerchantType } from '../shell/api';
import { earningsQuery, ledgerQuery, type EarningsOverview, type LedgerLine } from './api';
import { axisDollars, dailyColumns, hoursUntil, pctFromBps, weekdayLong } from './format';
import { useFinanceT, type FinanceKey, type FinanceT } from './messages';
import { QueryState } from './QueryState';
import { InsightCard } from '../assistant/InsightCard';
import './finance.css';

const TIERS = ['master', 'trusted', 'registered'] as const;

/**
 * Earnings (S-176, on the Studio dashboard's language): the page head with "… releasing Friday", the KPI strip, money in
 * motion, the last 14 days of net earnings as columns, then the ledger with status tabs (view-only).
 */
export function EarningsScreen() {
  const merchantId = useMerchantId();
  const overview = useQuery(earningsQuery(merchantId));
  const ledger = useQuery(ledgerQuery(merchantId));
  return (
    <div className="fin-page">
      <QueryState query={overview} skeleton={<PageSkeleton kpis={4} rows={0} />}>
        {o => <Overview o={o} />}
      </QueryState>
      <InsightCard merchantId={merchantId} screen="earnings" />
      {ledger.data?.length ? <NetChart lines={ledger.data} /> : null}
      <Ledger />
    </div>
  );
}

function Overview({ o }: { o: EarningsOverview }) {
  const t = useFinanceT();
  const f = useFormatters();
  const { locale } = useLocale();
  const merchant = useMerchant();
  const type: MerchantType = merchant?.type ?? 'provider';
  const pctOf = (bps: number) => pctFromBps(bps, locale);
  const within7Days = o.nextPayoutAt && new Date(o.nextPayoutAt).getTime() - Date.now() < 7 * 86_400_000;
  const day = o.nextPayoutAt ? (within7Days ? weekdayLong(o.nextPayoutAt, locale) : f.when(o.nextPayoutAt)) : '';
  const headline = o.frequency === 'manual' || !o.nextPayoutAt
    ? t('headlineAvailable', { amount: f.price(o.availableCents) })
    : t('headlineReleasing', { amount: f.price(o.headlineCents), day });
  const others = TIERS.filter(x => x !== o.tier).map(x => `${t(`tier_${x}`)} ${pctOf(o.takeRates[x] ?? 0)}`).join(', ');
  const onHold = o.onHoldDisputes && o.onHoldRefunds ? t('kpiOnHoldCases', { count: o.onHoldDisputes + o.onHoldRefunds })
    : o.onHoldDisputes ? t('kpiOnHoldDisputes', { count: o.onHoldDisputes })
    : o.onHoldRefunds ? t('kpiOnHoldRefunds', { count: o.onHoldRefunds }) : t('kpiOnHold');
  const held = o.onHoldDisputes + o.onHoldRefunds;
  return (
    <>
      <BoHeader title={t('earningsKicker')} sub={headline} />
      <KpiStrip label={t('earningsKpis')} items={[
        { key: 'escrow', value: f.price(o.escrowNetCents), label: t(`kpiEscrow_${type}` as FinanceKey, { count: o.escrowCount }) },
        { key: 'released', value: f.price(o.availableCents), label: t('kpiReleased') },
        { key: 'take', value: pctOf(o.takeRateBps), label: t('kpiTakeRate', { tier: t(`tier_${o.tier}`), others }) },
        { key: 'hold', value: f.price(o.onHoldCents), label: onHold, ...(held ? { delta: t('kpiHoldDelta'), trend: 'down' as const } : {}) },
      ]} />
      <section className="bo-card" aria-labelledby="fin-motion">
        <h2 className="bo-card-title" id="fin-motion">{t('moneyInMotion')}</h2>
        <ol className="fin-steps" aria-label={t('moneyInMotion')}>
          <Step n={1} k={t('step1')} v={t('step1b')} />
          <Step n={2} k={t(`step2_${type}` as FinanceKey)} v={t(`step2b_${type}` as FinanceKey)} />
          <Step n={3} k={t(`step3_${type}` as FinanceKey)} v={t('step3b')} current />
          <Step n={4} k={t(`step4_${o.frequency}` as FinanceKey)} v={t('step4b')} />
        </ol>
      </section>
    </>
  );
}

const Step = ({ n, k, v, current }: { n: number; k: string; v: string; current?: boolean }) => (
  <li className="fin-step" data-current={current ? 'true' : undefined} aria-current={current ? 'step' : undefined}>
    <span className="fin-step-n" aria-hidden>{n}</span><div className="fin-step-k">{k}</div><div className="fin-step-v">{v}</div>
  </li>
);

/** The last 14 days of net earnings (the ledger's lines by the day they happened; refunds count nothing), today in ink. */
function NetChart({ lines }: { lines: LedgerLine[] }) {
  const t = useFinanceT();
  const f = useFormatters();
  const { locale } = useLocale();
  const points = useMemo(() => dailyColumns(lines, l => l.occurredAt, l => (l.state === 'refunded' ? 0 : l.netCents), locale), [lines, locale]);
  const past = points.slice(0, -1);
  const avg = past.length ? Math.round(past.reduce((n, p) => n + p.value, 0) / past.length) : 0;
  return (
    <ChartCard id="fin-net" title={t('netChart')}>
      <ColumnChart title={t('netChart')} data={points} highlightLast format={c => f.price(c)} axisFormat={c => axisDollars(c, locale)}
        legend={{ current: t('chartToday', { amount: f.price(points[points.length - 1]?.value ?? 0) }), past: t('chartPastDays', { count: past.length, amount: f.price(avg) }) }} />
    </ChartCard>
  );
}

type LedgerState = 'escrow' | 'released' | 'refunded' | 'disputed';
interface LedgerRow { id: string; job: string; when: string; customer: string; held: number; tax: number; fee: number; net: number; state: string; status: LedgerState }

/** S-176: one status colour per ledger state (released success, escrow info, disputed danger, refunded neutral). */
export const ledgerTone = (s: LedgerState): StatusTone => (s === 'released' ? 'success' : s === 'disputed' ? 'danger' : s === 'refunded' ? 'neutral' : 'info');

export function ledgerRows(lines: LedgerLine[], t: FinanceT, date: (iso: string) => string, now = Date.now()): LedgerRow[] {
  return lines.map(l => {
    const [state, status]: [string, LedgerState] =
      l.state === 'released' ? [t('stReleased'), 'released']
      : l.state === 'disputed' ? [t('stDisputed'), 'disputed']
      : l.state === 'refunded' ? [t('stRefunded'), 'refunded']
      : !l.releaseAt ? [t('stEscrowWaiting'), 'escrow']
      : new Date(l.releaseAt).getTime() <= now ? [t('stEscrowSoon'), 'escrow']
      : [t('stEscrowHours', { hours: hoursUntil(l.releaseAt, now) }), 'escrow'];
    return {
      id: l.id, job: l.orderNumber ? `${l.label} · ${l.orderNumber}` : l.label, when: date(l.occurredAt), customer: l.customerName ?? '',
      held: l.heldCents, tax: l.taxCents, fee: l.feeCents, net: l.netCents, state, status,
    };
  });
}

function Ledger() {
  const merchantId = useMerchantId();
  const t = useFinanceT();
  const f = useFormatters();
  const role = useRole();
  const ledger = useQuery(ledgerQuery(merchantId));
  const rows = useMemo(() => ledgerRows(ledger.data ?? [], t, iso => f.when(iso)), [ledger.data, t, f]);
  const price = (v: unknown) => f.price(v as number);
  const columns: DataTableColumn<LedgerRow>[] = [
    { key: 'job', label: t('colJob'), primary: true },
    { key: 'when', label: t('colDate'), priority: 2, nowrap: true },
    { key: 'customer', label: t('colCustomer'), priority: 2 },
    { key: 'held', label: t('colHeld'), type: 'money', format: price, priority: 3 },
    { key: 'tax', label: t('colTax'), type: 'money', format: price, priority: 1 },
    { key: 'fee', label: t('colFee'), type: 'money', format: price, priority: 1 },
    { key: 'net', label: t('colNetYou'), type: 'money', format: price, priority: 4 },
    { key: 'state', label: t('colState'), type: 'tag', status: r => ledgerTone(r.status) },
  ];
  const is = (s: LedgerState) => (r: LedgerRow) => r.status === s;
  const views: DataTableView<LedgerRow>[] = [
    { id: 'all', label: t('viewAll') },
    { id: 'escrow', label: t('viewEscrow'), when: is('escrow') },
    { id: 'released', label: t('stReleased'), when: is('released') },
    { id: 'disputed', label: t('stDisputed'), when: is('disputed'), tone: 'danger', hideEmpty: true },
    { id: 'refunded', label: t('stRefunded'), when: is('refunded'), hideEmpty: true },
  ];
  if (ledger.isPending) return <Skeleton height={300} />;
  return (
    <section className="bo-card" aria-labelledby="fin-ledger"><h2 className="bo-card-title" id="fin-ledger">{t('ledgerTitle')}</h2>
    <DataTable<LedgerRow>
      entity={t('ledgerEntity')} plural={t('ledgerPlural')} columns={columns} rows={rows} views={views}
      can={{ create: false, update: false, delete: false, export: true }}
      roleName={t(`role_${role}` as FinanceKey)} emptyText={t('ledgerEmpty')} reportName="earnings-ledger"
      error={ledger.isError ? t('loadError') : null} onRetry={() => void ledger.refetch()}
    />
    </section>
  );
}
