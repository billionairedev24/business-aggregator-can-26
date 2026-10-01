import { redirect } from '@tanstack/react-router';
import type { QueryClient } from '@tanstack/react-query';
import { sessionQuery } from '../../lib/session';
import { meQuery } from './api';
import { applyRole, initialRole } from './roleView';

/** beforeLoad of every console screen: signed-out people go to /sign-in and come back to where they were. */
export async function requireSession(queryClient: QueryClient, href: string) {
  const session = await queryClient.ensureQueryData(sessionQuery);
  if (!session) throw redirect({ to: '/sign-in', search: { next: href } as never });
  return session;
}

/** The staff member's roles, and the role view the console acts with (before any screen asks the api for data). */
export async function loadStaff(queryClient: QueryClient) {
  const me = await queryClient.ensureQueryData(meQuery);
  applyRole(initialRole(me.roles));
  return me;
}
