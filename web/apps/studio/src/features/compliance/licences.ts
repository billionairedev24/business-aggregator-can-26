import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { http } from '../../lib/http';

/**
 * Age-restricted sales (2026-10-04): the business's licences for alcohol, tobacco/vape and cannabis accessories —
 * `GET|POST /api/v1/merchants/{id}/restricted-licences`. A class's listings and dishes go live only while an approved,
 * unexpired licence for it in the business's province is on file; trust & safety review every one.
 */
export const AGE_CLASSES = ['alcohol', 'tobacco', 'cannabis'] as const;
export type AgeClass = (typeof AGE_CLASSES)[number];

export const Licence = z.object({
  id: z.string(), ageClass: z.enum(AGE_CLASSES), province: z.string(), licenceNumber: z.string(), expiresOn: z.string(),
  status: z.enum(['pending', 'approved', 'rejected', 'expired', 'replaced']), submittedAt: z.string(), decidedAt: z.string().nullish(),
  rejectReason: z.string().nullish(), note: z.string().nullish(),
});
export type Licence = z.infer<typeof Licence>;
export const Licences = z.object({ licensed: z.array(z.enum(AGE_CLASSES)), items: z.array(Licence) });
export type Licences = z.infer<typeof Licences>;

const base = (m: string) => `/api/v1/merchants/${encodeURIComponent(m)}/restricted-licences`;

export const licencesQuery = (m: string) => queryOptions({
  queryKey: ['merchant', m, 'restricted-licences'],
  queryFn: () => http(base(m), {}, Licences),
});

export function useSubmitLicence(m: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { ageClass: string; licenceNumber: string; expiresOn: string; file: File | null }) => {
      const form = new FormData();
      form.append('ageClass', v.ageClass);
      form.append('licenceNumber', v.licenceNumber);
      form.append('expiresOn', v.expiresOn);
      if (v.file) form.append('file', v.file);
      return http(base(m), { method: 'POST', body: form }, Licence);
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['merchant', m, 'restricted-licences'] }),
  });
}
