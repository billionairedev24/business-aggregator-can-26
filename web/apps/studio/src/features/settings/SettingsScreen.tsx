import { BoHeader, UnderlineTabs } from '@northline/ui';
import { SETTINGS_TABS, type SettingsTab } from './api';
import { ApiTab } from './ApiTab';
import { BusinessTab } from './BusinessTab';
import { useSettingsT } from './messages';
import { NotificationsTab } from './NotificationsTab';
import { SecurityTab } from './SecurityTab';
import { TeamTab } from './TeamTab';
import './Settings.css';

/**
 * Settings (S-176, on the Studio editor's parts): the page head, the sections as underline tabs (`?tab=`), then the
 * current section's panels (FormSection); the Business section saves through the sticky SaveBar.
 * Business · Team & roles · Security · Notifications · API & integrations.
 */
export function SettingsScreen({ tab, onTab }: { tab: SettingsTab; onTab: (tab: SettingsTab) => void }) {
  const t = useSettingsT();
  return (
    <div className="nl-set">
      <BoHeader title={t('kicker')} sub={t('title')} />
      <div className="nl-set-tabs">
        <UnderlineTabs<SettingsTab> aria-label={t('tabs')} value={tab} onChange={onTab} tabId={v => `set-tab-${v}`} panelId={v => `set-panel-${v}`}
          options={SETTINGS_TABS.map(v => ({ value: v, label: t(`tab_${v}`) }))} />
      </div>
      <div role="tabpanel" id={`set-panel-${tab}`} aria-labelledby={`set-tab-${tab}`} className={tab === 'business' ? 'nl-set-panel' : 'bo-card nl-set-panel'}>
        {tab === 'business' && <BusinessTab />}
        {tab === 'team' && <TeamTab />}
        {tab === 'security' && <SecurityTab />}
        {tab === 'notifications' && <NotificationsTab />}
        {tab === 'api' && <ApiTab />}
      </div>
    </div>
  );
}
