import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { VettingScreen } from '../../features/vetting/VettingScreen';

/** Listing vetting (S-92): province and market of the region model; licences and age checks (2026-10-04). */
export const Route = createFileRoute('/_console/vetting')({
  validateSearch: z.object({ province: z.string().optional(), market: z.string().optional(), view: z.enum(['listings', 'licences', 'age']).optional() }),
  component: VettingScreen,
});
