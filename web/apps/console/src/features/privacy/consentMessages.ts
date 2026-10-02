import { defineMessages } from '@northline/ui';

/** CASL proof of consent on the privacy screen (S-108). No design screen exists: copy ours, in the console's voice. */
export const useConsentT = defineMessages({
  en: {
    title: 'Consent to marketing (CASL)',
    lede: 'Proof of consent to Northline’s marketing messages: who agreed to what, when, where and to which wording, and every withdrawal. Search by account id, email or phone — records are kept three years after a withdrawal, even after an account is deleted.',
    query: 'Account id, email or phone', search: 'Search', empty: 'No consent records for this person.', loadError: 'The consent records couldn’t load. Try again.',
    colWhen: 'When', colAccount: 'Account', colWhat: 'What', colAction: 'Consent', colWhere: 'Where', colWording: 'Wording', colNetwork: 'Network',
    cat_marketing_email: 'Marketing email', cat_marketing_sms: 'Marketing texts', cat_marketing_push: 'Promotional push',
    act_granted: 'Given', act_withdrawn: 'Withdrawn',
    src_web_signup: 'Sign-up (web)', src_app_signup: 'Sign-up (app)', src_web_settings: 'Account settings (web)', src_app_settings: 'Account settings (app)',
    src_checkout: 'Checkout', src_studio: 'Studio', src_import: 'Import', src_unsubscribe_link: 'Unsubscribe link', src_list_unsubscribe: 'Mailbox one-click',
    src_sms_keyword: 'STOP reply', src_console: 'Staff, on request', src_erasure: 'Account erased',
    withdraw: 'Withdraw {what} for {account}', withdrawn: 'Withdrawn. The person won’t get it any more.', withdrawError: 'That wasn’t saved. Try again.',
  },
  fr: {
    title: 'Consentement à la publicité (LCAP)',
    lede: 'La preuve du consentement aux messages publicitaires de Northline : qui a accepté quoi, quand, où et selon quel texte, et chaque retrait. Cherchez par identifiant de compte, courriel ou téléphone — les registres sont gardés trois ans après un retrait, même après la suppression d’un compte.',
    query: 'Identifiant de compte, courriel ou téléphone', search: 'Chercher', empty: 'Aucun registre de consentement pour cette personne.', loadError: 'Les registres de consentement n’ont pas pu se charger. Réessayez.',
    colWhen: 'Quand', colAccount: 'Compte', colWhat: 'Quoi', colAction: 'Consentement', colWhere: 'Où', colWording: 'Texte', colNetwork: 'Réseau',
    cat_marketing_email: 'Courriels publicitaires', cat_marketing_sms: 'Textos publicitaires', cat_marketing_push: 'Notifications promotionnelles',
    act_granted: 'Donné', act_withdrawn: 'Retiré',
    src_web_signup: 'Inscription (Web)', src_app_signup: 'Inscription (appli)', src_web_settings: 'Paramètres du compte (Web)', src_app_settings: 'Paramètres du compte (appli)',
    src_checkout: 'Paiement', src_studio: 'Studio', src_import: 'Importation', src_unsubscribe_link: 'Lien de désabonnement', src_list_unsubscribe: 'Désabonnement en un clic de la messagerie',
    src_sms_keyword: 'Réponse ARRET', src_console: 'Personnel, sur demande', src_erasure: 'Compte supprimé',
    withdraw: 'Retirer {what} pour {account}', withdrawn: 'Retiré. La personne ne le recevra plus.', withdrawError: 'Ce n’a pas été enregistré. Réessayez.',
  },
});
export type ConsentT = ReturnType<typeof useConsentT>;
