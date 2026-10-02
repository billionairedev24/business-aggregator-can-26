import { useCallback } from 'react';
import { defineMessages, type Translate } from '@northline/ui';
import { KIT_MESSAGES, useAuthKitT, type AuthKitKey } from '@northline/auth-kit';

/**
 * Consumer sign-in / create account (design 06 `auth`: `authKicker`, `authHero`, `authPoints`, … in the prototype's
 * logic). English is the design's; French from its `T(…)` pairs and design/i18n-fr.js, the rest written in fr-CA.
 * Validation and error messages come from @northline/auth-kit (shared with the Studio).
 */
const usePageT = defineMessages({
  en: {
    // sign in
    kickerSignIn: 'Welcome back',
    heroSignIn: 'Sign in to pick up where you left off.',
    heroSubSignIn: 'Your cart, bookings and points are waiting. Sign in with your passkey or a code to your phone — nothing to remember.',
    pointSignIn1: 'Passkeys, not passwords', pointSignIn1Body: 'Face ID, Touch ID or Windows Hello. Phishing-resistant by design.',
    pointSignIn2: 'Two factors for payments', pointSignIn2Body: 'Escrow and saved cards sit behind a second factor.',
    pointSignIn3: 'Your data stays in Canada', pointSignIn3Body: 'Stored in ca-central-1 under {privacyLaw}.',
    subSignIn: 'Welcome back. Use your passkey or a code to your phone.',
    legalSignIn: 'Trouble signing in? Use a code to your phone, or contact support — we never ask for a password.',
    signIn: 'Sign in',
    continue: 'Continue',
    passkeySignIn: 'Sign in with a passkey',
    newHere: 'New to Northline?',
    // create account
    kickerRegister: 'Join Northline · {province}',
    heroRegister: 'One account for groceries, hot food and every trusted local.',
    heroSubRegister: 'No passwords. Your phone and a passkey are your login; your address decides what you see. Data stays in Canada.',
    pointRegister1: 'Paid into escrow', pointRegister1Body: 'Providers and shops are paid only after you confirm — or 48 hours pass with no dispute.',
    pointRegister2: 'Verified, not just listed', pointRegister2Body: 'Licences, insurance and permits are checked before anyone appears.',
    pointRegister3: 'Points on everything', pointRegister3Body: '1 point per dollar across services, groceries and food; Plus doubles it.',
    subRegister: "A phone number is all we need. No passwords — you'll set a passkey.",
    legalRegisterBefore: 'By continuing you agree to the ', legalRegisterAnd: ' and ', legalRegisterAfter: '. Standard message rates may apply for codes.',
    createAccount: 'Create account',
    haveAccount: 'Already have an account?',
    fullName: 'Full name', fullNamePh: 'First and last name',
    mobile: 'Mobile number', mobilePh: '+1 (403) …',
    email: 'Email (receipts)', emailPh: 'you@example.ca',
    termsBefore: 'I agree to the ', terms: 'Terms', termsAnd: ' and ', privacy: 'Privacy Policy', termsAfter: '. Data stays in Canada.',
    // S-116: French-first places (region configuration) — the Terms in French first, English on request
    termsFrenchFirst: 'The Terms are shown in French.', termsEnglish: 'Show me the English version', termsEnglishChosen: 'You asked for the Terms in English.', termsFrench: 'Back to French',
    termsFrenchPending: 'The Terms and Privacy Policy are only available in English for now; the French version is coming.',
    sendCode: 'Send code', sending: 'Sending…',
    attention: '{count, plural, one {# thing needs attention.} other {# things need attention.}}',
    // shared
    foot: 'Bilingual support 7 am – 11 pm MT · northline.ca/help',
    cancel: 'Back to browsing',
    progress: 'Progress',
    newTab: '(opens in a new tab)',
    // code
    codeTo: '6-digit code sent to {phone}',
    codeToAccount: 'Enter the 6-digit code we sent to the mobile number on your account.',
    codePh: '······',
    resend: 'Resend code', callMe: 'Call me instead', calling: 'Calling you now with a new code.', resent: 'New code sent.',
    verify: 'Verify', back: 'Back', verifyCode: 'Verify code',
    // second factor
    mfaLabel: 'Second factor',
    mfaPasskey: 'Passkey (Face ID / Touch ID)', mfaPasskeyDesc: 'Recommended · phishing-resistant',
    mfaTotp: 'Authenticator app', mfaTotpDesc: 'Time-based codes',
    mfaSms: 'SMS code', mfaSmsDesc: 'Backup only',
    createPasskey: 'Create passkey', scanQr: 'Scan QR code', continueSms: 'Continue with SMS',
    qrAlt: 'QR code for your authenticator app',
    qrHelp: 'Scan this with your authenticator app, then enter the 6-digit code it shows.',
    qrKey: "Can't scan? Enter this key: {secret}",
    code6: '6-digit code',
    // done
    signedIn: 'Signed in.', signedInBody: 'Your cart and bookings are back.',
    created: 'Account created.', createdBody: "Set your delivery address next so we can show your province's shops.",
    setAddress: 'Set my address',
    // federation (S-18)
    federationLink: 'Enter the code we sent to your mobile to link your {provider} account.',
    federationRegister: 'You’re signed in with {provider}. Add your mobile number to finish creating your account.',
    federationRelay: 'Your email is Apple’s Hide My Email address: messages from Northline reach you through it.',
    signInFailed: "Signing in didn't finish. Try again.",
  },
  fr: {
    kickerSignIn: 'Bon retour',
    heroSignIn: 'Connectez-vous pour reprendre où vous en étiez.',
    heroSubSignIn: "Votre panier, vos réservations et vos points vous attendent. Connectez-vous avec votre clé d'accès ou un code envoyé à votre téléphone.",
    pointSignIn1: "Des clés d'accès, pas de mots de passe", pointSignIn1Body: "Face ID, Touch ID ou Windows Hello. Résistantes à l'hameçonnage par conception.",
    pointSignIn2: 'Deux facteurs pour les paiements', pointSignIn2Body: 'La fiducie et les cartes enregistrées sont protégées par un deuxième facteur.',
    pointSignIn3: 'Vos données restent au Canada', pointSignIn3Body: 'Stockées dans ca-central-1 selon {privacyLaw}.',
    subSignIn: "Bon retour. Utilisez votre clé d'accès ou un code envoyé à votre téléphone.",
    legalSignIn: 'Un problème ? Utilisez un code envoyé à votre téléphone ou contactez le soutien.',
    signIn: 'Se connecter',
    continue: 'Continuer',
    passkeySignIn: "Se connecter avec une clé d'accès",
    newHere: 'Nouveau sur Northline ?',
    kickerRegister: 'Rejoindre Northline · {province}',
    heroRegister: "Un seul compte pour l'épicerie, les repas chauds et tous les commerçants de confiance.",
    heroSubRegister: "Pas de mot de passe. Votre téléphone et une clé d'accès suffisent; votre adresse détermine ce que vous voyez. Vos données restent au Canada.",
    pointRegister1: 'Payé en fiducie', pointRegister1Body: "Les prestataires et les commerces ne sont payés qu'après votre confirmation — ou après 48 heures sans litige.",
    pointRegister2: 'Vérifiés, pas seulement inscrits', pointRegister2Body: 'Permis, assurances et autorisations sont vérifiés avant toute inscription.',
    pointRegister3: 'Des points sur tout', pointRegister3Body: "1 point par dollar sur les services, l'épicerie et les repas; Plus les double.",
    subRegister: "Un numéro de téléphone suffit. Pas de mot de passe — vous créerez une clé d'accès.",
    legalRegisterBefore: 'En continuant, vous acceptez les ', legalRegisterAnd: ' et la ', legalRegisterAfter: '. Des frais de messagerie standard peuvent s’appliquer aux codes.',
    createAccount: 'Créer un compte',
    haveAccount: 'Vous avez déjà un compte ?',
    fullName: 'Nom complet', fullNamePh: 'Prénom et nom',
    mobile: 'Numéro de mobile', mobilePh: '+1 (403) …',
    email: 'Courriel (reçus)', emailPh: 'vous@exemple.ca',
    termsBefore: "J'accepte les ", terms: 'Conditions', termsAnd: ' et la ', privacy: 'Politique de confidentialité', termsAfter: '. Les données restent au Canada.',
    termsFrenchFirst: 'Les conditions vous sont présentées en français.', termsEnglish: 'Voir la version anglaise', termsEnglishChosen: 'Vous avez demandé les conditions en anglais.', termsFrench: 'Revenir au français',
    termsFrenchPending: 'Les conditions et la politique de confidentialité ne sont offertes qu’en anglais pour le moment; la version française s’en vient.',
    sendCode: 'Envoyer le code', sending: 'Envoi…',
    attention: '{count, plural, one {# élément à corriger.} other {# éléments à corriger.}}',
    foot: 'Soutien bilingue de 7 h à 23 h (HR) · northline.ca/aide',
    cancel: 'Retour',
    progress: 'Progression',
    newTab: "(s'ouvre dans un nouvel onglet)",
    codeTo: 'Code à 6 chiffres envoyé au {phone}',
    codeToAccount: 'Entrez le code à 6 chiffres envoyé au numéro de mobile de votre compte.',
    codePh: '······',
    resend: 'Renvoyer le code', callMe: 'M’appeler plutôt', calling: 'Nous vous appelons avec un nouveau code.', resent: 'Nouveau code envoyé.',
    verify: 'Vérifier', back: 'Retour', verifyCode: 'Vérifier le code',
    mfaLabel: 'Deuxième facteur',
    mfaPasskey: "Clé d'accès (Face ID / Touch ID)", mfaPasskeyDesc: "Recommandé · résiste à l'hameçonnage",
    mfaTotp: "Application d'authentification", mfaTotpDesc: 'Codes temporaires',
    mfaSms: 'Code SMS', mfaSmsDesc: 'Secours seulement',
    createPasskey: "Créer une clé d'accès", scanQr: 'Balayer le code QR', continueSms: 'Continuer avec le SMS',
    qrAlt: "Code QR pour votre application d'authentification",
    qrHelp: "Balayez-le avec votre application d'authentification, puis entrez le code à 6 chiffres affiché.",
    qrKey: 'Impossible de balayer ? Entrez cette clé : {secret}',
    code6: 'Code à 6 chiffres',
    signedIn: 'Connecté.', signedInBody: 'Votre panier et vos réservations sont de retour.',
    created: 'Compte créé.', createdBody: 'Indiquez ensuite votre adresse pour voir les commerces de votre province.',
    setAddress: 'Saisir mon adresse',
    federationLink: 'Entrez le code envoyé à votre mobile pour lier votre compte {provider}.',
    federationRegister: 'Vous êtes connecté avec {provider}. Ajoutez votre numéro de mobile pour terminer la création de votre compte.',
    federationRelay: 'Votre courriel est une adresse « Masquer mon adresse courriel » d’Apple : les messages de Northline vous parviennent par elle.',
    signInFailed: "La connexion n'a pas abouti. Réessayez.",
  },
});

type PageKey = Parameters<ReturnType<typeof usePageT>>[0];
export type AuthKey = PageKey | AuthKitKey;
export type AuthT = Translate<AuthKey>;

/** Page copy and the shared validation / error messages. */
export function useAuthT(): AuthT {
  const page = usePageT();
  const kit = useAuthKitT();
  return useCallback((key, values) => (key in KIT_MESSAGES.en ? kit(key as AuthKitKey, values) : page(key as PageKey, values)), [page, kit]);
}
