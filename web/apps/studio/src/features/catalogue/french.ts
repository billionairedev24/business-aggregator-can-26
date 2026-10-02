import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { defineMessages } from '@northline/ui';
import { http } from '../../lib/http';

/**
 * S-116 (Loi 96 readiness): a listing's French name and description (`GET`/`PUT /listings/{id}/french`). `rule` is
 * what the business's place asks — region configuration, never a place in this app: `off`, `warn` (say what is
 * missing) or `require` (submitting and publishing are refused while it is missing).
 */
export const FrenchRule = z.enum(['off', 'warn', 'require']);
export const FrenchText = z.object({ rule: FrenchRule, title: z.string(), description: z.string().nullish(), missing: z.boolean() });
export type FrenchText = z.infer<typeof FrenchText>;

const path = (m: string, id: string) => `/api/v1/merchants/${m}/listings/${id}/french`;
export const frenchKey = (m: string, id: string) => ['catalogue', m, 'listing', id, 'french'] as const;

export const frenchQuery = (m: string, id: string) => queryOptions({ queryKey: frenchKey(m, id), queryFn: () => http(path(m, id), {}, FrenchText) });

export function useSaveFrench(m: string, id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { title: string; description: string }) => http(path(m, id), { method: 'PUT', body: { title: body.title, description: body.description || null } }, FrenchText),
    onSuccess: d => qc.setQueryData(frenchKey(m, id), d),
  });
}

/** The French panel's copy. The place is the ambient {provinceIn} (the business's province, S-134). */
export const useFrenchT = defineMessages({
  en: {
    title: 'French name and description',
    hint: 'Shown to customers who read Northline in French.',
    warn: 'Listings {provinceIn} need a French name and description.',
    require: 'Listings {provinceIn} need a French name and description before they can go live.',
    name: 'Name in French',
    description: 'Description in French',
    save: 'Save French text',
    saving: 'Saving…',
    saved: 'French text saved.',
    error: "The French text couldn't be saved. Try again.",
    loadError: "The French text couldn't load.",
    saveFirst: 'Save the listing first, then add its French text.',
  },
  fr: {
    title: 'Nom et description en français',
    hint: 'Affichés aux clients qui consultent Northline en français.',
    warn: 'Les annonces {provinceIn} doivent avoir un nom et une description en français.',
    require: 'Les annonces {provinceIn} doivent avoir un nom et une description en français avant d’être mises en ligne.',
    name: 'Nom en français',
    description: 'Description en français',
    save: 'Enregistrer le texte français',
    saving: 'Enregistrement…',
    saved: 'Texte français enregistré.',
    error: 'Le texte français n’a pas pu être enregistré. Réessayez.',
    loadError: 'Le texte français n’a pas pu être chargé.',
    saveFirst: 'Enregistrez d’abord l’annonce, puis ajoutez son texte en français.',
  },
});
