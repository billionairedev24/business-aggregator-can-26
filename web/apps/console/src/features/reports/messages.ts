import { defineMessages } from '@northline/ui';

/** Reports & analytics (S-95, design 03 lines 325–345). en = the design's copy; fr-CA after design/i18n-fr.js. */
export const useReportsT = defineMessages({
  en: {
    kicker: 'Reports & analytics',
    title: 'Marketplace health · 90 days',
    all: 'All', region: 'Region',
    wacTitle: 'Weekly active customers', wacSub: 'Solid: this period · dotted: previous', thisPeriod: 'This period', previous: 'Previous',
    funnelTitle: 'Funnel · shop',
    step_app_opens: 'App opens', step_browsed: 'Searched / browsed', step_cart: 'Added to cart', step_checkout: 'Checkout started', step_paid: 'Paid',
    cohortsTitle: 'Cohorts · 90-day repeat rate', c_month: 'Signup month', c_customers: 'Customers', c_m1: 'M1', c_m2: 'M2', c_m3: 'M3',
    topTitle: 'Top categories · GMV',
    gapsTitle: 'Supply & demand gaps',
    waitlist: 'Waitlist demand · {province}',
    // ours: the honest notes the design doesn't need
    notRecorded: 'not recorded', withheld: 'fewer than 5', none: '—',
    funnelNote: 'Browsed = storefront visits. App opens aren’t recorded yet; carts can’t be split by province.',
    privacyNote: 'Counts only, from orders, bookings, carts, checkouts and storefront visits. Any count from 1 to 4 is withheld so no one can be singled out.',
    noTop: 'No sales in the period.', noGaps: 'No waitlist outside live provinces.',
    asTable: 'Show as table', week: 'Week of', loadError: "Reports couldn't load. Try again.",
  },
  fr: {
    kicker: 'Rapports et analytique',
    title: 'Santé du marché · 90 jours',
    all: 'Toutes', region: 'Région',
    wacTitle: 'Clients actifs par semaine', wacSub: 'Plein : cette période · pointillé : précédente', thisPeriod: 'Cette période', previous: 'Précédente',
    funnelTitle: 'Entonnoir · boutique',
    step_app_opens: 'Ouvertures de l’app', step_browsed: 'Recherche / navigation', step_cart: 'Ajout au panier', step_checkout: 'Paiement commencé', step_paid: 'Payé',
    cohortsTitle: 'Cohortes · taux de rachat sur 90 jours', c_month: 'Mois d’inscription', c_customers: 'Clients', c_m1: 'M1', c_m2: 'M2', c_m3: 'M3',
    topTitle: 'Principales catégories · VMB',
    gapsTitle: 'Écarts d’offre et de demande',
    waitlist: 'Liste d’attente · {province}',
    notRecorded: 'non mesuré', withheld: 'moins de 5', none: '—',
    funnelNote: 'Navigation = visites des vitrines. Les ouvertures de l’app ne sont pas encore mesurées; les paniers ne se répartissent pas par province.',
    privacyNote: 'Des totaux seulement, tirés des commandes, réservations, paniers, paiements et visites de vitrines. Tout total de 1 à 4 est retenu pour que personne ne puisse être identifié.',
    noTop: 'Aucune vente sur la période.', noGaps: 'Aucune liste d’attente hors des provinces en service.',
    asTable: 'Afficher en tableau', week: 'Semaine du', loadError: 'Les rapports n’ont pas pu être chargés. Réessayez.',
  },
});
export type ReportsT = ReturnType<typeof useReportsT>;
export type ReportsKey = Parameters<ReportsT>[0];
