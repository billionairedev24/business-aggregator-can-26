import { createFileRoute, redirect } from '@tanstack/react-router';
import { z } from 'zod';

/**
 * The old Location page (S-47) is now a dialog over the page the person is on (consumer location, 2026-10-19): this
 * route redirects to `next` (a local path; else home) with `#location`, which opens the dialog there.
 */
export const Route = createFileRoute('/location')({
  validateSearch: z.object({ next: z.string().optional().catch(undefined) }),
  beforeLoad: ({ search }) => {
    const next = search.next && search.next.startsWith('/') && !search.next.startsWith('//') ? search.next : '/';
    throw redirect({ href: `${next.split('#')[0]}#location`, replace: true });
  },
});
