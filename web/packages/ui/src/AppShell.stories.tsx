import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Bank, Briefcase, CalendarCheck, ChartBar, ChartLineUp, ChatCircleText, Clock, GearSix, Lifebuoy, ShieldCheck, SquaresFour, Star, Storefront, Tag, UserCircle, Wallet, ArrowUUpLeft } from '@phosphor-icons/react';
import { AppShell, type NavGroup, type NavItem } from './AppShell';
import { PageHeader } from './Layout';

const item = (key: string, label: string, icon: NavItem['icon'], badge?: string): NavItem => ({ key, label, icon, badge, href: `#${key}` });
const pinned = [item('dashboard', 'Dashboard', SquaresFour)];
const groups: NavGroup[] = [
  { label: 'Operations', icon: Briefcase, items: [item('appointments', 'Appointments', CalendarCheck, '3'), item('messages', 'Messages', ChatCircleText, '3')] },
  { label: 'Catalogue', icon: Storefront, items: [item('products', 'Services & prices', Tag, '5'), item('availability', 'Availability', Clock), item('storefront', 'Business page', Storefront)] },
  { label: 'Finance', icon: Wallet, items: [item('earnings', 'Earnings', ChartLineUp), item('reports', 'Reports', ChartBar), item('payouts', 'Payouts', Bank, 'Fri'), item('refunds', 'Refunds & disputes', ArrowUUpLeft, '1'), item('compliance', 'Stripe & compliance', ShieldCheck, '1 due')] },
  { label: 'Account', icon: UserCircle, items: [item('reviews', 'Reviews', Star, '4.9'), item('settings', 'Settings', GearSix)] },
  { label: 'Help', icon: Lifebuoy, items: [item('help', 'Help & support', Lifebuoy, '1 open')] },
];

const meta = { title: 'Shell/AppShell', component: AppShell, parameters: { layout: 'fullscreen' } } satisfies Meta<typeof AppShell>;
export default meta;
type S = StoryObj<typeof meta>;

function Demo({ rail }: { rail?: boolean }) {
  const [cur, setCur] = useState('appointments');
  return (
    <AppShell rail={rail} brand={<span className="nav-brand">Northline <span style={{ fontWeight: 400, color: 'var(--color-neutral-700)' }}>Studio</span></span>}
      headerStart={<><span style={{ fontSize: 14, color: 'var(--color-neutral-700)' }}>Prairie Wrench · Calgary</span><span className="tag tag-accent">Master tier</span></>}
      headerEnd={<span style={{ fontSize: 13 }}>Ravi Sandhu</span>} pinned={pinned} groups={groups} currentKey={cur} onNavigate={i => setCur(i.key)}>
      <PageHeader kicker="Operations" title={cur} />
    </AppShell>
  );
}
export const Full: S = { args: {} as never, render: () => <Demo /> };
export const Rail: S = { args: {} as never, render: () => <Demo rail /> };
export const Narrow: S = { args: {} as never, render: () => <Demo />, parameters: { viewport: { defaultViewport: 'mobile1' } } };
