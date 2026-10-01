import { useState } from 'react';
import { Button, Dialog, Field, Select, TextArea } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useOversight, type Check, type OversightAction, type Seller } from './api';
import { checkName } from './format';
import { useSellersT, type SellersKey } from './messages';

const TITLE: Record<OversightAction, SellersKey> = { suspend: 'o_suspend', reinstate: 'o_reinstate', reverification: 'o_reverify', tier: 'o_tier' };

/**
 * The confirmation step of an oversight action (S-82): the reason the business will see, and the new tier or the check
 * to verify again. The api checks the role, writes the audit log and emails the owners.
 */
export function OversightDialog({ seller, action, checks, onClose, onDone }: { seller: Seller; action: OversightAction; checks: readonly Check[]; onClose: () => void; onDone?: () => void }) {
  const t = useSellersT();
  const run = useOversight();
  const [reason, setReason] = useState('');
  const tiers = (['registered', 'trusted', 'master'] as const).filter(x => x !== seller.tier);
  const [tier, setTier] = useState<string>(tiers[0] ?? 'registered');
  const verifiable = checks.filter(c => (c.status === 'verified' || c.status === 'submitted') && c.checkType !== 'kyc');
  const [verificationId, setVerificationId] = useState(verifiable[0]?.id ?? '');
  const errors = run.error instanceof ValidationError ? run.error.byField() : {};
  const conflict = run.error && !(run.error instanceof ValidationError) ? (run.error instanceof ApiError ? run.error.message : String(run.error)) : undefined;
  const blocked = action === 'reverification' && !verifiable.length;
  const submit = () => run.mutate({ sellerId: seller.id, action, reason, tier, verificationId }, { onSuccess: () => { onDone?.(); onClose(); } });
  return (
    <Dialog open onClose={onClose} title={t('dialogTitle', { action: t(TITLE[action]), name: seller.name })}
      actions={<><Button variant="ghost" onClick={onClose}>{t('cancel')}</Button><Button disabled={run.isPending || blocked} onClick={submit}>{t('confirm')}</Button></>}>
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
