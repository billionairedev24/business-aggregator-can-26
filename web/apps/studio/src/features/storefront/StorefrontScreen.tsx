import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Dialog, EmptyState, ErrorState, Field, FormGrid, PageSkeleton, SaveBar, Select, StatusTag, Switch, TextInput, formatDate, useFormatters, useLocale, BoHeader } from '@northline/ui';
import { ApiError } from '../../lib/http';
import { useMerchant, useMerchantId, useRole } from '../shell/api';
import { useShellT } from '../shell/messages';
import { rewardQuery, storefrontQuery, storefrontStatsQuery, useCreateStorefront, usePublishStorefront, useSaveReward, useUpdateStorefront, type Reward, type Storefront } from './api';
import { EmbedSnippet } from '../settings/EmbedSnippet';
import { serverError } from './PageBuilder';
import { PublicPageForm } from './PublicPageForm';
import { StorefrontPreview } from './StorefrontPreview';
import { useStorefrontT } from './messages';

/** Studio → Business page / Store / Menu page: the public-page form (redesign § 3.11, PH:171), not the block builder. */
export function StorefrontScreen() {
  const t = useStorefrontT();
  const merchantId = useMerchantId();
  const merchant = useMerchant();
  const role = useRole();
  const query = useQuery(storefrontQuery(merchantId));
  const create = useCreateStorefront(merchantId);
  const title = t(`kind_${merchant.type}`);

  if (query.isPending) return <PageSkeleton kpis={0} rows={6} />;
  if (query.isError) return <><BoHeader title={title} /><ErrorState message={t('loadError')} onRetry={() => void query.refetch()} /></>;
  if (!query.data) {
    return (
      <>
        <BoHeader title={title} />
        <EmptyState action={role === 'owner' ? <button type="button" className="btn btn-primary" disabled={create.isPending} onClick={() => create.mutate()}>{t('create')}</button> : undefined}>{t('empty')}</EmptyState>
      </>
    );
  }
  return <Loaded storefront={query.data} title={title} canEdit={role === 'owner'} role={role} />;
}

function Loaded({ storefront: s, title, canEdit, role }: { storefront: Storefront; title: string; canEdit: boolean; role: string }) {
  const t = useStorefrontT();
  const shellT = useShellT();
  const { date } = useFormatters();
  const publish = usePublishStorefront(s.merchantId);
  const update = useUpdateStorefront(s.merchantId);
  const [announcement, setAnnouncement] = useState(s.announcement ?? '');
  useEffect(() => setAnnouncement(s.announcement ?? ''), [s.announcement]);
  const [dialog, setDialog] = useState<'preview' | 'embed' | null>(null);
  const approved = s.business.status === 'active';
  const publishError = publish.error instanceof ApiError && publish.error.status === 409 ? t('notApproved') : serverError(publish.error, 'customDomain') ?? (publish.isError ? t('saveError') : undefined);
  const announcementError = announcement.length > 120 ? t('announcementTooLong') : serverError(update.error, 'announcement');
  const stats = useQuery(storefrontStatsQuery(s.merchantId)).data;
  const { locale } = useLocale();
  // S-75: "1,204 visits last 30 days · 8.6% booked" (design 02 lede), then the publish state
  const statsLine = !stats ? '' : stats.visits === 0 ? `${t('statsNoVisits')} `
    : `${t('stats', { visits: stats.visits, rate: new Intl.NumberFormat(locale === 'fr' ? 'fr-CA' : 'en-CA', { style: 'percent', maximumFractionDigits: 1 }).format((stats.bookedRateBps ?? 0) / 10_000) })} `;
  const lede = `${statsLine}${s.publishedAt ? t('published', { date: date(s.publishedAt, 'full') }) : t('notPublished')} ${t('publishTail')}`;
  const roleName = shellT(`role_${role}` as Parameters<typeof shellT>[0]);

  return (
    <>
      <BoHeader title={title} sub={<>{lede} <strong className="nl-pp-url">{s.url}</strong></>}
        actions={<>
          {canEdit ? null : <StatusTag tone="neutral">{t('viewOnly', { role: roleName })}</StatusTag>}
          <button type="button" className="btn btn-secondary" onClick={() => setDialog('embed')}>{t('embedCode')}</button>
        </>} />
      <PublicPageForm storefront={s} canEdit={canEdit}>
        <Field label={t('announcement')} error={announcementError}>
          <TextInput value={announcement} disabled={!canEdit} onChange={e => setAnnouncement(e.target.value)} onBlur={() => { if (announcement.length <= 120 && announcement !== (s.announcement ?? '')) update.mutate({ announcement }); }} />
        </Field>
        <RewardField merchantId={s.merchantId} canEdit={canEdit} />
        {!approved ? <Alert tone="neutral">{t('notApproved')}</Alert> : null}
        {publish.isSuccess ? <Alert tone="info" role="status">{t('publishedOk')}</Alert> : null}
        {publishError ? <Alert tone="error">{publishError}</Alert> : null}
      </PublicPageForm>
      {/* S-176: the page saves as it changes; the bar says where it stands and publishes it */}
      <SaveBar label={t('saveBarLabel')}
        status={s.publishedAt ? <StatusTag tone="success">{t('pubLive', { date: date(s.publishedAt) })}</StatusTag> : <StatusTag tone="warning">{t('pubDraft')}</StatusTag>}
        note={!approved ? t('pubNeedsApproval') : t('pubNote')}>
        <button type="button" className="btn btn-secondary" onClick={() => setDialog('preview')}>{t('previewAsCustomer')}</button>
        {canEdit ? <button type="button" className="btn btn-primary" disabled={!approved || publish.isPending} onClick={() => publish.mutate()}>{publish.isPending ? t('publishing') : s.publishedAt ? t('republish') : t('publish')}</button> : null}
      </SaveBar>
      <Dialog open={dialog === 'preview'} onClose={() => setDialog(null)} title={t('previewAsCustomer')} width={760} actions={<button type="button" className="btn btn-secondary" onClick={() => setDialog(null)}>{t('close')}</button>}>
        <StorefrontPreview storefront={s} device="web" />
      </Dialog>
      <EmbedDialog open={dialog === 'embed'} onClose={() => setDialog(null)} storefront={s} />
    </>
  );
}

function EmbedDialog({ open, onClose, storefront }: { open: boolean; onClose: () => void; storefront: Storefront }) {
  const t = useStorefrontT();
  // S-76: the snippet carries the business's publishable key (Settings › API also limits the websites)
  return (
    <Dialog open={open} onClose={onClose} title={t('embedTitle')} actions={<button type="button" className="btn btn-primary" onClick={onClose}>{t('close')}</button>}>
      {open ? <EmbedSnippet slug={storefront.slug} /> : null}
    </Dialog>
  );
}

const tomorrowPlus = (days: number) => new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);

/**
 * S-75 "Provider-funded reward" (design 02): on/off, 2× or 3× points, on what ("brake jobs"; empty = everything) and
 * until when. The owner funds it (MANAGE); other roles see it. S-152: its budget (what the extra points may cost in
 * all; it stops when that runs out) and what it has paid for — the cost comes out of the business's payouts.
 */
function RewardField({ merchantId, canEdit }: { merchantId: string; canEdit: boolean }) {
  const t = useStorefrontT();
  const { locale } = useLocale();
  // endsOn is a calendar date (no time), so it is shown as that date, not shifted into a time zone
  const day = (d: string) => formatDate(`${d}T12:00:00Z`, locale, 'full', 'UTC');
  const q = useQuery(rewardQuery(merchantId));
  const save = useSaveReward(merchantId);
  const { price, number } = useFormatters();
  const [draft, setDraft] = useState<{ multiplier: 2 | 3; label: string; endsOn: string; budget: string } | null>(null);
  const current: Reward | null | undefined = q.data;
  useEffect(() => {
    if (q.isSuccess) setDraft({ multiplier: current?.multiplier === 3 ? 3 : 2, label: current?.label ?? '', endsOn: current?.endsOn ?? tomorrowPlus(14),
      budget: current?.budgetCents != null ? String(current.budgetCents / 100) : '' });
  }, [q.isSuccess, current]);
  if (!draft) return <Field label={t('reward')} hint={t('rewardNote')}><TextInput disabled aria-busy="true" /></Field>;
  const errorOf = (field: string) => serverError(save.error, field);
  const budgetCents = draft.budget.trim() === '' || Number.isNaN(Number(draft.budget)) ? null : Math.round(Number(draft.budget) * 100);
  const submit = (active: boolean) => save.mutate({ active, multiplier: draft.multiplier, label: draft.label.trim() || null, endsOn: draft.endsOn || null, budgetCents });
  const status = !current ? t('rewardStopped')
    : current.exhausted ? t('rewardExhausted')
    : current.running ? t('rewardRunning', { n: current.multiplier, date: day(current.endsOn) })
    : current.active ? t('rewardEnded', { date: day(current.endsOn) }) : t('rewardStopped');
  return (
    <div className="nl-field" role="group" aria-labelledby="reward-label">
      <div className="nl-pp-reward-head">
        <span className="nl-label" id="reward-label">{t('reward')}</span>
        <span className="nl-pp-reward-state">
          <StatusTag tone={current?.running ? 'success' : current?.exhausted ? 'warning' : 'neutral'}><span role="status">{status}</span></StatusTag>
          <Switch checked={!!current?.active} disabled={!canEdit || save.isPending} label={t('rewardOn')} onChange={on => submit(on)} />
        </span>
      </div>
      <FormGrid min={150}>
        <Field label={t('rewardMultiplier')} error={errorOf('multiplier')}>
          <Select value={String(draft.multiplier)} disabled={!canEdit} options={[2, 3].map(n => ({ value: String(n), label: t('rewardTimes', { n }) }))}
            onChange={e => setDraft({ ...draft, multiplier: e.target.value === '3' ? 3 : 2 })} />
        </Field>
        <Field label={t('rewardLabel')} error={errorOf('label')}>
          <TextInput value={draft.label} maxLength={60} disabled={!canEdit} placeholder={t('rewardLabelPlaceholder')} onChange={e => setDraft({ ...draft, label: e.target.value })} />
        </Field>
        <Field label={t('rewardUntil')} error={errorOf('endsOn')}>
          <TextInput type="date" value={draft.endsOn} disabled={!canEdit} onChange={e => setDraft({ ...draft, endsOn: e.target.value })} />
        </Field>
        <Field label={t('rewardBudget')} hint={t('rewardBudgetHint')} error={errorOf('budgetCents')}>
          <TextInput inputMode="decimal" value={draft.budget} disabled={!canEdit} placeholder="50" onChange={e => setDraft({ ...draft, budget: e.target.value })} />
        </Field>
      </FormGrid>
      {current && current.budgetCents != null ? (
        <div className="nl-pp-small" role="status" aria-label={t('rewardSpentLabel')}>
          {t('rewardSpent', { spent: price(current.spentCents), budget: price(current.budgetCents), points: number(current.pointsCredited), sales: current.salesCredited })}
        </div>
      ) : null}
      <div className="nl-hint">{t('rewardNote')}{canEdit ? '' : ` ${t('rewardOwnerOnly')}`}</div>
      {canEdit && current?.active ? <div><button type="button" className="btn btn-secondary" disabled={save.isPending} onClick={() => submit(true)}>{t('rewardSave')}</button></div> : null}
      {save.isError && !errorOf('multiplier') && !errorOf('label') && !errorOf('endsOn') && !errorOf('budgetCents') ? <Alert tone="error">{t('saveError')}</Alert> : null}
    </div>
  );
}
