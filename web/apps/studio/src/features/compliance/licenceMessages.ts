import { defineMessages } from '@northline/ui';

/** Compliance › Age-restricted sales (2026-10-04). Ours (no design drawing); en + fr-CA. */
export const useLicenceT = defineMessages({
  en: {
    title: 'Age-restricted sales',
    lede: 'To list alcohol, tobacco and vape, or cannabis accessories, upload your licence for them in your province. Northline reviews it; those listings stay hidden until it’s approved and are hidden again if it expires. Customers verify their age once, and couriers check photo ID at the door.',
    none: 'No licence on file. Your age-restricted listings stay hidden until one is approved.',
    row: '{cls} · {number} · expires {expires}',
    licensed: 'You can sell: {classes}.',
    add: 'Add a licence',
    addTitle: 'Add a licence for age-restricted sales',
    addLede: 'Your liquor licence, or your tobacco and vape retail permit, as issued in your province. A renewal is a new licence: the approved one replaces the old.',
    class: 'Products', number: 'Licence or permit number', numberHint: 'As printed on the licence.', expires: 'Expiry date',
    document: 'The licence (PDF, PNG or JPEG under 10 MB)', documentHint: 'A clear scan or photo of the whole licence.',
    chooseFile: 'Choose a file', replaceFile: 'Choose another file',
    send: 'Send for review', cancel: 'Cancel', error: 'The licence couldn’t be sent. Try again.',
    cls_alcohol: 'Alcohol', cls_tobacco: 'Tobacco and vape', cls_cannabis: 'Cannabis accessories',
    st_pending: 'In review', st_approved: 'Approved', st_rejected: 'Not approved', st_expired: 'Expired', st_replaced: 'Replaced',
    why_unreadable: 'We couldn’t read the document', why_wrong_class: 'It doesn’t cover these products', why_wrong_business: 'It’s in another business’s name',
    why_expired: 'It has expired', why_not_valid: 'We couldn’t confirm it with the issuing body', why_other: 'Another reason',
  },
  fr: {
    title: 'Ventes soumises à un âge minimal',
    lede: 'Pour publier de l’alcool, du tabac et des produits de vapotage, ou des accessoires de cannabis, téléversez votre permis pour ces produits dans votre province. Northline l’examine; ces annonces restent masquées jusqu’à son approbation et sont masquées de nouveau s’il expire. Les clients vérifient leur âge une fois, et les livreurs vérifient la pièce d’identité avec photo à la porte.',
    none: 'Aucun permis au dossier. Vos annonces soumises à un âge minimal restent masquées jusqu’à l’approbation d’un permis.',
    row: '{cls} · {number} · expire le {expires}',
    licensed: 'Vous pouvez vendre : {classes}.',
    add: 'Ajouter un permis',
    addTitle: 'Ajouter un permis pour les ventes soumises à un âge minimal',
    addLede: 'Votre permis d’alcool, ou votre permis de vente de tabac et de produits de vapotage, tel que délivré dans votre province. Un renouvellement est un nouveau permis : celui qui est approuvé remplace l’ancien.',
    class: 'Produits', number: 'Numéro du permis', numberHint: 'Tel qu’imprimé sur le permis.', expires: 'Date d’expiration',
    document: 'Le permis (PDF, PNG ou JPEG de moins de 10 Mo)', documentHint: 'Une numérisation ou une photo nette du permis entier.',
    chooseFile: 'Choisir un fichier', replaceFile: 'Choisir un autre fichier',
    send: 'Envoyer pour examen', cancel: 'Annuler', error: 'Le permis n’a pas pu être envoyé. Réessayez.',
    cls_alcohol: 'Alcool', cls_tobacco: 'Tabac et vapotage', cls_cannabis: 'Accessoires de cannabis',
    st_pending: 'En examen', st_approved: 'Approuvé', st_rejected: 'Non approuvé', st_expired: 'Expiré', st_replaced: 'Remplacé',
    why_unreadable: 'Nous n’avons pas pu lire le document', why_wrong_class: 'Il ne couvre pas ces produits', why_wrong_business: 'Il est au nom d’une autre entreprise',
    why_expired: 'Il est expiré', why_not_valid: 'Nous n’avons pas pu le confirmer auprès de l’organisme qui l’a délivré', why_other: 'Une autre raison',
  },
});
export type LicenceT = ReturnType<typeof useLicenceT>;
