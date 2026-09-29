import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState, type ReactNode } from 'react';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import { I18nProvider } from '../i18n';
import { DataTable } from './DataTable';
import {
  CAN,
  earningColumns,
  earnings,
  helpCaseColumns,
  helpCases,
  listingActions,
  listingActionsFr,
  listingColumns,
  listingColumnsFr,
  listings,
  listingsFr,
  orderActions,
  orderColumns,
  orders,
  orderTone,
  payoutColumns,
  payouts,
  taxColumns,
  taxRows,
  team,
  teamColumns,
  type Listing,
} from './DataTable.fixtures';
import type { DataTableAction, DataTableProps } from './types';

const LATENCY = 450;
const wait = () => new Promise((r) => setTimeout(r, LATENCY));

type LiveProps<T extends { id: string }> = Omit<DataTableProps<T>, 'rows' | 'onCreate' | 'onUpdate' | 'onDelete' | 'onAction'> & {
  initialRows: T[];
  /** How a custom action changes a row (the story's stand-in for the server). */
  applyAction?: (a: DataTableAction<T>, row: T) => T;
  crud?: boolean;
};

/** Holds rows in story state and fakes a server with latency — the table itself never persists. */
function Live<T extends { id: string }>({ initialRows, applyAction, crud = true, ...rest }: LiveProps<T>) {
  const [rows, setRows] = useState(initialRows);
  return (
    <DataTable<T>
      {...rest}
      rows={rows}
      onCreate={
        crud
          ? async (v) => {
              await wait();
              setRows((rs) => [{ ...(v as T), id: `n${Date.now()}` }, ...rs]);
            }
          : undefined
      }
      onUpdate={
        crud
          ? async (row, v) => {
              await wait();
              setRows((rs) => rs.map((r) => (r.id === row.id ? { ...r, ...v } : r)));
            }
          : undefined
      }
      onDelete={
        crud
          ? async (del) => {
              await wait();
              const ids = new Set(del.map((d) => d.id));
              setRows((rs) => rs.filter((r) => !ids.has(r.id)));
            }
          : undefined
      }
      onAction={
        applyAction
          ? async (a, hit) => {
              await wait();
              const ids = new Set(hit.map((h) => h.id));
              setRows((rs) => rs.map((r) => (ids.has(r.id) ? applyAction(a, r) : r)));
            }
          : undefined
      }
    />
  );
}

const Frame = ({ children, width }: { children: ReactNode; width?: number }) => <div style={{ maxWidth: width ?? 1040 }}>{children}</div>;

const listingAction = (a: DataTableAction<Listing>, r: Listing): Listing => ({ ...r, live: a.id === 'pub' ? 'Live' : 'Hidden' });
const listingActionFr = (a: DataTableAction<Listing>, r: Listing): Listing => ({ ...r, live: a.id === 'pub' ? 'En ligne' : 'Masquée' });
const products = (width?: number) => (
  <Frame width={width}>
    <Live
      entity="listing"
      roleName="Owner"
      can={CAN.record('owner')}
      columns={listingColumns}
      initialRows={listings}
      rowTones={(r) => ({ vet: r.vetTone })}
      actions={listingActions}
      applyAction={listingAction}
      createLabel="New listing"
      searchPlaceholder="Search by name or SKU…"
    />
  </Frame>
);

const meta = {
  title: 'Data/DataTable',
  parameters: {
    docs: {
      description: {
        component:
          'The shared record list (design/Data Table.dc.html). Search, facets with counts, ranges, multi-sort (Shift-click), selection across pages, column visibility, pagination, CSV/Excel/PDF reports, role-gated CRUD and custom actions. Mutations are async callbacks; the table shows pending and error states. Resize the container to see columns auto-hide by priority and the card list below 600px.',
      },
    },
  },
} satisfies Meta;
export default meta;
type Story = StoryObj<typeof meta>;

/** Provider Studio · Catalogue — owner can create, edit, delete, publish/hide. */
export const Products: Story = {
  render: () => products(),
  play: async ({ canvasElement, step }) => {
    const c = within(canvasElement);
    await step('facet filter with counts', async () => {
      await userEvent.click(c.getByRole('button', { name: /^Filters/ }));
      await userEvent.click(c.getByRole('button', { name: /^Product\s*4$/ }));
      await expect(c.getByRole('button', { name: 'Remove filter Type: Product' })).toBeVisible();
      await expect(c.getByText(/1–4 of 4 listings/)).toBeVisible();
    });
    await step('sort by price, then add a second sort', async () => {
      const price = c.getByRole('columnheader', { name: /Price/ });
      await userEvent.click(within(price).getByRole('button'));
      await expect(price).toHaveAttribute('aria-sort', 'ascending');
      await userEvent.click(within(price).getByRole('button'));
      await expect(price).toHaveAttribute('aria-sort', 'descending');
    });
    await step('select a row → bulk bar', async () => {
      await userEvent.click(c.getByRole('checkbox', { name: 'Select Brake pads · ceramic (front)' }));
      await expect(c.getByRole('region', { name: '1 selected' })).toBeVisible();
      await userEvent.click(c.getByRole('button', { name: 'Clear selection' }));
      await userEvent.click(c.getByRole('button', { name: 'Clear all' }));
    });
  },
};

/** Finance · Earnings for a bookkeeper: view + export only. */
export const LedgerViewOnly: Story = {
  render: () => (
    <Frame>
      <DataTable entity="ledger entry" plural="ledger entries" roleName="Bookkeeper" can={CAN.ledger} columns={earningColumns} rows={earnings} rowTones={(r) => ({ state: r.tone })} />
    </Frame>
  ),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await expect(c.getByText('View only · Bookkeeper')).toBeVisible();
    await expect(c.queryByRole('button', { name: /New/ })).toBeNull();
    await expect(c.queryByRole('button', { name: /^Edit/ })).toBeNull();
    await userEvent.click(c.getByRole('button', { name: /^Filters/ }));
    await userEvent.type(c.getByRole('spinbutton', { name: 'Net minimum' }), '100');
    await expect(c.getByText(/1–3 of 3 ledger entries/)).toBeVisible();
  },
};

/** Settings · Team & roles — only the owner may change members. */
export const TeamOwnerOnly: Story = {
  render: () => (
    <Frame width={760}>
      <Live entity="team member" roleName="Owner" can={CAN.owner('owner')} columns={teamColumns} initialRows={team} />
    </Frame>
  ),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await userEvent.click(c.getByRole('button', { name: 'New team member' }));
    const dialog = await within(document.body).findByRole('dialog', { name: 'New team member' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create team member' }));
    await expect(within(dialog).getByText('Member is required.')).toBeVisible();
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Member/ }), 'Alex Chen');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create team member' }));
    await waitFor(() => expect(c.getByText('Alex Chen')).toBeVisible(), { timeout: 3000 });
  },
};

/** The same team table for a technician: no mutating controls. */
export const TeamAsTechnician: Story = {
  render: () => (
    <Frame width={760}>
      <DataTable entity="team member" roleName="Technician" can={CAN.owner('technician')} columns={teamColumns} rows={team} />
    </Frame>
  ),
};

/** Seller orders — inline "Mark packed" action guarded by status. */
export const OrdersInlineAction: Story = {
  render: () => (
    <Frame>
      <Live
        entity="order"
        roleName="Owner"
        can={{ create: false, update: true, delete: false, export: true }}
        columns={orderColumns}
        initialRows={orders}
        rowTones={orderTone}
        actions={orderActions}
        applyAction={(_a, r) => ({ ...r, state: 'Awaiting pickup' })}
        crud={false}
      />
    </Frame>
  ),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await userEvent.click(c.getByRole('button', { name: 'Mark packed · NL-48213' }));
    await waitFor(() => expect(within(c.getByRole('row', { name: /NL-48213/ })).getByText('Awaiting pickup')).toBeVisible(), { timeout: 3000 });
  },
};

export const Loading: Story = {
  render: () => (
    <Frame>
      <DataTable entity="listing" roleName="Owner" can={CAN.record('owner')} columns={listingColumns} rows={[]} loading pageSize={5} onCreate={async () => {}} />
    </Frame>
  ),
};

export const Empty: Story = {
  render: () => (
    <Frame width={760}>
      <Live entity="combo" roleName="Owner" can={CAN.record('owner')} columns={[{ key: 'name', label: 'Combo', sub: 'rule', subLabel: 'Rule', required: true }, { key: 'price', label: 'Price', type: 'money' }, { key: 'state', label: 'Status', type: 'tag', options: ['Live', 'Scheduled', 'Paused'] }]} initialRows={[] as { id: string; name: string; rule: string; price: number; state: string }[]} emptyText="No combos yet — bundle mains, sides and drinks." />
    </Frame>
  ),
};

export const LoadError: Story = {
  render: () => (
    <Frame>
      <DataTable entity="payout" roleName="Owner" can={CAN.ledger} columns={payoutColumns} rows={[]} error="The payouts service did not respond." onRetry={() => {}} />
    </Frame>
  ),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await expect(c.getByRole('alert')).toHaveTextContent('Couldn’t load payouts.');
    await expect(c.getByRole('button', { name: 'Retry' })).toBeEnabled();
  },
};

/** A mutation that fails keeps the dialog open with the server's message. */
export const MutationError: Story = {
  render: () => (
    <Frame width={900}>
      <DataTable
        entity="case"
        roleName="Owner"
        can={{ create: true, update: true, delete: false, export: true }}
        columns={helpCaseColumns}
        rows={helpCases}
        rowTones={(r) => ({ meta: r.tone })}
        createLabel="Open a case"
        onCreate={async () => {
          await wait();
          throw new Error('Support is offline for maintenance. Try again in a few minutes.');
        }}
        onUpdate={async () => {}}
      />
    </Frame>
  ),
};

/** Container under 600px: the table becomes a card list (no horizontal scroll). */
export const NarrowContainer: Story = {
  render: () => products(360),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await expect(c.getByRole('list', { name: 'Listings' })).toBeVisible();
    await expect(c.queryByRole('table')).toBeNull();
  },
};

/** Mid-width container: lowest-priority columns auto-hide to fit. */
export const MidWidthAutoHide: Story = {
  render: () => products(720),
};

export const French: Story = {
  render: () => (
    <I18nProvider initial="fr">
      <Frame>
        <Live
          entity="annonce"
          plural="annonces"
          roleName="Propriétaire"
          can={CAN.record('owner')}
          columns={listingColumnsFr}
          initialRows={listingsFr}
          rowTones={(r) => ({ vet: r.vetTone })}
          actions={listingActionsFr}
          applyAction={listingActionFr}
          createLabel="Nouvelle annonce"
          searchPlaceholder="Rechercher par nom ou UGS…"
        />
      </Frame>
    </I18nProvider>
  ),
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await expect(c.getByRole('button', { name: /^Filtres/ })).toBeVisible();
    await expect(c.getByText(/1–9 sur 9 annonces/)).toBeVisible();
  },
};

/** Remaining Studio call sites, compact: payouts, tax (ledger kind). */
export const PayoutsAndTax: Story = {
  render: () => (
    <div style={{ display: 'grid', gap: 32, maxWidth: 900 }}>
      <DataTable entity="payout" roleName="Owner" can={CAN.ledger} columns={payoutColumns} rows={payouts} aria-label="Payout history" />
      <DataTable entity="jurisdiction" roleName="Owner" can={CAN.ledger} columns={taxColumns} rows={taxRows} aria-label="Tax collected" pageSize={5} />
    </div>
  ),
};
