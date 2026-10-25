import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { I18nProvider } from '../i18n';
import { DataTable } from './DataTable';
import type { DataTableColumn } from './types';
import { FormSection, SaveBar } from '../Editor';

/** S-175: the work-table options — status tabs, quick filters, status cells, emphasised actions, compact phone cards. */
interface Row { id: string; ref: string; who: string; total: number; state: string; code: 'pack' | 'done' | 'issue' }
const rows: Row[] = [
  { id: '1', ref: 'NL-1', who: 'A. Osei', total: 2625, state: 'To pack', code: 'pack' },
  { id: '2', ref: 'NL-2', who: 'K. Nguyen', total: 1550, state: 'To pack', code: 'pack' },
  { id: '3', ref: 'NL-3', who: 'M. Tran', total: 925, state: 'Wrong item', code: 'issue' },
  { id: '4', ref: 'NL-4', who: 'P. Singh', total: 775, state: 'Delivered', code: 'done' },
];
const columns: DataTableColumn<Row>[] = [
  { key: 'ref', label: 'Order', primary: true },
  { key: 'who', label: 'Customer', card: 'line' },
  { key: 'total', label: 'Total', type: 'money', card: 'end', cardFormat: v => `Total ${String(v)}` },
  { key: 'state', label: 'Status', type: 'tag', status: r => (r.code === 'pack' ? 'warning' : r.code === 'issue' ? 'danger' : 'success') },
];

function table(extra: Partial<React.ComponentProps<typeof DataTable<Row>>> = {}) {
  return render(
    <I18nProvider initial="en">
      <DataTable<Row> entity="order" columns={columns} rows={rows} can={{ update: true, export: false }}
        views={[{ id: 'all', label: 'All' }, { id: 'pack', label: 'To pack', when: r => r.code === 'pack' }, { id: 'issue', label: 'Issues', when: r => r.code === 'issue', tone: 'danger' }, { id: 'gone', label: 'Cancelled', when: () => false, hideEmpty: true }]}
        quickFilters={[{ id: 'big', label: 'Over $10', when: r => r.total > 1000 }]}
        actions={[{ id: 'pack', label: 'Mark packed', inline: true, emphasis: 'primary', when: { key: 'code', in: ['pack'] } }]}
        onAction={vi.fn()} {...extra} />
    </I18nProvider>,
  );
}

describe('DataTable work-table options (S-175)', () => {
  it('status tabs count every row, narrow the table, and hide an empty tab that asked to', async () => {
    const onViewChange = vi.fn();
    table({ onViewChange });
    const tabs = within(screen.getByRole('tablist', { name: 'Orders' }));
    expect(tabs.getAllByRole('tab').map(t => t.textContent)).toEqual(['All4', 'To pack2', 'Issues1']);
    expect(tabs.getByRole('tab', { name: /^Issues/ }).querySelector('[data-tone="danger"]')).not.toBeNull();
    await userEvent.click(tabs.getByRole('tab', { name: /^To pack/ }));
    expect(onViewChange).toHaveBeenCalledWith('pack');
    expect(screen.queryByText('NL-3')).toBeNull();
    expect(screen.getByText('NL-1')).toBeInTheDocument();
  });

  it('a quick filter applies as a removable chip; statuses draw as StatusTags; the row action is a primary button', async () => {
    table();
    await userEvent.click(screen.getByRole('button', { name: 'Over $10' }));
    expect(screen.queryByText('NL-3')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Remove filter: Over $10' }));
    expect(screen.getByText('NL-3')).toBeInTheDocument();
    expect(screen.getByText('Wrong item')).toHaveClass('nl-status', 'nl-status-danger');
    expect(screen.getAllByRole('button', { name: /^Mark packed/ })[0]).toHaveClass('btn-primary');
  });

  it('compact cards (jsdom has no width: cards) put card columns on the body and meta lines, in their card words', () => {
    table();
    const card = screen.getByText('NL-1').closest('li')!;
    expect(card).toHaveClass('nl-dt-card-compact');
    expect(within(card).getByText('A. Osei')).toHaveClass('nl-dt-cc-line');
    expect(within(card).getByText('Total 2625')).toHaveClass('nl-dt-cc-end');
  });

  it('a controlled view wins; an unknown one falls back to the first tab', () => {
    table({ view: 'nope' });
    expect(screen.getByRole('tab', { name: /^All/ })).toHaveAttribute('aria-selected', 'true');
  });
});

describe('editor parts (S-175)', () => {
  it('FormSection names its region by its title; SaveBar is a named region with the state and the buttons', () => {
    render(<>
      <FormSection title="Price and stock" description="What customers pay."><input aria-label="Price" /></FormSection>
      <SaveBar label="Save product" status={<span>Draft</span>} note="Unsaved changes"><button type="button">Save draft</button></SaveBar>
    </>);
    expect(screen.getByRole('region', { name: 'Price and stock' })).toBeInTheDocument();
    const bar = within(screen.getByRole('region', { name: 'Save product' }));
    expect(bar.getByText('Unsaved changes')).toBeInTheDocument();
    expect(bar.getByRole('button', { name: 'Save draft' })).toBeInTheDocument();
  });
});
