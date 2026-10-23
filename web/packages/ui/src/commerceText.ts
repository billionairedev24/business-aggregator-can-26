import { defineMessages } from './i18n';

/** The commerce kit's strings (Commerce.tsx and Cards.tsx share them; not exported from the package). */
export const useCommerceT = defineMessages({
  en: {
    stars: '{rating} out of 5 stars',
    reviews: '{count, plural, one {# review} other {# reviews}}',
    noReviews: 'New',
    from: 'from',
    addToCart: 'Add to cart', addNamed: 'Add to cart: {name}', adding: 'Adding…',
    inCart: '{count} in cart', qtyOf: 'Quantity of {name}', less: 'One less {name}', more: 'One more {name}', remove: 'Remove {name}',
    soldOut: 'Currently unavailable', onlyLeft: 'Only {count} left',
    verified: 'Verified',
    was: 'Was {price}',
    off: '{percent}% off',
    andUp: '& up', ratingUp: '{rating} stars & up',
  },
  fr: {
    stars: '{rating} sur 5 étoiles',
    reviews: '{count, plural, one {# avis} other {# avis}}',
    noReviews: 'Nouveau',
    from: 'à partir de',
    addToCart: 'Ajouter au panier', addNamed: 'Ajouter au panier : {name}', adding: 'Ajout…',
    inCart: '{count} au panier', qtyOf: 'Quantité de {name}', less: 'Un {name} de moins', more: 'Un {name} de plus', remove: 'Retirer {name}',
    soldOut: 'Non disponible pour le moment', onlyLeft: 'Plus que {count}',
    verified: 'Vérifié',
    was: 'Avant {price}',
    off: '{percent} % de rabais',
    andUp: 'et plus', ratingUp: '{rating} étoiles et plus',
  },
});
