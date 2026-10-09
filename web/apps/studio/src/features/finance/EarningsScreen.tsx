import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { DataTable, PageSkeleton, Skeleton, useFormatters, useLocale, type DataTableColumn } from '@northline/ui';
import { useMerchant, useMerchantId, useRole, type MerchantType } from '../shell/api';
import { earningsQuery, ledgerQuery, type EarningsOverview, type LedgerLine } from './api';
import { hoursUntil, pctFromBps, weekdayLong } from './format';
import { useFinanceT, type FinanceKey, type FinanceT } from './messages';
import { QueryState } from './QueryState';
import { InsightCard } from '../assistant/InsightCard';
import './finance.css';

const TIERS = ['master', 'trusted', 'registered'] as const;

/** Design 02 · Earnings: "… releasing Friday", KPIs, Money in motion, ledger (view-only). */
export function EarningsScreen() {
  const merchantId = useMerchantId();
  const t = useFinanceT();
  const overview = useQuery(earningsQuery(merchantId));
  return (
    <>
      <span className="nl-kicker">{t('earningsKicker')}</span>
      <QueryState query={overview} skeleton={<PageSkeleton kpis={4} rows={0} />}>
        {o => <Overview o={o} />}
      </QueryState>
      <InsightCard merchantId={merchantId} screen="earnings" />
      <Ledger />
    </>
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
  const day = o.nextPayoutAt ? (within7Days ? weekdayLong(o.nextPayoutAt, locale) : f.date(o.nextPayoutAt)) : '';
  const headline = o.frequency === 'manual' || !o.nextPayoutAt
    ? t('headlineAvailable', { amount: f.money(o.availableCents) })
    : t('headlineReleasing', { amount: f.money(o.headlineCents), day });
  const others = TIERS.filter(x => x !== o.tier).map(x => `${t(`tier_${x}`)} ${pctOf(o.takeRates[x] ?? 0)}`).join(', ');
  const onHold = o.onHoldDisputes && o.onHoldRefunds ? t('kpiOnHoldCases', { count: o.onHoldDisputes + o.onHoldRefunds })
    : o.onHoldDisputes ? t('kpiOnHoldDisputes', { count: o.onHoldDisputes })
    : o.onHoldRefunds ? t('kpiOnHoldRefunds', { count: o.onHoldRefunds }) : t('kpiOnHold');
  return (
    <>
      <h1 className="nl-page-title" style={{ margin: '0 0 28px' }}>{headline}</h1>
      <div className="fin-kpis">
        <Kpi value={f.money(o.escrowNetCents)} label={t(`kpiEscrow_${type}` as FinanceKey, { count: o.escrowCount })} />
        <Kpi value={f.money(o.availableCents)} label={t('kpiReleased')} />
        <Kpi value={pctOf(o.takeRateBps)} label={t('kpiTakeRate', { tier: t(`tier_${o.tier}`), others })} />
        <Kpi value={f.money(o.onHoldCents)} label={onHold} />
      </div>
      <h2 className="fin-h2">{t('moneyInMotion')}</h2>
      <ol className="fin-steps" aria-label={t('moneyInMotion')}>
        <Step k={t('step1')} v={t('step1b')} />
        <Step k={t(`step2_${type}` as FinanceKey)} v={t(`step2b_${type}` as FinanceKey)} />
        <Step k={t(`step3_${type}` as FinanceKey)} v={t('step3b')} current />
        <Step k={t(`step4_${o.frequency}` as FinanceKey)} v={t('step4b')} />
      </ol>
    </>
  );
}

const Kpi = ({ value, label }: { value: string; label: string }) => <div><div className="fin-kpi-value">{value}</div><div className="fin-kpi-label">{label}</div></div>;
const Step = ({ k, v, current }: { k: string; v: string; current?: boolean }) => (
  <li className="fin-step" data-current={current ? 'true' : undefined} aria-current={current ? 'step' : undefined}><div className="fin-step-k">{k}</div><div className="fin-step-v">{v}</div></li>
);

interface LedgerRow { id: string; job: string; customer: string; held: number; tax: number; fee: number; net: number; state: string; tone: 'tag-accent' | 'tag-accent-2' | 'tag-neutral' }

export function ledgerRows(lines: LedgerLine[], t: FinanceT, date: (iso: string) => string, now = Date.now()): LedgerRow[] {
  return lines.map(l => {
    const [state, tone] =
      l.state === 'released' ? [t('stReleased'), 'tag-accent' as const]
      : l.state === 'disputed' ? [t('stDisputed'), 'tag-accent-2' as const]
      : l.state === 'refunded' ? [t('stRefunded'), 'tag-neutral' as const]
      : !l.releaseAt ? [t('stEscrowWaiting'), 'tag-neutral' as const]
      : new Date(l.releaseAt).getTime() <= now ? [t('stEscrowSoon'), 'tag-neutral' as const]
      : [t('stEscrowHours', { hours: hoursUntil(l.releaseAt, now) }), 'tag-neutral' as const];
    return {
      id: l.id, job: `${l.label} · ${l.orderNumber ?? date(l.occurredAt)}`, customer: l.customerName ?? '',
      held: l.heldCents, tax: l.taxCents, fee: l.feeCents, net: l.netCents, state, tone,
    };
  });
}

function Ledger() {
  const merchantId = useMerchantId();
  const t = useFinanceT();
  const f = useFormatters();
  const role = useRole();
  const ledger = useQuery(ledgerQuery(merchantId));
  const rows = useMemo(() => ledgerRows(ledger.data ?? [], t, iso => f.date(iso)), [ledger.data, t, f]);
  const columns: DataTableColumn<LedgerRow>[] = [
    { key: 'job', label: t('colJob'), primary: true },
    { key: 'customer', label: t('colCustomer'), priority: 2 },
    { key: 'held', label: t('colHeld'), type: 'money', priority: 3 },
    { key: 'tax', label: t('colTax'), type: 'money', priority: 1 },
    { key: 'fee', label: t('colFee'), type: 'money', priority: 1 },
    { key: 'net', label: t('colNetYou'), type: 'money', priority: 4 },
    { key: 'state', label: t('colState'), type: 'tag' },
  ];
  if (ledger.isPending) return <Skeleton height={300} />;
  return (
    <DataTable<LedgerRow>
      entity={t('ledgerEntity')} plural={t('ledgerPlural')} columns={columns} rows={rows}
      rowTones={r => ({ state: r.tone })} can={{ create: false, update: false, delete: false, export: true }}
      roleName={t(`role_${role}` as FinanceKey)} emptyText={t('ledgerEmpty')} reportName="earnings-ledger"
      error={ledger.isError ? t('loadError') : null} onRetry={() => void ledger.refetch()}
    />
  );
}
