import { useState } from 'react';
import { Alert, Button, Checkbox, Dialog, Field, Select } from '@northline/ui';
import { REFUSE_REASONS, useCounterCheck, type RefuseReason, type Ticket } from './api';
import { useCounterT } from './counterMessages';

/**
 * Age-restricted pickup at the counter (2026-10-04): the team member confirms they checked government photo ID, the
 * name matches the customer's and the person is of age — or says why the order can't be handed over (it is returned
 * and the restricted dishes refunded). No ID details are typed or kept.
 */
export function CounterCheckDialog({ merchantId, ticket, onClose }: { merchantId: string; ticket: Ticket; onClose: () => void }) {
  const t = useCounterT();
  const check = useCounterCheck(merchantId);
  const [idChecked, setIdChecked] = useState(false);
  const [matches, setMatches] = useState(false);
  const [ofAge, setOfAge] = useState(false);
  const [refusing, setRefusing] = useState(false);
  const [reason, setReason] = useState<RefuseReason>('no_id');
  const all = idChecked && matches && ofAge;
  const age = ticket.idCheckAge ?? 0;
  return (
    <Dialog open onClose={onClose} title={t('title', { ref: ticket.ref ?? ticket.orderId })}
      actions={refusing ? (
        <>
          <Button variant="ghost" onClick={() => setRefusing(false)}>{t('back')}</Button>
          <Button disabled={check.isPending} onClick={() => check.mutate({ orderId: ticket.orderId, reason }, { onSuccess: onClose })}>{t('refuseSend')}</Button>
        </>
      ) : (
        <>
          <Button variant="ghost" onClick={() => setRefusing(true)}>{t('cantHandOver')}</Button>
          <Button disabled={!all || check.isPending} onClick={() => check.mutate({ orderId: ticket.orderId, idCheck: { idChecked, recipientMatches: matches, ofAge } }, { onSuccess: onClose })}>{t('handOver')}</Button>
        </>
      )}>
      {refusing ? (
        <>
          <p>{t('refuseLede')}</p>
          <Field label={t('why')}>
            <Select value={reason} onChange={e => setReason(e.target.value as RefuseReason)} options={REFUSE_REASONS.map(r => ({ value: r, label: t(`r_${r}`) }))} />
          </Field>
        </>
      ) : (
        <>
          <p>{t('lede', { age, name: ticket.customerName ?? t('theCustomer') })}</p>
          <fieldset className="nl-k-check">
            <legend className="nl-sr-only">{t('legend')}</legend>
            <Checkbox label={t('idChecked')} checked={idChecked} onChange={setIdChecked} />
            <Checkbox label={t('matches', { name: ticket.customerName ?? t('theCustomer') })} checked={matches} onChange={setMatches} />
            <Checkbox label={t('ofAge', { age })} checked={ofAge} onChange={setOfAge} />
          </fieldset>
          <p className="nl-k-muted">{t('privacy')}</p>
        </>
      )}
      {check.isError ? <Alert tone="error" role="alert">{t('error')}</Alert> : null}
    </Dialog>
  );
}
