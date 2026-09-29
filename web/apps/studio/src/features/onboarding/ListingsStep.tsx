import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Alert, ErrorState, Skeleton, useFormatters } from '@northline/ui';
import { useAdvanceStep, type Onboarding } from './api';
import { ListingsList } from './ListingsList';
import { listingsQuery, type MenuItem } from './listingsApi';
import { MenuItemForm, ProductForm, ServiceForm } from './ListingForms';
import { useOnboardingT } from './messages';

export interface ListingsStepProps { onboarding: Onboarding; onBack: () => void; onDone: () => void }

/** Step 6 · Listings (design 02 lines 252–298): first service / product / menu item through the catalogue contract. */
export function ListingsStep({ onboarding: o, onBack, onDone }: ListingsStepProps) {
  const t = useOnboardingT();
  const { money } = useFormatters();
  const advance = useAdvanceStep(o.merchantId);
  const kitchen = o.type === 'kitchen';
  const kind = o.type === 'seller' ? 'product' : o.type === 'provider' ? 'service' : undefined;
  const listings = useQuery({ ...listingsQuery(o.merchantId, kind), enabled: !kitchen });
  const [menuItems, setMenuItems] = useState<MenuItem[]>([]);

  const rows = kitchen
    ? menuItems.map(i => ({ id: i.id, name: i.name, meta: i.priceCents != null ? money(i.priceCents) : '', state: t('hiddenUntil'), cls: 'tag-accent-2' }))
    : (listings.data ?? []).map(l => ({
      id: l.id,
      name: l.name,
      meta: l.meta ?? (l.kind === 'service'
        ? t('meta_service', { price: l.priceCents != null ? money(l.priceCents) : t('pr_quote') })
        : t('meta_product', { price: l.priceCents != null ? money(l.priceCents) : '—', stock: l.stock ?? 0 })),
      state: t(`vet_${l.vetting}`),
      cls: l.vetting === 'approved' ? 'tag-accent' : l.vetting === 'rejected' ? 'tag-neutral' : 'tag-accent-2',
    }));

  return (
    <>
      <h1 className="nl-ob-title">{t(`fl_title_${o.type}`)}</h1>
      <p className="nl-ob-intro" style={{ marginBottom: 24 }}>{t(`fl_intro_${o.type}`)}</p>
      <div className="nl-ob-two">
        <section aria-labelledby="nl-fl-new">
          <h3 id="nl-fl-new" style={{ fontSize: 18, margin: '0 0 10px' }}>{t(`fl_new_${o.type}`)}</h3>
          {kitchen ? <MenuItemForm merchantId={o.merchantId} onCreated={i => setMenuItems(list => [i, ...list])} />
            : o.type === 'seller' ? <ProductForm onboarding={o} />
            : <ServiceForm merchantId={o.merchantId} />}
        </section>
        <section aria-labelledby="nl-fl-list">
          <h3 id="nl-fl-list" style={{ fontSize: 18, margin: '0 0 10px' }}>{t('listingsCount', { count: rows.length })}</h3>
          {!kitchen && listings.isPending ? <><Skeleton height={44} style={{ marginBottom: 8 }} /><Skeleton height={44} /></>
            : !kitchen && listings.isError ? <ErrorState message={t('listingsError')} onRetry={() => void listings.refetch()} />
            : <ListingsList rows={rows} empty={t('listingsEmpty')} />}
          <div className="nl-alert nl-alert-neutral" style={{ marginTop: 14, fontSize: 13 }}><strong>{t('vettingTitle')}</strong> {t('vettingBody')}</div>
          <div style={{ marginTop: 14, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <Link className="btn btn-secondary" to="/b/$merchantId/listings/bulk" params={{ merchantId: o.merchantId }}>{t('importCsv')}</Link>
            <Link className="btn btn-secondary" to="/b/$merchantId/listings/bulk" params={{ merchantId: o.merchantId }}>{t('syncShop')}</Link>
          </div>
        </section>
      </div>
      {advance.isError ? <div style={{ marginTop: 14 }}><Alert tone="error">{t('saveError')}</Alert></div> : null}
      <div className="nl-ob-actions" style={{ marginTop: 28 }}>
        <button type="button" className="btn btn-primary" disabled={advance.isPending} onClick={() => advance.mutate('done', { onSuccess: onDone })}>{t(`fl_cta_${o.type}`)}</button>
        <button type="button" className="btn btn-ghost" onClick={onBack}>{t('back')}</button>
      </div>
    </>
  );
}
