---
title: "Scénario de test d’acceptation — Commerçant — vendeur"
---

# Scénario de test d’acceptation — Commerçant — vendeur

| | |
|---|---|
| Profil | Commerçant — vendeur (`seller`) |
| Version | 1.0 |
| Pour qui | Le propriétaire (ou un employé) d’une boutique qui vend des produits sur Northline pendant le pilote. |
| Ce qu’il vous faut | Un ordinateur avec un navigateur à jour; votre connexion Northline avec un deuxième facteur; une photo de produit; un téléphone pour le côté client. |
| Tiré de | Parcours S-117 1 (intégration) et 3 (commande → livraison); SCREENS.md Studios (vendeur). |

[Version anglaise](../merchant-seller.md)

## Avant de commencer

1. Utilisez l’environnement pilote fourni par l’équipe, jamais la production.
2. Faites chaque étape dans l’ordre. Cochez **Réussi** si ce que vous voyez correspond au résultat attendu, **Échoué** sinon.
3. Pour chaque échec, et tout ce qui est déroutant, utilisez **Envoyer un commentaire** dans le produit (ou le formulaire, ou votre contact du pilote) et inscrivez le numéro (UAT-…) dans la colonne Notes.
4. N’écrivez ni mot de passe, ni numéro de carte, ni code dans un commentaire; l’écran, la version de l’application, votre langue et votre navigateur ou système de téléphone sont envoyés avec — rien d’autre.
5. À la fin, remplissez le formulaire d’approbation : [formulaire d’approbation](merchant-seller-signoff.md).

## Étapes

| No | Étape | Résultat attendu | Réussi / Échoué | Notes (UAT-…) |
|---|---|---|---|---|
| 1 | Connectez-vous au Studio avec votre deuxième facteur. | Vous arrivez au tableau de bord; « Envoyer un commentaire » est dans le coin. | ☐ Réussi ☐ Échoué | |
| 2 | Ajoutez un produit avec une photo, un prix et un stock. | Il est enregistré comme brouillon et passe au contrôle; la liste affiche son état. | ☐ Réussi ☐ Échoué | |
| 3 | Ajoutez un produit avec des tailles ou des couleurs (variantes). | Chaque variante a son propre stock et son prix. | ☐ Réussi ☐ Échoué | |
| 4 | Une fois approuvé, trouvez le produit sur le site client. | La page du produit est publique, avec le bon prix et le bon stock. | ☐ Réussi ☐ Échoué | |
| 5 | Depuis le téléphone client, achetez-le en livraison. | La commande apparaît dans Commandes comme nouvelle, avec les détails de livraison. | ☐ Réussi ☐ Échoué | |
| 6 | Marquez la commande comme emballée. | La commande indique « Emballée »; la répartition peut assigner un livreur. | ☐ Réussi ☐ Échoué | |
| 7 | Remettez la commande au livreur. | La commande indique ramassée; le client voit le livreur en route. | ☐ Réussi ☐ Échoué | |
| 8 | Après la livraison, rouvrez la commande. | Elle indique livrée; l’argent est retenu jusqu’à la date de libération (7 jours après la livraison). | ☐ Réussi ☐ Échoué | |
| 9 | Ouvrez Remboursements et consultez une demande (l’équipe du pilote peut en créer une). | Vous pouvez l’accepter ou y répondre; le client voit votre réponse. | ☐ Réussi ☐ Échoué | |
| 10 | Ouvrez Rapports et exportez un CSV. | Le fichier s’ouvre dans un tableur avec vos ventes. | ☐ Réussi ☐ Échoué | |
| 11 | Passez en français et refaites un écran. | Tout l’écran est en français. | ☐ Réussi ☐ Échoué | |
| 12 | Envoyez une note avec « Envoyer un commentaire ». | Vous recevez un numéro (UAT-…). | ☐ Réussi ☐ Échoué | |
