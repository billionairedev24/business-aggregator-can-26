import { createFileRoute, redirect } from '@tanstack/react-router';
import { z } from 'zod';

/**
 * The old Location page (S-47) is now a dialog over the page the person is on (consumer location, 2026-10-19): this
 * route redirects to `next` (a local path; else home) with `#location`, which opens the dialog there. S-180: a `?lang=`
 * goes along (`/location?lang=fr` opened the dialog over an English page).
 */
export const Route = createFileRoute('/location')({
  validateSearch: z.object({ next: z.string().optional().catch(undefined), lang: z.enum(['en', 'fr']).optional().catch(undefined) }),
  beforeLoad: ({ search }) => {
    const next = (search.next && search.next.startsWith('/') && !search.next.startsWith('//') ? search.next : '/').split('#')[0]!;
    const lang = search.lang && !/[?&]lang=/.test(next) ? `${next.includes('?') ? '&' : '?'}lang=${search.lang}` : '';
    throw redirect({ href: `${next}${lang}#location`, replace: true });
  },
});
