import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, fn, userEvent, within } from 'storybook/test';
import { FailureScreen } from './FailureScreen';

const meta = {
  title: 'States/FailureScreen',
  component: FailureScreen,
  args: { kind: 'error', onRetry: fn(), supportHref: 'mailto:support@example.ca' },
  decorators: [Story => <main><Story /></main>],
} satisfies Meta<typeof FailureScreen>;
export default meta;
type S = StoryObj<typeof meta>;

/** A bug on our side: Try again re-runs what failed. */
export const ServerError: S = {
  play: async ({ canvasElement, args }) => {
    await userEvent.click(within(canvasElement).getByRole('button', { name: 'Try again' }));
    await expect(args.onRetry).toHaveBeenCalled();
  },
};
/** The BFF or the api is down: tried again on its own (2, 4, 8, 16, 30 s), the countdown announced. */
export const Unreachable: S = {
  args: { kind: 'unreachable', autoRetry: true, title: 'We can’t reach the shop right now.' },
  play: async ({ canvasElement }) => {
    await expect(await within(canvasElement).findByRole('status')).toHaveTextContent('Trying again in 2 seconds');
  },
};
/** A deploy removed the code this page was loading. */
export const Updated: S = { args: { kind: 'updated' } };
export const NotFound: S = { args: { kind: 'notFound' } };
export const Forbidden: S = { args: { kind: 'forbidden' } };
/** Development builds add the error behind it (never in production). */
export const WithDetail: S = { args: { detail: { name: 'TypeError', message: 'fetch failed', stack: 'TypeError: fetch failed\n    at http (http.ts:120:15)' } } };
