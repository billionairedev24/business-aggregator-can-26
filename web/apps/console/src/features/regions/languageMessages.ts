import { defineMessages } from '@northline/ui';

/** S-158: the switchboard's language toggles (S-116 French-first readiness; our copy). */
export const useLanguageT = defineMessages({
  en: {
    title: 'Languages · {name}',
    lede: 'French-first sets every app to French there unless the person chose English, shows the Terms in French first and writes receipts in French. A French-first place always requires French listing text.',
    place: 'Place', provinceRow: 'The province', frenchFirst: 'French first', frenchListings: 'French listing text',
    ff_inherit: 'As the province', ff_unset: 'No rule', ff_yes: 'Yes', ff_no: 'No',
    fl_inherit: 'As the province', fl_unset: 'Not set (off)', fl_off: 'Off', fl_warn: 'Warn the merchant', fl_require: 'Required before going live',
    applies: 'Applies: {first, select, true {French first} other {person’s language}} · listings {listings}',
    lst_off: 'off', lst_warn: 'warn', lst_require: 'required',
    configured: 'French first by configuration (REGION_FRENCH_FIRST): the toggle can’t turn it off.',
    save: 'Save', saved: 'Saved', saveLabel: 'Save the language rules of {place}', audited: 'Every change is audit-logged.',
    cannot: 'Only admins change the language rules.', failed: 'That didn’t work. Try again.',
  },
  fr: {
    title: 'Langues · {name}',
    lede: 'Le français d’abord met toutes les applications en français à cet endroit sauf si la personne a choisi l’anglais, présente les Conditions en français d’abord et rédige les reçus en français. Un endroit en français d’abord exige toujours le texte des annonces en français.',
    place: 'Endroit', provinceRow: 'La province', frenchFirst: 'Français d’abord', frenchListings: 'Texte des annonces en français',
    ff_inherit: 'Comme la province', ff_unset: 'Aucune règle', ff_yes: 'Oui', ff_no: 'Non',
    fl_inherit: 'Comme la province', fl_unset: 'Non défini (désactivé)', fl_off: 'Désactivé', fl_warn: 'Avertir le commerçant', fl_require: 'Exigé avant la mise en ligne',
    applies: 'S’applique : {first, select, true {français d’abord} other {langue de la personne}} · annonces {listings}',
    lst_off: 'désactivé', lst_warn: 'avertissement', lst_require: 'exigé',
    configured: 'Français d’abord par configuration (REGION_FRENCH_FIRST) : l’interrupteur ne peut pas le désactiver.',
    save: 'Enregistrer', saved: 'Enregistré', saveLabel: 'Enregistrer les règles de langue de {place}', audited: 'Chaque changement est consigné au journal d’audit.',
    cannot: 'Seuls les administrateurs modifient les règles de langue.', failed: 'Cela n’a pas fonctionné. Réessayez.',
  },
});
