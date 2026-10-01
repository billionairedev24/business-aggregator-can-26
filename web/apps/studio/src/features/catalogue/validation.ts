import { useCallback } from 'react';
import { z } from 'zod';
import { useLocale } from '@northline/ui';

/**
 * Listing validation messages — identical to the server's (ca.northline.catalogue.domain.ListingMessages), so a 422
 * and a client-side check read the same. validation-rules.md has no catalogue section; see DECISIONS.md › Catalogue.
 */
export const MSG = {
  TITLE_REQUIRED: 'Enter a product title.',
  TOO_LONG_80: 'At most 80 characters.',
  TITLE_PROMO: 'Leave out promo words like sale, free or best.',
  CATEGORY_REQUIRED: 'Choose a category.',
  CATEGORY_LEAF: 'Choose a category down to the last level.',
  CATEGORY_WRONG_ROOT: 'Category not allowed in Shop',
  CATEGORY_WRONG_ROOT_SERVICE: 'Choose a service category.',
  ATTRIBUTE_REQUIRED: 'Choose {label}.',
  BULLETS_TOO_MANY: 'Up to 5 bullet points.',
  BULLET_TOO_LONG: 'At most 250 characters.',
  DESCRIPTION_TOO_LONG: 'At most 4000 characters.',
  GTIN_REQUIRED: 'Enter the GTIN, or choose None (handmade / local).',
  GTIN_FORMAT: 'GTIN must be 8, 12, 13 or 14 digits.',
  GTIN_CHECK_DIGIT: 'GTIN check digit invalid',
  VARIANTS_REQUIRED: 'Add at least one variant.',
  VARIANT_VALUE_REQUIRED: 'Name this variant.',
  VARIANT_DUPLICATE: 'Each variant needs its own value.',
  SKU_REQUIRED: 'Enter a SKU.',
  SKU_TOO_LONG: 'At most 40 characters.',
  SKU_TAKEN: 'That SKU is already used by another listing.',
  SKU_DUPLICATE: 'Each variant needs its own SKU.',
  IMAGES_REQUIRED: 'Add a main image on white, at least 1000 px.',
  IMAGES_TOO_MANY: 'Main image plus up to 8 more.',
  IMAGE_TYPE: 'Use a JPG or PNG image.',
  IMAGE_TOO_SMALL: 'Images must be at least 1000 px on the longest side.',
  IMAGE_TOO_LARGE: 'Images must be 15 MB or smaller.',
  PRICE_REQUIRED: 'Enter a price.',
  PRICE_POSITIVE: 'Enter a price above $0.',
  COMPARE_AT_HIGHER: 'Compare-at must be higher than your price.',
  COST_NEGATIVE: "Cost can't be negative.",
  STOCK_REQUIRED: 'Enter stock on hand.',
  STOCK_NEGATIVE: "Stock can't be negative.",
  LOW_STOCK_NEGATIVE: "Alert level can't be negative.",
  FULFILMENT_REQUIRED: 'Choose at least one fulfilment option.',
  FINAL_SALE_PERISHABLE: 'Final sale is allowed only for perishables.',
  ORIGIN_REQUIRED: 'Choose the country of origin.',
  RESTRICTED_REQUIRED: 'Confirm this is not a restricted product.',
  BILINGUAL_REQUIRED: 'Confirm bilingual labelling.',
  KEYWORDS_TOO_LONG: 'At most 250 characters.',
  NAME_REQUIRED: 'Enter a service name.',
  PRICING_REQUIRED: 'Choose how you price this service.',
  DURATION_REQUIRED: 'Choose a duration.',
  DURATION_RANGE: 'Choose a duration between 15 minutes and 12 hours.',
  BUFFER_RANGE: 'Buffer must be between 0 and 120 minutes.',
  INCLUDED_REQUIRED: "Describe what's included.",
  INCLUDED_TOO_LONG: 'At most 2000 characters.',
} as const;

export const FR: Record<string, string> = {
  [MSG.TITLE_REQUIRED]: 'Entrez un titre de produit.',
  [MSG.TOO_LONG_80]: 'Au plus 80 caractères.',
  [MSG.TITLE_PROMO]: 'Retirez les mots promotionnels comme solde, gratuit ou meilleur.',
  [MSG.CATEGORY_REQUIRED]: 'Choisissez une catégorie.',
  [MSG.CATEGORY_LEAF]: "Choisissez une catégorie jusqu'au dernier niveau.",
  [MSG.CATEGORY_WRONG_ROOT]: 'Catégorie non permise dans la Boutique',
  [MSG.CATEGORY_WRONG_ROOT_SERVICE]: 'Choisissez une catégorie de service.',
  [MSG.BULLETS_TOO_MANY]: "Jusqu'à 5 points clés.",
  [MSG.BULLET_TOO_LONG]: 'Au plus 250 caractères.',
  [MSG.DESCRIPTION_TOO_LONG]: 'Au plus 4000 caractères.',
  [MSG.GTIN_REQUIRED]: 'Entrez le GTIN ou choisissez Aucun (fait main / local).',
  [MSG.GTIN_FORMAT]: 'Le GTIN compte 8, 12, 13 ou 14 chiffres.',
  [MSG.GTIN_CHECK_DIGIT]: 'Chiffre de contrôle du GTIN invalide',
  [MSG.VARIANTS_REQUIRED]: 'Ajoutez au moins une variante.',
  [MSG.VARIANT_VALUE_REQUIRED]: 'Nommez cette variante.',
  [MSG.VARIANT_DUPLICATE]: 'Chaque variante doit avoir sa propre valeur.',
  [MSG.SKU_REQUIRED]: 'Entrez une UGS.',
  [MSG.SKU_TOO_LONG]: 'Au plus 40 caractères.',
  [MSG.SKU_TAKEN]: 'Cette UGS est déjà utilisée par une autre annonce.',
  [MSG.SKU_DUPLICATE]: 'Chaque variante doit avoir sa propre UGS.',
  [MSG.IMAGES_REQUIRED]: 'Ajoutez une image principale sur fond blanc, d’au moins 1000 px.',
  [MSG.IMAGES_TOO_MANY]: "Image principale et jusqu'à 8 autres.",
  [MSG.IMAGE_TYPE]: 'Utilisez une image JPG ou PNG.',
  [MSG.IMAGE_TOO_SMALL]: 'Les images doivent mesurer au moins 1000 px sur le côté le plus long.',
  [MSG.IMAGE_TOO_LARGE]: 'Les images doivent faire 15 Mo ou moins.',
  [MSG.PRICE_REQUIRED]: 'Entrez un prix.',
  [MSG.PRICE_POSITIVE]: 'Entrez un prix supérieur à 0 $.',
  [MSG.COMPARE_AT_HIGHER]: 'Le prix de comparaison doit dépasser votre prix.',
  [MSG.COST_NEGATIVE]: 'Le coût ne peut pas être négatif.',
  [MSG.STOCK_REQUIRED]: 'Entrez le stock disponible.',
  [MSG.STOCK_NEGATIVE]: 'Le stock ne peut pas être négatif.',
  [MSG.LOW_STOCK_NEGATIVE]: "Le seuil d'alerte ne peut pas être négatif.",
  [MSG.FULFILMENT_REQUIRED]: "Choisissez au moins un mode d'exécution.",
  [MSG.FINAL_SALE_PERISHABLE]: 'La vente finale est permise seulement pour les périssables.',
  [MSG.ORIGIN_REQUIRED]: "Choisissez le pays d'origine.",
  [MSG.RESTRICTED_REQUIRED]: "Confirmez qu'il ne s'agit pas d'un produit restreint.",
  [MSG.BILINGUAL_REQUIRED]: "Confirmez l'étiquetage bilingue.",
  [MSG.NAME_REQUIRED]: 'Entrez un nom de service.',
  [MSG.PRICING_REQUIRED]: 'Choisissez comment vous tarifez ce service.',
  [MSG.DURATION_REQUIRED]: 'Choisissez une durée.',
  [MSG.DURATION_RANGE]: 'Choisissez une durée entre 15 minutes et 12 heures.',
  [MSG.BUFFER_RANGE]: 'Le battement doit être entre 0 et 120 minutes.',
  [MSG.INCLUDED_REQUIRED]: 'Décrivez ce qui est inclus.',
  [MSG.INCLUDED_TOO_LONG]: 'Au plus 2000 caractères.',
  'That image is no longer available — upload it again.': 'Cette image n’est plus disponible — téléversez-la de nouveau.',
};

/** Localises a validation message (client-side or from a 422). Unknown messages pass through. */
export function useMessageT() {
  const { locale } = useLocale();
  return useCallback((message: string | undefined) => {
    if (!message || locale !== 'fr') return message;
    const attr = /^Choose (.+)\.$/.exec(message);
    if (FR[message]) return FR[message];
    if (attr && !FR[message]) return `Choisissez : ${attr[1]}.`;
    return message;
  }, [locale]);
}

// ── field helpers ─────────────────────────────────────────────────────────────────────────────────────────────────
const PROMO = /(?<![\w-])(sale|free|best|cheapest|discount|deal|promo|clearance|\d+\s?% off)(?![\w-])/i;

/** GS1 mod-10 check: GTIN-8/12/13/14. */
export function gtinProblem(raw: string): string | undefined {
  const digits = raw.replace(/[\s-]/g, '');
  if (!/^(\d{8}|\d{12}|\d{13}|\d{14})$/.test(digits)) return MSG.GTIN_FORMAT;
  let sum = 0;
  for (let i = digits.length - 2, w = 3; i >= 0; i--, w = 4 - w) sum += Number(digits[i]) * w;
  return (10 - (sum % 10)) % 10 === Number(digits[digits.length - 1]) ? undefined : MSG.GTIN_CHECK_DIGIT;
}

/** "$19.00", "19", "1,299.5" → cents. Empty → null; unreadable → NaN. */
export function parseMoney(text: string): number | null {
  const s = text.replace(/[$\s,]/g, '').replace(/CAD$/i, '');
  if (!s) return null;
  if (!/^\d+(\.\d{0,2})?$/.test(s)) return Number.NaN;
  const [whole = '0', frac = ''] = s.split('.');
  return Number(whole) * 100 + Number(frac.padEnd(2, '0') || 0);
}
export const centsToInput = (cents: number | null | undefined) => (cents == null ? '' : (cents / 100).toFixed(2));

/** Whole numbers; empty → null; anything else → NaN. */
export function parseCount(text: string): number | null {
  const s = text.trim();
  if (!s) return null;
  return /^-?\d+$/.test(s) ? Number(s) : Number.NaN;
}

const money = (required: string) => z.number({ error: required }).refine(n => !Number.isNaN(n), required);

// ── product draft (Save draft) ────────────────────────────────────────────────────────────────────────────────────
export const productDraftSchema = z.object({
  title: z.string().trim().min(1, MSG.TITLE_REQUIRED).max(80, MSG.TOO_LONG_80).refine(t => !PROMO.test(t), MSG.TITLE_PROMO),
  categoryId: z.string().min(1, MSG.CATEGORY_REQUIRED),
  priceCents: money(MSG.PRICE_REQUIRED).refine(n => n > 0, MSG.PRICE_POSITIVE),
  stock: money(MSG.STOCK_REQUIRED).refine(n => Number.isNaN(n) || n >= 0, MSG.STOCK_NEGATIVE),
  compareAtCents: z.number().nullable().refine(n => n === null || !Number.isNaN(n), MSG.COMPARE_AT_HIGHER),
  costCents: z.number().nullable().refine(n => n === null || (!Number.isNaN(n) && n >= 0), MSG.COST_NEGATIVE),
  lowStockAt: z.number().nullable().refine(n => n === null || (!Number.isNaN(n) && n >= 0), MSG.LOW_STOCK_NEGATIVE),
  sku: z.string().max(40, MSG.SKU_TOO_LONG).nullable(),
  description: z.string().max(4000, MSG.DESCRIPTION_TOO_LONG).nullable(),
  bullets: z.array(z.string().max(250, MSG.BULLET_TOO_LONG)).max(5, MSG.BULLETS_TOO_MANY),
  searchKeywords: z.string().max(250, MSG.KEYWORDS_TOO_LONG).nullable(),
  imageIds: z.array(z.string()).max(9, MSG.IMAGES_TOO_MANY),
  variants: z.array(z.object({
    value: z.string().trim().min(1, MSG.VARIANT_VALUE_REQUIRED),
    sku: z.string().trim().min(1, MSG.SKU_REQUIRED).max(40, MSG.SKU_TOO_LONG),
    priceCents: money(MSG.PRICE_REQUIRED).refine(n => n > 0, MSG.PRICE_POSITIVE),
    stock: money(MSG.STOCK_REQUIRED).refine(n => Number.isNaN(n) || n >= 0, MSG.STOCK_NEGATIVE),
  })),
});

/** First message per field path ("variants[1].sku") — the same keys the server's 422 uses. */
export function issuesByField(error: z.ZodError): Record<string, string> {
  const out: Record<string, string> = {};
  for (const issue of error.issues) {
    const key = issue.path.map((p, i) => (typeof p === 'number' ? `[${p}]` : i === 0 ? String(p) : `.${String(p)}`)).join('');
    out[key] ??= issue.message;
  }
  return out;
}

// ── service draft ─────────────────────────────────────────────────────────────────────────────────────────────────
export const serviceDraftSchema = z.object({
  name: z.string().trim().min(1, MSG.NAME_REQUIRED).max(80, MSG.TOO_LONG_80),
  pricingMode: z.enum(['fixed', 'quote', 'hourly'], { error: MSG.PRICING_REQUIRED }),
  priceCents: z.number().nullable(),
  durationMin: z.number({ error: MSG.DURATION_REQUIRED }).min(15, MSG.DURATION_RANGE).max(720, MSG.DURATION_RANGE),
  bufferMin: z.number().min(0, MSG.BUFFER_RANGE).max(120, MSG.BUFFER_RANGE),
  included: z.string().max(2000, MSG.INCLUDED_TOO_LONG).nullable(),
  sku: z.string().max(40, MSG.SKU_TOO_LONG).nullable(),
}).superRefine((v, ctx) => {
  if (v.pricingMode === 'quote') return;
  if (v.priceCents === null || Number.isNaN(v.priceCents)) ctx.addIssue({ code: 'custom', path: ['priceCents'], message: MSG.PRICE_REQUIRED });
  else if (v.priceCents <= 0) ctx.addIssue({ code: 'custom', path: ['priceCents'], message: MSG.PRICE_POSITIVE });
});

export { PROMO };
