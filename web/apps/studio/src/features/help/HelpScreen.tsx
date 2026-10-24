import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { BoHeader, UnderlineTabs } from '@northline/ui';
import { useMerchant, useMerchantId } from '../shell/api';
import type { Channel } from '../messages/validation';
import { casesQuery } from './api';
import { ContactForm } from './ContactForm';
import { HelpCases, type SentCase } from './HelpCases';
import { HelpHome } from './HelpHome';
import { useHelpT } from './messages';
import { PlatformStatus } from './PlatformStatus';
import './help.css';

export type HelpTab = 'home' | 'cases' | 'new' | 'status';
export interface HelpSearch { tab?: HelpTab; topic?: string; case?: string }

/**
 * /b/$merchantId/help — design: help. Tabs: Help centre · My cases · Contact support · Platform status.
 * `?topic=<case topic>` (e.g. onboarding's "Need help with a document?" → `?topic=verification`) opens Contact support
 * with that topic chosen; `?case=<id>` opens a case's conversation.
 */
export function HelpScreen({ search, onNavigate }: { search: HelpSearch; onNavigate: (next: HelpSearch) => void }) {
  const t = useHelpT();
  const merchantId = useMerchantId();
  const merchant = useMerchant();
  const tab: HelpTab = search.tab ?? (search.topic ? 'new' : 'home');
  const cases = useQuery(casesQuery(merchantId));
  const open = (cases.data ?? []).filter(c => c.state !== 'resolved').length;
  const [channel, setChannel] = useState<Channel>('chat');
  const [sent, setSent] = useState<SentCase | null>(null);

  const tabs: { value: HelpTab; label: string; count?: number; name?: string }[] = [
    { value: 'home', label: t('tab_home') },
    { value: 'cases', label: t('tab_cases'), count: open > 0 ? open : undefined, name: open > 0 ? t('tab_casesOpen', { count: open }) : undefined },
    { value: 'new', label: t('tab_new') },
    { value: 'status', label: t('tab_status') },
  ];

  return (
    <>
      <BoHeader title={t('kicker')} sub={t('title', { name: merchant.displayName })} />
      <div className="nl-help-tabs"><UnderlineTabs aria-label={t('tabs')} options={tabs} value={tab} tabId={v => `help-tab-${v}`} panelId={v => `help-panel-${v}`} onChange={v => { setSent(null); onNavigate({ tab: v }); }} /></div>
      <div role="tabpanel" id={`help-panel-${tab}`} aria-labelledby={`help-tab-${tab}`} className="nl-help-panel">
        {tab === 'home' ? (
          <HelpHome merchantId={merchantId}
            onContact={p => { setChannel(p.channel ?? 'chat'); onNavigate({ tab: 'new', topic: p.topic }); }}
            onOpenCase={id => onNavigate({ tab: 'cases', case: id })} />
        ) : tab === 'cases' ? (
          <HelpCases merchantId={merchantId} caseId={search.case} sent={sent}
            onSelect={id => onNavigate({ tab: 'cases', case: id })} onCreate={() => { setSent(null); onNavigate({ tab: 'new' }); }} />
        ) : tab === 'new' ? (
          <ContactForm key={`${search.topic ?? ''}:${channel}`} merchantId={merchantId} initialTopic={search.topic} initialChannel={channel}
            onSent={(created, form) => { setSent({ code: created.code, topic: form.topic, urgent: form.urgent }); onNavigate({ tab: 'cases', case: created.id }); }} />
        ) : (
          <PlatformStatus merchantId={merchantId} />
        )}
      </div>
    </>
  );
}
