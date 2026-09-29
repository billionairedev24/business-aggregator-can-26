import { ChipTabs, PageHeader } from '@northline/ui';
import { SETTINGS_TABS, type SettingsTab } from './api';
import { ApiTab } from './ApiTab';
import { BusinessTab } from './BusinessTab';
import { useSettingsT } from './messages';
import { NotificationsTab } from './NotificationsTab';
import { SecurityTab } from './SecurityTab';
import { TeamTab } from './TeamTab';
import './Settings.css';

/** Settings (design 02 › SETTINGS): Business · Team & roles · Security · Notifications · API & integrations. */
export function SettingsScreen({ tab, onTab }: { tab: SettingsTab; onTab: (tab: SettingsTab) => void }) {
  const t = useSettingsT();
  return (
    <div className="nl-set">
      <PageHeader kicker={t('kicker')} title={t('title')} />
      <div className="nl-set-tabs">
        <ChipTabs<SettingsTab> aria-label={t('tabs')} value={tab} onChange={onTab} options={SETTINGS_TABS.map(v => ({ value: v, label: t(`tab_${v}`) }))} />
      </div>
      <div role="tabpanel" aria-label={t(`tab_${tab}`)}>
        {tab === 'business' && <BusinessTab />}
        {tab === 'team' && <TeamTab />}
        {tab === 'security' && <SecurityTab />}
        {tab === 'notifications' && <NotificationsTab />}
        {tab === 'api' && <ApiTab />}
      </div>
    </div>
  );
}
