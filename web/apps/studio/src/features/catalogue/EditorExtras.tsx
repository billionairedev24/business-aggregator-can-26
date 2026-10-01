import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { X } from '@phosphor-icons/react';
import { Button, Field, Select, TextInput, useFormatters } from '@northline/ui';
import { useMerchantId } from '../shell/api';
import { documentsQuery, listingQuery, listingsQuery, useListingDocuments, useUploadImage, type DocumentPurpose, type Media, type Variant } from './api';
import { imageProblem } from './imageFile';
import { useCatalogueT } from './messages';
import { rowKey, type BundleRow, type ProductForm, type VariantRow } from './model';
import { MSG, useMessageT } from './validation';
import { ValidationError } from '../../lib/http';

/** S-65 parts of the product editor: a variant's own photos, a bundle's contents and price, compliance documents. */

type Update = (patch: Partial<ProductForm>, fields?: string[]) => void;
const errorOf = (e: unknown, fallback: string) => (e instanceof ValidationError ? e.errors[0]?.message ?? fallback : fallback);

/** Variants table "Images" cell: "inherits" or "main + N", add photos (main first), or go back to the listing's. */
export function VariantImages({ row, onChange, error }: { row: VariantRow; onChange: (images: Media[]) => void; error?: string }) {
  const t = useCatalogueT();
  const mt = useMessageT();
  const merchantId = useMerchantId();
  const upload = useUploadImage(merchantId);
  const input = useRef<HTMLInputElement>(null);
  const [problem, setProblem] = useState<string | null>(null);
  async function onFiles(files: FileList | null) {
    setProblem(null);
    let images = row.images;
    for (const file of Array.from(files ?? [])) {
      if (images.length >= 9) { setProblem(MSG.IMAGES_TOO_MANY); break; }
      const bad = await imageProblem(file);
      if (bad) { setProblem(bad); continue; }
      try { images = [...images, await upload.mutateAsync(file)]; onChange(images); }
      catch (e) { setProblem(errorOf(e, t('saveError'))); }
    }
    if (input.current) input.current.value = '';
  }
  const shown = problem ?? error;
  return (
    <div className="nl-cat-variant-images">
      {row.images[0] && <img className="nl-cat-thumb" src={row.images[0].url} alt={t('variantPhoto', { value: row.value })} width={32} height={32} />}
      <span>{row.images.length ? t('mainPlus', { count: row.images.length - 1 }) : t('inherits')}</span>
      <Button variant="ghost" onClick={() => input.current?.click()} disabled={upload.isPending} aria-label={t('variantPhotos', { value: row.value })}>
        {upload.isPending ? t('uploadingShort') : '+'}
      </Button>
      {row.images.length > 0 && <Button variant="ghost" icon aria-label={t('variantPhotosClear', { value: row.value })} onClick={() => onChange([])}><X size={14} /></Button>}
      <input ref={input} type="file" accept="image/jpeg,image/png" multiple hidden aria-label={t('variantPhotos', { value: row.value })} data-testid={`variant-images-${row.value}`} onChange={e => void onFiles(e.target.files)} />
      {shown && <div role="alert" className="nl-error">{mt(shown)}</div>}
    </div>
  );
}

/** "Bundle contents": the business's own products (not bundles), a variant when the product has some, and a quantity. */
export function BundleTab({ form, update, error, listingId }: { form: ProductForm; update: Update; error: (f: string) => string | undefined; listingId?: string }) {
  const t = useCatalogueT();
  const { money } = useFormatters();
  const merchantId = useMerchantId();
  const qc = useQueryClient();
  const listings = useQuery(listingsQuery(merchantId));
  const [choice, setChoice] = useState('');
  const [variants, setVariants] = useState<Record<string, Variant[]>>({});
  const products = (listings.data ?? []).filter(l => l.kind === 'product' && !l.bundle && l.id !== listingId);
  const setRow = (key: string, patch: Partial<BundleRow>) => update({ bundle: form.bundle.map(r => (r.key === key ? { ...r, ...patch } : r)) }, ['bundleItems']);

  async function variantsOf(offerId: string) {
    if (variants[offerId]) return variants[offerId]!;
    const d = await qc.fetchQuery(listingQuery(merchantId, offerId));
    const list = d.kind === 'product' ? d.variants : [];
    setVariants(v => ({ ...v, [offerId]: list }));
    return list;
  }
  async function add() {
    const product = products.find(p => p.id === choice);
    if (!product) return;
    const list = await variantsOf(product.id);
    const same = form.bundle.find(r => r.offerId === product.id && (list.length === 0 || r.variantId === null));
    if (same && list.length === 0) setRow(same.key, { qty: String((Number(same.qty) || 0) + 1) });
    else update({ bundle: [...form.bundle, { key: rowKey(), offerId: product.id, variantId: null, qty: '1', name: product.name, option: null, unitPriceCents: product.priceCents ?? 0, stock: product.stock ?? 0 }] }, ['bundleItems']);
    setChoice('');
  }

  return <>
    <p className="nl-muted nl-small">{t('bundleIntro')}</p>
    {listings.isSuccess && products.length === 0 && <p className="nl-small">{t('noProducts')}</p>}
    <div className="nl-cat-row">
      <Select aria-label={t('chooseProduct')} placeholder={t('chooseProduct')} value={choice} style={{ maxWidth: 360 }}
        options={products.map(p => ({ value: p.id, label: p.name }))} onChange={e => setChoice(e.target.value)} />
      <Button variant="secondary" onClick={() => void add()} disabled={!choice || form.bundle.length >= 10}>{t('addItem')}</Button>
    </div>
    {error('bundleItems') && <div role="alert" className="nl-error">{error('bundleItems')}</div>}
    {form.bundle.length > 0 && (
      <table className="table nl-cat-variants">
        <thead><tr><th>{t('colItem')}</th><th>{t('colQty')}</th><th>{t('colEach')}</th><th>{t('colAvailable')}</th><th><span className="visually-hidden">{t('removeItem', { name: '' })}</span></th></tr></thead>
        <tbody>
          {form.bundle.map((r, i) => (
            <BundleItemRow key={r.key} row={r} index={i} variants={variants[r.offerId]} loadVariants={() => void variantsOf(r.offerId)} error={error}
              money={money} onChange={patch => setRow(r.key, patch)} onRemove={() => update({ bundle: form.bundle.filter(x => x.key !== r.key) }, ['bundleItems'])} />
          ))}
        </tbody>
      </table>
    )}
  </>;
}

function BundleItemRow({ row, index, variants, loadVariants, error, money, onChange, onRemove }: {
  row: BundleRow; index: number; variants?: Variant[]; loadVariants: () => void; error: (f: string) => string | undefined;
  money: (c: number) => string; onChange: (patch: Partial<BundleRow>) => void; onRemove: () => void;
}) {
  const t = useCatalogueT();
  const at = `bundleItems[${index}]`;
  const options = variants ?? [];
  const needsVariant = row.variantId !== null || options.length > 0;
  return (
    <tr>
      <td data-label={t('colItem')}>
        <strong>{row.name}</strong>
        {needsVariant && (
          <Select aria-label={t('itemVariant', { name: row.name })} placeholder={t('chooseVariant')} value={row.variantId ?? ''} onFocus={loadVariants}
            options={options.length ? options.map(v => ({ value: v.id ?? '', label: v.value })) : [{ value: row.variantId ?? '', label: row.option ?? '' }]}
            onChange={e => {
              const v = options.find(x => x.id === e.target.value);
              onChange({ variantId: e.target.value || null, option: v?.value ?? null, unitPriceCents: v?.priceCents ?? row.unitPriceCents, stock: v?.stock ?? row.stock });
            }} />
        )}
        {(error(`${at}.offerId`) || error(`${at}.variantId`)) && <div role="alert" className="nl-error">{error(`${at}.offerId`) ?? error(`${at}.variantId`)}</div>}
      </td>
      <td data-label={t('colQty')}>
        <TextInput aria-label={t('itemQty', { name: row.name })} inputMode="numeric" value={row.qty} style={{ maxWidth: 72 }}
          aria-invalid={!!error(`${at}.qty`) || undefined} onChange={e => onChange({ qty: e.target.value })} />
        {error(`${at}.qty`) && <div role="alert" className="nl-error">{error(`${at}.qty`)}</div>}
      </td>
      <td data-label={t('colEach')}>{money(row.unitPriceCents)}</td>
      <td data-label={t('colAvailable')}>{row.stock}</td>
      <td><Button variant="ghost" icon aria-label={t('removeItem', { name: row.name })} onClick={onRemove}><X size={16} /></Button></td>
    </tr>
  );
}

/** Under the bundle's price: what the items cost bought separately, and the saving. */
export function BundlePrice({ separateCents, priceCents }: { separateCents: number; priceCents: number | null }) {
  const t = useCatalogueT();
  const { money } = useFormatters();
  const saving = priceCents !== null && !Number.isNaN(priceCents) ? separateCents - priceCents : 0;
  return (
    <p className="nl-small nl-muted" role="status">
      {t('bundleSeparate', { amount: money(separateCents) })}{saving > 0 ? ` · ${t('bundleSaves', { amount: money(saving) })}` : ''}
    </p>
  );
}

/** Compliance › Documents: upload a spec sheet or an invoice (PDF/PNG/JPEG ≤ 10 MB), list, download, remove. */
export function DocumentsField({ listingId, canEdit }: { listingId?: string; canEdit: boolean }) {
  const t = useCatalogueT();
  if (!listingId) {
    return <Field label={t('documents')} hint={t('documentsSaveFirst')}><div className="nl-cat-row"><Button variant="secondary" disabled>{t('specSheet')}</Button><Button variant="secondary" disabled>{t('invoice')}</Button></div></Field>;
  }
  return <SavedDocuments listingId={listingId} canEdit={canEdit} />;
}

function SavedDocuments({ listingId, canEdit }: { listingId: string; canEdit: boolean }) {
  const t = useCatalogueT();
  const mt = useMessageT();
  const merchantId = useMerchantId();
  const docs = useQuery(documentsQuery(merchantId, listingId));
  const { upload, remove } = useListingDocuments(merchantId, listingId);
  const inputs = { spec_sheet: useRef<HTMLInputElement>(null), invoice: useRef<HTMLInputElement>(null) };
  const [problem, setProblem] = useState<string | null>(null);
  async function onFile(purpose: DocumentPurpose, files: FileList | null) {
    setProblem(null);
    const file = files?.[0];
    if (!file) return;
    if (file.size > 10 * 1024 * 1024 || !['application/pdf', 'image/png', 'image/jpeg'].includes(file.type)) setProblem(MSG.DOCUMENT_TYPE);
    else {
      try { await upload.mutateAsync({ file, purpose }); }
      catch (e) { setProblem(errorOf(e, t('saveError'))); }
    }
    const input = inputs[purpose].current;
    if (input) input.value = '';
  }
  return (
    <Field label={t('documents')} hint={t('documentsHint')}>
      <div className="nl-cat-stack">
        {docs.isError && <div role="alert" className="nl-error">{t('documentsLoadError')}</div>}
        {(docs.data ?? []).length > 0 && (
          <ul className="nl-cat-documents">
            {docs.data!.map(d => (
              <li key={d.id} className="nl-cat-row">
                <span className="tag tag-neutral">{t(`doc_${d.purpose}`)}</span>
                <a href={d.url} download={d.fileName}>{d.fileName}</a>
                {canEdit && <Button variant="ghost" icon aria-label={t('removeDocument', { name: d.fileName })} onClick={() => remove.mutate(d.id)}><X size={14} /></Button>}
              </li>
            ))}
          </ul>
        )}
        {canEdit && (
          <div className="nl-cat-row">
            {(['spec_sheet', 'invoice'] as const).map(purpose => (
              <span key={purpose}>
                <Button variant="secondary" disabled={upload.isPending} onClick={() => inputs[purpose].current?.click()}>
                  {upload.isPending && upload.variables?.purpose === purpose ? t('documentsUploading') : t(purpose === 'spec_sheet' ? 'specSheet' : 'invoice')}
                </Button>
                <input ref={inputs[purpose]} type="file" accept="application/pdf,image/png,image/jpeg" hidden aria-label={t(purpose === 'spec_sheet' ? 'specSheet' : 'invoice')}
                  data-testid={`doc-input-${purpose}`} onChange={e => void onFile(purpose, e.target.files)} />
              </span>
            ))}
          </div>
        )}
        {problem && <div role="alert" className="nl-error">{mt(problem)}</div>}
      </div>
    </Field>
  );
}
