import { defineMessages, messagesFor, type Locale } from '@northline/ui';

/**
 * Help (S-145, WCAG 3.2.6 Consistent Help): the page the footer's Help link opens on every consumer page, for guests and
 * signed-in people alike. Not in design 06 (its help is the account's "Help & cases"); the copy repeats rules the
 * product already has (escrow release, card entry, the accessibility statement's promise). French: ours.
 */
const HELP = {
  en: {
    title: 'Help · Northline',
    description: 'Get help with an order, a booking or a payment on Northline, with or without an account.',
    heading: 'Help',
    lede: 'Answers for everyone — with an account or without one.',
    problemTitle: 'A problem with an order, a booking or a quote',
    problemSignedIn: 'Report it from the order or booking: it opens a case our team follows with you in Help & cases.',
    problemGuest: 'Orders, bookings and quotes belong to an account. Sign in to report a problem — it opens a case our team follows with you.',
    toOrders: 'Orders & bookings', toCases: 'Help & cases', signIn: 'Sign in for help with an order',
    guestTitle: 'Shopping as a guest',
    guestBody: 'You can browse and fill a cart without an account. Paying, booking and asking for quotes need one — sign-in takes a passkey, an authenticator app or a code we text you.',
    payTitle: 'Payments',
    payBody: 'You enter card details only in Stripe’s secure form; Northline never sees your card number. We hold the payment until the job is done: 48 hours after a service is completed, 7 days after goods are delivered, and at handoff for food.',
    a11yTitle: 'Accessibility',
    a11yBody: 'Every page works with a keyboard and with screen readers, in English and in French. If something stops you, tell us — we answer within two business days and can give you the information in another format.',
    contactTitle: 'Contact us',
    contactSignedIn: 'Open a case in Help & cases and we answer you there.',
    contactGuest: 'Sign in and open a case in Help & cases, and we answer you there.',
    contactEmail: 'Or write to {email}.',
    businessTitle: 'Selling or offering a service?',
    businessBody: 'Northline Studio has its own help for businesses.',
    business: 'Sell or offer a service',
  },
  fr: {
    title: 'Aide · Northline',
    description: 'Obtenez de l’aide pour une commande, une réservation ou un paiement sur Northline, avec ou sans compte.',
    heading: 'Aide',
    lede: 'Des réponses pour tout le monde — avec ou sans compte.',
    problemTitle: 'Un problème avec une commande, une réservation ou un devis',
    problemSignedIn: 'Signalez-le depuis la commande ou la réservation : un dossier s’ouvre et notre équipe le suit avec vous dans Aide et dossiers.',
    problemGuest: 'Les commandes, réservations et devis sont liés à un compte. Connectez-vous pour signaler un problème — un dossier s’ouvre et notre équipe le suit avec vous.',
    toOrders: 'Commandes et réservations', toCases: 'Aide et dossiers', signIn: 'Se connecter pour obtenir de l’aide',
    guestTitle: 'Magasiner sans compte',
    guestBody: 'Vous pouvez parcourir le site et remplir un panier sans compte. Payer, réserver et demander des devis en exigent un — la connexion se fait avec une clé d’accès, une application d’authentification ou un code reçu par texto.',
    payTitle: 'Paiements',
    payBody: 'Vous saisissez votre carte uniquement dans le formulaire sécurisé de Stripe; Northline ne voit jamais votre numéro de carte. Nous retenons le paiement jusqu’à ce que le travail soit fait : 48 heures après un service terminé, 7 jours après la livraison des biens, et à la remise pour la nourriture.',
    a11yTitle: 'Accessibilité',
    a11yBody: 'Chaque page s’utilise au clavier et avec un lecteur d’écran, en français et en anglais. Si quelque chose vous bloque, dites-le-nous — nous répondons en deux jours ouvrables et pouvons vous fournir l’information sous un autre format.',
    contactTitle: 'Nous joindre',
    contactSignedIn: 'Ouvrez un dossier dans Aide et dossiers; nous vous y répondons.',
    contactGuest: 'Connectez-vous et ouvrez un dossier dans Aide et dossiers; nous vous y répondons.',
    contactEmail: 'Ou écrivez à {email}.',
    businessTitle: 'Vous vendez ou offrez un service?',
    businessBody: 'Northline Studio a sa propre aide pour les entreprises.',
    business: 'Vendre ou offrir un service',
  },
};

export const useHelpT = defineMessages(HELP);
/** The same catalogue for the route's head (title, description), before render. */
export const helpText = (locale: Locale) => messagesFor(HELP, locale);
