/** S-109: keyboard, focus and announcement behaviour of the shared components (what axe cannot see). */
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Bank, House } from '@phosphor-icons/react';
import { AppShell, Button, ChipTabs, DataTable, Dialog, I18nProvider, LineChart, Menu, Meter, SiteHeader, StackedBarChart, UnderlineTabs, type Locale } from './index';
import { CAN, listingColumns, listings } from './DataTable/DataTable.fixtures';

const wrap = (ui: React.ReactNode, locale: Locale = 'en') => render(<I18nProvider initial={locale}>{ui}</I18nProvider>);
const narrowScreen = () => vi.spyOn(window, 'matchMedia').mockImplementation(q => ({ matches: true, media: q, onchange: null, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {}, dispatchEvent: () => false }) as MediaQueryList);

function DialogDemo({ withActions }: { withActions: boolean }) {
  const [open, setOpen] = useState(false);
  return <>
    <button type="button" onClick={() => setOpen(true)}>Open</button>
    <Dialog open={open} onClose={() => setOpen(false)} title="Notice" actions={withActions ? <><Button variant="secondary" onClick={() => setOpen(false)}>Cancel</Button><Button>Confirm</Button></> : undefined}>
      Read-only text.
    </Dialog>
  </>;
}

describe('Dialog focus', () => {
  it('moves focus in, keeps Tab inside and returns focus to the trigger on Escape', async () => {
    const user = userEvent.setup();
    wrap(<DialogDemo withActions />);
    await user.click(screen.getByRole('button', { name: 'Open' }));
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Confirm' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await user.tab({ shift: true });
    expect(screen.getByRole('button', { name: 'Confirm' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('button', { name: 'Open' })).toHaveFocus();
  });

  it('focuses a dialog with nothing focusable inside, so Tab cannot reach the page behind', async () => {
    const user = userEvent.setup();
    wrap(<DialogDemo withActions={false} />);
    await user.click(screen.getByRole('button', { name: 'Open' }));
    expect(screen.getByRole('dialog', { name: 'Notice' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('dialog', { name: 'Notice' })).toHaveFocus();
  });
});

describe('Menu keys', () => {
  it('Home/End move to the ends and Tab closes the menu', async () => {
    const user = userEvent.setup();
    wrap(<><Menu label="Business" trigger={({ props }) => <button type="button" {...props}>Account</button>}
      items={[{ label: 'One', onSelect: () => {} }, { label: 'Two', onSelect: () => {} }, { label: 'Three', onSelect: () => {} }]} /><button type="button">After</button></>);
    await user.click(screen.getByRole('button', { name: 'Account' }));
    expect(screen.getByRole('menuitem', { name: 'One' })).toHaveFocus();
    await user.keyboard('{End}');
    expect(screen.getByRole('menuitem', { name: 'Three' })).toHaveFocus();
    await user.keyboard('{Home}');
    expect(screen.getByRole('menuitem', { name: 'One' })).toHaveFocus();
    await user.tab();
    expect(screen.queryByRole('menu')).toBeNull();
  });
});

const shell = (
  <AppShell brand={<span>Northline</span>} pinned={[{ key: 'home', label: 'Dashboard', icon: House, href: '/' }]}
    groups={[{ label: 'Finance', icon: Bank, items: [{ key: 'payouts', label: 'Payouts', icon: Bank, href: '/payouts' }] }]} currentKey="home" onNavigate={() => {}}>
    <h1>Dashboard</h1>
  </AppShell>
);

describe('AppShell', () => {
  it('starts with a skip link to the focusable main region and publishes the top bar height as scroll padding', async () => {
    const user = userEvent.setup();
    wrap(shell);
    await user.tab();
    const skip = screen.getByRole('link', { name: 'Skip to content' });
    expect(skip).toHaveFocus();
    expect(skip).toHaveAttribute('href', '#main');
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main');
    expect(screen.getByRole('main')).toHaveAttribute('tabindex', '-1');
    expect(document.documentElement.style.getPropertyValue('--nl-sticky-top')).toMatch(/px$/);
  });

  it('says "Passer au contenu" in French', () => {
    wrap(shell, 'fr');
    expect(screen.getByRole('link', { name: 'Passer au contenu' })).toBeInTheDocument();
  });

  it('below 900 px the menu is a modal sheet: focus in, Tab kept inside, Escape returns focus to the menu button', async () => {
    const mm = narrowScreen();
    const user = userEvent.setup();
    wrap(shell);
    const opener = screen.getByRole('button', { name: 'Open menu' });
    await user.click(opener);
    const sheet = screen.getByRole('dialog', { name: 'Main navigation' });
    expect(within(sheet).getByRole('button', { name: 'Close menu' })).toHaveFocus();
    for (let i = 0; i < 6; i++) { await user.tab(); expect(sheet.contains(document.activeElement)).toBe(true); }
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(opener).toHaveFocus();
    mm.mockRestore();
  });
});

describe('Meter and charts', () => {
  it('a meter is named by its visible label whatever its markup, and reads its displayed value', () => {
    wrap(<Meter label={<strong>Dispute rate</strong>} value={91} display="0.9%" />);
    const meter = screen.getByRole('meter', { name: 'Dispute rate' });
    expect(meter).toHaveAttribute('aria-valuetext', '0.9%');
  });

  it('charts carry their numbers as a table for screen readers', () => {
    wrap(<>
      <StackedBarChart title="Net earnings" format={n => `$${n}`} series={[{ key: 'a', label: 'Services', color: 'var(--color-accent)' }, { key: 'b', label: 'Tips', color: 'var(--color-highlight)' }]}
        data={[{ label: 'W1', values: { a: 400, b: 20 } }]} />
      <LineChart title="Weekly gross" current={[3, 5]} previous={[2, 4]} labels={['Mon', 'Tue']} />
    </>);
    const bars = screen.getByRole('table', { name: 'Net earnings' });
    expect(within(bars).getByRole('rowheader', { name: 'W1' })).toBeInTheDocument();
    expect(within(bars).getAllByRole('cell').map(c => c.textContent)).toEqual(['$400', '$20']);
    const line = screen.getByRole('table', { name: 'Weekly gross' });
    expect(within(line).getAllByRole('columnheader').map(c => c.textContent)).toEqual(['Point', 'This period', 'Previous period']);
    expect(within(line).getAllByRole('cell').map(c => c.textContent)).toEqual(['3', '2', '5', '4']);
  });
});

describe('language', () => {
  it('sets <html lang> from the first render (fr-CA), not only after a switch', () => {
    document.documentElement.lang = 'en-CA';
    wrap(<p>Bonjour</p>, 'fr');
    expect(document.documentElement.lang).toBe('fr-CA');
  });

  it('the header language button is named from its visible text and marks the other language up', () => {
    wrap(<SiteHeader location={null} links={[]} locale="en" onToggleLocale={() => {}} cart={{ count: 0, href: '/cart' }} account={null} />);
    const button = screen.getByRole('button', { name: 'EN — Switch language: Français' });
    expect(button.querySelector('[lang="fr-CA"]')?.textContent).toBe('Français');
  });
});

describe('tabs', () => {
  function Tabs({ kind }: { kind: 'chips' | 'underline' }) {
    const [v, setV] = useState<'a' | 'b' | 'c'>('a');
    const options = [{ value: 'a' as const, label: 'Business' }, { value: 'b' as const, label: 'Team' }, { value: 'c' as const, label: 'Security' }];
    return kind === 'chips' ? <ChipTabs aria-label="Settings" options={options} value={v} onChange={setV} /> : <UnderlineTabs aria-label="Settings" options={options} value={v} onChange={setV} />;
  }
  it.each(['chips', 'underline'] as const)('%s: one tab stop, arrows and Home/End move and select', async kind => {
    const user = userEvent.setup();
    wrap(<Tabs kind={kind} />);
    await user.tab();
    expect(screen.getByRole('tab', { name: 'Business' })).toHaveFocus();
    expect(screen.getByRole('tab', { name: 'Team' })).toHaveAttribute('tabindex', '-1');
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('tab', { name: 'Team' })).toHaveFocus();
    expect(screen.getByRole('tab', { name: 'Team' })).toHaveAttribute('aria-selected', 'true');
    await user.keyboard('{End}');
    expect(screen.getByRole('tab', { name: 'Security' })).toHaveAttribute('aria-selected', 'true');
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('tab', { name: 'Business' })).toHaveFocus();
  });
});

describe('DataTable announcements', () => {
  it('says what a sort did and how many rows a search leaves', async () => {
    const user = userEvent.setup();
    const rect = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ width: 1200, height: 600, x: 0, y: 0, top: 0, left: 0, right: 1200, bottom: 600, toJSON: () => ({}) });
    wrap(<DataTable entity="listing" columns={listingColumns} rows={listings} can={CAN.ledger} />);
    const table = await screen.findByRole('table');
    const status = () => screen.getAllByRole('status').map(s => s.textContent ?? '').join(' | ');
    const firstSortable = within(table).getAllByRole('columnheader')[1]!;
    const label = within(firstSortable).getByRole('button').textContent!.trim();
    await user.click(within(firstSortable).getByRole('button'));
    expect(status()).toContain(`Sorted by ${label}, ascending`);
    await user.click(within(firstSortable).getByRole('button'));
    expect(status()).toContain(`Sorted by ${label}, descending`);
    await user.type(screen.getByRole('searchbox', { name: 'Search table' }), 'zzzz-no-match');
    await waitFor(() => expect(status()).toContain('No matching listings'));
    rect.mockRestore();
  });
});
