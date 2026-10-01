import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { Switchboard } from '../../features/regions/Switchboard';

/** The province switchboard (S-84): `?province=<code>`. Admins only. */
export const Route = createFileRoute('/_console/provinces')({
  validateSearch: z.object({ province: z.string().optional() }),
  component: Switchboard,
});
