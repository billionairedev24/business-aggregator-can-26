import { defineMessages } from '@northline/ui';

/**
 * Home copy — the light, photo-first home (phase B): the hero (search and place first), the illustrated tiles, the
 * rails and the seller banner. Region-neutral: a place is a parameter (`{place}`, `{city}`), never written in. The old
 * design 06 copy (the greeting, the "what do you need" question, live counts, the promises, tiers, "Your week") left
 * with § 3.2; the carousel left with phase B.
 */
export const useHomeT = defineMessages({
  en: {
    heroTitle: 'Everything local, from people you can trust',
    heroSub: 'Book verified pros, shop neighbourhood stores and get it all delivered together — you pay only when it’s done.',
    heroSearch: 'Try “brake pads”, “sourdough” or “house cleaning”',
    near: 'Near {place}', noPlace: 'Choose where you are to see what’s close', changePlace: 'Change', setPlace: 'Set your location',
    popular: 'Popular', badge: 'Every pro and shop verified',
    tileServices: 'Book a service', tileShop: 'Shop local', allServices: 'All services', allDepartments: 'All departments',
    seeMore: 'See more', seeMoreOf: 'See more: {title}',
    productsNear: 'Popular near {city}', productsAnywhere: 'Popular near you',
    servicesNear: 'Pros near {city}', servicesAnywhere: 'Pros near you',
    loading: 'Loading what’s near you…', loadError: 'We couldn’t load what’s near you.',
    emptyCity: 'Nothing near {city} yet.', emptyAnywhere: 'Nothing near you yet.', emptyHint: 'Shops and providers are joining every week. Try another location.',
    changeLocation: 'Choose another location',
    sellTitle: 'Sell or offer services', sellText: 'Open your shop or take bookings — verification, payments and payouts included.', sellCta: 'Get started',
  },
  fr: {
    heroTitle: 'Tout le local, par des gens de confiance',
    heroSub: 'Réservez des pros vérifiés, magasinez dans les commerces du quartier et recevez tout en une seule livraison — vous payez seulement une fois le travail terminé.',
    heroSearch: 'Essayez « plaquettes de frein », « pain au levain » ou « ménage »',
    near: 'Près de {place}', noPlace: 'Indiquez où vous êtes pour voir ce qui est proche', changePlace: 'Changer', setPlace: 'Choisir votre emplacement',
    popular: 'Populaire', badge: 'Chaque pro et commerce vérifié',
    tileServices: 'Réserver un service', tileShop: 'Acheter local', allServices: 'Tous les services', allDepartments: 'Tous les rayons',
    seeMore: 'Voir plus', seeMoreOf: 'Voir plus : {title}',
    productsNear: 'Populaire près de {city}', productsAnywhere: 'Populaire près de chez vous',
    servicesNear: 'Des pros près de {city}', servicesAnywhere: 'Des pros près de chez vous',
    loading: 'Chargement de ce qui est près de chez vous…', loadError: 'Impossible de charger ce qui est près de chez vous.',
    emptyCity: 'Rien près de {city} pour l’instant.', emptyAnywhere: 'Rien près de chez vous pour l’instant.', emptyHint: 'De nouveaux commerces et prestataires s’inscrivent chaque semaine. Essayez un autre lieu.',
    changeLocation: 'Choisir un autre lieu',
    sellTitle: 'Vendre ou offrir des services', sellText: 'Ouvrez votre boutique ou prenez des réservations — vérification, paiements et versements compris.', sellCta: 'Commencer',
  },
});
