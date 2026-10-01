import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError, http } from '../../lib/http';
import { MerchantStatus, MerchantTier, MerchantType } from '../shell/api';
import { CTA_LABELS, SECTION_KINDS, type SectionKind } from './sections';

/** `GET /api/v1/merchants/{id}/storefront` — see DECISIONS.md › storefront API. */
export const Section = z.object({ id: z.string(), kind: z.enum(SECTION_KINDS), position: z.number(), enabled: z.boolean(), required: z.boolean(), settings: z.record(z.string(), z.unknown()).default({}) });
export type Section = z.infer<typeof Section>;
/** S-31: pending → verified → issuing → live, failed, expired (DECISIONS.md › S-31). */
export const DomainStatus = z.enum(['pending', 'verified', 'issuing', 'live', 'failed', 'expired']);
export type DomainStatus = z.infer<typeof DomainStatus>;
export const DomainProblem = z.enum(['txt_missing', 'txt_mismatch', 'no_record', 'not_pointing', 'dns_error', 'certificate', 'capacity', 'rate_limited']);
export type DomainProblem = z.infer<typeof DomainProblem>;
/** What the owner adds at their DNS host (CNAME, or ALIAS / A for an apex, and the ownership TXT) and where it stands. */
export const DomainSetup = z.object({
  target: z.string(),
  apex: z.boolean(),
  records: z.array(z.object({ type: z.string(), name: z.string(), value: z.string() })),
  problem: DomainProblem.nullish(),
  checkedAt: z.string().nullish(),
  nextCheckAt: z.string().nullish(),
  verifiedAt: z.string().nullish(),
  liveAt: z.string().nullish(),
  graceEndsAt: z.string().nullish(),
});
export type DomainSetup = z.infer<typeof DomainSetup>;

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
  customDomainStatus: DomainStatus.nullish(),
  customDomainTarget: z.string().default('pages.northline.ca'),
  customDomainSetup: DomainSetup.nullish(),
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

/** DEV ONLY ("Simulate DNS records →"): the api exposes it under the `local` profile only (in-memory DNS zone). */
export const useSimulateDns = (merchantId: string) =>
  useStorefrontMutation<void>(merchantId, () => http(`/api/v1/dev/merchants/${merchantId}/storefront/domain/dns`, { method: 'POST' }, Storefront));

export const usePublishStorefront = (merchantId: string) =>
  useStorefrontMutation<void>(merchantId, () => http(`/api/v1/merchants/${merchantId}/storefront/publish`, { method: 'POST' }, Storefront));

/** S-75: GET …/storefront-stats — visits to the public page and the booked rate, last 30 days. */
export const StorefrontStats = z.object({
  visits: z.number(), booked: z.number(), bookedRateBps: z.number().nullish(),
  daily: z.array(z.object({ date: z.string(), visits: z.number() })),
});
export type StorefrontStats = z.infer<typeof StorefrontStats>;
export const storefrontStatsQuery = (merchantId: string) => queryOptions({
  queryKey: ['merchant', merchantId, 'storefront-stats'],
  queryFn: () => http(`/api/v1/merchants/${merchantId}/storefront-stats`, {}, StorefrontStats),
  staleTime: 5 * 60_000,
});

/** S-75: the provider-funded reward (`GET`, 204 = never set; `PUT` switches it on with its terms, or off). */
export const Reward = z.object({ active: z.boolean(), multiplier: z.number(), label: z.string().nullish(), endsOn: z.string(), running: z.boolean() });
export type Reward = z.infer<typeof Reward>;
export interface RewardInput { active: boolean; multiplier: 2 | 3; label: string | null; endsOn: string | null }
const rewardKey = (merchantId: string) => ['merchant', merchantId, 'reward'] as const;
export const rewardQuery = (merchantId: string) => queryOptions({
  queryKey: rewardKey(merchantId),
  queryFn: async () => (await http(`/api/v1/merchants/${merchantId}/reward`, {}, Reward.nullish())) ?? null,
});
export function useSaveReward(merchantId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: RewardInput) => http(`/api/v1/merchants/${merchantId}/reward`, { method: 'PUT', body }, Reward),
    onSuccess: r => qc.setQueryData(rewardKey(merchantId), r),
  });
}

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
