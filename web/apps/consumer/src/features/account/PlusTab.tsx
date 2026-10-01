import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Dialog, ErrorState, OptionCard, useFormatters } from '@northline/ui';
import { FormSkeleton } from './ProfileTab';
import { householdQuery, useCancelPlus, useStartPlus } from './settingsApi';
import { useSettingsT } from './settingsMessages';
import { tabHref } from './tabs';

/**
 * Northline Plus (design 06 `at.plus`): the two plans, what you get, and — active — since when and the renewal (the
 * free trial's end), Manage household / Cancel Plus; inactive — "Start 30-day free trial". No card is charged: Plus
 * billing doesn't exist yet (DECISIONS S-59).
 */
export function PlusTab() {
  const t = useSettingsT();
  const { date } = useFormatters();
  const household = useQuery(householdQuery);
  const start = useStartPlus();
  const cancel = useCancelPlus();
  const [plan, setPlan] = useState<'monthly' | 'annual'>('monthly');
  const [confirmCancel, setConfirmCancel] = useState(false);
  const active = household.data && household.data.plan !== 'none';
  return (
    <>
      <h1 id="acct-title" className="nl-acct-h1">{t('plusTitle')}</h1>
      <p className="nl-acct-lede">{t('plusLede', { sub: t(active ? 'plusSubOn' : 'plusSubOff') })}</p>
      {household.isPending ? <FormSkeleton label={t('loading')} rows={2} />
        : household.isError ? <ErrorState message={t('loadError')} onRetry={() => void household.refetch()} />
          : (
            <>
              <div className="nl-plus-plans" role="group" aria-label={t('plusTitle')}>
                {(['monthly', 'annual'] as const).map(p => (
                  <OptionCard key={p} type="button" selected={(active ? household.data.plan : plan) === p} aria-pressed={(active ? household.data.plan : plan) === p}
                    disabled={!!active} title={t(p === 'monthly' ? 'planMonthly' : 'planAnnual')} description={t(p === 'monthly' ? 'planMonthlySub' : 'planAnnualSub')}
                    onClick={() => setPlan(p)} />
                ))}
              </div>
              <h2 className="nl-acct-h2">{t('whatYouGet')}</h2>
              <ul className="nl-plus-perks">{(['perk1', 'perk2', 'perk3', 'perk4', 'perk5'] as const).map(k => <li key={k}>{t(k)}</li>)}</ul>
              {active ? (
                <>
                  <div className="nl-sec-banner" role="status">
                    <strong>{household.data.plusSince ? t('plusActive', { since: date(household.data.plusSince, 'date') }) : null}</strong>
                    {' '}{household.data.renewsAt ? t('plusTrialEnds', { date: date(household.data.renewsAt, 'date') }) : null}
                  </div>
                  <div className="nl-acct-actions">
                    <a className="btn btn-secondary" href={tabHref('addresses')}>{t('manageHousehold')}</a>
                    <Button type="button" variant="ghost" onClick={() => setConfirmCancel(true)}>{t('cancelPlus')}</Button>
                  </div>
                </>
              ) : (
                <div className="nl-acct-actions">
                  <Button type="button" disabled={start.isPending} aria-busy={start.isPending} onClick={() => start.mutate(plan)}>{t('startTrial')}</Button>
                  <span className="nl-small nl-muted">{t('then', { price: t(plan === 'monthly' ? 'priceMonthly' : 'priceAnnual') })} · {t('trialNote')}</span>
                </div>
              )}
              {start.isError || cancel.isError ? <p className="nl-error" role="alert">{t('saveError')}</p> : null}
            </>
          )}
      <Dialog open={confirmCancel} onClose={() => setConfirmCancel(false)} title={t('cancelPlusTitle')} role="alertdialog"
        actions={<>
          <Button type="button" variant="ghost" onClick={() => setConfirmCancel(false)}>{t('cancel')}</Button>
          <Button type="button" disabled={cancel.isPending} onClick={() => cancel.mutate(undefined, { onSuccess: () => setConfirmCancel(false) })}>{t('cancelPlus')}</Button>
        </>}>
        <p>{t('cancelPlusBody')}</p>
      </Dialog>
    </>
  );
}
