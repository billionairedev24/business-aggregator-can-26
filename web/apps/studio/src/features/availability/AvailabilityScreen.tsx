import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ChipTabs, PageHeader, formatDate, useLocale } from '@northline/ui';
import { useMerchantId } from '../shell/api';
import { hoursQuery, rulesQuery } from './api';
import { HoursTab } from './HoursTab';
import { useAvailabilityT } from './messages';
import { RulesTab } from './RulesTab';
import { SyncTab, type CalendarReturn } from './SyncTab';
import { TimeOffTab } from './TimeOffTab';
import './Availability.css';

export type AvTab = 'hours' | 'rules' | 'timeoff' | 'sync';
export type SaveState = 'clean' | 'dirty' | 'saved';

/** `returned`: the outcome of a calendar connection (OAuth callback) — opens the sync tab with a notice. */
export function AvailabilityScreen({ returned, onReturnSeen }: { returned?: CalendarReturn; onReturnSeen?: () => void } = {}) {
  const t = useAvailabilityT();
  const merchantId = useMerchantId();
  const { locale } = useLocale();
  const [tab, setTab] = useState<AvTab>(returned?.calendar ? 'sync' : 'hours');
  const [state, setState] = useState<SaveState>('clean');
  const hours = useQuery(hoursQuery(merchantId));
  const rules = useQuery(rulesQuery(merchantId));
  const last = [hours.data?.lastSavedAt, rules.data?.lastSavedAt].filter(Boolean).sort().at(-1);
  const status = state === 'dirty' ? t('statusDirty') : state === 'saved' ? t('statusSaved') : last ? t('statusLast', { date: formatDate(last, locale) }) : t('statusNever');
  const change = (s: SaveState) => setState(s);
  return (
    <div className="nl-av">
      <PageHeader kicker={t('kicker')} title={t('title')} lede={t('lede')} />
      <div className="nl-av-tabs">
        <ChipTabs<AvTab> aria-label={t('tabs')} value={tab} onChange={v => { setTab(v); setState(s => (s === 'saved' ? 'clean' : s)); }}
          options={(['hours', 'rules', 'timeoff', 'sync'] as const).map(v => ({ value: v, label: t(`tab_${v}`) }))} />
        <span className="nl-av-status" role="status">{status}</span>
      </div>
      <div role="tabpanel" aria-label={t(`tab_${tab}`)}>
        {tab === 'hours' && <HoursTab onState={change} />}
        {tab === 'rules' && <RulesTab onState={change} />}
        {tab === 'timeoff' && <TimeOffTab />}
        {tab === 'sync' && <SyncTab returned={returned} onReturnSeen={onReturnSeen} />}
      </div>
    </div>
  );
}
