import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Field, FormGrid, Select, TextArea, TextInput } from './Field';
import { Checkbox, Chip, ChipTabs, OptionCard, Segmented, StepBars, Switch, UnderlineTabs } from './Choice';
import { Dialog, Drawer, Menu } from './Overlay';
import { Alert, Avatar, EmptyState, ErrorState, Kpi, KpiRow, LinkRow, Meter, PageHeader, PageSkeleton, Panel } from './Layout';
import { BarList, LineChart, StackedBarChart } from './Charts';
import { Button } from './Button';

const meta = { title: 'Core/Primitives', parameters: { layout: 'padded' } } satisfies Meta;
export default meta;
type S = StoryObj;

export const Fields: S = {
  render: () => {
    const [name, setName] = useState('');
    const [touched, setTouched] = useState(false);
    const err = touched && !name.trim() ? 'Enter the name customers will see.' : touched && name.trim().length < 2 ? 'At least 2 characters.' : null;
    return (
      <FormGrid>
        <Field label="Business name" error={err}><TextInput placeholder="e.g. Prairie Wrench" value={name} onChange={e => setName(e.target.value)} onBlur={() => setTouched(true)} /></Field>
        <Field label="GST/HST number" note="· optional for sole proprietors" hint="Format is 9 digits + RT0001."><TextInput placeholder="123456789 RT0001" /></Field>
        <Field label="Business structure"><Select options={[{ value: 'sole', label: 'Sole proprietorship' }, { value: 'corp_ab', label: 'Corporation (Alberta)' }]} /></Field>
        <Field label="What's included" span><TextArea placeholder="Scope, what the customer gets, what's excluded." /></Field>
      </FormGrid>
    );
  },
};

export const Choices: S = {
  render: () => {
    const [c, setC] = useState(true), [sw, setSw] = useState(true), [mfa, setMfa] = useState(0), [tab, setTab] = useState<'business' | 'team'>('business'), [seg, setSeg] = useState<'30' | '90'>('90'), [ut, setUt] = useState<'in' | 'up'>('in');
    return (
      <div style={{ display: 'grid', gap: 18, maxWidth: 520 }}>
        <Checkbox checked={c} onChange={setC} label="Instant book (no approval needed)" />
        <Checkbox checked={false} indeterminate onChange={() => {}} label="Some selected" />
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}><Switch checked={sw} onChange={setSw} label="About" /> About section</div>
        <div style={{ display: 'grid', gap: 8 }}>
          {['Passkey (Face ID / Touch ID / Windows Hello)', 'Authenticator app'].map((n, i) => <OptionCard key={n} selected={mfa === i} onClick={() => setMfa(i)} title={n} description={i ? 'Time-based codes · Google / Microsoft / 1Password' : 'Phishing-resistant · recommended'} />)}
        </div>
        <ChipTabs aria-label="Settings" value={tab} onChange={setTab} options={[{ value: 'business', label: 'Business' }, { value: 'team', label: 'Team & roles' }]} />
        <Segmented name="period" value={seg} onChange={setSeg} options={[{ value: '30', label: '30 d' }, { value: '90', label: '90 d' }]} />
        <UnderlineTabs aria-label="Auth" value={ut} onChange={setUt} options={[{ value: 'in', label: 'Sign in' }, { value: 'up', label: 'Create account' }]} />
        <StepBars total={3} done={1} label="Step 2 of 3" />
        <div className="nl-chips"><Chip selected>Mains</Chip><Chip>Starters</Chip></div>
      </div>
    );
  },
};

export const Overlays: S = {
  render: () => {
    const [d, setD] = useState(false), [dr, setDr] = useState(false);
    return (
      <div style={{ display: 'flex', gap: 10 }}>
        <Button onClick={() => setD(true)}>Open dialog</Button>
        <Button variant="secondary" onClick={() => setDr(true)}>Open drawer</Button>
        <Menu label="Account" trigger={({ props }) => <Button variant="secondary" {...props}>Menu ▾</Button>} items={[{ label: 'My profile & security', meta: 'Passkey', onSelect: () => {} }, { label: 'Team & roles', meta: '3 members', onSelect: () => {} }, { kind: 'separator' }, { label: 'Sign out', onSelect: () => {} }]} />
        <Dialog open={d} onClose={() => setD(false)} title="Delete listing?" role="alertdialog" actions={<><Button variant="ghost" onClick={() => setD(false)}>Cancel</Button><Button onClick={() => setD(false)}>Delete</Button></>}>This removes “Cabin air filter”. The action is logged.</Dialog>
        <Drawer open={dr} onClose={() => setDr(false)} title="Write quote" footer={<Button onClick={() => setDr(false)}>Send quote</Button>}><p>Drawer body</p></Drawer>
      </div>
    );
  },
};

export const Feedback: S = {
  render: () => (
    <div style={{ display: 'grid', gap: 14, maxWidth: 640 }}>
      <Alert title="Security step-up.">Your customer passkey carries over.</Alert>
      <Alert tone="error" title="2 things need attention.">Fix the fields marked in magenta to continue.</Alert>
      <Alert tone="highlight">Instant book pauses in 7 days.</Alert>
      <EmptyState action={<Button>Add service</Button>}>No services yet.</EmptyState>
      <ErrorState message="Couldn't load payouts." onRetry={() => {}} />
      <PageSkeleton kpis={3} rows={3} />
    </div>
  ),
};

export const PageParts: S = {
  render: () => (
    <div>
      <PageHeader kicker="Sales reports" title="Last 90 days" actions={<Button variant="secondary">Export CSV</Button>} />
      <KpiRow><Kpi value="$6,820" label="net this month · +18% vs Aug" /><Kpi value="4.9" label="rating · 212 reviews" /><Kpi value="97%" label="on time" /></KpiRow>
      <div style={{ display: 'grid', gap: 8, marginTop: 24, maxWidth: 560 }}><LinkRow>2 quote requests · respond within 1 h 12 m</LinkRow><LinkRow>WCB clearance letter expired Aug 31</LinkRow></div>
      <Panel style={{ marginTop: 24, maxWidth: 560 }}><Meter label="On-time arrival" value={97} floor={90} /><Meter label="Photo compliance" value={85} floor={90} /></Panel>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}><Avatar initials="RS" /><Avatar initials="AO" size={44} tone="dark" /></div>
    </div>
  ),
};

export const ChartsStory: S = {
  name: 'Charts',
  render: () => (
    <div style={{ display: 'grid', gap: 32, maxWidth: 560 }}>
      <StackedBarChart title="Net earnings, 12 weeks" series={[{ key: 's', label: 'Services', color: 'var(--color-accent)' }, { key: 'p', label: 'Parts', color: 'var(--color-accent-2-400)' }]} data={[[820, 210], [960, 180], [1100, 260], [880, 140], [1240, 310], [1180, 220], [1320, 280], [1050, 190], [1410, 330], [1290, 240], [1520, 360], [1460, 300]].map((d, i) => ({ label: `W${i + 1}`, values: { s: d[0]!, p: d[1]! } }))} />
      <LineChart title="Weekly gross" legend={{ current: 'This period', previous: 'Previous period' }} current={[3200, 3600, 3100, 3900, 4200, 3800, 4600, 4400, 4900, 5100, 4700, 5400, 5600]} previous={[2600, 2900, 2800, 3000, 3300, 3100, 3500, 3600, 3400, 3900, 3800, 4000, 4100]} />
      <BarList items={[{ label: 'Brake inspection', value: 2759 }, { label: 'Oil & filter', value: 2212 }]} format={n => `$${n.toLocaleString('en-CA')}`} />
    </div>
  ),
};
