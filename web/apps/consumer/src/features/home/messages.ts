import { defineMessages } from '@northline/ui';

/**
 * Home copy — the redesign's home (§ 1.3, AT Shop:182): the hero slides, the tiles, the rails and the seller banner.
 * Region-neutral: a place is a parameter (`{city}`), never written in. The old design 06 copy (the greeting, the
 * "what do you need" question, live counts, the promises, tiers, "Your week") left with § 3.2.
 */
export const useHomeT = defineMessages({
  en: {
    carousel: 'carousel', slide: 'slide', featured: 'Featured', slides: 'Choose a slide',
    slideOf: 'Slide {n} of {total}', prevSlide: 'Previous slide', nextSlide: 'Next slide',
    kicker_services: 'Services', title_services: 'Book trusted local pros', sub_services: 'Mechanics, cleaners, tutors and more — verified before they appear, paid only when the job is done.', cta_services: 'Find a pro',
    kicker_shop: 'Shop', title_shop: 'Your neighbourhood shops, delivered together', sub_shop: 'Groceries, bakeries, pharmacies and more on one delivery, from shops near you.', cta_shop: 'Start shopping',
    kicker_quotes: 'Quotes', title_quotes: 'Bigger job? Get itemized quotes', sub_quotes: 'Describe the job once and compare quotes line by line — scope, warranty and deposit included.', cta_quotes: 'Get quotes',
    tilesLabel: 'Browse',
    tileServices: 'Book a service', tileShop: 'Shop local', tileGroceries: 'Groceries & fresh food', tileHome: 'Around the home',
    seeMore: 'See more', seeMoreOf: 'See more: {title}',
    productsNear: 'Popular near {city}', productsAnywhere: 'Popular near you',
    servicesNear: 'Pros near {city}', servicesAnywhere: 'Pros near you',
    loading: 'Loading what’s near you…', loadError: 'We couldn’t load what’s near you.',
    emptyCity: 'Nothing near {city} yet.', emptyAnywhere: 'Nothing near you yet.', emptyHint: 'Shops and providers are joining every week. Try another location.',
    changeLocation: 'Choose another location',
    sellTitle: 'Sell or offer services', sellText: 'Open your shop or take bookings — verification, payments and payouts included.', sellCta: 'Get started',
  },
  fr: {
    carousel: 'carrousel', slide: 'diapositive', featured: 'À la une', slides: 'Choisir une diapositive',
    slideOf: 'Diapositive {n} sur {total}', prevSlide: 'Diapositive précédente', nextSlide: 'Diapositive suivante',
    kicker_services: 'Services', title_services: 'Réservez des pros de confiance près de chez vous', sub_services: 'Mécaniciens, ménage, tuteurs et plus — vérifiés avant d’apparaître, payés seulement une fois le travail terminé.', cta_services: 'Trouver un pro',
    kicker_shop: 'Boutique', title_shop: 'Les commerces du quartier, livrés ensemble', sub_shop: 'Épicerie, boulangeries, pharmacies et plus en une seule livraison, depuis des commerces près de chez vous.', cta_shop: 'Magasiner',
    kicker_quotes: 'Devis', title_quotes: 'Un plus gros projet ? Obtenez des devis détaillés', sub_quotes: 'Décrivez le travail une fois et comparez les devis ligne par ligne — portée, garantie et acompte compris.', cta_quotes: 'Obtenir des devis',
    tilesLabel: 'Parcourir',
    tileServices: 'Réserver un service', tileShop: 'Acheter local', tileGroceries: 'Épicerie et produits frais', tileHome: 'Autour de la maison',
    seeMore: 'Voir plus', seeMoreOf: 'Voir plus : {title}',
    productsNear: 'Populaire près de {city}', productsAnywhere: 'Populaire près de chez vous',
    servicesNear: 'Des pros près de {city}', servicesAnywhere: 'Des pros près de chez vous',
    loading: 'Chargement de ce qui est près de chez vous…', loadError: 'Impossible de charger ce qui est près de chez vous.',
    emptyCity: 'Rien près de {city} pour l’instant.', emptyAnywhere: 'Rien près de chez vous pour l’instant.', emptyHint: 'De nouveaux commerces et prestataires s’inscrivent chaque semaine. Essayez un autre lieu.',
    changeLocation: 'Choisir un autre lieu',
    sellTitle: 'Vendre ou offrir des services', sellText: 'Ouvrez votre boutique ou prenez des réservations — vérification, paiements et versements compris.', sellCta: 'Commencer',
  },
});
