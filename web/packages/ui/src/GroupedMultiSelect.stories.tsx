import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { expect, userEvent, within } from 'storybook/test';
import { GroupedMultiSelect, type MultiSelectGroup } from './GroupedMultiSelect';
import { FileButton } from './FileButton';

const groups: MultiSelectGroup[] = [
  { id: 'service.automotive', name: 'Automotive', note: 'AMVIC licence checked', items: [
    { id: 'a1', name: 'Mobile mechanic', badge: 'AMVIC' }, { id: 'a2', name: 'Oil change & fluids', badge: 'AMVIC' }, { id: 'a3', name: 'Detailing' },
  ] },
  { id: 'service.pets', name: 'Pets', items: [{ id: 'p1', name: 'Dog walker' }, { id: 'p2', name: 'Pet sitter' }] },
];

function Demo({ initial = [], max = 3 }: { initial?: string[]; max?: number }) {
  const [value, setValue] = useState<string[]>(initial);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  return (
    <div style={{ maxWidth: 520, minHeight: 420 }}>
      <label htmlFor="cats" style={{ fontSize: 13 }}>Services you offer · {value.length + suggestions.length} of {max} selected</label>
      <GroupedMultiSelect
        id="cats"
        label="Services you offer"
        groups={groups}
        value={value}
        onChange={setValue}
        suggestions={suggestions}
        onSuggestionsChange={setSuggestions}
        max={max}
        placeholder="Search 100+ services — e.g. plumber, DJ, tutor…"
        limitMessage="Limit reached — remove one to add another."
        renderNoMatch={(q, suggest) => <>No match. {suggest ? <button type="button" className="btn btn-ghost" onClick={suggest}>Suggest “{q}”</button> : null}</>}
      />
    </div>
  );
}

const meta = { title: 'Forms/GroupedMultiSelect', component: GroupedMultiSelect, parameters: { layout: 'padded' } } satisfies Meta<typeof GroupedMultiSelect>;
export default meta;
type S = StoryObj<typeof meta>;

export const Empty: S = { args: {} as never, render: () => <Demo /> };
export const WithSelection: S = { args: {} as never, render: () => <Demo initial={['a1', 'p1']} /> };
export const AtLimit: S = {
  args: {} as never,
  render: () => <Demo initial={['a1', 'a2', 'p1']} />,
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await userEvent.click(c.getByRole('combobox'));
    await expect(c.getByRole('status')).toHaveTextContent('Limit reached');
    await expect(c.getByRole('option', { name: /Detailing/ })).toHaveAttribute('aria-disabled', 'true');
  },
};
export const SearchAndSuggest: S = {
  args: {} as never,
  render: () => <Demo />,
  play: async ({ canvasElement }) => {
    const c = within(canvasElement);
    await userEvent.type(c.getByRole('combobox'), 'bike repair');
    await userEvent.click(c.getByRole('button', { name: /Suggest/ }));
    await expect(c.getByText('bike repair')).toBeInTheDocument();
  },
};

export const Upload: S = {
  args: {} as never,
  render: () => <div style={{ display: 'flex', gap: 8 }}><FileButton accept="application/pdf" onFile={() => {}}>Upload · PDF</FileButton><FileButton onFile={() => {}} pending>Uploading…</FileButton></div>,
};
