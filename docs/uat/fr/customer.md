---
title: "Scénario de test d’acceptation — Client"
---

# Scénario de test d’acceptation — Client

| | |
|---|---|
| Profil | Client (`customer`) |
| Version | 1.0 |
| Pour qui | Un client du pilote, sur le site client et dans l’application Northline. |
| Ce qu’il vous faut | Votre téléphone avec l’application pilote Northline (TestFlight ou la piste interne de Play) et un ordinateur avec un navigateur; une carte de test fournie par l’équipe (aucun vrai débit dans l’environnement du pilote). |
| Tiré de | Parcours S-117 2 (soumission → réservation → approbation) et 3 (commande → livraison); SCREENS.md Site client et Application client. |

[Version anglaise](../customer.md)

## Avant de commencer

1. Utilisez l’environnement pilote fourni par l’équipe, jamais la production.
2. Faites chaque étape dans l’ordre. Cochez **Réussi** si ce que vous voyez correspond au résultat attendu, **Échoué** sinon.
3. Pour chaque échec, et tout ce qui est déroutant, utilisez **Envoyer un commentaire** dans le produit (ou le formulaire, ou votre contact du pilote) et inscrivez le numéro (UAT-…) dans la colonne Notes.
4. N’écrivez ni mot de passe, ni numéro de carte, ni code dans un commentaire; l’écran, la version de l’application, votre langue et votre navigateur ou système de téléphone sont envoyés avec — rien d’autre.
5. À la fin, remplissez le formulaire d’approbation : [formulaire d’approbation](customer-signoff.md).

## Étapes

| No | Étape | Résultat attendu | Réussi / Échoué | Notes (UAT-…) |
|---|---|---|---|---|
| 1 | Inscrivez-vous (ou connectez-vous) dans l’application avec votre numéro et le code. | Vous êtes connecté; on vous demande un deuxième facteur ou une clé d’accès. | ☐ Réussi ☐ Échoué | |
| 2 | Indiquez votre adresse de livraison. | L’accueil montre ce qui est offert là où vous êtes. | ☐ Réussi ☐ Échoué | |
| 3 | Cherchez un produit et ouvrez-le. | La page du produit affiche le prix, le stock et les options de livraison. | ☐ Réussi ☐ Échoué | |
| 4 | Ajoutez-le au panier et payez avec la carte de test. | Vous voyez la confirmation avec le numéro de commande; un courriel arrive. | ☐ Réussi ☐ Échoué | |
| 5 | Suivez la livraison. | Vous voyez le livreur en route et votre NIP de remise. | ☐ Réussi ☐ Échoué | |
| 6 | Recevez la commande; donnez le NIP. | La commande indique livrée. | ☐ Réussi ☐ Échoué | |
| 7 | Demandez des soumissions à un prestataire pour un travail. | Vous pouvez comparer les soumissions reçues ligne par ligne. | ☐ Réussi ☐ Échoué | |
| 8 | Acceptez une soumission et payez le dépôt. | La réservation figure sous Commandes et réservations; le dépôt est retenu, pas encore versé au prestataire. | ☐ Réussi ☐ Échoué | |
| 9 | Quand le prestataire a terminé, approuvez le travail et laissez un avis. | La réservation indique terminée; votre avis paraît sur la page du prestataire. | ☐ Réussi ☐ Échoué | |
| 10 | Signalez un problème avec la commande (« Un problème? »). | Un dossier s’ouvre avec un numéro; vous pouvez le suivre sous Aide et dossiers. | ☐ Réussi ☐ Échoué | |
| 11 | Passez l’application et le site en français; refaites une étape. | Tout est en français, y compris les courriels et les textos. | ☐ Réussi ☐ Échoué | |
| 12 | Envoyez une note avec le bouton « Commentaire » de l’application et une depuis le site. | Chacune reçoit un numéro (UAT-…). | ☐ Réussi ☐ Échoué | |
