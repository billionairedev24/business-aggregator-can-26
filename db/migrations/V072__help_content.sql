-- 2026-09-29 (messaging, help & reviews workstream): help centre content, en + fr-CA.
-- Topics and suggested articles are the design's (design/02 › help: helpTopics, helpArticles); the other articles fill
-- the topics. Platform content, not dev data: it ships in every environment and the console edits it later.

CREATE FUNCTION pg_temp.i18n(en text, fr text) RETURNS jsonb LANGUAGE sql IMMUTABLE AS $f$ SELECT jsonb_build_object('en', en, 'fr', fr) $f$;

INSERT INTO messaging.help_topics (key, portals, position, case_topic, name_i18n) VALUES
  ('verification', '{provider,seller,both}', 1, 'verification', pg_temp.i18n('Getting verified · licences & insurance', 'Vérification · permis et assurances')),
  ('appointments', '{provider,seller,both}', 2, 'appointments', pg_temp.i18n('Appointments, availability & no-shows', 'Rendez-vous, disponibilités et absences')),
  ('listings',     '{provider,seller,both}', 3, 'listings',     pg_temp.i18n('Listings, products & bulk upload', 'Annonces, produits et import en lot')),
  ('escrow',       '{provider,seller,both}', 4, 'payouts',      pg_temp.i18n('Escrow, payouts & fees', 'Séquestre, versements et frais')),
  ('refunds',      '{provider,seller,both}', 5, 'refunds',      pg_temp.i18n('Refunds & disputes', 'Remboursements et litiges')),
  ('storefront',   '{provider,seller,both}', 6, 'api',          pg_temp.i18n('Storefront, API & integrations', 'Vitrine, API et intégrations')),
  ('k_verification', '{kitchen}', 1, 'verification', pg_temp.i18n('Getting verified · AHS permits & inspections', 'Vérification · permis AHS et inspections')),
  ('k_menus',        '{kitchen}', 2, 'listings',     pg_temp.i18n('Menus, modifiers & combos', 'Menus, options et combos')),
  ('k_live',         '{kitchen}', 3, 'other',        pg_temp.i18n('Live orders & prep times', 'Commandes en direct et temps de préparation')),
  ('k_allergens',    '{kitchen}', 4, 'listings',     pg_temp.i18n('Allergens & labelling rules', 'Allergènes et règles d''étiquetage')),
  ('k_payouts',      '{kitchen}', 5, 'payouts',      pg_temp.i18n('Payouts, fees & GST', 'Versements, frais et TPS')),
  ('k_refunds',      '{kitchen}', 6, 'refunds',      pg_temp.i18n('Refunds, late orders & disputes', 'Remboursements, retards et litiges'));

INSERT INTO messaging.help_articles (id, slug, topic_keys, portals, read_min, featured, section_i18n, title_i18n, body_i18n) VALUES
-- Studio (provider · seller · both) — suggested
('01J9ZD3VHA0000000000000001', 'completion-photo', '{escrow}', '{provider,seller,both}', 2, 1,
  pg_temp.i18n('Escrow', 'Séquestre'),
  pg_temp.i18n('What the completion photo must show', 'Ce que la photo de fin de travail doit montrer'),
  pg_temp.i18n($$Every job ends with a completion photo. It is the customer's proof that the work was done and your proof if they dispute it.

Show the finished work itself (the new pads behind the wheel, the installed part, the cleaned area) in focus and in daylight or with a light. Include something that ties it to the job: the vehicle, the address or the part box.

Jobs without a completion photo release 48 hours later than usual and count against your quality score.$$,
$$Chaque travail se termine par une photo de fin. C'est la preuve pour le client que le travail est fait, et votre preuve en cas de litige.

Montrez le travail terminé lui-même (les plaquettes neuves derrière la roue, la pièce installée, la zone nettoyée), net, à la lumière du jour ou avec un éclairage. Ajoutez un élément qui le relie au travail : le véhicule, l'adresse ou la boîte de la pièce.

Sans photo de fin, le paiement est libéré 48 heures plus tard que d'habitude et votre score de qualité baisse.$$)),
('01J9ZD3VHA0000000000000002', 'quality-score-tiers', '{verification}', '{provider,seller,both}', 5, 2,
  pg_temp.i18n('Reputation', 'Réputation'),
  pg_temp.i18n('How the quality score and tiers are calculated', 'Comment le score de qualité et les niveaux sont calculés'),
  pg_temp.i18n($$Your quality score (0–100) is recalculated every night from five parts: on-time arrival, completion photos, replies to customers within 2 hours, the re-book rate and the dispute rate.

Tiers are reviewed on the first of each month. Master needs a score of 85 or more and a dispute rate under 1 %. Trusted needs 70 or more. Winning a dispute never penalises you; losing one counts once.

The dashboard shows each part against its floor, so you can see which one to work on first.$$,
$$Votre score de qualité (0 à 100) est recalculé chaque nuit à partir de cinq éléments : ponctualité, photos de fin de travail, réponses aux clients en moins de 2 heures, taux de nouvelles réservations et taux de litiges.

Les niveaux sont revus le premier de chaque mois. Le niveau Maître exige un score d'au moins 85 et un taux de litiges inférieur à 1 %. Le niveau Fiable exige 70 ou plus. Gagner un litige ne vous pénalise jamais; en perdre un compte une seule fois.

Le tableau de bord montre chaque élément par rapport à son seuil pour savoir sur quoi travailler en premier.$$)),
('01J9ZD3VHA0000000000000003', 'contest-auto-refund', '{refunds}', '{provider,seller,both}', 3, 3,
  pg_temp.i18n('Disputes', 'Litiges'),
  pg_temp.i18n('Contesting an auto-refund within 48 h', 'Contester un remboursement automatique en 48 h'),
  pg_temp.i18n($$Some refunds are approved automatically, for example when an order is marked not delivered. You have 48 hours to contest one from Refunds & disputes.

Attach your evidence: the completion photo, the delivery photo, messages with the customer. A Northline agent decides within 2 business days. If you win, the refund is reversed and the money goes back into escrow.$$,
$$Certains remboursements sont approuvés automatiquement, par exemple quand une commande est déclarée non livrée. Vous avez 48 heures pour le contester depuis Remboursements et litiges.

Joignez vos preuves : photo de fin de travail, photo de livraison, messages avec le client. Un agent Northline tranche en 2 jours ouvrables. Si vous avez gain de cause, le remboursement est annulé et l'argent retourne en séquestre.$$)),
('01J9ZD3VHA0000000000000004', 'split-shifts-travel-buffers', '{appointments}', '{provider,seller,both}', 3, 4,
  pg_temp.i18n('Availability', 'Disponibilités'),
  pg_temp.i18n('Split shifts and travel buffers', 'Quarts fractionnés et temps de déplacement'),
  pg_temp.i18n($$Add more than one block to a day (for example 7–11 am and 1–6 pm) to create a split shift. Customers only see slots inside your blocks.

A travel buffer is added after each job before the next slot opens. Set it per service; mobile jobs across town usually need 20–30 minutes.$$,
$$Ajoutez plus d'une plage à une journée (par exemple 7 h–11 h et 13 h–18 h) pour créer un quart fractionné. Les clients ne voient que les créneaux à l'intérieur de vos plages.

Un temps de déplacement est ajouté après chaque travail avant l'ouverture du créneau suivant. Réglez-le par service; les travaux mobiles d'un bout à l'autre de la ville demandent en général 20 à 30 minutes.$$)),
-- Studio — the design's search results for "payout on hold"
('01J9ZD3VHA0000000000000005', 'payout-on-hold', '{escrow,k_payouts}', '{provider,seller,both,kitchen}', 2, NULL,
  pg_temp.i18n('Escrow', 'Séquestre'),
  pg_temp.i18n('Why is my payout on hold?', 'Pourquoi mon versement est-il retenu?'),
  pg_temp.i18n($$A payout is held when Stripe needs something from you (an ID, a bank statement, a business number), when a document on file has lapsed, or when a dispute is open on money in that payout.

Open Stripe & compliance: anything Stripe needs is listed at the top with a button to fix it. Payouts resume on the next scheduled day once the hold is cleared.$$,
$$Un versement est retenu quand Stripe a besoin de quelque chose (pièce d'identité, relevé bancaire, numéro d'entreprise), quand un document au dossier est expiré, ou quand un litige est ouvert sur un montant de ce versement.

Ouvrez Stripe et conformité : tout ce dont Stripe a besoin est listé en haut avec un bouton pour le régler. Les versements reprennent à la prochaine date prévue une fois la retenue levée.$$)),
('01J9ZD3VHA0000000000000006', 'auto-release-48h', '{escrow}', '{provider,seller,both}', 3, NULL,
  pg_temp.i18n('Escrow', 'Séquestre'),
  pg_temp.i18n('How the 48-hour auto-release works', 'Comment fonctionne la libération automatique après 48 heures'),
  pg_temp.i18n($$The customer's payment is held in escrow until the job is done. When you mark a service complete (with the completion photo), the customer can sign off at once; if they don't, the money releases automatically 48 hours later.

Goods release 7 days after delivery and food on handoff. The timer for each job shows in Earnings. A dispute opened before release pauses the timer, and released money goes out with your next payout.$$,
$$Le paiement du client est retenu en séquestre jusqu'à la fin du travail. Quand vous marquez un service terminé (avec la photo de fin), le client peut confirmer tout de suite; sinon, l'argent est libéré automatiquement 48 heures plus tard.

Les produits sont libérés 7 jours après la livraison et les repas à la remise. La minuterie de chaque travail s'affiche dans Revenus. Un litige ouvert avant la libération met la minuterie en pause, et l'argent libéré part avec votre prochain versement.$$)),
('01J9ZD3VHA0000000000000007', 'instant-payouts', '{escrow,k_payouts}', '{provider,seller,both,kitchen}', 2, NULL,
  pg_temp.i18n('Payouts', 'Versements'),
  pg_temp.i18n('Instant payouts: fees and eligibility', 'Versements instantanés : frais et admissibilité'),
  pg_temp.i18n($$Released money is paid out every Friday at no cost. Instant payouts send released money to an eligible Canadian debit card within minutes, for a fee of 1 % (minimum $0.50).

You need 30 days on Northline and no open payout hold. Money still in escrow can't be paid out early.$$,
$$L'argent libéré est versé chaque vendredi sans frais. Les versements instantanés envoient l'argent libéré sur une carte de débit canadienne admissible en quelques minutes, moyennant des frais de 1 % (minimum 0,50 $).

Il faut 30 jours sur Northline et aucune retenue de versement en cours. L'argent encore en séquestre ne peut pas être versé à l'avance.$$)),
-- Studio — other articles per topic
('01J9ZD3VHA0000000000000008', 'licences-insurance', '{verification}', '{provider,seller,both}', 4, NULL,
  pg_temp.i18n('Verification', 'Vérification'),
  pg_temp.i18n('Which licences and insurance you need', 'Quels permis et assurances vous faut-il'),
  pg_temp.i18n($$Regulated categories need their licence before listings go live: AMVIC for automotive repair and sales, a trade certificate for electrical, gas and plumbing work.

Every provider needs commercial general liability insurance (at least $2 million) and a WCB clearance letter if you have workers. Upload them in Stripe & compliance; we check them within one business day and remind you 30 days before they expire.$$,
$$Les catégories réglementées exigent leur permis avant la mise en ligne : AMVIC pour la réparation et la vente automobile, un certificat de métier pour l'électricité, le gaz et la plomberie.

Chaque prestataire doit avoir une assurance responsabilité civile générale commerciale (au moins 2 millions $) et une lettre d'attestation de la WCB s'il a des employés. Téléversez-les dans Stripe et conformité; nous les vérifions en un jour ouvrable et vous prévenons 30 jours avant leur expiration.$$)),
('01J9ZD3VHA0000000000000009', 'wcb-clearance-renewal', '{verification}', '{provider,both}', 2, NULL,
  pg_temp.i18n('Verification', 'Vérification'),
  pg_temp.i18n('Renewing a WCB clearance letter', 'Renouveler une lettre d''attestation de la WCB'),
  pg_temp.i18n($$WCB Alberta clearance letters are valid for a limited time. Download a new one from your WCB online account and upload it in Stripe & compliance.

When a letter lapses, instant book pauses after 7 days; customers can still request quotes. Compliance re-verifies uploads within 4 business hours.$$,
$$Les lettres d'attestation de la WCB Alberta sont valides pour une durée limitée. Téléchargez-en une nouvelle depuis votre compte WCB en ligne et téléversez-la dans Stripe et conformité.

Quand une lettre expire, la réservation instantanée est suspendue après 7 jours; les clients peuvent toujours demander des devis. La conformité revérifie les documents en 4 heures ouvrables.$$)),
('01J9ZD3VHA000000000000000A', 'no-show-fee', '{appointments}', '{provider,both}', 2, NULL,
  pg_temp.i18n('Appointments', 'Rendez-vous'),
  pg_temp.i18n('Charging a no-show fee', 'Facturer des frais d''absence'),
  pg_temp.i18n($$If the customer isn't there within 15 minutes of the appointment, mark the job as a no-show from Appointments. The deposit held in escrow covers your fee; the rest is returned to the customer.

Use the ETA message before you leave so customers know you're coming; no-show claims with an ETA sent are rarely contested.$$,
$$Si le client n'est pas présent dans les 15 minutes suivant le rendez-vous, marquez le travail comme une absence dans Rendez-vous. Le dépôt retenu en séquestre couvre vos frais; le reste est rendu au client.

Envoyez l'heure d'arrivée avant de partir pour que le client sache que vous arrivez; les absences déclarées après un tel message sont rarement contestées.$$)),
('01J9ZD3VHA000000000000000B', 'bulk-upload-errors', '{listings}', '{provider,seller,both}', 4, NULL,
  pg_temp.i18n('Listings', 'Annonces'),
  pg_temp.i18n('Bulk upload: templates and common errors', 'Import en lot : modèles et erreurs fréquentes'),
  pg_temp.i18n($$Download the template for your category from Listings › Bulk upload, fill one row per product (or per variant with a shared parent SKU) and upload the file.

The most common errors are an invalid GTIN check digit, a missing required attribute for the category, and a price outside the usual range. Fix the rows listed in the error report and upload again; rows that passed are not duplicated.$$,
$$Téléchargez le modèle de votre catégorie depuis Annonces › Import en lot, remplissez une ligne par produit (ou par variante avec un SKU parent commun) et téléversez le fichier.

Les erreurs les plus fréquentes sont un chiffre de contrôle GTIN invalide, un attribut obligatoire manquant pour la catégorie et un prix hors de la fourchette habituelle. Corrigez les lignes du rapport d'erreurs et téléversez de nouveau; les lignes acceptées ne sont pas dupliquées.$$)),
('01J9ZD3VHA000000000000000C', 'shared-catalogue-matching', '{listings}', '{seller,both}', 3, NULL,
  pg_temp.i18n('Listings', 'Annonces'),
  pg_temp.i18n('Matching products to the shared catalogue', 'Associer vos produits au catalogue commun'),
  pg_temp.i18n($$Enter the GTIN (UPC or EAN) first. If the product is already in the Northline catalogue, your listing joins it: title, images and attributes are shared and you set price, stock and fulfilment.

No match? Saving creates the shared record and your photos become its images.$$,
$$Entrez d'abord le GTIN (CUP ou EAN). Si le produit est déjà dans le catalogue Northline, votre annonce s'y rattache : le titre, les images et les attributs sont communs et vous fixez le prix, le stock et l'expédition.

Aucune correspondance? L'enregistrement crée la fiche commune et vos photos deviennent ses images.$$)),
('01J9ZD3VHA000000000000000D', 'gst-by-category', '{listings}', '{provider,seller,both}', 2, NULL,
  pg_temp.i18n('Listings', 'Annonces'),
  pg_temp.i18n('Why the category decides GST', 'Pourquoi la catégorie détermine la TPS'),
  pg_temp.i18n($$Tax is charged from the listing's category: most goods and services in Alberta carry 5 % GST and no PST, and a few categories are zero-rated.

If an item shows the wrong tax, change its category in Listings rather than the price. The new rate applies to orders placed after the change.$$,
$$La taxe est calculée selon la catégorie de l'annonce : la plupart des biens et services en Alberta sont assujettis à la TPS de 5 % sans TVP, et quelques catégories sont détaxées.

Si un article affiche la mauvaise taxe, changez sa catégorie dans Annonces plutôt que le prix. Le nouveau taux s'applique aux commandes passées après la modification.$$)),
('01J9ZD3VHA000000000000000E', 'dispute-evidence', '{refunds}', '{provider,seller,both}', 3, NULL,
  pg_temp.i18n('Disputes', 'Litiges'),
  pg_temp.i18n('Responding to a dispute with evidence', 'Répondre à un litige avec des preuves'),
  pg_temp.i18n($$When a customer opens a dispute, the money for that job or order stays in escrow and you get a message from Northline support with a reply-by date.

Reply in the dispute with what happened and attach the completion or delivery photo and any messages. Keep everything on Northline: messages and payments outside the platform can't be used as evidence.$$,
$$Quand un client ouvre un litige, l'argent de ce travail ou de cette commande reste en séquestre et vous recevez un message du soutien Northline avec une date limite de réponse.

Répondez dans le litige en expliquant ce qui s'est passé et joignez la photo de fin ou de livraison et les messages. Gardez tout sur Northline : les messages et paiements hors plateforme ne peuvent pas servir de preuve.$$)),
('01J9ZD3VHA000000000000000F', 'storefront-embed', '{storefront}', '{provider,seller,both}', 2, NULL,
  pg_temp.i18n('Storefront', 'Vitrine'),
  pg_temp.i18n('Embedding your booking button on your own site', 'Intégrer votre bouton de réservation à votre site'),
  pg_temp.i18n($$Open your business page or store and choose Embed code. Paste the snippet into your website; it shows your live prices and opens Northline checkout, so payments stay protected by escrow.

Changes you publish in Studio appear in the embed immediately.$$,
$$Ouvrez votre page d'entreprise ou votre boutique et choisissez Code d'intégration. Collez l'extrait dans votre site; il affiche vos prix à jour et ouvre le paiement Northline, pour que les paiements restent protégés par le séquestre.

Les modifications publiées dans le Studio apparaissent aussitôt dans l'intégration.$$)),
('01J9ZD3VHA000000000000000G', 'api-keys-webhooks', '{storefront}', '{provider,seller,both}', 4, NULL,
  pg_temp.i18n('API', 'API'),
  pg_temp.i18n('API keys and webhooks', 'Clés d''API et webhooks'),
  pg_temp.i18n($$Owners create API keys in Settings › API. Keys are shown once; store them in your password manager.

Webhooks send booking, order and payout events to your endpoint, signed with your webhook secret. Failed deliveries are retried for 24 hours.$$,
$$Les propriétaires créent les clés d'API dans Paramètres › API. Une clé n'est affichée qu'une fois; conservez-la dans votre gestionnaire de mots de passe.

Les webhooks envoient les événements de réservation, de commande et de versement à votre point de terminaison, signés avec votre secret. Les envois échoués sont relancés pendant 24 heures.$$)),
-- Kitchen — suggested
('01J9ZD3VHA000000000000000H', 'ahs-permit-renewal', '{k_verification}', '{kitchen}', 3, 1,
  pg_temp.i18n('Verification', 'Vérification'),
  pg_temp.i18n('Renewing your AHS Food Handling Permit before it lapses', 'Renouveler votre permis de manipulation des aliments AHS avant son expiration'),
  pg_temp.i18n($$Alberta Health Services food handling permits must be current for your menu to stay live. We remind you 30 days before the expiry date on file.

Upload the renewed permit in Stripe & compliance. Until it is verified, scheduled orders still come in but new orders pause on the expiry date.$$,
$$Le permis de manipulation des aliments d'Alberta Health Services doit être valide pour que votre menu reste en ligne. Nous vous le rappelons 30 jours avant la date d'expiration au dossier.

Téléversez le permis renouvelé dans Stripe et conformité. Tant qu'il n'est pas vérifié, les commandes planifiées continuent d'arriver, mais les nouvelles commandes sont suspendues à la date d'expiration.$$)),
('01J9ZD3VHA000000000000000J', 'priority-allergens', '{k_allergens}', '{kitchen}', 4, 2,
  pg_temp.i18n('Allergens', 'Allergènes'),
  pg_temp.i18n('Declaring the 11 priority allergens per item', 'Déclarer les 11 allergènes prioritaires par plat'),
  pg_temp.i18n($$Health Canada lists priority allergens: peanuts, tree nuts, milk, eggs, fish, crustaceans and shellfish, sesame, soy, wheat and triticale, mustard, and sulphites (plus gluten sources).

Tick every allergen an item contains in the menu builder, including those in sauces and modifiers. Customers filter on them, so a missing allergen is treated as a safety issue.$$,
$$Santé Canada liste les allergènes prioritaires : arachides, noix, lait, œufs, poisson, crustacés et mollusques, sésame, soja, blé et triticale, moutarde et sulfites (ainsi que les sources de gluten).

Cochez chaque allergène présent dans un plat dans le créateur de menu, y compris ceux des sauces et des options. Les clients filtrent selon ces allergènes; un oubli est traité comme un enjeu de sécurité.$$)),
('01J9ZD3VHA000000000000000K', 'prep-time-pause', '{k_live}', '{kitchen}', 2, 3,
  pg_temp.i18n('Live orders', 'Commandes en direct'),
  pg_temp.i18n('Why orders pause when prep time is exceeded', 'Pourquoi les commandes sont suspendues quand le temps de préparation est dépassé'),
  pg_temp.i18n($$When several tickets run past the prep time customers were promised, new orders pause for a few minutes so the kitchen can catch up. Customers see "Not accepting orders right now"; scheduled orders still come in.

Use Busy · +5 min on Live orders before it gets that far: the prep time shown to customers goes up instead.$$,
$$Quand plusieurs bons dépassent le temps de préparation promis aux clients, les nouvelles commandes sont suspendues quelques minutes pour laisser la cuisine rattraper son retard. Les clients voient « Commandes non acceptées pour le moment »; les commandes planifiées continuent d'arriver.

Utilisez Occupé · +5 min dans Commandes en direct avant d'en arriver là : le temps de préparation affiché augmente plutôt.$$)),
('01J9ZD3VHA000000000000000M', 'combo-swappable-slots', '{k_menus}', '{kitchen}', 3, 4,
  pg_temp.i18n('Menus', 'Menus'),
  pg_temp.i18n('Setting up a combo with swappable slots', 'Créer un combo avec des choix interchangeables'),
  pg_temp.i18n($$A combo is a set of slots (main, side, drink) at one price. Each slot lists the items a customer may pick, with an optional upcharge for premium choices.

Create it in Modifiers & combos, then add it to a menu section. If an item in a slot is 86'd, the slot offers the remaining choices automatically.$$,
$$Un combo est un ensemble de choix (plat, accompagnement, boisson) à prix fixe. Chaque choix liste les plats offerts, avec un supplément facultatif pour les options haut de gamme.

Créez-le dans Options et combos, puis ajoutez-le à une section du menu. Si un plat d'un choix est épuisé, les autres options sont proposées automatiquement.$$)),
-- Kitchen — other articles per topic
('01J9ZD3VHA000000000000000N', 'health-inspection', '{k_verification}', '{kitchen}', 3, NULL,
  pg_temp.i18n('Verification', 'Vérification'),
  pg_temp.i18n('Preparing for a health inspection', 'Se préparer à une inspection sanitaire'),
  pg_temp.i18n($$Inspection reports are public in Alberta. Upload your latest report in Stripe & compliance; a clean report shows as a badge on your menu page.

If an inspection finds critical violations, your menu pauses until the follow-up report is uploaded.$$,
$$Les rapports d'inspection sont publics en Alberta. Téléversez votre plus récent rapport dans Stripe et conformité; un rapport sans infraction s'affiche comme un badge sur votre page de menu.

Si une inspection relève des infractions critiques, votre menu est suspendu jusqu'au dépôt du rapport de suivi.$$)),
('01J9ZD3VHA000000000000000P', 'modifier-groups', '{k_menus}', '{kitchen}', 3, NULL,
  pg_temp.i18n('Menus', 'Menus'),
  pg_temp.i18n('Modifier groups: required vs optional', 'Groupes d''options : obligatoires ou facultatifs'),
  pg_temp.i18n($$A required group makes the customer choose before adding the item (broth, size, spice). An optional group offers extras with a minimum and maximum number of picks.

Reuse groups across items so one price change updates the whole menu.$$,
$$Un groupe obligatoire force le client à choisir avant d'ajouter le plat (bouillon, format, piquant). Un groupe facultatif propose des extras avec un nombre minimum et maximum de choix.

Réutilisez les groupes entre les plats pour qu'un seul changement de prix mette tout le menu à jour.$$)),
('01J9ZD3VHA000000000000000Q', 'busy-mode', '{k_live}', '{kitchen}', 2, NULL,
  pg_temp.i18n('Live orders', 'Commandes en direct'),
  pg_temp.i18n('Busy mode and pausing orders', 'Mode occupé et suspension des commandes'),
  pg_temp.i18n($$Busy · +5 min adds five minutes to the prep time customers see, each time you press it. Reset returns to your usual prep time.

Pause stops new orders for 30 minutes and resumes by itself. Scheduled orders still come in while paused.$$,
$$Occupé · +5 min ajoute cinq minutes au temps de préparation affiché, à chaque pression. Réinitialiser revient à votre temps habituel.

Suspendre arrête les nouvelles commandes pendant 30 minutes, puis reprend tout seul. Les commandes planifiées continuent d'arriver pendant la pause.$$)),
('01J9ZD3VHA000000000000000R', 'packaged-food-labels', '{k_allergens}', '{kitchen}', 3, NULL,
  pg_temp.i18n('Allergens', 'Allergènes'),
  pg_temp.i18n('Labelling packaged food you sell', 'Étiqueter les aliments emballés que vous vendez'),
  pg_temp.i18n($$Sauces, broths and other packaged items sold to take home need a label with the product name, ingredients in descending order, allergens and a best-before date.

Add the label photo to the menu item; items without one stay hidden after vetting.$$,
$$Les sauces, bouillons et autres produits emballés vendus pour emporter doivent porter une étiquette avec le nom du produit, les ingrédients en ordre décroissant, les allergènes et une date de péremption.

Ajoutez la photo de l'étiquette au plat; sans elle, le plat reste masqué après la vérification.$$)),
('01J9ZD3VHA000000000000000S', 'late-order-refunds', '{k_refunds}', '{kitchen}', 2, NULL,
  pg_temp.i18n('Refunds', 'Remboursements'),
  pg_temp.i18n('Late orders: when customers are refunded', 'Commandes en retard : quand les clients sont remboursés'),
  pg_temp.i18n($$If an order is ready more than 30 minutes after the time shown at checkout, the customer may ask for a partial refund, which Northline approves automatically for the delivery fee.

Missing or wrong items are refunded after the customer sends a photo; you can contest within 48 hours from Refunds & disputes.$$,
$$Si une commande est prête plus de 30 minutes après l'heure affichée au paiement, le client peut demander un remboursement partiel, que Northline approuve automatiquement pour les frais de livraison.

Les articles manquants ou erronés sont remboursés après l'envoi d'une photo par le client; vous pouvez contester dans les 48 heures depuis Remboursements et litiges.$$)),
('01J9ZD3VHA000000000000000T', 'food-payout-release', '{k_payouts}', '{kitchen}', 2, NULL,
  pg_temp.i18n('Payouts', 'Versements'),
  pg_temp.i18n('When food payouts release', 'Quand les versements pour les repas sont libérés'),
  pg_temp.i18n($$Food orders release from escrow on handoff: when the courier picks up or the customer collects the order. Released money is paid out every Friday.

Your statement shows the 5 % GST collected on each order; Northline remits GST on its own fees.$$,
$$Les commandes de repas sont libérées du séquestre à la remise : quand le livreur ou le client récupère la commande. L'argent libéré est versé chaque vendredi.

Votre relevé indique la TPS de 5 % perçue sur chaque commande; Northline verse la TPS sur ses propres frais.$$));
