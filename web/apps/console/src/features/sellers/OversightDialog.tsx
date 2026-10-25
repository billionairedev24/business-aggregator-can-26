import { useState } from 'react';
import { Button, Dialog, Field, Select, TextArea } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useOversight, type Check, type OversightAction, type Seller } from './api';
import { checkName } from './format';
import { useSellersT, type SellersKey } from './messages';

const TITLE: Record<Exclude<OversightAction, 'search'>, SellersKey> = { suspend: 'o_suspend', reinstate: 'o_reinstate', reverification: 'o_reverify', tier: 'o_tier' };

/** The dialog's name for an action: "search" hides a shown business and shows a hidden one. */
const actionName = (action: OversightAction, seller: Seller): SellersKey =>
  action === 'search' ? (seller.searchHidden ? 'o_show' : 'o_hide') : TITLE[action];

/**
 * The confirmation step of an oversight action (S-82): the reason the business will see, and the new tier or the check
 * to verify again. The api checks the role, writes the audit log and emails the owners.
 */
export function OversightDialog({ seller, more = [], action, checks, onClose, onDone }: {
  seller: Seller; /** S-177: the bulk bar's other businesses: the same action and reason, one request each. */ more?: readonly Seller[];
  action: OversightAction; checks: readonly Check[]; onClose: () => void; onDone?: () => void;
}) {
  const t = useSellersT();
  const run = useOversight();
  const [done, setDone] = useState(0);
  const [reason, setReason] = useState('');
  const tiers = (['registered', 'trusted', 'master'] as const).filter(x => x !== seller.tier);
  const [tier, setTier] = useState<string>(tiers[0] ?? 'registered');
  const verifiable = checks.filter(c => (c.status === 'verified' || c.status === 'submitted') && c.checkType !== 'kyc');
  const [verificationId, setVerificationId] = useState(verifiable[0]?.id ?? '');
  const errors = run.error instanceof ValidationError ? run.error.byField() : {};
  const conflict = run.error && !(run.error instanceof ValidationError) ? (run.error instanceof ApiError ? run.error.message : String(run.error)) : undefined;
  const blocked = action === 'reverification' && !verifiable.length;
  const all = [seller, ...more];
  const submit = async () => {
    try {
      for (const [i, s] of all.entries()) {
        if (i < done) continue; // a retry after a failure goes on from the business that failed
        await run.mutateAsync({ sellerId: s.id, action, reason, tier, verificationId, hidden: !s.searchHidden });
        setDone(i + 1);
      }
      onDone?.(); onClose();
    } catch { /* the error shows in the dialog */ }
  };
  const destructive = action === 'suspend';
  return (
    <Dialog open onClose={onClose} title={t('dialogTitle', { action: t(actionName(action, seller)), name: more.length ? t('nBusinesses', { n: all.length }) : seller.name })}
      actions={<><Button variant="secondary" onClick={onClose}>{t('cancel')}</Button><Button variant={destructive ? 'destructive' : 'primary'} disabled={run.isPending || blocked} onClick={() => void submit()}>{destructive ? t('confirmSuspend', { n: all.length }) : t('confirm')}</Button></>}>
      {more.length ? <p className="nl-sl-note">{all.map(s => s.name).join(' · ')}{done ? ` — ${t('doneOf', { done, n: all.length })}` : ''}</p> : null}
      {action === 'tier' ? (
        <Field label={t('dialogTier')} error={errors.tier}>
          <Select value={tier} onChange={e => setTier(e.target.value)} options={tiers.map(x => ({ value: x, label: t(`tier_${x}` as SellersKey) }))} />
        </Field>
      ) : null}
      {action === 'reverification' ? (
        blocked ? <p className="nl-sl-note">{t('noChecks')}</p> : (
          <Field label={t('dialogCheck')} error={errors.verificationId}>
            <Select value={verificationId} onChange={e => setVerificationId(e.target.value)}
              options={verifiable.map(c => ({ value: c.id, label: [checkName(c.checkType, t), c.registry, c.reference].filter(Boolean).join(' · ') }))} />
          </Field>
        )
      ) : null}
      <Field label={t('dialogReason')} error={errors.reason}>
        <TextArea value={reason} maxLength={500} onChange={e => setReason(e.target.value)} />
      </Field>
      {conflict ? <p role="alert" className="nl-sl-error">{conflict}</p> : null}
    </Dialog>
  );
}
