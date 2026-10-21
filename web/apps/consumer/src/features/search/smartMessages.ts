import { defineMessages } from '@northline/ui';

/** S-158: natural-language search on the results page (our copy; CONSUMER_WEB_PLAN § AI contracts). */
export const useSmartT = defineMessages({
  en: {
    label: 'Suggested filters',
    understood: 'Searching for: {explanation}',
    hint: 'Suggested by {brand}’s AI from what you typed. Remove any you don’t want, then apply them.',
    apply: 'Apply these filters', dismiss: 'Just search the words', remove: 'Remove {chip}',
    thinking: 'Reading what you typed…',
    unavailable: 'Smart search isn’t available right now. These are results for your words.',
    none: 'No filters to suggest: these are results for your words.',
    c_q: 'Words: “{q}”', c_services: 'Services', c_shop: 'Shop', c_food: 'Food', c_trusted: 'Trusted pros', c_master: 'Master pros', c_registered: 'Registered',
    c_minPrice: 'From {price}', c_maxPrice: 'Up to {price}', c_minRating: '{rating}+ stars', c_openNow: 'Open now', c_instantBook: 'Instant book',
    c_tonight: 'On tonight’s run', c_radius: 'Within {km} km', c_free: 'No {allergen}',
    s_distance: 'Nearest first', s_price_asc: 'Lowest price first', s_price_desc: 'Highest price first', s_rating: 'Best rated first',
    d_gluten_free: 'Gluten-free', d_vegan: 'Vegan', d_vegetarian: 'Vegetarian', d_halal: 'Halal',
    a_eggs: 'eggs', a_milk: 'milk', a_peanuts: 'peanuts', a_tree_nuts: 'tree nuts', a_sesame: 'sesame', a_soy: 'soy', a_wheat: 'wheat',
    a_fish: 'fish', a_shellfish: 'shellfish', a_mustard: 'mustard', a_sulphites: 'sulphites',
  },
  fr: {
    label: 'Filtres suggérés',
    understood: 'Recherche : {explanation}',
    hint: 'Suggérés par l’IA de {brand} à partir de ce que vous avez tapé. Retirez ceux que vous ne voulez pas, puis appliquez-les.',
    apply: 'Appliquer ces filtres', dismiss: 'Chercher seulement les mots', remove: 'Retirer {chip}',
    thinking: 'Lecture de votre recherche…',
    unavailable: 'La recherche intelligente n’est pas disponible pour l’instant. Voici les résultats pour vos mots.',
    none: 'Aucun filtre à suggérer : voici les résultats pour vos mots.',
    c_q: 'Mots : « {q} »', c_services: 'Services', c_shop: 'Boutique', c_food: 'Repas', c_trusted: 'Pros de confiance', c_master: 'Pros maîtres', c_registered: 'Inscrits',
    c_minPrice: 'À partir de {price}', c_maxPrice: 'Jusqu’à {price}', c_minRating: '{rating} étoiles et plus', c_openNow: 'Ouvert maintenant', c_instantBook: 'Réservation instantanée',
    c_tonight: 'Sur la tournée de ce soir', c_radius: 'À moins de {km} km', c_free: 'Sans {allergen}',
    s_distance: 'Les plus proches d’abord', s_price_asc: 'Prix le plus bas d’abord', s_price_desc: 'Prix le plus élevé d’abord', s_rating: 'Les mieux notés d’abord',
    d_gluten_free: 'Sans gluten', d_vegan: 'Végane', d_vegetarian: 'Végétarien', d_halal: 'Halal',
    a_eggs: 'œufs', a_milk: 'lait', a_peanuts: 'arachides', a_tree_nuts: 'noix', a_sesame: 'sésame', a_soy: 'soya', a_wheat: 'blé',
    a_fish: 'poisson', a_shellfish: 'fruits de mer', a_mustard: 'moutarde', a_sulphites: 'sulfites',
  },
});
export type SmartT = ReturnType<typeof useSmartT>;
export type SmartKey = Parameters<SmartT>[0];
