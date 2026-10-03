import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, fn, userEvent, within } from 'storybook/test';
import { PilotFeedback } from './PilotFeedback';

const meta = {
  title: 'Pilot/PilotFeedback',
  component: PilotFeedback,
  args: { onSubmit: fn(async () => ({ reference: 'UAT-1042' })), placement: 'inline' },
} satisfies Meta<typeof PilotFeedback>;
export default meta;
type S = StoryObj<typeof meta>;

/** The control a pilot participant sees (inline here; apps put it in the corner). */
export const Closed: S = {};

/** Open, then sent: the reference to follow it by. */
export const Sent: S = {
  play: async ({ canvasElement, args }) => {
    const page = within(canvasElement.ownerDocument.body);
    await userEvent.click(within(canvasElement).getByRole('button', { name: 'Send feedback' }));
    await userEvent.type(page.getByLabelText('What happened?'), 'The Pay button stays grey.');
    await userEvent.click(page.getByRole('button', { name: 'Send' }));
    await expect(args.onSubmit).toHaveBeenCalled();
    await expect(await page.findByText(/UAT-1042/)).toBeInTheDocument();
  },
};

/** The note is required. */
export const Empty: S = {
  play: async ({ canvasElement }) => {
    const page = within(canvasElement.ownerDocument.body);
    await userEvent.click(within(canvasElement).getByRole('button', { name: 'Send feedback' }));
    await userEvent.click(page.getByRole('button', { name: 'Send' }));
    await expect(await page.findByRole('alert')).toHaveTextContent('Tell us what happened');
  },
};

/** The server refused (shown as it said, in the person's language). */
export const Failed: S = {
  args: { onSubmit: fn(async () => { throw new Error('Feedback here is for pilot participants.'); }) },
  play: async ({ canvasElement }) => {
    const page = within(canvasElement.ownerDocument.body);
    await userEvent.click(within(canvasElement).getByRole('button', { name: 'Send feedback' }));
    await userEvent.type(page.getByLabelText('What happened?'), 'x');
    await userEvent.click(page.getByRole('button', { name: 'Send' }));
    await expect(await page.findByRole('alert')).toHaveTextContent('pilot participants');
  },
};
