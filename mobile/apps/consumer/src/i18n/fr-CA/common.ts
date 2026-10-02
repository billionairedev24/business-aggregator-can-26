import type { common as en } from '../en/common';

export const common: { [k in keyof typeof en]: string } = {
  'app.name': 'Northline',
  'common.back': 'Retour',
  'common.retry': 'Réessayer',
  'common.loading': 'Chargement…',
  'common.offline': 'Vous êtes hors ligne. Northline se mettra à jour à votre retour en ligne.',
  'common.network': 'Impossible de joindre Northline. Vérifiez votre connexion et réessayez.',
  'common.error': 'Un problème est survenu. Réessayez.',
  'common.serverError': 'Northline éprouve des difficultés en ce moment. Réessayez dans un instant.',
  'common.tooMany': 'Trop de tentatives. Patientez un moment, puis réessayez.',
  'common.signedOut': 'Votre connexion a pris fin. Reconnectez-vous pour continuer.',
  'common.signIn': 'Se connecter',
  'common.createAccount': 'Créer un compte',
  'common.opensInBrowser': '(s’ouvre dans votre navigateur)',

  'tab.label': 'Sections principales',
  'tab.home': 'Accueil',
  'tab.services': 'Services',
  'tab.cart': 'Panier',
  'tab.orders': 'Commandes',
  'tab.account': 'Vous',
  'tab.cartCount': '{n, plural, one {# article} other {# articles}} dans votre panier',

  'browserSignIn.body': 'Connectez-vous sur le site Northline dans votre navigateur, avec une clé d’accès, Google ou Apple. Vous revenez ici une fois connecté.',
  'browserSignIn.button': 'Se connecter dans le navigateur',
  'browserSignIn.cancelled': 'La connexion a été annulée.',
  'browserSignIn.failed': 'Impossible de vous connecter. {detail}',
  'browserSignIn.webOnly': 'La connexion dans le navigateur fonctionne sur iPhone et Android. Cet aperçu a besoin du serveur de démonstration.',

  'stub.body': 'Cet écran est en construction ({story}).',
  'stub.api': 'Il utilisera {apis}.',
};
