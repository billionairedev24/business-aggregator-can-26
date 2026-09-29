import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, http } from '../../lib/http';
import { MerchantStatus, MerchantTier, MerchantType } from '../shell/api';
import { CTA_LABELS, SECTION_KINDS, type SectionKind } from './sections';

/** `GET /api/v1/merchants/{id}/storefront` — see DECISIONS.md › storefront API. */
export const Section = z.object({ id: z.string(), kind: z.enum(SECTION_KINDS), position: z.number(), enabled: z.boolean(), required: z.boolean(), settings: z.record(z.string(), z.unknown()).default({}) });
export type Section = z.infer<typeof Section>;
export const Storefront = z.object({
  id: z.string(),
  merchantId: z.string(),
  slug: z.string(),
  url: z.string(),
  pageKind: z.enum(['business_page', 'store', 'menu_page']),
  brandColor: z.string(),
  brandContrast: z.number(),
  logo: z.object({ id: z.string(), fileName: z.string(), url: z.string() }).nullish(),
  tagline: z.string().nullish(),
  ctaLabel: z.enum(CTA_LABELS),
  announcement: z.string().nullish(),
  customDomain: z.string().nullish(),
  customDomainStatus: z.enum(['pending', 'verified', 'failed']).nullish(),
  publishedAt: z.string().nullish(),
  sections: z.array(Section),
  business: z.object({
    displayName: z.string(),
    type: MerchantType,
    tier: MerchantTier.nullish(),
    status: MerchantStatus,
    city: z.string().nullish(),
    about: z.string().nullish(),
    serviceArea: z.string().nullish(),
    sameDayCutoff: z.string().nullish(),
    fulfilment: z.array(z.string()).default([]),
    cuisines: z.array(z.string()).default([]),
    verifiedFacts: z.array(z.string()).default([]),
  }),
});
export type Storefront = z.infer<typeof Storefront>;

const key = (merchantId: string) => ['merchant', merchantId, 'storefront'] as const;

/** null = the business has no page yet (404). */
export const storefrontQuery = (merchantId: string) => queryOptions({
  queryKey: key(merchantId),
  queryFn: async () => {
    try { return await http(`/api/v1/merchants/${merchantId}/storefront`, {}, Storefront); } catch (e) { if (e instanceof ApiError && e.status === 404) return null; throw e; }
  },
});

export interface StorefrontPatch { brandColor?: string; tagline?: string; ctaLabel?: string; announcement?: string; customDomain?: string; slug?: string; logoDocumentId?: string }
export interface SectionState { kind: SectionKind; enabled: boolean }

function useStorefrontMutation<V>(merchantId: string, run: (v: V) => Promise<Storefront>, optimistic?: (current: Storefront, v: V) => Storefront) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: run,
    onMutate: async (v: V) => {
      if (!optimistic) return { previous: undefined };
      await qc.cancelQueries({ queryKey: key(merchantId) });
      const previous = qc.getQueryData<Storefront | null>(key(merchantId));
      if (previous) qc.setQueryData(key(merchantId), optimistic(previous, v));
      return { previous };
    },
    onError: (_e, _v, ctx) => { if (ctx?.previous) qc.setQueryData(key(merchantId), ctx.previous); },
    onSuccess: data => qc.setQueryData(key(merchantId), data),
  });
}

export const useCreateStorefront = (merchantId: string) =>
  useStorefrontMutation<void>(merchantId, () => http(`/api/v1/merchants/${merchantId}/storefront`, { method: 'POST' }, Storefront));

export const useUpdateStorefront = (merchantId: string) =>
  useStorefrontMutation<StorefrontPatch>(
    merchantId,
    body => http(`/api/v1/merchants/${merchantId}/storefront`, { method: 'PATCH', body }, Storefront),
    (s, p) => ({ ...s, ...(p.brandColor ? { brandColor: p.brandColor } : {}), ...(p.ctaLabel ? { ctaLabel: p.ctaLabel as Storefront['ctaLabel'] } : {}) }),
  );

/** Reorder / toggle: one PATCH with the full ordered list, applied optimistically. */
export const useArrangeSections = (merchantId: string) =>
  useStorefrontMutation<SectionState[]>(
    merchantId,
    sections => http(`/api/v1/merchants/${merchantId}/storefront/sections`, { method: 'PATCH', body: { sections } }, Storefront),
    (s, list) => ({
      ...s,
      sections: list.map((it, i) => {
        const prev = s.sections.find(x => x.kind === it.kind)!;
        return { ...prev, position: i, enabled: prev.required || it.enabled };
      }),
    }),
  );

export const useVerifyDomain = (merchantId: string) =>
  useStorefrontMutation<void>(merchantId, () => http(`/api/v1/merchants/${merchantId}/storefront/domain/verify`, { method: 'POST' }, Storefront));

export const usePublishStorefront = (merchantId: string) =>
  useStorefrontMutation<void>(merchantId, () => http(`/api/v1/merchants/${merchantId}/storefront/publish`, { method: 'POST' }, Storefront));

export const UploadedDocument = z.object({ id: z.string(), fileName: z.string(), contentType: z.string(), sizeBytes: z.number() });
export type UploadedDocument = z.infer<typeof UploadedDocument>;

/** `POST …/onboarding/documents` (multipart). Purpose: legal | verification | logo. */
export function uploadDocument(merchantId: string, file: File, purpose: 'legal' | 'verification' | 'logo') {
  const body = new FormData();
  body.append('file', file);
  body.append('purpose', purpose);
  return http(`/api/v1/merchants/${merchantId}/onboarding/documents`, { method: 'POST', body }, UploadedDocument);
}

/** Logo: upload the file, then point the page at it. */
export function useUploadLogo(merchantId: string) {
  return useStorefrontMutation<File>(merchantId, async file => {
    const doc = await uploadDocument(merchantId, file, 'logo');
    return http(`/api/v1/merchants/${merchantId}/storefront`, { method: 'PATCH', body: { logoDocumentId: doc.id } }, Storefront);
  });
}
