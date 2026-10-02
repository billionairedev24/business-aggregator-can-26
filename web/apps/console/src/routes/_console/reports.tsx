import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { Reports } from '../../features/reports/Reports';

/** Reports & analytics (S-95): every province, or one of the region model's. */
export const Route = createFileRoute('/_console/reports')({
  validateSearch: z.object({ province: z.string().optional() }),
  component: Reports,
});
