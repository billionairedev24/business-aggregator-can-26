import { defineMessages } from '@northline/ui';
import type { ScreenKey } from './screens';

/** Shell copy — design 06 (`t` in the prototype's logic) and design/i18n-fr.js for French. */
export const useShellT = defineMessages({
  en: {
    services: 'Services', shop: 'Shop', food: 'Food',
    search: 'Search “sourdough”, “mobile mechanic”, “DJ”…',
    signIn: 'Sign in', createAccount: 'Create account',
    guestNote: "You're browsing as a guest. Sign in to pay, book, track orders and earn points — your cart is kept.",
    skip: 'Skip to content',
    privacy: 'Privacy', terms: 'Terms', langSwitch: 'Français',
    sell: 'Sell on Northline', offer: 'Offer a service', kitchen: 'Run a kitchen',
    // account menu
    activity: 'Activity', account: 'Account', preferences: 'Preferences',
    orders: 'Orders & bookings', favourites: 'Favourites', wallet: 'Wallet & points', profile: 'Profile',
    addresses: 'Addresses & household', payments: 'Payment methods', security: 'Security & sign-in',
    notifications: 'Notifications', language: 'Language / Langue & region', dietary: 'Dietary & accessibility',
    plus: 'Northline Plus', help: 'Help & cases', sellOrOffer: 'Sell or offer a service', signOut: 'Sign out',
    addPhoto: 'Add photo', reliability: '{email} · reliability {score}',
    active: '{count} active', open: '{count} open', points: '{count} pts', plusActive: 'Active', plusTry: 'Try free',
    plusTag: 'Plus · 2× points', standardTag: 'Standard', members: '{count} · {members} members',
    quiet: 'Quiet {from}–{to}', passkey: 'Passkey', authenticator: 'Authenticator app', sms: 'SMS code',
    languageValue: 'English · {province}',
    // states
    pendingKicker: 'Design 06 · {state}', pendingBody: 'This screen is being built ({story}).', backHome: 'Back to home',
    notFoundTitle: 'We couldn’t find that page.', notFoundBody: 'The link may be old, or the page has moved.',
    errorTitle: 'Something went wrong on our side.', retry: 'Retry',
  },
  fr: {
    services: 'Services', shop: 'Boutique', food: 'Restaurants',
    search: 'Rechercher « pain au levain », « mécanicien mobile », « DJ »…',
    signIn: 'Se connecter', createAccount: 'Créer un compte',
    guestNote: "Vous naviguez en tant qu'invité. Connectez-vous pour payer, réserver, suivre vos commandes et gagner des points — votre panier est conservé.",
    skip: 'Aller au contenu',
    privacy: 'Confidentialité', terms: 'Conditions', langSwitch: 'English',
    sell: 'Vendre sur Northline', offer: 'Offrir un service', kitchen: 'Gérer une cuisine',
    activity: 'Activité', account: 'Compte', preferences: 'Préférences',
    orders: 'Commandes et réservations', favourites: 'Favoris', wallet: 'Portefeuille et points', profile: 'Profil',
    addresses: 'Adresses et foyer', payments: 'Modes de paiement', security: 'Sécurité et connexion',
    notifications: 'Notifications', language: 'Langue / Language et région', dietary: 'Alimentation et accessibilité',
    plus: 'Northline Plus', help: 'Aide et dossiers', sellOrOffer: 'Vendre ou offrir un service', signOut: 'Se déconnecter',
    addPhoto: 'Ajouter une photo', reliability: '{email} · fiabilité {score}',
    active: '{count} active(s)', open: '{count} ouvert(s)', points: '{count} pts', plusActive: 'Actif', plusTry: 'Essayer gratuitement',
    plusTag: 'Plus · points ×2', standardTag: 'Standard', members: '{count} · {members} membres',
    quiet: 'Silence {from}–{to}', passkey: "Clé d'accès", authenticator: "Application d'authentification", sms: 'Code SMS',
    languageValue: 'Français · {province}',
    pendingKicker: 'Design 06 · {state}', pendingBody: "Cet écran est en cours de construction ({story}).", backHome: "Retour à l'accueil",
    notFoundTitle: 'Page introuvable.', notFoundBody: 'Le lien est peut-être ancien, ou la page a été déplacée.',
    errorTitle: 'Un problème est survenu de notre côté.', retry: 'Réessayer',
  },
});

/** Screen names (page titles, ScreenPending). */
export const SCREEN_NAMES: { en: Record<ScreenKey, string>; fr: Record<ScreenKey, string> } = {
  en: {
    home: 'Home', location: 'Where should we bring things?', search: 'Search', shop: 'Shop', category: 'Department',
    product: 'Product', cart: 'Cart', confirmed: 'Order confirmed', food: 'Food', restaurant: 'Restaurant',
    foodCheckout: 'Checkout', foodTrack: 'Track order', services: 'Services', svcCategory: 'Service category',
    providers: 'Providers', provider: 'Provider', book: 'Book a service', quote: 'Quote', quoteRequest: 'Request quotes', quoteCompare: 'Compare quotes', orders: 'Orders & bookings',
    account: 'Account', problem: 'Report a problem', signIn: 'Sign in', register: 'Create account', sell: 'Sell or offer a service',
  },
  fr: {
    home: 'Accueil', location: 'Où devons-nous livrer ?', search: 'Rechercher', shop: 'Boutique', category: 'Rayon',
    product: 'Produit', cart: 'Panier', confirmed: 'Commande confirmée', food: 'Restaurants', restaurant: 'Restaurant',
    foodCheckout: 'Paiement', foodTrack: 'Suivre la commande', services: 'Services', svcCategory: 'Catégorie de service',
    providers: 'Prestataires', provider: 'Prestataire', book: 'Réserver un service', quote: 'Devis', quoteRequest: 'Demander des devis', quoteCompare: 'Comparer les devis', orders: 'Commandes et réservations',
    account: 'Compte', problem: 'Signaler un problème', signIn: 'Se connecter', register: 'Créer un compte', sell: 'Vendre ou offrir un service',
  },
};
export const useScreenT = defineMessages(SCREEN_NAMES);

/** `<title>` of a screen: "Cart · Northline" (routes' `head`). */
export const pageTitle = (locale: 'en' | 'fr', screen: ScreenKey) => `${SCREEN_NAMES[locale][screen]} · Northline`;
