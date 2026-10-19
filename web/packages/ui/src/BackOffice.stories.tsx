import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, fn, userEvent, within } from 'storybook/test';
import { IdentificationCard, Scales, Truck } from '@phosphor-icons/react';
import { BoHeader, ChartCard, FactList, KpiStrip, SheetSection, TodoCard } from './BackOffice';
import { Button } from './Button';
import { StackedBarChart } from './Charts';

/** The back-office blocks (redesign § 1.3, BO:263–280, BO:811) the Studio and the console share. */
const meta = { title: 'Back office/Blocks', parameters: { layout: 'padded' } } satisfies Meta;
export default meta;
type S = StoryObj<typeof meta>;

const open = fn();

export const PageHead: S = {
  render: () => <BoHeader title="Overview" sub="Tuesday 8 September · live" actions={<Button variant="secondary">Export</Button>} />,
};

export const Kpis: S = {
  render: () => (
    <KpiStrip label="This week" items={[
      { key: 'gmv', label: 'GMV · week', value: '$212k', delta: '+9% vs last week', trend: 'up' },
      { key: 'rev', label: 'Net revenue', value: '$24.1k', delta: '11.4% blended take' },
      { key: 'disp', label: 'Dispute rate', value: '0.9%', delta: '+0.2 pts', trend: 'down', onClick: open },
    ]} />
  ),
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByRole('button', { name: /Dispute rate/ }));
    await expect(open).toHaveBeenCalled();
  },
};

export const Cards: S = {
  render: () => (
    <div className="bo-grid">
      <ChartCard id="sb-gmv" title="GMV · 12 weeks">
        <StackedBarChart title="GMV · 12 weeks" series={[{ key: 'a', label: 'Goods', color: 'var(--color-action-primary)' }, { key: 'b', label: 'Services', color: 'var(--color-action-accent)' }]}
          data={[1, 2, 3, 4].map(i => ({ label: `W${i}`, values: { a: i * 10, b: 40 - i * 5 } }))} />
      </ChartCard>
      <TodoCard id="sb-todo" title="To do" empty="Nothing waits." items={[
        { key: 'a', icon: IdentificationCard, text: 'Applications to review', meta: '7', onClick: open },
        { key: 'b', icon: Scales, text: 'Disputes for an agent', meta: '3', onClick: open },
        { key: 'c', icon: Truck, text: 'Stuck delivery runs', meta: '1', onClick: open },
      ]} />
      <TodoCard id="sb-empty" title="Needs you" empty="Nothing waits for you." items={[]} />
    </div>
  ),
};

export const SheetFacts: S = {
  render: () => (
    <div style={{ maxWidth: 680 }}>
      <SheetSection title="Business">
        <FactList rows={[['Legal name', 'Prairie Plumbing Ltd.'], ['Type', 'Provider'], null, ['Submitted', '3 days ago']]} />
      </SheetSection>
    </div>
  ),
};
