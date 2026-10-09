import { defineMessages } from '@northline/ui';

/** Promo codes, points and the courier's tip at checkout (mobile gaps part 2). Server 422s arrive in the page's language. */
export const usePromoT = defineMessages({
  en: {
    promoTitle: 'Promo code and points',
    promoLabel: 'Promo code',
    apply: 'Apply',
    remove: 'Remove',
    applied: '{code} applied · {amount} off',
    pointsUse: 'Use my points · {points, plural, one {# point} other {# points}} available',
    pointsUsed: 'Points · {amount}',
    discount: 'Promo {code}',
    pointsLine: 'Points',
    pointsCheck: 'Use my points',
    tipTitle: 'Tip the courier · 100% goes to them',
    noTip: 'No tip',
    tip: 'Tip',
    taxNote: 'Tax is on the price after the promo code. Points pay like money.',
  },
  fr: {
    promoTitle: 'Code promo et points',
    promoLabel: 'Code promo',
    apply: 'Appliquer',
    remove: 'Retirer',
    applied: '{code} appliqué · {amount} de rabais',
    pointsUse: 'Utiliser mes points · {points, plural, one {# point disponible} other {# points disponibles}}',
    pointsUsed: 'Points · {amount}',
    discount: 'Promo {code}',
    pointsLine: 'Points',
    pointsCheck: 'Utiliser mes points',
    tipTitle: 'Pourboire au livreur · 100 % lui revient',
    noTip: 'Aucun',
    tip: 'Pourboire',
    taxNote: 'Les taxes s’appliquent au prix après le code promo. Les points paient comme de l’argent.',
  },
});
