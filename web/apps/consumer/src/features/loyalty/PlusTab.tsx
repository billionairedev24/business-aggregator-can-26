import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Dialog, ErrorState, OptionCard, SiteLink, useFormatters } from '@northline/ui';
import { ApiError } from '@northline/client';
import { FormSkeleton } from '../account/ProfileTab';
import { tabHref } from '../account/tabs';
import { plusQuery, useCancelPlus, useChangePlus, useJoinPlus, useResumePlus, type Plus } from './api';
import { useLoyaltyT } from './messages';

interface Stripe3ds { confirmCardPayment: (secret: string) => Promise<{ error?: { message?: string } }> }

async function loadStripe(key: string): Promise<Stripe3ds> {
  const w = window as unknown as { Stripe?: (key: string) => Stripe3ds };
  if (!w.Stripe) {
    await new Promise<void>((resolve, reject) => {
      const s = document.createElement('script');
      s.src = 'https://js.stripe.com/v3';
      s.onload = () => resolve();
      s.onerror = () => reject(new Error('Stripe.js failed to load'));
      document.head.appendChild(s);
    });
  }
  return w.Stripe!(key);
}

/**
 * S-152 {brand} Plus: the plans and prices (configuration), what members get, and — joined — the status (trial,
 * active, retrying, cancelled to the period's end), switch plan, cancel / keep, for the person who pays; the others
 * of the household see it. Joining bills the default saved card (Stripe Billing in the cloud, the local fake here);
 * a first payment that needs the bank is confirmed with Stripe.js.
 */
export function PlusTab() {
  const t = useLoyaltyT();
  const { money, date } = useFormatters();
  const plus = useQuery(plusQuery);
  const join = useJoinPlus();
  const change = useChangePlus();
  const cancel = useCancelPlus();
  const resume = useResumePlus();
  const [plan, setPlan] = useState<'monthly' | 'annual'>('monthly');
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [bankError, setBankError] = useState<string>();
  const cardMissing = join.error instanceof ApiError && join.error.status === 409 && (join.error.body as { code?: string } | undefined)?.code === 'card_required';
  const failed = (join.isError && !cardMissing) || change.isError || cancel.isError || resume.isError;

  const start = () => join.mutate(plan, {
    onSuccess: async p => {
      if (p.clientSecret && p.publishableKey) {
        const stripe = await loadStripe(p.publishableKey).catch(() => null);
        const result = stripe ? await stripe.confirmCardPayment(p.clientSecret) : { error: { message: t('saveError') } };
        if (result.error) setBankError(result.error.message ?? t('saveError'));
        void plus.refetch();
      }
    },
  });

  const price = (p: Plus, which: 'monthly' | 'annual') => which === 'monthly'
    ? t('perMonth', { price: money(p.monthlyPriceCents) }) : t('perYear', { price: money(p.annualPriceCents) });

  return (
    <>
      <h1 id="acct-title" className="nl-acct-h1">{t('plusTitle')}</h1>
      <p className="nl-acct-lede">{t('plusLede')}</p>
      {plus.isPending ? <FormSkeleton label={t('loading')} rows={2} />
        : plus.isError ? <ErrorState message={t('loadError')} onRetry={() => void plus.refetch()} />
          : (() => {
            const p = plus.data;
            const member = p.status !== 'none' && p.status !== 'canceled';
            const shown = member && p.plan ? p.plan : plan;
            const mult = p.benefits.pointsMultiplierPct / 100;
            return (
              <>
                <div className="nl-plus-plans" role="group" aria-label={t('plusTitle')}>
                  {(['monthly', 'annual'] as const).map(k => (
                    <OptionCard key={k} type="button" selected={shown === k} aria-pressed={shown === k} disabled={member}
                      title={`${t(k === 'monthly' ? 'planMonthly' : 'planAnnual')} · ${price(p, k)}`} onClick={() => setPlan(k)} />
                  ))}
                </div>
                <h2 className="nl-acct-h2">{t('whatYouGet')}</h2>
                <ul className="nl-plus-perks">
                  {p.benefits.serviceFeeWaived ? <li>{t('perkFee')}</li> : null}
                  {mult > 1 ? <li>{t('perkPoints', { multiplier: mult })}</li> : null}
                  {p.benefits.memberPrices ? <li>{t('perkPrices')}</li> : null}
                  <li>{t('perkHousehold')}</li>
                  <li>{t('perkCancel')}</li>
                </ul>
                {member ? (
                  <>
                    <div className="nl-sec-banner" role="status">
                      {p.since ? <strong>{t('since', { date: date(p.since, 'date') })}</strong> : null}{' '}
                      {p.currentPeriodEnd
                        ? p.cancelAtPeriodEnd ? t('s_canceling', { date: date(p.currentPeriodEnd, 'date') })
                          : p.status === 'trialing' ? t('s_trialing', { date: date(p.currentPeriodEnd, 'date') })
                            : p.status === 'active' ? t('s_active', { date: date(p.currentPeriodEnd, 'date') }) : null
                        : null}
                      {p.status === 'past_due' ? t('s_past_due') : p.status === 'incomplete' ? t('s_incomplete') : null}
                    </div>
                    {p.payer ? (
                      <div className="nl-acct-actions">
                        {p.cancelAtPeriodEnd
                          ? <Button type="button" disabled={resume.isPending} onClick={() => resume.mutate()}>{t('resume')}</Button>
                          : <>
                            <Button type="button" variant="secondary" disabled={change.isPending}
                              onClick={() => change.mutate(p.plan === 'annual' ? 'monthly' : 'annual')}>
                              {t('switchTo', { plan: t(p.plan === 'annual' ? 'planMonthly' : 'planAnnual') })}
                            </Button>
                            <Button type="button" variant="ghost" onClick={() => setConfirmCancel(true)}>{t('cancel')}</Button>
                          </>}
                        <SiteLink href={tabHref('addresses')} className="btn btn-ghost">{t('perkHousehold')}</SiteLink>
                      </div>
                    ) : <p className="nl-muted">{t('notPayer')}</p>}
                  </>
                ) : (
                  <div className="nl-acct-actions">
                    <Button type="button" disabled={join.isPending} aria-busy={join.isPending} onClick={start}>
                      {p.trialAvailable && p.trialDays > 0 ? t('startTrial', { days: p.trialDays }) : t('join')}
                    </Button>
                    <span className="nl-small nl-muted">
                      {t('then', { price: price(p, plan) })} · {p.trialAvailable && p.trialDays > 0 ? t('trialNote') : t('cardNote')}
                    </span>
                  </div>
                )}
                {cardMissing ? (
                  <div className="nl-sec-banner" role="alert">
                    <strong>{t('addCard')}</strong> {t('addCardBody')} <SiteLink href={tabHref('payments')}>{t('addCard')}</SiteLink>
                  </div>
                ) : null}
                {bankError ? <p className="nl-error" role="alert">{t('confirm3ds')}: {bankError}</p> : null}
                {failed ? <p className="nl-error" role="alert">{t('saveError')}</p> : null}
                <Dialog open={confirmCancel} onClose={() => setConfirmCancel(false)} title={t('cancelTitle')} role="alertdialog"
                  actions={<>
                    <Button type="button" variant="ghost" onClick={() => setConfirmCancel(false)}>{t('keep')}</Button>
                    <Button type="button" disabled={cancel.isPending} onClick={() => cancel.mutate(undefined, { onSuccess: () => setConfirmCancel(false) })}>{t('cancel')}</Button>
                  </>}>
                  <p>{t('cancelBody', { date: p.currentPeriodEnd ? date(p.currentPeriodEnd, 'date') : '' })}</p>
                </Dialog>
              </>
            );
          })()}
    </>
  );
}
