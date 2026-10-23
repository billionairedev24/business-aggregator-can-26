import { useState } from 'react';
import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { Button, Checkbox, ChartCard, ErrorState, Field, Select, Skeleton, TextInput, useFormatters } from '@northline/ui';
import { http, ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import type { Finance } from './api';
import { useLoyaltyT } from './loyaltyMessages';

/**
 * S-152 on the Finance screen: what customers' points are worth (the liability), what businesses' rewards hold, what
 * points took off sales this week, Plus households and referrals waiting — and the points earning rules (region data,
 * `GET/PUT/DELETE /api/v1/console/loyalty/rules`; changes need `promotions`, audit-logged).
 */
export function LoyaltyFigures({ data }: { data: Finance }) {
  const t = useLoyaltyT();
  const fmt = useFormatters();
  const l = data.loyalty;
  if (!l) return null;
  return (
    <ChartCard id="fi-loyalty" title={t('liabTitle')}>
      <p className="bo-muted">{t('liabSub')}</p>
      <dl className="nl-fi-loyalty">
        <div><dt>{t('outstanding')}</dt><dd>{t('outstandingValue', { points: fmt.number(l.pointsOutstanding), value: fmt.price(l.pointsOutstandingCents), holders: l.pointHolders })}</dd></div>
        <div><dt>{t('funded')}</dt><dd>{fmt.price(l.rewardsFundedCents)}</dd></div>
        <div><dt>{t('redeemed')}</dt><dd>{fmt.price(l.pointsRedeemedWeekCents)}</dd></div>
        <div><dt>{t('plusMembers')}</dt><dd>{t('plusMembersValue', { members: fmt.number(l.plusMembers), trialing: fmt.number(l.plusTrialing) })}</dd></div>
        <div><dt>{t('referrals')}</dt><dd>{fmt.number(l.pendingReferrals)}</dd></div>
      </dl>
    </ChartCard>
  );
}

const KINDS = ['goods', 'food', 'service'] as const;
const Rule = z.object({
  id: z.string(), regionId: z.string().nullish(), regionName: z.string().nullish(), pointsPerDollar: z.number().int(),
  kinds: z.array(z.enum(KINDS)), expiryMonths: z.number().int().nullish(), referralPoints: z.number().int(),
  referralMonthlyCap: z.number().int(), referralMinOrderCents: z.number().int(), updatedAt: z.string(),
});
type Rule = z.infer<typeof Rule>;
const RegionChoice = z.object({ id: z.string(), kind: z.string(), name: z.string(), province: z.string().nullish() });
const PATH = '/api/v1/console/loyalty/rules';
export const loyaltyRulesQuery = queryOptions({
  queryKey: ['console', 'loyalty-rules'],
  queryFn: () => http(PATH, {}, z.object({ items: z.array(Rule), regions: z.array(RegionChoice) })),
});

export function LoyaltyRules() {
  const t = useLoyaltyT();
  const { can } = useGrant();
  const q = useQuery(loyaltyRulesQuery);
  const [editing, setEditing] = useState<string | null>(null); // a region id, 'default', or 'new'
  const canChange = can('promotions');
  return (
    <ChartCard id="fi-loyalty-rules" title={t('rulesTitle')}>
      <p className="bo-muted">{t('rulesSub')}{canChange ? '' : ` ${t('readOnly')}`}</p>
      {q.isPending ? <Skeleton height={80} />
        : q.isError ? <ErrorState message={t('rulesError')} onRetry={() => void q.refetch()} />
          : (
            <>
              <ul className="nl-fi-rules">
                {q.data.items.map(r => (
                  <li key={r.id} className="nl-fi-rule">
                    {editing === (r.regionId ?? 'default')
                      ? <RuleForm rule={r} regions={q.data.regions} onDone={() => setEditing(null)} />
                      : <RuleRow rule={r} canChange={canChange} onEdit={() => setEditing(r.regionId ?? 'default')} />}
                  </li>
                ))}
              </ul>
              {canChange && editing === 'new'
                ? <RuleForm regions={q.data.regions.filter(c => !q.data.items.some(r => r.regionId === c.id))} onDone={() => setEditing(null)} />
                : canChange ? <Button variant="secondary" onClick={() => setEditing('new')}>{t('add')}</Button> : null}
            </>
          )}
    </ChartCard>
  );
}

function RuleRow({ rule: r, canChange, onEdit }: { rule: Rule; canChange: boolean; onEdit: () => void }) {
  const t = useLoyaltyT();
  const fmt = useFormatters();
  const qc = useQueryClient();
  const remove = useMutation({
    mutationFn: () => http(`${PATH}/${encodeURIComponent(r.regionId!)}`, { method: 'DELETE' }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: loyaltyRulesQuery.queryKey }),
  });
  return (
    <div className="nl-fi-rule-row">
      <div>
        <strong>{r.regionId ? r.regionName ?? r.regionId : t('defaultRule')}</strong>
        <div className="nl-fi-sub">
          {t('c_ppd')} {r.pointsPerDollar} · {r.kinds.map(k => t(`kind_${k}`)).join(', ')} · {t('c_expiry')} {r.expiryMonths ? t('months', { n: r.expiryMonths }) : t('never')}
          {' · '}{t('c_referral')} {fmt.number(r.referralPoints)} · {t('c_cap')} {r.referralMonthlyCap} · {t('c_min')} {fmt.price(r.referralMinOrderCents)}
        </div>
      </div>
      {canChange ? (
        <span className="nl-fi-rule-actions">
          <Button variant="tertiary" onClick={onEdit} aria-label={`${t('edit')} — ${r.regionName ?? t('defaultRule')}`}>{t('edit')}</Button>
          {r.regionId ? <Button variant="tertiary" disabled={remove.isPending} onClick={() => remove.mutate()} aria-label={`${t('remove')} — ${r.regionName ?? r.regionId}`}>{t('remove')}</Button> : null}
        </span>
      ) : null}
    </div>
  );
}

function RuleForm({ rule, regions, onDone }: { rule?: Rule; regions: z.infer<typeof RegionChoice>[]; onDone: () => void }) {
  const t = useLoyaltyT();
  const qc = useQueryClient();
  const [f, setF] = useState({
    region: rule ? rule.regionId ?? 'default' : regions[0]?.id ?? '',
    ppd: String(rule?.pointsPerDollar ?? 2), expiry: rule?.expiryMonths != null ? String(rule.expiryMonths) : '', never: rule ? rule.expiryMonths == null : false,
    referral: String(rule?.referralPoints ?? 1000), cap: String(rule?.referralMonthlyCap ?? 5), min: String((rule?.referralMinOrderCents ?? 2000) / 100),
  });
  const [kinds, setKinds] = useState<ReadonlySet<string>>(new Set(rule?.kinds ?? KINDS));
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF(v => ({ ...v, [k]: e.target.value }));
  const num = (s: string) => (s.trim() === '' ? undefined : Number(s));
  const save = useMutation({
    mutationFn: () => http(`${PATH}/${encodeURIComponent(f.region)}`, {
      method: 'PUT',
      body: {
        pointsPerDollar: num(f.ppd), kinds: [...kinds], expiryMonths: f.never ? undefined : num(f.expiry), neverExpire: f.never,
        referralPoints: num(f.referral), referralMonthlyCap: num(f.cap), referralMinOrderCents: f.min.trim() === '' ? undefined : Math.round(Number(f.min) * 100),
      },
    }, Rule),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: loyaltyRulesQuery.queryKey }); onDone(); },
  });
  const errors = save.error instanceof ValidationError ? save.error.byField() : {};
  return (
    <form className="nl-fi-promo-form" onSubmit={e => { e.preventDefault(); save.mutate(); }}>
      {rule ? null : (
        <Field label={t('f_region')} error={errors.regionId}>
          <Select value={f.region} onChange={set('region')} options={regions.map(c => ({ value: c.id, label: c.kind === 'market' && c.province ? `${c.name} (${c.province})` : c.name }))} />
        </Field>
      )}
      <Field label={t('f_ppd')} error={errors.pointsPerDollar}><TextInput value={f.ppd} onChange={set('ppd')} inputMode="numeric" /></Field>
      <fieldset className="nl-fi-promo-kinds"><legend>{t('c_kinds')}</legend>
        {KINDS.map(k => <Checkbox key={k} label={t(`kind_${k}`)} checked={kinds.has(k)} onChange={on => setKinds(s => { const n = new Set(s); if (on) n.add(k); else n.delete(k); return n; })} />)}
      </fieldset>
      {errors.kinds ? <p className="nl-error" role="alert">{errors.kinds}</p> : null}
      <Checkbox label={t('f_never')} checked={f.never} onChange={on => setF(v => ({ ...v, never: on }))} />
      {f.never ? null : <Field label={t('f_expiry')} error={errors.expiryMonths}><TextInput value={f.expiry} onChange={set('expiry')} inputMode="numeric" /></Field>}
      <Field label={t('f_referral')} error={errors.referralPoints}><TextInput value={f.referral} onChange={set('referral')} inputMode="numeric" /></Field>
      <Field label={t('f_cap')} error={errors.referralMonthlyCap}><TextInput value={f.cap} onChange={set('cap')} inputMode="numeric" /></Field>
      <Field label={t('f_min')} error={errors.referralMinOrderCents}><TextInput value={f.min} onChange={set('min')} inputMode="decimal" /></Field>
      {save.isError && !(save.error instanceof ValidationError) ? <p className="nl-error" role="alert">{t('saveError')}</p> : null}
      <div className="nl-fi-promo-actions">
        <Button type="submit" disabled={save.isPending}>{t('save')}</Button>
        <Button variant="tertiary" onClick={onDone}>{t('cancel')}</Button>
      </div>
    </form>
  );
}
