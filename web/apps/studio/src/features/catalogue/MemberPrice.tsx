import { useEffect, useState } from 'react';
import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { Button, Field, TextInput, useFormatters } from '@northline/ui';
import { http, ValidationError } from '../../lib/http';
import { useMerchantId } from '../shell/api';
import { useCatalogueT } from './messages';

/**
 * S-152 "Plus member price": a shop may give {brand} Plus members a percentage off this product (and every variant),
 * 1–50 %, or none. The shop funds it (it sells for less; tax is on the member price). Saved on its own, at once
 * (`PUT …/products/{id}/member-price`), like the documents — it never goes through vetting.
 */
const MemberPriceBody = z.object({ memberDiscountPct: z.number().int().nullish() });
const memberPriceQuery = (merchantId: string, listingId: string) => queryOptions({
  queryKey: ['merchant', merchantId, 'listing', listingId, 'member-price'],
  queryFn: () => http(`/api/v1/merchants/${merchantId}/products/${listingId}/member-price`, {}, MemberPriceBody),
});

export function MemberPriceField({ listingId, priceCents, canEdit }: { listingId?: string; priceCents: number | null; canEdit: boolean }) {
  const t = useCatalogueT();
  if (!listingId) return <Field label={t('memberPrice')} hint={t('memberPriceSaveFirst')}><TextInput disabled /></Field>;
  return <SavedMemberPrice listingId={listingId} priceCents={priceCents} canEdit={canEdit} />;
}

function SavedMemberPrice({ listingId, priceCents, canEdit }: { listingId: string; priceCents: number | null; canEdit: boolean }) {
  const t = useCatalogueT();
  const { price } = useFormatters();
  const merchantId = useMerchantId();
  const qc = useQueryClient();
  const q = useQuery(memberPriceQuery(merchantId, listingId));
  const [pct, setPct] = useState('');
  useEffect(() => { if (q.isSuccess) setPct(q.data.memberDiscountPct != null ? String(q.data.memberDiscountPct) : ''); }, [q.isSuccess, q.data]);
  const save = useMutation({
    mutationFn: (value: number | null) => http(`/api/v1/merchants/${merchantId}/products/${listingId}/member-price`,
      { method: 'PUT', body: { memberDiscountPct: value } }, MemberPriceBody),
    onSuccess: data => qc.setQueryData(memberPriceQuery(merchantId, listingId).queryKey, data),
  });
  const value = pct.trim() === '' ? null : Number(pct);
  const error = save.error instanceof ValidationError ? save.error.byField().memberDiscountPct : save.error ? t('saveError') : undefined;
  const member = priceCents != null && !Number.isNaN(priceCents) && value != null && Number.isInteger(value) && value >= 1 && value <= 50
    ? priceCents - Math.round((priceCents * value) / 100) : null;
  return (
    <div className="nl-cat-row nl-cat-row-end">
      <Field label={t('memberPrice')} hint={member != null ? t('memberPriceIs', { price: price(member) }) : t('memberPriceHint')} error={error}>
        <TextInput inputMode="numeric" value={pct} disabled={!canEdit || q.isPending} placeholder={t('memberPriceNone')} aria-invalid={!!error}
          onChange={e => setPct(e.target.value.replace(/[^0-9]/g, ''))} className="nl-cat-w-pct" />
      </Field>
      {canEdit ? <Button variant="secondary" disabled={save.isPending} onClick={() => save.mutate(value)}>{t('memberPriceSave')}</Button> : null}
      {save.isSuccess ? <span role="status" className="nl-small">{t('memberPriceSaved')}</span> : null}
    </div>
  );
}
