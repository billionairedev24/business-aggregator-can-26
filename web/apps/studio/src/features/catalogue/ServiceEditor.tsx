import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react';
import { ArrowLeft, ArrowRight, ImageSquare, X } from '@phosphor-icons/react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Alert, Checkbox, Field, FormGrid, Segmented, Select, ServiceCard, TextArea, TextInput, useFormatters, useLocale } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useMerchant, useMerchantId, useRole } from '../shell/api';
import { useShellT } from '../shell/messages';
import { categoriesQuery, useSaveService, useSubmitListing, useUploadImage, type PricingMode, type ServiceDetail } from './api';
import { CatalogueThumb } from './ProductEditor';
import { imageProblem } from './imageFile';
import { AttentionSummary, CategoryPicker, CompletenessPanel, EditorHeader, EditorSaveBar, FeesPanel, PhotoTips, RevetNotice, Side, VettingPanel, draftTag, useEditorForm } from './EditorParts';
import { useCatalogueT } from './messages';
import { emptyService, permissions, serviceCompleteness, serviceFromDetail, servicePayload, validateServiceDraft, vettingChecks, type Portal, type ServiceForm } from './model';
import { parseMoney, useMessageT } from './validation';
import { ListingCopyButton } from '../writing/WritingHelp';
import { FrenchTextPanel } from './FrenchTextPanel';

const DURATIONS = [30, 45, 60, 90, 120, 240] as const;
const BUFFERS = [0, 15, 20, 30] as const;

/**
 * Service editor (providers): name, category, pricing (fixed / quote / hourly), price incl. travel, duration, buffer,
 * what's included, instant book, photos of the work (optional; real photos 2026-10-19: customers see the first on the
 * provider page, in search and on the category's cards) — Save draft separate from Submit for vetting, same side panels
 * as products.
 */
export function ServiceEditor({ detail, portal, typePicker }: { detail?: ServiceDetail; portal: Portal; typePicker?: ReactNode }) {
  const t = useCatalogueT();
  const mt = useMessageT();
  const shellT = useShellT();
  const merchantId = useMerchantId();
  const merchant = useMerchant();
  const role = useRole();
  const perms = permissions(role);
  const { locale } = useLocale();
  const { price: fmtPrice, date } = useFormatters();
  const navigate = useNavigate();
  const categoriesQ = useQuery(categoriesQuery(merchantId, 'service', locale));
  const categories = useMemo(() => categoriesQ.data ?? [], [categoriesQ.data]);
  const byId = useMemo(() => new Map(categories.map(c => [c.id, c])), [categories]);
  const [saved, setSaved] = useState<ServiceDetail | undefined>(detail);
  const [failure, setFailure] = useState<string | null>(null);
  const validate = useCallback((f: ServiceForm) => validateServiceDraft(f, byId.get(f.categoryId)), [byId]);
  const fm = useEditorForm<ServiceForm>(detail ? serviceFromDetail(detail) : emptyService(), validate);
  const { form, update, touch } = fm;
  const err = (field: string) => mt(fm.error(field));
  const category = byId.get(form.categoryId);
  const save = useSaveService(merchantId);
  const submit = useSubmitListing(merchantId);
  const completeness = serviceCompleteness(form, category);
  const canSubmit = !saved || saved.vetting === 'draft' || saved.vetting === 'rejected';
  const price = form.pricingMode === 'quote' ? null : parseMoney(form.price);

  async function persist(): Promise<ServiceDetail | null> {
    setFailure(null);
    fm.setAttempted(true);
    if (Object.keys(fm.client).length) return null;
    try {
      const d = await save.mutateAsync({ id: saved?.id, body: servicePayload(form) });
      setSaved(d); fm.reset(serviceFromDetail(d));
      if (!saved) void navigate({ to: '/b/$merchantId/listings/$listingId', params: { merchantId, listingId: d.id }, replace: true });
      return d;
    } catch (e) {
      if (!fm.fromServer(e)) setFailure(t('saveError'));
      return null;
    }
  }
  async function onSubmit() {
    const d = fm.dirty || !saved ? await persist() : saved;
    if (!d) return;
    try {
      const r = await submit.mutateAsync(d.id);
      if (r.kind === 'service') { setSaved(r); fm.reset(serviceFromDetail(r)); }
    } catch (e) { if (!(e instanceof ValidationError && fm.fromServer(e))) setFailure(t('saveError')); }
  }

  const sections = [
    { name: t('secDetails'), done: completeness.done.details }, { name: t('secPricing'), done: completeness.done.pricing },
    { name: t('secSchedule'), done: completeness.done.schedule }, { name: t('secIncluded'), done: completeness.done.included },
  ];
  const kicker = t('editorKicker', { section: portal === 'seller' ? t('kickerProducts') : t('kickerListings'), kind: 'service' });

  return (
    <div className="nl-cat-page">
      <EditorHeader kicker={kicker} title={saved?.name || form.name || t('newService')} canEdit={perms.update} roleName={shellT(`role_${role}` as Parameters<typeof shellT>[0])} />
      {failure && <Alert tone="error">{failure}</Alert>}
      <RevetNotice detail={saved} kind="service" />
      <AttentionSummary errors={fm.visible} t={t} />
      <div className="nl-cat-editor">
        <fieldset className="nl-cat-main" disabled={!perms.update}>
          <section className="nl-cat-card" aria-labelledby="svc-details">
          <h2 className="nl-cat-card-title" id="svc-details">{t('secDetails')}</h2>
          {typePicker}
          <Field label={t('serviceName')} error={err('name')}>
            <TextInput placeholder={t('serviceNamePlaceholder')} value={form.name} maxLength={120} onChange={e => update({ name: e.target.value })} onBlur={() => touch('name')} />
          </Field>
          <Field label={t('category')} hint={t('categoryHint')} error={err('categoryId')}>
            <CategoryPicker categories={categories} value={form.categoryId} rootLabel={t('rootService')} disabled={categoriesQ.isPending} invalid={!!err('categoryId')}
              onChange={id => { update({ categoryId: id }); touch('categoryId'); }} />
          </Field>
          </section>
          <section className="nl-cat-card" aria-labelledby="svc-pricing">
          <h2 className="nl-cat-card-title" id="svc-pricing">{t('secPricingSchedule')}</h2>
          <FormGrid min={220}>
            <div className="nl-field" role="group" aria-labelledby="pricing-label">
              <span className="nl-label" id="pricing-label">{t('pricing')}</span>
              <Segmented<PricingMode> name="pricing" aria-label={t('pricing')} value={form.pricingMode} onChange={v => update({ pricingMode: v }, ['pricingMode', 'priceCents'])}
                options={(['fixed', 'quote', 'hourly'] as const).map(v => ({ value: v, label: t(`pr_${v}`) }))} />
              {err('pricingMode') && <div role="alert" className="nl-error">{err('pricingMode')}</div>}
            </div>
            {form.pricingMode === 'quote'
              ? <p className="nl-hint nl-cat-self-end">{t('quoteHint')}</p>
              : <Field label={form.pricingMode === 'hourly' ? t('pricePerHour') : t('priceInclTravel')} error={err('priceCents')}>
                  <TextInput inputMode="decimal" placeholder="$0" value={form.price} onChange={e => update({ price: e.target.value }, ['priceCents'])} onBlur={() => touch('priceCents')} />
                </Field>}
          </FormGrid>
          <FormGrid min={180}>
            <Field label={t('duration')} error={err('durationMin')}>
              <Select value={String(form.durationMin)} options={DURATIONS.map(d => ({ value: String(d), label: t(`dur_${d}`) }))} onChange={e => update({ durationMin: Number(e.target.value) })} />
            </Field>
            <Field label={t('buffer')} error={err('bufferMin')}>
              <Select value={String(form.bufferMin)} options={BUFFERS.map(b => ({ value: String(b), label: t(`buf_${b}`) }))} onChange={e => update({ bufferMin: Number(e.target.value) })} />
            </Field>
          </FormGrid>
          <Checkbox checked={form.instantBook} onChange={v => update({ instantBook: v })} label={t('instantBook')} />
          </section>
          <section className="nl-cat-card" aria-labelledby="svc-included">
          <h2 className="nl-cat-card-title" id="svc-included">{t('secIncluded')}</h2>
          <Field label={t('included')} error={err('included')}>
            <TextArea rows={3} placeholder={t('includedPlaceholder')} value={form.included} onChange={e => update({ included: e.target.value })} onBlur={() => touch('included')} />
          </Field>
          <ListingCopyButton merchantId={merchantId} disabled={!perms.update}
            facts={{ kind: 'service', name: form.name, categoryId: form.categoryId, included: form.included, durationMin: form.durationMin }}
            onUse={c => update({ name: c.title, included: [c.description, ...c.bullets.map(b => `• ${b}`)].join('\n') }, ['name', 'included'])} />
          <FrenchTextPanel merchantId={merchantId} listingId={saved?.id} disabled={!perms.update} />
          <Field label={t('skuLabel')} hint={t('skuHint')} error={err('sku')}>
            <TextInput className="nl-cat-w-sm" value={form.sku} maxLength={60} onChange={e => update({ sku: e.target.value })} onBlur={() => touch('sku')} />
          </Field>
          </section>
          <section className="nl-cat-card" aria-labelledby="svc-photos">
          <h2 className="nl-cat-card-title" id="svc-photos">{t('secPhotos')}</h2>
          <p className="nl-hint">{t('photosHint')}</p>
          <div className="nl-cat-upload">
            <ServicePhotos images={form.images} onChange={images => update({ images }, ['imageIds'])} error={err('imageIds')} />
            <PhotoTips />
          </div>
          </section>
        </fieldset>
        <Side>
          {/* S-175: the live preview — the service as customers see it on the provider page and in results */}
          <section className="nl-cat-side nl-cat-preview" aria-labelledby="svc-preview">
            <h2 className="nl-cat-side-title" id="svc-preview">{t('previewLive')}</h2>
            <p className="nl-cat-flush nl-cat-muted">{t('previewNote')}</p>
            <div className="nl-cat-preview-card" inert>
              <ServiceCard layout="stack" name={form.name || t('newService')} kind={category?.name} imageUrl={form.images[0]?.url}
                place={merchant.displayName} note={t(`dur_${form.durationMin}` as Parameters<typeof t>[0])}
                priceCents={form.pricingMode === 'quote' || price === null || Number.isNaN(price) ? null : price}
                priceText={form.pricingMode === 'quote' ? t('quote') : undefined} priceSuffix={form.pricingMode === 'hourly' ? t('perHour') : undefined} />
            </div>
          </section>
          <CompletenessPanel t={t} percent={completeness.percent} items={sections} />
          <VettingPanel t={t} checks={vettingChecks({ category, priceCents: price, images: [], imageSource: 'own', link: null, text: form.name, gtin: null, money: fmtPrice }).filter(c => !c.key.startsWith('vpImages'))} />
          <FeesPanel priceCents={price} costCents={null} tier={merchant.tier} />
        </Side>
      </div>
      <EditorSaveBar label={t('saveBarService')} tag={draftTag(saved, t, iso => date(iso, 'time'))} dirty={fm.dirty} canEdit={perms.update}
        saveLabel={saved?.vetting === 'approved' ? t('saveChanges') : t('saveDraft')} saving={save.isPending} submitting={submit.isPending}
        cannotSubmit={!completeness.complete || !canSubmit} onSave={() => void persist()} onSubmit={() => void onSubmit()} />
    </div>
  );
}

const PHOTOS_MAX = 9;

/** Photos of the work: upload (JPG/PNG, ≥ 1000 px, as product images), reorder, remove; the first is the main one. */
function ServicePhotos({ images, onChange, error }: { images: ServiceForm['images']; onChange: (images: ServiceForm['images']) => void; error?: string }) {
  const t = useCatalogueT();
  const mt = useMessageT();
  const upload = useUploadImage(useMerchantId());
  const input = useRef<HTMLInputElement>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const move = (from: number, to: number) => {
    if (to < 0 || to >= images.length) return;
    const next = [...images]; const [m] = next.splice(from, 1); next.splice(to, 0, m!);
    onChange(next);
  };
  async function onFiles(files: FileList | null) {
    setProblem(null);
    let next = images;
    for (const file of Array.from(files ?? [])) {
      if (next.length >= PHOTOS_MAX) { setProblem(t('imagesFull')); break; }
      const bad = await imageProblem(file);
      if (bad) { setProblem(mt(bad) ?? bad); continue; }
      try {
        next = [...next, await upload.mutateAsync(file)];
        onChange(next);
      } catch (e) {
        setProblem(e instanceof ValidationError ? (mt(e.errors[0]?.message) ?? t('saveError')) : t('saveError'));
      }
    }
    if (input.current) input.current.value = '';
  }
  return (
    <div className="nl-field" role="group" aria-labelledby="svc-photos">
      <ul className="nl-cat-images">
        {images.map((m, i) => (
          <li key={m.id} className="nl-cat-image">
            <CatalogueThumb media={m} label={t('photoAlt', { n: i + 1 })} />
            {i === 0 && <span className="tag tag-neutral nl-cat-main-tag">{t('main')}</span>}
            <div className="nl-cat-image-tools">
              <button type="button" aria-label={t('moveLeft', { n: i + 1 })} disabled={i === 0} onClick={() => move(i, i - 1)}><ArrowLeft size={14} /></button>
              <button type="button" aria-label={t('moveRight', { n: i + 1 })} disabled={i === images.length - 1} onClick={() => move(i, i + 1)}><ArrowRight size={14} /></button>
              <button type="button" aria-label={t('removeImage', { n: i + 1 })} onClick={() => onChange(images.filter((_, j) => j !== i))}><X size={14} /></button>
            </div>
          </li>
        ))}
        {images.length < PHOTOS_MAX && (
          <li className={images.length ? undefined : 'nl-cat-image-first'}>
            <button type="button" className="nl-cat-add-image" data-empty={!images.length || undefined} onClick={() => input.current?.click()} disabled={upload.isPending}
              onDragOver={e => { if (e.dataTransfer.types.includes('Files')) e.preventDefault(); }}
              onDrop={e => { if (!e.dataTransfer.files.length) return; e.preventDefault(); void onFiles(e.dataTransfer.files); }}>
              <ImageSquare size={images.length ? 24 : 32} aria-hidden />
              {upload.isPending ? <span>{t('uploading')}</span> : <><span className="nl-cat-add-image-label">{images.length ? t('addImage') : t('addFirstPhoto')}</span><span className="nl-cat-add-image-hint">{t('addImageHint')}</span></>}
            </button>
            <input ref={input} type="file" accept="image/jpeg,image/png" multiple hidden aria-label={t('addFirstPhoto')} data-testid="service-photo-input" onChange={e => void onFiles(e.target.files)} />
          </li>
        )}
      </ul>
      {(problem || error) && <div role="alert" className="nl-error">{problem ?? error}</div>}
    </div>
  );
}
