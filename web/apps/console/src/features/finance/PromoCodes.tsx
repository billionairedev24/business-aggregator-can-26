import { useState } from 'react';
import { useMutation, useQuery, useQueryClient, queryOptions } from '@tanstack/react-query';
import { z } from 'zod';
import { Button, Checkbox, ErrorState, Field, Select, Skeleton, Switch, TextInput, useFormatters } from '@northline/ui';
import { ApiError, http, isNotFound, ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { usePromoT } from './promoMessages';

/**
 * Promo codes (mobile gaps part 2) on the Finance screen: the codes with their use and discount given; finance and
 * admins (`promotions`) make a code — percent or amount off, a minimum spend, a window, limits per customer and overall,
 * funded by Northline or by one business — and switch it off or on. Codes aren't sent from here: a promotion message
 * is commercial and goes only to people who consented (S-108).
 */
export const PromoCode = z.object({
  id: z.string(), code: z.string(), description: z.string().nullish(), kind: z.enum(['percent', 'amount']),
  percent: z.number().int().nullish(), amountCents: z.number().int().nullish(), maxDiscountCents: z.number().int().nullish(),
  minSpendCents: z.number().int(), startsAt: z.string(), endsAt: z.string(), perCustomerLimit: z.number().int(), totalLimit: z.number().int().nullish(),
  fundedBy: z.enum(['northline', 'merchant']), merchantId: z.string().nullish(), merchantName: z.string().nullish(),
  appliesTo: z.array(z.string()), active: z.boolean(), state: z.enum(['scheduled', 'live', 'ended', 'off']), redeemed: z.number().int(), discountCents: z.number().int(),
});
export type PromoCode = z.infer<typeof PromoCode>;
const PATH = '/api/v1/console/promotions/codes';
export const promoCodesQuery = queryOptions({ queryKey: ['console', 'promo-codes'], queryFn: () => http(PATH, {}, z.object({ items: z.array(PromoCode) })).then(r => r.items) });

const KINDS = ['goods', 'food', 'service'] as const;
const cents = (dollars: string) => (dollars.trim() ? Math.round(Number(dollars.replace(',', '.')) * 100) : undefined);
const isoOf = (local: string) => (local ? new Date(local).toISOString() : undefined);

export function PromoCodes() {
  const t = usePromoT();
  const q = useQuery(promoCodesQuery);
  const { can } = useGrant();
  const [creating, setCreating] = useState(false);
  return (
    <section aria-labelledby="promo-title" className="nl-fi-promos">
      <h2 id="promo-title" className="nl-fi-h2">{t('title')}</h2>
      <div className="nl-fi-sub">{t('sub')}</div>
      {q.isPending ? <Skeleton height={80} /> : q.isError && isNotFound(q.error) ? <p className="nl-fi-sub">{t('none')}</p> : q.isError ? <ErrorState message={t('error')} onRetry={() => void q.refetch()} />
        : q.data.length === 0 ? <p className="nl-fi-sub">{t('none')}</p>
          : <ul className="nl-fi-refunds">{q.data.map(c => <CodeRow key={c.id} code={c} canChange={can('promotions')} />)}</ul>}
      {can('promotions') ? (creating ? <NewCode onDone={() => setCreating(false)} /> : <Button variant="secondary" onClick={() => setCreating(true)}>{t('new')}</Button>) : null}
    </section>
  );
}

function CodeRow({ code: c, canChange }: { code: PromoCode; canChange: boolean }) {
  const t = usePromoT();
  const fmt = useFormatters();
  const qc = useQueryClient();
  const toggle = useMutation({
    mutationFn: (active: boolean) => http(`${PATH}/${encodeURIComponent(c.id)}`, { method: 'PATCH', body: { active } }, PromoCode),
    onSuccess: () => void qc.invalidateQueries({ queryKey: promoCodesQuery.queryKey }),
  });
  const off = c.kind === 'percent' ? t('percentOff', { percent: c.percent ?? 0 }) : t('amountOff', { amount: fmt.money(c.amountCents ?? 0) });
  const who = c.fundedBy === 'merchant' ? t('fundedMerchant', { name: c.merchantName ?? c.merchantId ?? '' }) : t('fundedNorthline');
  return (
    <li className="nl-fi-refund">
      <div>
        <strong>{c.code}</strong> · {off} · {who}
        <div className="nl-fi-sub">{t(`state_${c.state}`)} · {t('used', { n: c.redeemed, amount: fmt.money(c.discountCents) })} · {c.appliesTo.map(k => t(`kind_${k as 'goods'}`)).join(', ')}</div>
      </div>
      {canChange ? <Switch checked={c.active} onChange={v => toggle.mutate(v)} label={t('activeLabel', { code: c.code })} disabled={toggle.isPending} /> : null}
    </li>
  );
}

function NewCode({ onDone }: { onDone: () => void }) {
  const t = usePromoT();
  const qc = useQueryClient();
  const [f, setF] = useState({ code: '', kind: 'percent', value: '', max: '', minSpend: '', startsAt: '', endsAt: '', perCustomer: '1', total: '', fundedBy: 'northline', merchantId: '', description: '' });
  const [applies, setApplies] = useState<ReadonlySet<string>>(new Set(KINDS));
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF(v => ({ ...v, [k]: e.target.value }));
  const create = useMutation({
    mutationFn: () => http(PATH, {
      method: 'POST',
      body: {
        code: f.code, kind: f.kind, ...(f.kind === 'percent' ? { percent: Number(f.value) || undefined, maxDiscountCents: cents(f.max) } : { amountCents: cents(f.value) }),
        minSpendCents: cents(f.minSpend), startsAt: isoOf(f.startsAt), endsAt: isoOf(f.endsAt), perCustomerLimit: Number(f.perCustomer) || undefined,
        totalLimit: f.total ? Number(f.total) : undefined, fundedBy: f.fundedBy, merchantId: f.fundedBy === 'merchant' ? f.merchantId.trim() : undefined,
        appliesTo: [...applies], description: f.description.trim() || undefined,
      },
    }, PromoCode),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: promoCodesQuery.queryKey }); onDone(); },
  });
  const errors = create.error instanceof ValidationError ? create.error.byField() : {};
  const failed = create.error instanceof ApiError && !(create.error instanceof ValidationError) ? create.error.message : undefined;
  return (
    <form className="nl-fi-promo-form" onSubmit={e => { e.preventDefault(); create.mutate(); }}>
      <Field label={t('code')} error={errors.code}><TextInput value={f.code} onChange={set('code')} autoCapitalize="characters" /></Field>
      <Field label={t('kind')} error={errors.kind}><Select value={f.kind} onChange={set('kind')} options={[{ value: 'percent', label: t('kind_percent') }, { value: 'amount', label: t('kind_amount') }]} /></Field>
      <Field label={f.kind === 'percent' ? t('percent') : t('amount')} error={errors.percent ?? errors.amountCents}><TextInput value={f.value} onChange={set('value')} inputMode="decimal" /></Field>
      {f.kind === 'percent' ? <Field label={t('max')} error={errors.maxDiscountCents}><TextInput value={f.max} onChange={set('max')} inputMode="decimal" /></Field> : null}
      <Field label={t('minSpend')} error={errors.minSpendCents}><TextInput value={f.minSpend} onChange={set('minSpend')} inputMode="decimal" /></Field>
      <Field label={t('startsAt')}><TextInput type="datetime-local" value={f.startsAt} onChange={set('startsAt')} /></Field>
      <Field label={t('endsAt')} error={errors.endsAt}><TextInput type="datetime-local" value={f.endsAt} onChange={set('endsAt')} /></Field>
      <Field label={t('perCustomer')} error={errors.perCustomerLimit}><TextInput value={f.perCustomer} onChange={set('perCustomer')} inputMode="numeric" /></Field>
      <Field label={t('total')} error={errors.totalLimit}><TextInput value={f.total} onChange={set('total')} inputMode="numeric" /></Field>
      <Field label={t('fundedBy')} error={errors.fundedBy}><Select value={f.fundedBy} onChange={set('fundedBy')} options={[{ value: 'northline', label: t('fundedNorthline') }, { value: 'merchant', label: t('fundedMerchantPick') }]} /></Field>
      {f.fundedBy === 'merchant' ? <Field label={t('merchantId')} error={errors.merchantId}><TextInput value={f.merchantId} onChange={set('merchantId')} /></Field> : null}
      <fieldset className="nl-fi-promo-kinds"><legend>{t('appliesTo')}</legend>
        {KINDS.map(k => <Checkbox key={k} label={t(`kind_${k}`)} checked={applies.has(k)} onChange={on => setApplies(s => { const n = new Set(s); if (on) n.add(k); else n.delete(k); return n; })} />)}
      </fieldset>
      {errors.appliesTo ? <p className="nl-error" role="alert">{errors.appliesTo}</p> : null}
      <Field label={t('description')} error={errors.description}><TextInput value={f.description} onChange={set('description')} /></Field>
      {failed ? <p className="nl-error" role="alert">{failed}</p> : null}
      <div className="nl-fi-promo-actions">
        <Button type="submit" disabled={create.isPending}>{t('create')}</Button>
        <Button variant="ghost" onClick={onDone}>{t('cancel')}</Button>
      </div>
    </form>
  );
}
