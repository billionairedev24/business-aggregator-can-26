import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Dialog, EmptyState, ErrorState, Field, FormGrid, PageHeader, PageSkeleton, Select, Switch, TextInput, formatDate, useFormatters, useLocale } from '@northline/ui';
import { ApiError } from '../../lib/http';
import { useMerchant, useMerchantId, useRole } from '../shell/api';
import { useShellT } from '../shell/messages';
import { rewardQuery, storefrontQuery, storefrontStatsQuery, useCreateStorefront, usePublishStorefront, useSaveReward, useUpdateStorefront, type Reward, type Storefront } from './api';
import { EmbedSnippet } from '../settings/EmbedSnippet';
import { PageBuilder, serverError } from './PageBuilder';
import { StorefrontPreview } from './StorefrontPreview';
import { useStorefrontT } from './messages';

/** Studio → Business page / Store / Menu page (design 02 `v.storefront`). */
export function StorefrontScreen() {
  const t = useStorefrontT();
  const merchantId = useMerchantId();
  const merchant = useMerchant();
  const role = useRole();
  const query = useQuery(storefrontQuery(merchantId));
  const create = useCreateStorefront(merchantId);
  const kicker = t(`kind_${merchant.type}`);

  if (query.isPending) return <PageSkeleton kpis={0} rows={6} />;
  if (query.isError) return <><PageHeader kicker={kicker} title={t(`title_${merchant.type}`)} /><ErrorState message={t('loadError')} onRetry={() => void query.refetch()} /></>;
  if (!query.data) {
    return (
      <>
        <PageHeader kicker={kicker} title={t(`title_${merchant.type}`)} />
        <EmptyState action={role === 'owner' ? <button type="button" className="btn btn-primary" disabled={create.isPending} onClick={() => create.mutate()}>{t('create')}</button> : undefined}>{t('empty')}</EmptyState>
      </>
    );
  }
  return <Loaded storefront={query.data} kicker={kicker} canEdit={role === 'owner'} role={role} />;
}

function Loaded({ storefront: s, kicker, canEdit, role }: { storefront: Storefront; kicker: string; canEdit: boolean; role: string }) {
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
      <PageHeader kicker={kicker} title={s.url} lede={lede} actions={canEdit ? undefined : <span className="tag tag-neutral">{t('viewOnly', { role: roleName })}</span>} />
      <PageBuilder storefront={s} canEdit={canEdit} variant="studio">
        <Field label={t('announcement')} error={announcementError}>
          <TextInput value={announcement} disabled={!canEdit} onChange={e => setAnnouncement(e.target.value)} onBlur={() => { if (announcement.length <= 120 && announcement !== (s.announcement ?? '')) update.mutate({ announcement }); }} />
        </Field>
        <RewardField merchantId={s.merchantId} canEdit={canEdit} />
        {!approved ? <Alert tone="neutral">{t('notApproved')}</Alert> : null}
        {publish.isSuccess ? <Alert tone="info" role="status">{t('publishedOk')}</Alert> : null}
        {publishError ? <Alert tone="error">{publishError}</Alert> : null}
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          {canEdit ? <button type="button" className="btn btn-primary" disabled={!approved || publish.isPending} onClick={() => publish.mutate()}>{publish.isPending ? t('publishing') : t('publish')}</button> : null}
          <button type="button" className="btn btn-secondary" onClick={() => setDialog('preview')}>{t('previewAsCustomer')}</button>
          <button type="button" className="btn btn-ghost" onClick={() => setDialog('embed')}>{t('embedCode')}</button>
        </div>
      </PageBuilder>
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
 * until when. The owner funds it (MANAGE); other roles see it.
 */
function RewardField({ merchantId, canEdit }: { merchantId: string; canEdit: boolean }) {
  const t = useStorefrontT();
  const { locale } = useLocale();
  // endsOn is a calendar date (no time), so it is shown as that date, not shifted into a time zone
  const day = (d: string) => formatDate(`${d}T12:00:00Z`, locale, 'full', 'UTC');
  const q = useQuery(rewardQuery(merchantId));
  const save = useSaveReward(merchantId);
  const [draft, setDraft] = useState<{ multiplier: 2 | 3; label: string; endsOn: string } | null>(null);
  const current: Reward | null | undefined = q.data;
  useEffect(() => {
    if (q.isSuccess) setDraft({ multiplier: current?.multiplier === 3 ? 3 : 2, label: current?.label ?? '', endsOn: current?.endsOn ?? tomorrowPlus(14) });
  }, [q.isSuccess, current]);
  if (!draft) return <Field label={t('reward')} hint={t('rewardNote')}><TextInput disabled aria-busy="true" /></Field>;
  const errorOf = (field: string) => serverError(save.error, field);
  const submit = (active: boolean) => save.mutate({ active, multiplier: draft.multiplier, label: draft.label.trim() || null, endsOn: draft.endsOn || null });
  const status = !current ? t('rewardStopped')
    : current.running ? t('rewardRunning', { n: current.multiplier, date: day(current.endsOn) })
    : current.active ? t('rewardEnded', { date: day(current.endsOn) }) : t('rewardStopped');
  return (
    <div className="nl-field" role="group" aria-labelledby="reward-label">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
        <span className="nl-label" id="reward-label">{t('reward')}</span>
        <span style={{ display: 'inline-flex', gap: 8, alignItems: 'center' }}>
          <span className="nl-small nl-muted" role="status">{status}</span>
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
      </FormGrid>
      <div className="nl-hint">{t('rewardNote')}{canEdit ? '' : ` ${t('rewardOwnerOnly')}`}</div>
      {canEdit && current?.active ? <div><button type="button" className="btn btn-secondary" disabled={save.isPending} onClick={() => submit(true)}>{t('rewardSave')}</button></div> : null}
      {save.isError && !errorOf('multiplier') && !errorOf('label') && !errorOf('endsOn') ? <Alert tone="error">{t('saveError')}</Alert> : null}
    </div>
  );
}
