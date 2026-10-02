import { useCallback, useMemo, useRef, useState, type DragEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { ArrowLeft, ArrowRight, X } from '@phosphor-icons/react';
import { Alert, Button, Checkbox, Chip, Field, FormGrid, OptionCard, Select, TextArea, TextInput, useFormatters, useLocale } from '@northline/ui';
import { useMerchant, useMerchantId, useRole } from '../shell/api';
import { tierTag } from '../shell/StudioLayout';
import { useShellT } from '../shell/messages';
import { categoriesQuery, lookupGtin, useSaveProduct, useSubmitListing, useUploadImage, type Category, type Fulfilment, type IdentifierType, type Media, type OfferType, type ProductDetail, type VariantTheme } from './api';
import { BundlePrice, BundleTab, DocumentsField, VariantImages } from './EditorExtras';
import { AttentionSummary, CategoryPicker, CompletenessPanel, EditorHeader, EditorTabs, FeesPanel, RevetNotice, Side, VettingPanel, draftTag, useEditorForm } from './EditorParts';
import { imageProblem } from './imageFile';
import { useCatalogueT } from './messages';
import { SECTIONS, applyMatch, bundleFacts, emptyProduct, linkFromDetail, linkFromMatch, permissions, productCompleteness, productFromDetail, productPayload, rowKey, validateProductDraft, variantReady, variantSku, vettingChecks, type CatalogueLink, type Portal, type ProductForm, type Section, type VariantRow } from './model';
import { gtinProblem, parseMoney, useMessageT } from './validation';
import { ValidationError } from '../../lib/http';
import { ListingCopyButton } from '../writing/WritingHelp';
import { FrenchTextPanel } from './FrenchTextPanel';

type Tab = Section | 'preview';
const FULFILMENT: Fulfilment[] = ['pooled', 'install', 'pickup', 'ship'];
const COUNTRIES = ['DE', 'CA', 'US', 'CN', 'JP', 'MX', 'GB', 'IT', 'FR', 'KR', 'OTHER'] as const;
const SECTION_OF = (field: string): Section => {
  if (field.startsWith('variants') || field.startsWith('bundleItems')) return 'variants';
  if (field === 'images' || field === 'imageIds') return 'images';
  if (['sku', 'priceCents', 'compareAtCents', 'costCents', 'stock', 'lowStockAt', 'fulfilment', 'returnsPolicy', 'handlingTime', 'condition'].includes(field)) return 'offer';
  if (['countryOfOrigin', 'restrictedOk', 'bilingualOk', 'warranty', 'searchKeywords'].includes(field)) return 'compliance';
  return 'identity';
};

/**
 * Product editor (design: product_new) — six tabs, completeness meter, Save draft separate from Submit for vetting,
 * GTIN lookup against the shared catalogue (matched products inherit title, attributes and images).
 */
export function ProductEditor({ detail, portal, typePicker, type = 'product' }: { detail?: ProductDetail; portal: Portal; typePicker?: React.ReactNode; type?: OfferType }) {
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
  const categoriesQ = useQuery(categoriesQuery(merchantId, 'shop', locale));
  const categories = useMemo(() => categoriesQ.data ?? [], [categoriesQ.data]);
  const byId = useMemo(() => new Map(categories.map(c => [c.id, c])), [categories]);

  const [link, setLink] = useState<CatalogueLink | null>(detail ? linkFromDetail(detail) : null);
  const [saved, setSaved] = useState<ProductDetail | undefined>(detail);
  const [tab, setTab] = useState<Tab>('identity');
  const [lookup, setLookup] = useState<'idle' | 'loading' | 'nomatch' | 'error'>('idle');
  const [failure, setFailure] = useState<string | null>(null);
  const validate = useCallback((f: ProductForm) => validateProductDraft(f, byId.get(f.categoryId)), [byId]);
  const fm = useEditorForm<ProductForm>(detail ? productFromDetail(detail) : emptyProduct(type), validate);
  const { form, update, touch, error } = fm;
  const category = byId.get(form.categoryId);
  const readOnlyContent = !!link?.readOnly;
  const isBundle = form.type === 'bundle';
  const bundle = bundleFacts(form);
  const save = useSaveProduct(merchantId);
  const submit = useSubmitListing(merchantId);

  const completeness = productCompleteness(form, category, link);
  const canSubmit = !saved || saved.vetting === 'draft' || saved.vetting === 'rejected';
  const tabName: Record<Tab, string> = { identity: t('tabIdentity'), variants: isBundle ? t('tabBundle') : t('tabVariants'), images: t('tabImages'), offer: t('tabOffer'), compliance: t('tabCompliance'), preview: t('tabPreview') };
  const err = (field: string) => mt(error(field));

  const firstErrorTab = (errors: Record<string, string>) => { const first = Object.keys(errors)[0]; if (first) setTab(SECTION_OF(first)); };

  async function persist(): Promise<ProductDetail | null> {
    setFailure(null);
    fm.setAttempted(true);
    if (Object.keys(fm.client).length) { firstErrorTab(fm.client); return null; }
    try {
      const d = await save.mutateAsync({ id: saved?.id, body: productPayload(form) });
      setSaved(d); setLink(linkFromDetail(d)); fm.reset(productFromDetail(d));
      if (!saved) void navigate({ to: '/b/$merchantId/listings/$listingId', params: { merchantId, listingId: d.id }, replace: true });
      return d;
    } catch (e) {
      if (fm.fromServer(e)) { if (e instanceof ValidationError) firstErrorTab(Object.fromEntries(e.errors.map(x => [x.field, x.message]))); }
      else setFailure(t('saveError'));
      return null;
    }
  }
  async function onSubmit() {
    const d = fm.dirty || !saved ? await persist() : saved;
    if (!d) return;
    try {
      const r = await submit.mutateAsync(d.id);
      if (r.kind === 'product') { setSaved(r); fm.reset(productFromDetail(r)); }
    } catch (e) { if (!fm.fromServer(e)) setFailure(t('saveError')); }
  }

  async function onLookup() {
    touch('gtin');
    if (gtinProblem(form.gtin)) return;
    setLookup('loading');
    try {
      const m = await lookupGtin(merchantId, form.gtin.trim());
      if (m) { setLink(linkFromMatch(m)); update(applyMatch(form, m)); setLookup('idle'); }
      else { setLink(null); update({ imageSource: 'own' }); setLookup('nomatch'); }
    } catch { setLookup('error'); }
  }

  const tabs = [...SECTIONS.map(s => ({ key: s as Tab, name: tabName[s], done: completeness.done[s] })), { key: 'preview' as Tab, name: tabName.preview, done: true }];
  const kicker = t('editorKicker', { section: portal === 'seller' ? t('kickerProducts') : t('kickerListings'), kind: 'product' });
  const time = (iso: string) => date(iso, 'time');

  return (
    <>
      <EditorHeader kicker={kicker} title={saved?.title || form.title || (isBundle ? t('newBundle') : t('newProduct'))} tag={draftTag(saved, t, time)} canEdit={perms.update} roleName={shellT(`role_${role}` as Parameters<typeof shellT>[0])}
        saveLabel={saved?.vetting === 'approved' ? t('saveChanges') : t('saveDraft')} saving={save.isPending} submitting={submit.isPending}
        cannotSubmit={!completeness.complete || !canSubmit} onSave={() => void persist()} onSubmit={() => void onSubmit()} />
      <EditorTabs tabs={tabs} value={tab} onChange={setTab} label={t('editorTabs')} />
      {failure && <div style={{ marginBottom: 16 }}><Alert tone="error">{failure}</Alert></div>}
      <RevetNotice detail={saved} kind="product" />
      <div style={{ marginBottom: 16 }}><AttentionSummary errors={fm.visible} t={t} /></div>
      <div className="nl-cat-editor">
        <fieldset className="nl-cat-main" disabled={!perms.update} id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
          {tab === 'identity' && <>
            {typePicker}
            {!isBundle && <Field label={t('identifier')} error={err('gtin')}>
              <div className="nl-cat-row">
                <Select aria-label={t('identifierType')} className="nl-cat-idtype" value={form.identifierType} disabled={readOnlyContent}
                  options={(['gtin', 'ean', 'isbn', 'none'] as IdentifierType[]).map(v => ({ value: v, label: t(({ gtin: 'idGtin', ean: 'idEan', isbn: 'idIsbn', none: 'idNone' } as const)[v]) }))}
                  onChange={e => { const v = e.target.value as IdentifierType; update(v === 'none' ? { identifierType: v, gtin: '', imageSource: 'own' } : { identifierType: v }, ['identifierType', 'gtin']); if (v === 'none') { setLink(null); setLookup('idle'); } }} />
                <TextInput aria-label={t('identifier')} inputMode="numeric" placeholder={t('gtinPlaceholder')} value={form.gtin} disabled={form.identifierType === 'none' || readOnlyContent}
                  onChange={e => { update({ gtin: e.target.value }); setLookup('idle'); }} onBlur={() => touch('gtin')} aria-invalid={!!err('gtin') || undefined} />
                <Button variant="secondary" onClick={() => void onLookup()} disabled={form.identifierType === 'none' || lookup === 'loading' || readOnlyContent}>{lookup === 'loading' ? t('lookingUp') : t('lookUp')}</Button>
              </div>
            </Field>}
            {link && <div className="nl-cat-match" role="status">
              <CatalogueThumb media={link.images[0]} size={44} />
              <span><strong>{t('matched', { ref: link.ref })}</strong> · {t('matchedBody', { title: link.title, count: link.sellerCount })}</span>
            </div>}
            {lookup === 'nomatch' && <Alert tone="neutral" role="status">{t('noMatch')}</Alert>}
            {lookup === 'error' && <Alert tone="error">{t('lookupFailed')}</Alert>}
            <Field label={t('title')} hint={t('titleHint')} error={err('title')}>
              <TextInput value={form.title} maxLength={120} onChange={e => update({ title: e.target.value })} onBlur={() => touch('title')} />
            </Field>
            <FormGrid min={180}>
              <Field label={t('brand')} hint={readOnlyContent ? t('sharedField') : undefined} error={err('brand')}><TextInput value={form.brand} disabled={readOnlyContent} onChange={e => update({ brand: e.target.value })} /></Field>
              <Field label={t('mpn')} error={err('mpn')}><TextInput value={form.mpn} disabled={readOnlyContent} onChange={e => update({ mpn: e.target.value })} /></Field>
            </FormGrid>
            <Field label={t('category')} hint={t('categoryHint')} error={err('categoryId')}>
              <CategoryPicker categories={categories} value={form.categoryId} rootLabel={t('rootShop')} disabled={readOnlyContent || categoriesQ.isPending}
                invalid={!!err('categoryId')} onChange={id => { update({ categoryId: id, attributes: {} }, ['categoryId', 'attributes']); touch('categoryId'); }} />
            </Field>
            {category?.leaf && (
              <Field label={t('requiredAttributes', { category: category.name })}>
                {category.attributes.length ? (
                  <div className="nl-cat-attrs">
                    {category.attributes.map(a => (
                      <div key={a.key}>
                        <Select aria-label={a.label} placeholder={`${a.label}…`} options={a.options.map(o => ({ value: o, label: `${a.label}: ${o}` }))} value={form.attributes[a.key] ?? ''} disabled={readOnlyContent}
                          aria-invalid={!!err(`attributes.${a.key}`) || undefined} onChange={e => update({ attributes: { ...form.attributes, [a.key]: e.target.value } }, [`attributes.${a.key}`])} />
                        {err(`attributes.${a.key}`) && <div role="alert" className="nl-error">{err(`attributes.${a.key}`)}</div>}
                      </div>
                    ))}
                  </div>
                ) : <span className="nl-hint">{t('noAttributes')}</span>}
              </Field>
            )}
            <Field label={t('description')} error={err('description')}><TextArea rows={4} value={form.description} disabled={readOnlyContent} onChange={e => update({ description: e.target.value })} /></Field>
            <Bullets form={form} update={update} disabled={readOnlyContent} error={err('bullets')} />
            <ListingCopyButton merchantId={merchantId} disabled={readOnlyContent}
              facts={{ kind: 'product', name: form.title, categoryId: form.categoryId, brand: form.brand, attributes: form.attributes }}
              onUse={c => update({ title: c.title, description: c.description, bullets: c.bullets }, ['title', 'description', 'bullets'])} />
            <FrenchTextPanel merchantId={merchantId} listingId={saved?.id} />
          </>}

          {tab === 'variants' && (isBundle
            ? <BundleTab form={form} update={update} error={err} listingId={saved?.id} />
            : <VariantsTab form={form} update={update} category={category} error={err} />)}
          {tab === 'images' && <ImagesTab form={form} update={update} link={link} error={err('images')} />}

          {tab === 'offer' && <>
            <FormGrid min={150}>
              <Field label={t('yourPrice')} error={err('priceCents')}><TextInput inputMode="decimal" placeholder="$0.00" value={form.price} onChange={e => update({ price: e.target.value }, ['priceCents'])} onBlur={() => touch('priceCents')} /></Field>
              <Field label={t('compareAt')} error={err('compareAtCents')}><TextInput inputMode="decimal" value={form.compareAt} onChange={e => update({ compareAt: e.target.value }, ['compareAtCents'])} onBlur={() => touch('compareAtCents')} /></Field>
              <Field label={t('cost')} error={err('costCents')}><TextInput inputMode="decimal" value={form.cost} onChange={e => update({ cost: e.target.value }, ['costCents'])} onBlur={() => touch('costCents')} /></Field>
            </FormGrid>
            <FormGrid min={150}>
              <Field label={t('condition')}><Select value={form.condition} options={(['new', 'open_box', 'refurbished', 'used_good'] as const).map(c => ({ value: c, label: t(`cond_${c}`) }))} onChange={e => update({ condition: e.target.value as ProductForm['condition'] })} /></Field>
              {isBundle
                ? <Field label={t('stockOnHand')} hint={t('stockFollows')}><TextInput readOnly value={String(bundle.available)} /></Field>
                : <>
                  <Field label={t('stockOnHand')} error={err('stock')}><TextInput inputMode="numeric" value={form.stock} onChange={e => update({ stock: e.target.value })} onBlur={() => touch('stock')} /></Field>
                  <Field label={t('lowStock')} error={err('lowStockAt')}><TextInput inputMode="numeric" value={form.lowStockAt} onChange={e => update({ lowStockAt: e.target.value })} onBlur={() => touch('lowStockAt')} /></Field>
                </>}
            </FormGrid>
            {isBundle && form.bundle.length > 0 && <BundlePrice separateCents={bundle.separateCents} priceCents={parseMoney(form.price)} />}
            <Field label={t('skuLabel')} hint={t('skuHint')} error={err('sku')}><TextInput value={form.sku} maxLength={60} onChange={e => update({ sku: e.target.value })} onBlur={() => touch('sku')} style={{ maxWidth: 240 }} /></Field>
            <div className="field"><span className="nl-label">{t('tax')}</span><div style={{ fontSize: 14 }}>{t('taxAuto')} <strong>GST 5%</strong> · {t('taxBody')}</div></div>
            <div className="nl-field" role="group" aria-labelledby="ful-label">
              <span className="nl-label" id="ful-label">{t('fulfilment')}</span>
              <div className="nl-cat-options">
                {FULFILMENT.map(f => {
                  const on = form.fulfilment.includes(f);
                  return <OptionCard key={f} selected={on} role="checkbox" aria-checked={on} title={t(`ful_${f}`)} description={t(`ful_${f}_desc`)}
                    onClick={() => { update({ fulfilment: on ? form.fulfilment.filter(x => x !== f) : [...form.fulfilment, f] }); touch('fulfilment'); }} />;
                })}
              </div>
              {err('fulfilment') && <div role="alert" className="nl-error">{err('fulfilment')}</div>}
            </div>
            <Field label={t('handling')}><Select style={{ maxWidth: 280 }} value={form.handlingTime} options={(['same_day', 'next_day', 'two_days'] as const).map(h => ({ value: h, label: t(`hand_${h}`) }))} onChange={e => update({ handlingTime: e.target.value as ProductForm['handlingTime'] })} /></Field>
            <Field label={t('returns')} error={err('returnsPolicy')}><Select style={{ maxWidth: 360 }} value={form.returnsPolicy} options={(['standard_14', 'final_sale'] as const).map(r => ({ value: r, label: t(`ret_${r}`) }))} onChange={e => { update({ returnsPolicy: e.target.value as ProductForm['returnsPolicy'] }); touch('returnsPolicy'); }} /></Field>
          </>}

          {tab === 'compliance' && <>
            <Field label={t('origin')} error={err('countryOfOrigin')}>
              <Select style={{ maxWidth: 280 }} placeholder={t('choose')} value={form.countryOfOrigin} options={COUNTRIES.map(c => ({ value: c, label: t(`country_${c}`) }))} onChange={e => { update({ countryOfOrigin: e.target.value }); touch('countryOfOrigin'); }} />
            </Field>
            <div className="nl-field" role="group" aria-labelledby="safety-label">
              <span className="nl-label" id="safety-label">{t('safety')}</span>
              <div className="nl-cat-stack">
                <Checkbox checked={form.restrictedOk} onChange={v => { update({ restrictedOk: v }); touch('restrictedOk'); }} label={t('restrictedOk')} />
                {err('restrictedOk') && <div role="alert" className="nl-error">{err('restrictedOk')}</div>}
                <Checkbox checked={form.bilingualOk} onChange={v => { update({ bilingualOk: v }); touch('bilingualOk'); }} label={t('bilingualOk')} />
                {err('bilingualOk') && <div role="alert" className="nl-error">{err('bilingualOk')}</div>}
                <Checkbox checked={form.warranty} onChange={v => update({ warranty: v })} label={t('warranty')} />
              </div>
            </div>
            <DocumentsField listingId={saved?.id} canEdit={perms.update} />
            <Field label={t('keywords')} error={err('searchKeywords')}><TextInput value={form.searchKeywords} onChange={e => update({ searchKeywords: e.target.value })} onBlur={() => touch('searchKeywords')} /></Field>
          </>}

          {tab === 'preview' && <Preview form={form} link={link} business={merchant.displayName} tier={shellT(tierTag(merchant).key)} />}
        </fieldset>

        <Side>
          <CompletenessPanel t={t} percent={completeness.percent} items={SECTIONS.map(s => ({ name: tabName[s], done: completeness.done[s] }))} />
          <VettingPanel t={t} checks={vettingChecks({ category, priceCents: parseMoney(form.price), images: form.images, imageSource: form.imageSource, link, text: `${form.title} ${form.searchKeywords}`, gtin: isBundle ? null : { type: form.identifierType, value: form.gtin }, money })} />
          <FeesPanel priceCents={parseMoney(form.price)} costCents={parseMoney(form.cost)} tier={merchant.tier} />
          <p className="nl-cat-note"><strong>{t('howImages')}</strong> {t('howImagesBody')}</p>
        </Side>
      </div>
    </>
  );
}

type Update = (patch: Partial<ProductForm>, fields?: string[]) => void;

function Bullets({ form, update, disabled, error }: { form: ProductForm; update: Update; disabled: boolean; error?: string }) {
  const t = useCatalogueT();
  const [draft, setDraft] = useState('');
  const add = () => { if (draft.trim() && form.bullets.length < 5) { update({ bullets: [...form.bullets, draft.trim()] }); setDraft(''); } };
  return (
    <div className="nl-field" role="group" aria-labelledby="bullets-label">
      <span className="nl-label" id="bullets-label">{t('bullets')}</span>
      <div className="nl-cat-stack">
        {form.bullets.map((b, i) => (
          <div key={i} className="nl-cat-row">
            <TextInput aria-label={`${t('bullets')} ${i + 1}`} value={b} disabled={disabled} onChange={e => update({ bullets: form.bullets.map((x, j) => (j === i ? e.target.value : x)) })} />
            {!disabled && <Button variant="ghost" icon aria-label={t('removeBullet', { n: i + 1 })} onClick={() => update({ bullets: form.bullets.filter((_, j) => j !== i) })}><X size={16} /></Button>}
          </div>
        ))}
        {!disabled && form.bullets.length < 5 && (
          <TextInput aria-label={t('addBullet')} placeholder={t('addBullet')} value={draft} onChange={e => setDraft(e.target.value)} onBlur={add} onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); add(); } }} />
        )}
      </div>
      {error && <div role="alert" className="nl-error">{error}</div>}
    </div>
  );
}

function VariantsTab({ form, update, category, error }: { form: ProductForm; update: Update; category: Category | undefined; error: (f: string) => string | undefined }) {
  const t = useCatalogueT();
  const [value, setValue] = useState('');
  const themes: VariantTheme[] = ['none', ...(category?.variantThemes ?? ['size', 'colour', 'size_colour'])];
  const themeName = (v: VariantTheme) => t(`theme_${v}`);
  const setRow = (key: string, patch: Partial<VariantRow>) => update({ variants: form.variants.map(r => (r.key === key ? { ...r, ...patch } : r)) }, ['variants']);
  const addValue = () => {
    const v = value.trim();
    if (!v || form.variants.some(r => r.value.toLowerCase() === v.toLowerCase())) return;
    update({ variants: [...form.variants, { key: rowKey(), value: v, sku: variantSku(form.sku, v), gtin: '', price: form.price, stock: '0', images: [] }] });
    setValue('');
  };
  return <>
    <div className="nl-field" role="group" aria-labelledby="theme-label">
      <span className="nl-label" id="theme-label">{t('variationTheme')}</span>
      <div className="nl-chips">{themes.map(th => <Chip key={th} selected={form.variantTheme === th} onClick={() => update({ variantTheme: th })}>{themeName(th)}</Chip>)}</div>
      <span className="nl-hint">{t('variantsHint')}</span>
    </div>
    {form.variantTheme === 'none' ? <p className="nl-muted nl-small">{t('noVariants')}</p> : <>
      <div className="nl-field" role="group" aria-labelledby="values-label">
        <span className="nl-label" id="values-label">{t('themeValues', { theme: themeName(form.variantTheme) })}</span>
        <div className="nl-cat-values">
          {form.variants.map(r => (
            <span key={r.key} className="tag tag-accent nl-cat-value">{r.value}
              <button type="button" aria-label={t('removeValue', { value: r.value })} onClick={() => update({ variants: form.variants.filter(x => x.key !== r.key) })}>×</button>
            </span>
          ))}
          <TextInput aria-label={t('addValuePlaceholder')} className="nl-cat-value-input" placeholder={t('addValuePlaceholder')} value={value} onChange={e => setValue(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); addValue(); } }} />
          <Button variant="secondary" onClick={addValue}>{t('add')}</Button>
        </div>
        {error('variants') && <div role="alert" className="nl-error">{error('variants')}</div>}
      </div>
      {form.variants.length > 0 && (
        <table className="table nl-cat-variants">
          <thead><tr><th>{t('colVariant')}</th><th>{t('sku')}</th><th>{t('colGtin')}</th><th>{t('colPrice')}</th><th>{t('colStock')}</th><th>{t('colImages')}</th><th>{t('colStatus')}</th></tr></thead>
          <tbody>
            {form.variants.map((r, i) => {
              const ready = variantReady(r);
              const cell = (field: 'sku' | 'gtin' | 'price' | 'stock', label: string, server: string, mode?: 'decimal' | 'numeric') => (
                <td data-label={label} data-col={field}>
                  <TextInput aria-label={`${r.value} · ${label}`} inputMode={mode} value={r[field]} aria-invalid={!!error(`variants[${i}].${server}`) || undefined} onChange={e => setRow(r.key, { [field]: e.target.value })} />
                  {error(`variants[${i}].${server}`) && <div role="alert" className="nl-error">{error(`variants[${i}].${server}`)}</div>}
                </td>
              );
              return (
                <tr key={r.key}>
                  <td data-label={t('colVariant')}><strong>{r.value}</strong>{error(`variants[${i}].value`) && <div role="alert" className="nl-error">{error(`variants[${i}].value`)}</div>}</td>
                  {cell('sku', t('sku'), 'sku')}
                  {cell('gtin', t('colGtin'), 'gtin', 'numeric')}
                  {cell('price', t('colPrice'), 'priceCents', 'decimal')}
                  {cell('stock', t('colStock'), 'stock', 'numeric')}
                  <td data-label={t('colImages')} className="nl-small"><VariantImages row={r} onChange={images => setRow(r.key, { images })} error={error(`variants[${i}].imageIds`)} /></td>
                  <td data-label={t('colStatus')}><span className={`tag ${ready ? 'tag-accent' : 'tag-neutral'}`}>{ready ? t('ready') : t('needsGtin')}</span></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      <div className="nl-cat-row">
        <Button variant="secondary" disabled={!form.variants.length} onClick={() => update({ variants: form.variants.map(r => ({ ...r, price: form.price })) }, ['variants'])}>{t('applyPrice')}</Button>
        <Button variant="secondary" disabled={!form.variants.length} onClick={() => update({ variants: form.variants.map(r => ({ ...r, stock: form.stock })) }, ['variants'])}>{t('applyStock')}</Button>
      </div>
    </>}
  </>;
}

export function CatalogueThumb({ media, size = 110, label }: { media?: Media; size?: number; label?: string }) {
  const [broken, setBroken] = useState(false);
  if (!media || broken) return <div className="halftone nl-cat-thumb" style={{ width: size, height: size }} role={label ? 'img' : undefined} aria-label={label} aria-hidden={label ? undefined : true} />;
  return <img className="nl-cat-thumb" src={media.url} alt={label ?? ''} width={size} height={size} style={{ width: size, height: size }} onError={() => setBroken(true)} />;
}

function ImagesTab({ form, update, link, error }: { form: ProductForm; update: Update; link: CatalogueLink | null; error?: string }) {
  const t = useCatalogueT();
  const mt = useMessageT();
  const merchantId = useMerchantId();
  const upload = useUploadImage(merchantId);
  const input = useRef<HTMLInputElement>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [drag, setDrag] = useState<number | null>(null);
  const shared = form.imageSource === 'shared' && !!link;
  const limit = shared ? 3 : 9;
  const move = (from: number, to: number) => {
    if (to < 0 || to >= form.images.length) return;
    const next = [...form.images]; const [m] = next.splice(from, 1); next.splice(to, 0, m!);
    update({ images: next }, ['images']);
  };
  async function onFiles(files: FileList | null) {
    setProblem(null);
    for (const file of Array.from(files ?? [])) {
      if (form.images.length >= limit) { setProblem(shared ? t('supplementaryFull') : t('imagesFull')); break; }
      const bad = await imageProblem(file);
      if (bad) { setProblem(mt(bad) ?? bad); continue; }
      try {
        const media = await upload.mutateAsync(file);
        update({ images: [...form.images, media] }, ['images']);
      } catch (e) {
        setProblem(e instanceof ValidationError ? (mt(e.errors[0]?.message) ?? t('saveError')) : t('saveError'));
      }
    }
    if (input.current) input.current.value = '';
  }
  const tiles: { media: Media; own: boolean; index: number }[] = [
    ...(shared ? link!.images.map((m, i) => ({ media: m, own: false, index: i })) : []),
    ...form.images.map((m, i) => ({ media: m, own: true, index: i })),
  ];
  return <>
    <div className="nl-field" role="group" aria-labelledby="src-label">
      <span className="nl-label" id="src-label">{t('imageSource')}</span>
      <div className="nl-chips">
        <Chip selected={form.imageSource === 'shared'} disabled={!link} onClick={() => update({ imageSource: 'shared' }, ['images'])}>{t('useShared')}</Chip>
        <Chip selected={form.imageSource === 'own'} onClick={() => update({ imageSource: 'own' }, ['images'])}>{t('uploadOwn')}</Chip>
      </div>
      <div className="nl-cat-note-box">{!link ? t('imgNoteNoMatch') : form.imageSource === 'shared' ? t('imgNoteShared', { count: link.sellerCount, locked: String(link.locked) }) : t('imgNoteOwn')}</div>
    </div>
    <div className="nl-field" role="group" aria-labelledby="img-label">
      <span className="nl-label" id="img-label">{t('imagesLabel')}</span>
      <ul className="nl-cat-images">
        {tiles.map((tile, pos) => (
          <li key={`${tile.own ? 'o' : 's'}-${tile.media.id}`} className="nl-cat-image" draggable={tile.own}
            onDragStart={() => tile.own && setDrag(tile.index)} onDragOver={(e: DragEvent) => { if (tile.own) e.preventDefault(); }}
            onDrop={() => { if (tile.own && drag !== null) move(drag, tile.index); setDrag(null); }}>
            <CatalogueThumb media={tile.media} label={t('imageAlt', { n: pos + 1 })} />
            {pos === 0 && <span className="tag tag-accent nl-cat-main-tag">{t('main')}</span>}
            {tile.own && <div className="nl-cat-image-tools">
              <button type="button" aria-label={t('moveLeft', { n: pos + 1 })} disabled={tile.index === 0} onClick={() => move(tile.index, tile.index - 1)}><ArrowLeft size={14} /></button>
              <button type="button" aria-label={t('moveRight', { n: pos + 1 })} disabled={tile.index === form.images.length - 1} onClick={() => move(tile.index, tile.index + 1)}><ArrowRight size={14} /></button>
              <button type="button" aria-label={t('removeImage', { n: pos + 1 })} onClick={() => update({ images: form.images.filter((_, i) => i !== tile.index) }, ['images'])}><X size={14} /></button>
            </div>}
          </li>
        ))}
        {form.images.length < limit && (
          <li>
            <button type="button" className="nl-cat-add-image" onClick={() => input.current?.click()} disabled={upload.isPending}>
              {upload.isPending ? t('uploading') : <>{t('addImage')}<br />{t('addImageHint')}</>}
            </button>
            <input ref={input} type="file" accept="image/jpeg,image/png" multiple hidden aria-label={t('addImage')} data-testid="image-input" onChange={e => void onFiles(e.target.files)} />
          </li>
        )}
      </ul>
      {(problem || error) && <div role="alert" className="nl-error">{problem ?? error}</div>}
    </div>
    <p className="nl-cat-note"><strong>{t('standardsTitle')}</strong> {t('standardsBody')}</p>
  </>;
}

function Preview({ form, link, business, tier }: { form: ProductForm; link: CatalogueLink | null; business: string; tier: string }) {
  const t = useCatalogueT();
  const { money } = useFormatters();
  const price = parseMoney(form.price);
  const compare = parseMoney(form.compareAt);
  const image = form.imageSource === 'shared' ? link?.images[0] ?? form.images[0] : form.images[0];
  const values = form.variants.map(v => v.value);
  return (
    <section className="nl-cat-preview" aria-label={t('previewLabel')}>
      <CatalogueThumb media={image} size={300} />
      <div className="nl-cat-preview-head">
        <div>
          <div className="nl-cat-preview-title">{form.title || t('newProduct')}</div>
          <div className="nl-small nl-muted">{business} · {tier}{link && link.sellerCount > 1 ? ` · ${t('sellers', { count: link.sellerCount })}` : ''}</div>
        </div>
        <div style={{ textAlign: 'right' }}>
          {price !== null && !Number.isNaN(price) && <div className="nl-cat-preview-title">{money(price)}</div>}
          {compare !== null && !Number.isNaN(compare) && <div className="nl-small nl-muted" style={{ textDecoration: 'line-through' }}>{money(compare)}</div>}
        </div>
      </div>
      <div className="nl-cat-row" style={{ marginTop: 10 }}>
        {form.fulfilment.includes('pooled') && <span className="tag tag-accent">{t('tonightsRun')}</span>}
        {form.fulfilment.includes('install') && <span className="tag tag-neutral">{t('installedFree')}</span>}
      </div>
      {form.variantTheme !== 'none' && values.length > 0 && (
        <div className="nl-small nl-muted" style={{ marginTop: 12 }}>{t(`theme_${form.variantTheme}`)}: <strong style={{ color: 'var(--color-text)' }}>{values[0]}</strong>{values.slice(1).map(v => ` · ${v}`)}</div>
      )}
      <div className="nl-cat-preview-cta" aria-hidden>{t('addToCart')}</div>
    </section>
  );
}

