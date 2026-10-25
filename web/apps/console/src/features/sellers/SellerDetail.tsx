import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from '@tanstack/react-router';
import { ActionPanel, ActionPanelItem, ErrorState, formatCompactMoney, formatNumber, KpiStrip, PageSkeleton, RecordHeader, RecordHistory, RecordLayout, RecordTabs, StatusTag, Tag, useFormatters, useLocale, type Locale, type TagTone } from '@northline/ui';
import { ApiError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { sellerQuery, type Check, type Detail, type OversightAction, type Trail } from './api';
import { checkName, regionName } from './format';
import { useRegions } from '../shell/api';
import { STATUS_TONE } from './SellersDirectory';
import { StandingCard } from './StandingCard';
import { useSellersT, type SellersKey, type SellersT } from './messages';
import { OversightDialog } from './OversightDialog';
import './sellers.css';

const monthYear = (iso: string, locale: Locale) => new Intl.DateTimeFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { month: 'short', year: 'numeric' }).format(new Date(iso));
const fmt1 = (n: number, locale: Locale) => formatNumber(n, locale, { maximumFractionDigits: 1 });
const daysUntil = (iso: string, now: string) => Math.max(0, Math.round((Date.parse(iso) - Date.parse(now)) / 86_400_000));

/** A check as the design's "Verification on file" tags read it ("KYC passed", "AMVIC 51022", "Insurance expires in 21 d"). */
export function checkTag(c: Check, now: string, t: SellersT): { text: string; tone: TagTone } {
  const name = checkName(c.checkType, t);
  if (c.status === 'verified') {
    if (c.expiresAt && daysUntil(c.expiresAt, now) <= 30) return { text: t('ch_expires', { check: name, days: daysUntil(c.expiresAt, now) }), tone: 'warning' };
    if (c.registry && c.reference) return { text: t('ch_ref', { registry: c.registry, ref: c.reference }), tone: 'success' };
    return { text: t('ch_verified', { check: name }), tone: 'success' };
  }
  if (c.status === 'expired') return { text: t('ch_expired', { check: name }), tone: 'danger' };
  if (c.status === 'submitted') return { text: t('ch_submitted', { check: name }), tone: 'neutral' };
  if (c.status === 'rejected') return { text: t('ch_rejected', { check: name }), tone: 'danger' };
  return { text: t('ch_todo', { check: name }), tone: 'neutral' };
}

export function trailText(e: Trail, t: SellersT): string {
  const who = e.actorRole === 'system' ? t('trustRules') : e.actorName ?? t('staff');
  switch (e.action) {
    case 'tier_changed': return t('t_tier_changed', { from: t(`tier_${e.detail.from}` as SellersKey), to: t(`tier_${e.detail.to}` as SellersKey), who, reason: e.reason });
    case 'reverification_required': return t('t_reverification_required', { check: checkName(e.detail.checkType ?? '', t), who, reason: e.reason });
    default: return t(`t_${e.action}` as SellersKey, { who, reason: e.reason });
  }
}

/** "Services · Mobile mechanic · Calgary · joined Mar 2026 · Stripe acct_…": the business line under its name. */
export function sellerMeta(data: Detail, t: SellersT, locale: Locale): string {
  const s = data.seller;
  const category = s.category ? (s.category.names[locale] ?? s.category.names.en) : undefined;
  return [t(`type_${s.type}` as SellersKey), category, s.city, t('joined', { date: monthYear(data.joinedAt, locale) }), data.stripeAccount ? t('stripe', { account: data.stripeAccount }) : undefined].filter(Boolean).join(' · ');
}

/**
 * Seller detail (S-82, design 03 `seller_detail`): the business's KPIs against its tier's floors, the oversight actions
 * (suspend / reinstate, require re-verification, change tier — each with a reason the business sees, audited, emailed),
 * the quality signals, the timeline with every staff action, and the verifications on file.
 */
export function SellerDetail() {
  const t = useSellersT();
  const { sellerId } = useParams({ strict: false }) as { sellerId: string };
  const query = useQuery(sellerQuery(sellerId));
  const back = <div className="bo-rec-back"><Link to="/sellers">{t('back')}</Link></div>;
  if (query.isPending) return <div className="bo-page">{back}<PageSkeleton kpis={5} rows={6} /></div>;
  if (query.isError) {
    const missing = query.error instanceof ApiError && query.error.status === 404;
    return <div className="bo-page">{back}{missing ? <p role="alert">{t('notFound')}</p> : <ErrorState message={t('detailError')} onRetry={() => void query.refetch()} />}</div>;
  }
  return <DetailView data={query.data} />;
}

function DetailView({ data }: { data: Detail }) {
  const t = useSellersT();
  const { locale } = useLocale();
  const fmt = useFormatters();
  const regions = useRegions(locale).data;
  const { can } = useGrant();
  const s = data.seller;
  const suspended = s.status === 'suspended';
  const [dialog, setDialog] = useState<OversightAction | null>(null);
  const [applied, setApplied] = useState(false);
  const tierName = s.tier ? t(`tier_${s.tier}` as SellersKey) : '';
  const category = s.category ? (s.category.names[locale] ?? s.category.names.en) : undefined;
  const below = (m: { value?: number | null; floor?: number | null }, inverted = false) => m.value != null && m.floor != null && (inverted ? m.value > m.floor : m.value < m.floor);
  const timeline = [
    ...data.trail.map(e => ({ key: e.id, at: e.at, text: trailText(e, t) })),
    ...(data.approvedAt ? [{ key: 'approved', at: data.approvedAt, text: t('t_approved', { tier: tierName }) }] : []),
    { key: 'joined', at: data.joinedAt, text: t('t_joined') },
  ].sort((a, b) => b.at.localeCompare(a.at));
  const low = (on: boolean) => (on ? { delta: t('belowFloor'), trend: 'down' as const } : {});
  const act = (id: OversightAction) => () => { setApplied(false); setDialog(id); };
  return (
    <div className="bo-page">
      <RecordHeader back={<Link to="/sellers">{t('back')}</Link>} title={s.name}
        status={<>{s.status ? <StatusTag tone={STATUS_TONE[s.status]} size="lg">{t(`st_${s.status}` as SellersKey)}</StatusTag> : null}
          {s.searchHidden ? <StatusTag tone="warning" size="lg">{t(s.searchHidden === 'rating_floor' ? 'hiddenFloor' : 'hidden')}</StatusTag> : null}</>}
        facts={[
          { label: t('f_type'), value: [t(`type_${s.type}` as SellersKey), category].filter(Boolean).join(' · ') },
          { label: t('f_place'), value: [s.city, regionName(s.province, regions, t)].filter(Boolean).join(' · ') },
          s.tier ? { label: t('f_tier'), value: tierName } : null,
          { label: t('f_joined'), value: monthYear(data.joinedAt, locale) },
          data.stripeAccount ? { label: t('f_stripe'), value: data.stripeAccount } : null,
        ]} />
      <RecordLayout asideLabel={t('oversightTitle')} aside={
        <ActionPanel title={t('oversightTitle')} note={applied ? t('noteApplied') : can('suspend') ? t('noteNow') : t('cannot')}>
          {suspended
            ? <ActionPanelItem label={t('o_reinstate')} description={t('o_reinstateDesc')} variant="primary" disabled={!can('suspend')} onClick={act('reinstate')} />
            : <ActionPanelItem label={t('suspendCta')} description={t('o_suspendDesc')} variant="destructive" disabled={!can('suspend')} onClick={act('suspend')} />}
          <ActionPanelItem label={`${t('o_reverify')}…`} description={t('o_reverifyDesc')} disabled={!can('verify')} onClick={act('reverification')} />
          {s.tier ? <ActionPanelItem label={`${t('o_tier')}…`} description={t('o_tierDesc')} disabled={!can('suspend')} onClick={act('tier')} /> : null}
          {s.searchHidden
            ? <ActionPanelItem label={`${t('o_show')}…`} description={t(s.searchHidden === 'rating_floor' ? 'o_showFloorDesc' : 'o_showDesc')} disabled={!can('suspend')} onClick={act('search')} />
            : <ActionPanelItem label={`${t('o_hide')}…`} description={t('o_hideDesc')} disabled={!can('suspend')} onClick={act('search')} />}
        </ActionPanel>}>
        <KpiStrip label={t('kpis')} items={[
          { key: 'rating', label: t('k_rating', { n: data.ratingCount }), value: fmt1(data.ratingAverage, locale) },
          { key: 'quality', label: data.quality.floor == null ? t('k_quality') : t('k_qualityFloor', { tier: tierName, floor: data.quality.floor }),
            value: data.quality.value == null ? t('none') : formatNumber(data.quality.value, locale), ...low(below(data.quality)) },
          { key: 'onTime', label: data.onTime.floor == null ? t('k_onTime') : t('k_onTimeFloor', { floor: fmt1(data.onTime.floor, locale) }),
            value: data.onTime.value == null ? t('none') : `${fmt1(data.onTime.value, locale)}%`, ...low(below(data.onTime)) },
          { key: 'disputes', label: data.disputes.floor == null ? t('k_disputes') : t('k_disputesFloor', { floor: fmt1(data.disputes.floor, locale) }),
            value: data.disputes.value == null ? t('none') : `${fmt1(data.disputes.value, locale)}%`, ...low(below(data.disputes, true)) },
          { key: 'gmv', label: t('k_gmv'), value: formatCompactMoney(s.gmv90Cents, locale) },
        ]} />
        <RecordTabs label={s.name} tabs={[
          { value: 'standing', label: t('tabStanding'), render: () => <>
            <h2 className="bo-card-title">{t('signalsTitle')}</h2>
            {data.signals.length ? data.signals.map(sg => (
              <div key={sg.key} className="nl-sl-signal">
                <span>{t(`s_${sg.key}` as SellersKey)}</span>
                <div className="nl-sl-track"><div className="nl-sl-fill" data-low={sg.bar < sg.barFloor || undefined} style={{ ['--pct' as string]: `${Math.max(0, Math.min(100, sg.bar))}%` }} /></div>
                <strong>{`${fmt1(sg.value, locale)}%`}</strong>
              </div>
            )) : <p className="bo-muted">{t('noSignals')}</p>}
            <StandingCard businessId={s.id} />
          </> },
          { value: 'evidence', label: t('checksTitle'), count: data.checks.length, render: () => (data.checks.length
            ? <div className="nl-sl-tags">{data.checks.map(c => { const tag = checkTag(c, data.asOf, t); return <Tag key={c.id} tone={tag.tone}>{tag.text}</Tag>; })}</div>
            : <p className="bo-muted">{t('noChecksOnFile')}</p>) },
          { value: 'history', label: t('timelineTitle'), count: timeline.length, render: () => <RecordHistory items={timeline.map(e => ({ key: e.key, when: fmt.date(e.at), text: e.text }))} /> },
        ]} />
      </RecordLayout>
      {dialog ? <OversightDialog seller={s} action={dialog} checks={data.checks} onClose={() => setDialog(null)} onDone={() => setApplied(true)} /> : null}
    </div>
  );
}
