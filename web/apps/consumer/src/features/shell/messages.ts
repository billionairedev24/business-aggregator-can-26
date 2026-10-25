import { defineMessages } from '@northline/ui';
import { brand } from '@northline/tokens';
import type { ScreenKey } from './screens';

/** Shell copy — design 06 (`t` in the prototype's logic) and design/i18n-fr.js for French. */
export const useShellT = defineMessages({
  en: {
    services: 'Services', shop: 'Shop', food: 'Food',
    search: 'Search {brand}',
    hello: 'Hello, {name}', helloSignedIn: 'Hello', helloGuest: 'Hello, sign in', accountOrders: 'Account & orders',
    deals: 'Today’s deals', returns: 'Returns & refunds', copyright: '© {entity}',
    // the delivery cut-off strip (shop's next pooled run)
    cutoffLabel: 'Delivery cut-off',
    cutoffLead: '{when, select, tonight {Tonight’s} today {Today’s} tomorrow {Tomorrow’s} other {{weekday}’s}} pooled run to {area} closes at {time}.',
    cutoffRest: 'Mix shops for one {fee} delivery.',
    cutoffLeft: '{hours, plural, =0 {} other {# h }}{minutes} min left',
    signIn: 'Sign in', createAccount: 'Create account',
    skip: 'Skip to content',
    privacy: 'Privacy', terms: 'Terms', helpLink: 'Help', langSwitch: 'Français', photoCredits: 'Photo credits',
    sell: 'Sell on {brand}', offer: 'Offer a service', kitchen: 'Run a kitchen',
    footerBusiness: 'For businesses', footerHelp: 'Help and policies', footerLanguage: 'Language',
    // account menu
    activity: 'Activity', account: 'Account', preferences: 'Preferences',
    orders: 'Orders', bookings: 'Bookings', messages: 'Messages', quoteRequests: 'Quote requests', yourAccount: 'Your account', favourites: 'Saved', wallet: 'Wallet & points', profile: 'Profile',
    addresses: 'Addresses', payments: 'Payment methods', security: 'Login & security',
    notifications: 'Notifications', language: 'Language / Langue & region', dietary: 'Dietary & accessibility',
    plus: '{brand} Plus', help: 'Help & cases', sellOrOffer: 'Sell or offer a service', signOut: 'Sign out',
    addPhoto: 'Add photo', reliability: '{email} · reliability {score}',
    active: '{count} active', open: '{count} open', points: '{count} pts', plusActive: 'Active', plusTry: 'Try free',
    plusTag: 'Plus · 2× points', standardTag: 'Standard', members: '{count} · {members} members',
    quiet: 'Quiet {from}–{to}', passkey: 'Passkey', authenticator: 'Authenticator app', sms: 'SMS code',
    languageValue: 'English · {province}',
    // states
    pendingKicker: 'Design 06 · {state}', pendingBody: 'This screen is being built ({story}).', backHome: 'Back to home',
    notFoundTitle: 'We couldn’t find that page.', notFoundBody: 'The link may be old, or the page has moved.',
    errorTitle: 'Something went wrong on our side.', retry: 'Retry',
    unreachableTitle: 'We can’t reach the shop right now.',
    unreachableBody: 'Our servers aren’t answering at the moment. It’s not you — we’ll keep trying.',
  },
  fr: {
    services: 'Services', shop: 'Boutique', food: 'Restaurants',
    search: 'Rechercher sur {brand}',
    hello: 'Bonjour, {name}', helloSignedIn: 'Bonjour', helloGuest: 'Bonjour, connectez-vous', accountOrders: 'Compte et commandes',
    deals: 'Aubaines du jour', returns: 'Retours et remboursements', copyright: '© {entity}',
    cutoffLabel: 'Heure limite de livraison',
    cutoffLead: 'La tournée partagée {when, select, tonight {de ce soir} today {d’aujourd’hui} tomorrow {de demain} other {de {weekday}}} vers {area} ferme à {time}.',
    cutoffRest: 'Combinez les boutiques pour une seule livraison à {fee}.',
    cutoffLeft: 'Encore {hours, plural, =0 {} other {# h }}{minutes} min',
    signIn: 'Se connecter', createAccount: 'Créer un compte',
    skip: 'Aller au contenu',
    privacy: 'Confidentialité', terms: 'Conditions', helpLink: 'Aide', langSwitch: 'English', photoCredits: 'Crédits photo',
    sell: 'Vendre sur {brand}', offer: 'Offrir un service', kitchen: 'Gérer une cuisine',
    footerBusiness: 'Pour les entreprises', footerHelp: 'Aide et politiques', footerLanguage: 'Langue',
    activity: 'Activité', account: 'Compte', preferences: 'Préférences',
    orders: 'Commandes', bookings: 'Réservations', messages: 'Messages', quoteRequests: 'Demandes de devis', yourAccount: 'Votre compte', favourites: 'Enregistrés', wallet: 'Portefeuille et points', profile: 'Profil',
    addresses: 'Adresses', payments: 'Modes de paiement', security: 'Connexion et sécurité',
    notifications: 'Notifications', language: 'Langue / Language et région', dietary: 'Alimentation et accessibilité',
    plus: '{brand} Plus', help: 'Aide et dossiers', sellOrOffer: 'Vendre ou offrir un service', signOut: 'Se déconnecter',
    addPhoto: 'Ajouter une photo', reliability: '{email} · fiabilité {score}',
    active: '{count} active(s)', open: '{count} ouvert(s)', points: '{count} pts', plusActive: 'Actif', plusTry: 'Essayer gratuitement',
    plusTag: 'Plus · points ×2', standardTag: 'Standard', members: '{count} · {members} membres',
    quiet: 'Silence {from}–{to}', passkey: "Clé d'accès", authenticator: "Application d'authentification", sms: 'Code SMS',
    languageValue: 'Français · {province}',
    pendingKicker: 'Maquette 06 · {state}', pendingBody: "Cet écran est en cours de construction ({story}).", backHome: "Retour à l'accueil",
    notFoundTitle: 'Page introuvable.', notFoundBody: 'Le lien est peut-être ancien, ou la page a été déplacée.',
    errorTitle: 'Un problème est survenu de notre côté.', retry: 'Réessayer',
    unreachableTitle: 'Impossible de joindre la boutique pour le moment.',
    unreachableBody: 'Nos serveurs ne répondent pas en ce moment. Le problème ne vient pas de vous — nous réessayons.',
  },
});

/** Screen names (page titles, ScreenPending). */
export const SCREEN_NAMES: { en: Record<ScreenKey, string>; fr: Record<ScreenKey, string> } = {
  en: {
    home: 'Home', location: 'Where should we bring things?', search: 'Search', shop: 'Shop', category: 'Department', store: 'Store',
    product: 'Product', cart: 'Cart', checkout: 'Secure checkout', checkoutComplete: 'Order placed', confirmed: 'Your order', food: 'Food', restaurant: 'Restaurant',
    foodCheckout: 'Checkout', foodTrack: 'Track order', foodGroup: 'Group order', services: 'Services', svcCategory: 'Service category',
    providers: 'Providers', provider: 'Provider', book: 'Book a service', booking: 'Your booking', quote: 'Quote', quoteRequest: 'Request quotes', quoteCompare: 'Compare quotes', orders: 'Orders & bookings', messages: 'Messages',
    account: 'Account', accountDelete: 'Delete account', accountDeletion: 'Deleting your account', problem: 'Report a problem', signIn: 'Sign in', register: 'Create account', forgotPassword: 'Forgot password', resetPassword: 'Reset password', verifyEmail: 'Verify email', sell: 'Sell or offer a service', help: 'Help', credits: 'Photo credits',
  },
  fr: {
    home: 'Accueil', location: 'Où devons-nous livrer ?', search: 'Rechercher', shop: 'Boutique', category: 'Rayon', store: 'Commerce',
    product: 'Produit', cart: 'Panier', checkout: 'Paiement sécurisé', checkoutComplete: 'Commande passée', confirmed: 'Votre commande', food: 'Restaurants', restaurant: 'Restaurant',
    foodCheckout: 'Paiement', foodTrack: 'Suivre la commande', foodGroup: 'Commande de groupe', services: 'Services', svcCategory: 'Catégorie de service',
    providers: 'Prestataires', provider: 'Prestataire', book: 'Réserver un service', booking: 'Votre réservation', quote: 'Devis', quoteRequest: 'Demander des devis', quoteCompare: 'Comparer les devis', orders: 'Commandes et réservations', messages: 'Messages',
    account: 'Compte', accountDelete: 'Supprimer le compte', accountDeletion: 'Supprimer votre compte', problem: 'Signaler un problème', signIn: 'Se connecter', register: 'Créer un compte', forgotPassword: 'Mot de passe oublié', resetPassword: 'Réinitialiser le mot de passe', verifyEmail: 'Vérifier le courriel', sell: 'Vendre ou offrir un service', help: 'Aide', credits: 'Crédits photo',
  },
};
export const useScreenT = defineMessages(SCREEN_NAMES);

/** `<title>` of a screen: "Cart · <brand>" (routes' `head`). */
export const pageTitle = (locale: 'en' | 'fr', screen: ScreenKey) => `${SCREEN_NAMES[locale][screen]} · ${brand.name}`;
