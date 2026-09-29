import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Dialog, EmptyState, ErrorState, Field, PageHeader, PageSkeleton, TextInput, useFormatters } from '@northline/ui';
import { ApiError } from '../../lib/http';
import { useMerchant, useMerchantId, useRole } from '../shell/api';
import { useShellT } from '../shell/messages';
import { storefrontQuery, useCreateStorefront, usePublishStorefront, useUpdateStorefront, type Storefront } from './api';
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
  const lede = `${s.publishedAt ? t('published', { date: date(s.publishedAt, 'full') }) : t('notPublished')} ${t('publishTail')}`;
  const roleName = shellT(`role_${role}` as Parameters<typeof shellT>[0]);

  return (
    <>
      <PageHeader kicker={kicker} title={s.url} lede={lede} actions={canEdit ? undefined : <span className="tag tag-neutral">{t('viewOnly', { role: roleName })}</span>} />
      <PageBuilder storefront={s} canEdit={canEdit} variant="studio">
        <Field label={t('announcement')} error={announcementError}>
          <TextInput value={announcement} disabled={!canEdit} onChange={e => setAnnouncement(e.target.value)} onBlur={() => { if (announcement.length <= 120 && announcement !== (s.announcement ?? '')) update.mutate({ announcement }); }} />
        </Field>
        <Field label={t('reward')} hint={`${t('rewardNote')} ${t('rewardSoon')}`}>
          <TextInput disabled aria-disabled="true" />
        </Field>
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
  const [copied, setCopied] = useState(false);
  const code = `<script src="https://northline.ca/embed.js" data-page="${storefront.slug}" async></script>`;
  return (
    <Dialog open={open} onClose={onClose} title={t('embedTitle')} actions={<>
      <button type="button" className="btn btn-secondary" onClick={() => { void navigator.clipboard?.writeText(code).then(() => setCopied(true)); }}>{copied ? t('copied') : t('copy')}</button>
      <button type="button" className="btn btn-primary" onClick={onClose}>{t('close')}</button>
    </>}>
      <p className="nl-muted">{t('embedHelp')}</p>
      <pre className="nl-embed-code"><code>{code}</code></pre>
    </Dialog>
  );
}
