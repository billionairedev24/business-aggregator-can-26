import { redirect } from '@tanstack/react-router';
import type { QueryClient } from '@tanstack/react-query';
import { sessionQuery } from '../../lib/session';

/** beforeLoad guard: signed-out users go to /sign-in and come back to where they were. */
export async function requireSession(queryClient: QueryClient, href: string) {
  const session = await queryClient.ensureQueryData(sessionQuery);
  if (!session) throw redirect({ to: '/sign-in', search: { next: href } as never });
  return session;
}
