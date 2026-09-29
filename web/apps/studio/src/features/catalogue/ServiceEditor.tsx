import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Alert, Checkbox, Field, FormGrid, Segmented, Select, TextArea, TextInput, useFormatters, useLocale } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useMerchant, useMerchantId, useRole } from '../shell/api';
import { useShellT } from '../shell/messages';
import { categoriesQuery, useSaveService, useSubmitListing, type PricingMode, type ServiceDetail } from './api';
import { AttentionSummary, CategoryPicker, CompletenessPanel, EditorHeader, FeesPanel, Side, VettingPanel, draftTag, useEditorForm } from './EditorParts';
import { useCatalogueT } from './messages';
import { emptyService, permissions, serviceCompleteness, serviceFromDetail, servicePayload, validateServiceDraft, vettingChecks, type Portal, type ServiceForm } from './model';
import { parseMoney, useMessageT } from './validation';

const DURATIONS = [30, 45, 60, 90, 120, 240] as const;
const BUFFERS = [0, 15, 20, 30] as const;

/**
 * Service editor (providers): name, category, pricing (fixed / quote / hourly), price incl. travel, duration, buffer,
 * what's included, instant book — Save draft separate from Submit for vetting, same side panels as products.
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
  const { money, date } = useFormatters();
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
    <>
      <EditorHeader kicker={kicker} title={saved?.name || form.name || t('newService')} tag={draftTag(saved, t, iso => date(iso, 'time'))} canEdit={perms.update}
        roleName={shellT(`role_${role}` as Parameters<typeof shellT>[0])} saveLabel={saved?.vetting === 'approved' ? t('saveChanges') : t('saveDraft')}
        saving={save.isPending} submitting={submit.isPending} cannotSubmit={!completeness.complete || !canSubmit} onSave={() => void persist()} onSubmit={() => void onSubmit()} />
      {failure && <div style={{ marginBottom: 16 }}><Alert tone="error">{failure}</Alert></div>}
      <div style={{ marginBottom: 16 }}><AttentionSummary errors={fm.visible} t={t} /></div>
      <div className="nl-cat-editor">
        <fieldset className="nl-cat-main" disabled={!perms.update}>
          {typePicker}
          <Field label={t('serviceName')} error={err('name')}>
            <TextInput placeholder={t('serviceNamePlaceholder')} value={form.name} maxLength={120} onChange={e => update({ name: e.target.value })} onBlur={() => touch('name')} />
          </Field>
          <Field label={t('category')} hint={t('categoryHint')} error={err('categoryId')}>
            <CategoryPicker categories={categories} value={form.categoryId} rootLabel={t('rootService')} disabled={categoriesQ.isPending} invalid={!!err('categoryId')}
              onChange={id => { update({ categoryId: id }); touch('categoryId'); }} />
          </Field>
          <FormGrid min={220}>
            <div className="nl-field" role="group" aria-labelledby="pricing-label">
              <span className="nl-label" id="pricing-label">{t('pricing')}</span>
              <Segmented<PricingMode> name="pricing" aria-label={t('pricing')} value={form.pricingMode} onChange={v => update({ pricingMode: v }, ['pricingMode', 'priceCents'])}
                options={(['fixed', 'quote', 'hourly'] as const).map(v => ({ value: v, label: t(`pr_${v}`) }))} />
              {err('pricingMode') && <div role="alert" className="nl-error">{err('pricingMode')}</div>}
            </div>
            {form.pricingMode === 'quote'
              ? <p className="nl-hint" style={{ alignSelf: 'end' }}>{t('quoteHint')}</p>
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
          <Field label={t('included')} error={err('included')}>
            <TextArea rows={3} placeholder={t('includedPlaceholder')} value={form.included} onChange={e => update({ included: e.target.value })} onBlur={() => touch('included')} />
          </Field>
          <Checkbox checked={form.instantBook} onChange={v => update({ instantBook: v })} label={t('instantBook')} />
          <Field label={t('skuLabel')} hint={t('skuHint')} error={err('sku')}>
            <TextInput value={form.sku} maxLength={60} style={{ maxWidth: 240 }} onChange={e => update({ sku: e.target.value })} onBlur={() => touch('sku')} />
          </Field>
        </fieldset>
        <Side>
          <CompletenessPanel t={t} percent={completeness.percent} items={sections} />
          <VettingPanel t={t} checks={vettingChecks({ category, priceCents: price, images: [], imageSource: 'own', link: null, text: form.name, gtin: null, money }).filter(c => !c.key.startsWith('vpImages'))} />
          <FeesPanel priceCents={price} costCents={null} tier={merchant.tier} />
        </Side>
      </div>
    </>
  );
}
