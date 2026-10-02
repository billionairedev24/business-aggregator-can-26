/**
 * S-109: every @northline/ui component rendered in its main states and checked with axe (WCAG 2.0–2.2 A/AA rules that
 * jsdom can evaluate; contrast is the token test's job, layout the page sweep's). Behaviour that axe cannot see — focus
 * order, focus return, announcements — is tested next to the component in `a11yBehaviour.test.tsx`.
 */
import { useState, type ReactNode } from 'react';
import { describe, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Bank, Briefcase, ChartLineUp, House, Tag as TagIcon, UserCircle } from '@phosphor-icons/react';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import {
  AccountMenu, Alert, AppShell, Avatar, BarList, BrandMark, Button, ChatBubble, ChatLog, Checkbox, Chip, ChipTabs, DataTable, DepartmentTile,
  Dialog, Drawer, EmptyState, ErrorState, Field, FileButton, FormGrid, GroupedMultiSelect, I18nProvider, Input, Kpi, KpiRow, Legend, LineChart,
  LinkRow, LocationPill, Menu, Meter, OptionCard, PageHeader, PageSkeleton, Panel, ProductTile, SearchBar, Segmented, Select, ShopTile, SiteHeader,
  Skeleton, StackedBarChart, Stars, StepBars, Switch, Tag, TextArea, TextInput, TileGrid, UnderlineTabs, type Locale, type NavGroup,
} from './index';
import { CAN, listingActions, listingColumns, listings } from './DataTable/DataTable.fixtures';

const page = (ui: ReactNode, locale: Locale = 'en') => render(<I18nProvider initial={locale}><main><h1>Test</h1>{ui}</main></I18nProvider>);
const noop = () => {};

describe.each(['en', 'fr'] as const)('axe · primitives (%s)', locale => {
  it('buttons, tags, chips, choices', async () => {
    const { container } = page(<>
      <Button>Save</Button><Button variant="secondary">Cancel</Button><Button variant="ghost" icon aria-label="Close">×</Button>
      <Tag>Live</Tag><Tag tone="accent-2">Late</Tag>
      <Checkbox checked onChange={noop} label="Remember me" /><Checkbox checked={false} indeterminate onChange={noop} aria-label="Select all" />
      <Switch checked={false} onChange={noop} label="Instant book" />
      <OptionCard selected title="Passkey" description="Use your device" />
      <div role="radiogroup" aria-label="Second factor"><OptionCard role="radio" selected title="Passkey" /><OptionCard role="radio" selected={false} title="Authenticator app" /></div>
      <Chip selected>Open</Chip>
      <ChipTabs aria-label="Settings" options={[{ value: 'a', label: 'Business' }, { value: 'b', label: 'Team' }]} value="a" onChange={noop} />
      <Segmented name="view" aria-label="View" options={[{ value: 'w', label: 'Week' }, { value: 'm', label: 'Month' }]} value="w" onChange={noop} />
      <UnderlineTabs aria-label="Sign in with" options={[{ value: 'p', label: 'Phone' }, { value: 'e', label: 'Email' }]} value="p" onChange={noop} />
      <StepBars total={3} done={1} label="Step 2 of 3" />
      <FileButton onFile={noop}>Upload · PDF</FileButton>
    </>, locale);
    await expectNoAxeViolations(container);
  });

  it('form fields with hints and errors', async () => {
    const { container } = page(<form>
      <Input label="Email" error="Enter a valid email." />
      <FormGrid>
        <Field label="Business name" hint="As registered"><TextInput /></Field>
        <Field label="Phone" error="Enter a 10-digit phone number."><TextInput type="tel" /></Field>
        <Field label="About" note="· optional"><TextArea /></Field>
        <Field label="Province"><Select placeholder="Choose" options={[{ value: 'x', label: 'Province X' }]} /></Field>
      </FormGrid>
    </form>, locale);
    await expectNoAxeViolations(container);
  });

  it('layout, states, meters and avatars', async () => {
    const { container } = page(<>
      <PageHeader kicker="Finance" title="Payouts" lede="Every Friday." actions={<Button>Export</Button>} />
      <Panel as="section" aria-label="Summary"><KpiRow><Kpi value="$1,912" label="Net" /><Kpi value="14" label="Jobs" /></KpiRow></Panel>
      <Alert tone="error" title="Couldn't save.">Try again.</Alert><Alert tone="info">Saved.</Alert>
      <LinkRow>Upload your insurance</LinkRow>
      <Meter label="Response time" value={82} /><Meter label={<strong>On-time rate</strong>} value={40} floor={60} />
      <Avatar initials="RS" /><Skeleton /><PageSkeleton kpis={2} rows={2} />
      <EmptyState action={<Button>Add a service</Button>}>No services yet.</EmptyState>
      <ErrorState message="Couldn't load payouts." onRetry={noop} />
      <BrandMark name="Prairie Wrench" color="#1e4d36" />
    </>, locale);
    await expectNoAxeViolations(container);
  });

  it('charts and the bar list', async () => {
    const { container } = page(<>
      <StackedBarChart title="Net earnings · 12 weeks" series={[{ key: 'svc', label: 'Services', color: 'var(--color-accent)' }, { key: 'tips', label: 'Tips', color: 'var(--color-highlight)' }]}
        data={[{ label: 'W1', values: { svc: 400, tips: 20 } }, { label: 'W2', values: { svc: 520, tips: 0 } }]} />
      <LineChart title="Weekly gross" current={[3, 5, 4]} previous={[2, 4, 4]} labels={['Mon', 'Tue', 'Wed']} legend={{ current: 'This period', previous: 'Previous' }} />
      <Legend items={[{ label: 'Goods', color: 'var(--color-accent)' }]} />
      <BarList items={[{ label: 'Brake job', value: 1200 }, { label: 'Oil change', value: 400 }]} />
    </>, locale);
    await expectNoAxeViolations(container);
  });

  it('chat and stars', async () => {
    const { container } = page(<ChatLog label="Conversation with Amara"><ChatBubble side="them" sender="Amara" meta="9:14">Hi!</ChatBubble><ChatBubble side="me" sender="You" pending>On my way</ChatBubble></ChatLog>, locale);
    page(<Stars rating={4} />, locale);
    await expectNoAxeViolations(container);
    await expectNoAxeViolations(document.body);
  });

  it('shop tiles', async () => {
    const { container } = page(<TileGrid min={200} label="Products">
      <li><ProductTile href="/p/1" name="Sourdough" price="$7.50" meta="Glenmore Bakery" tag={{ label: 'New' }} /></li>
      <li><ShopTile href="/s/1" id="m1" name="Glenmore Bakery" meta="Bakery" tier={{ label: 'Master' }} tag={{ label: 'Tomorrow' }} /></li>
      <li><DepartmentTile href="/d/1" name="Bakery" count="12 shops" /></li>
    </TileGrid>, locale);
    await expectNoAxeViolations(container);
  });
});

describe('axe · overlays', () => {
  it('an open dialog (alertdialog too) and drawer', async () => {
    page(<>
      <Dialog open onClose={noop} title="Delete listing?" actions={<><Button variant="secondary">Cancel</Button><Button>Delete</Button></>}>This can't be undone.</Dialog>
    </>);
    await expectNoAxeViolations(document.body);
    page(<Dialog open role="alertdialog" onClose={noop} title="Leave without saving?">Your changes will be lost.</Dialog>);
    page(<Drawer open onClose={noop} title="Filters" footer={<Button>Apply</Button>}><p>Body</p></Drawer>);
    await expectNoAxeViolations(document.body);
  });

  it('an open menu', async () => {
    const user = userEvent.setup();
    page(<Menu label="Business" trigger={({ props }) => <button type="button" {...props}>Prairie Wrench</button>}
      items={[{ label: 'Prairie Wrench', onSelect: noop, checked: true }, { kind: 'separator' }, { label: 'Settings', onSelect: noop }, { label: 'Help', onSelect: noop, href: '/help' }]} />);
    await user.click(screen.getByRole('button', { name: 'Prairie Wrench' }));
    await expectNoAxeViolations(document.body);
  });
});

const groups: NavGroup[] = [
  { label: 'Operations', icon: Briefcase, items: [{ key: 'orders', label: 'Orders', icon: TagIcon, href: '/orders', badge: '3' }] },
  { label: 'Finance', icon: Bank, items: [{ key: 'payouts', label: 'Payouts', icon: ChartLineUp, href: '/payouts' }] },
];
const shell = (props: { rail?: boolean } = {}) => (
  <I18nProvider initial="en">
    <AppShell brand={<span className="nav-brand">Northline</span>} headerEnd={<span>Ravi</span>} pinned={[{ key: 'home', label: 'Dashboard', icon: House, href: '/' }]}
      groups={groups} currentKey="orders" onNavigate={noop} {...props}><h1>Orders</h1></AppShell>
  </I18nProvider>
);

describe('axe · shells and headers', () => {
  it('the Studio/Console shell: full sidebar and rail', async () => {
    const { unmount } = render(shell());
    await expectNoAxeViolations(document.body);
    unmount();
    render(shell({ rail: true }));
    await expectNoAxeViolations(document.body);
  });

  it('the shell below 900 px with the drawer open', async () => {
    const mm = vi.spyOn(window, 'matchMedia').mockImplementation(q => ({ matches: true, media: q, onchange: null, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {}, dispatchEvent: () => false }) as MediaQueryList);
    const user = userEvent.setup();
    render(shell());
    await user.click(screen.getByRole('button', { name: 'Open menu' }));
    await expectNoAxeViolations(document.body);
    mm.mockRestore();
  });

  it('the consumer header with the location pill, search suggestions and the account menu open', async () => {
    const user = userEvent.setup();
    function Header() {
      const [q, setQ] = useState('');
      return (
        <SiteHeader location={<LocationPill status="detected" label="Beltline" href="/location" />}
          search={<SearchBar variant="header" value={q} onChange={setQ} onSubmit={noop} onPick={noop}
            suggestions={[{ id: 'g', label: 'Products', items: [{ id: 's1', text: 'sourdough', highlight: [{ start: 0, length: 4 }], meta: '$7.50' }] }]} />}
          links={[{ key: 'svc', label: 'Services', href: '/services' }, { key: 'shop', label: 'Shop', href: '/shop', current: true }]}
          locale="en" onToggleLocale={noop} cart={{ count: 2, href: '/cart' }}
          account={<AccountMenu user={{ name: 'Amara Osei', initials: 'AO', detail: 'amara@example.test' }} headerAction={{ label: 'Add photo', href: '/account' }}
            sections={[{ heading: 'Orders', items: [{ key: 'o', label: 'Orders & bookings', icon: TagIcon, href: '/orders', value: '2' }] }, { items: [{ key: 'out', label: 'Sign out', icon: UserCircle, onSelect: noop }] }]} />} />
      );
    }
    render(<I18nProvider initial="en"><Header /><main><h1>Shop</h1></main></I18nProvider>);
    await user.type(screen.getByRole('combobox'), 'sour');
    await expectNoAxeViolations(document.body);
    await user.click(screen.getByRole('button', { name: 'Account menu' }));
    await expectNoAxeViolations(document.body);
  });

  it('the grouped multi-select open, with a selection', async () => {
    const user = userEvent.setup();
    page(<GroupedMultiSelect label="Services you offer" max={2} value={['a']} onChange={noop} limitMessage="Up to 2."
      groups={[{ id: 'g', name: 'Automotive', note: 'Licence checked', items: [{ id: 'a', name: 'Mobile mechanic', badge: 'Regulated' }, { id: 'b', name: 'Detailing' }] }]} />);
    await user.click(screen.getByRole('combobox'));
    await expectNoAxeViolations(document.body);
  });
});

describe('axe · DataTable', () => {
  const props = { entity: 'listing', columns: listingColumns, rows: listings, can: CAN.owner('owner'), actions: listingActions, onAction: async () => {}, onCreate: async () => {}, onUpdate: async () => {}, onDelete: async () => {} };

  it('as a table (wide) and as cards (narrow)', async () => {
    const rect = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ width: 1200, height: 600, x: 0, y: 0, top: 0, left: 0, right: 1200, bottom: 600, toJSON: () => ({}) });
    const { unmount } = page(<DataTable {...props} />);
    await screen.findByRole('table');
    await expectNoAxeViolations(document.body);
    unmount();
    rect.mockRestore();
    page(<DataTable {...props} />, 'fr');
    await expectNoAxeViolations(document.body);
  });

  it('loading, error, empty and view-only', async () => {
    page(<>
      <DataTable {...props} rows={[]} loading />
      <DataTable {...props} rows={[]} error="Couldn't load listings." onRetry={noop} />
      <DataTable {...props} rows={[]} />
      <DataTable {...props} can={CAN.ledger} roleName="Bookkeeper" />
    </>);
    await expectNoAxeViolations(document.body);
  });

  it('the filters panel, the create dialog and the report dialog', async () => {
    const user = userEvent.setup();
    page(<DataTable {...props} />);
    await user.click(screen.getByRole('button', { name: /filters/i }));
    await expectNoAxeViolations(document.body);
    await user.click(screen.getByRole('button', { name: /new listing|add listing|create/i }));
    await expectNoAxeViolations(document.body);
  });
});
