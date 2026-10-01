import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { DeliveryOps } from '../../features/delivery/DeliveryOps';

/** Delivery ops (S-81): `?market=<region market id>`. */
export const Route = createFileRoute('/_console/delivery')({
  validateSearch: z.object({ market: z.string().optional() }),
  component: DeliveryOps,
});
