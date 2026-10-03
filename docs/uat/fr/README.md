---
title: "Tests d’acceptation avec le groupe pilote"
---

# Tests d’acceptation avec le groupe pilote (S-121)

Comment Northline mène les tests d’acceptation avec ses commerçants, clients, livreurs et membres du personnel du
pilote : qui participe, quel scénario chacun suit, comment les commentaires arrivent à l’équipe et sont triés, comment
chacun approuve, et comment se décide le feu vert. Anglais : [../README.md](../README.md). L’essai à blanc :
[../dry-run.md](../dry-run.md).

## Les éléments

| élément | où |
|---|---|
| Qui participe (l’indicateur de participant au pilote) | console › Tests du pilote › Participants; `uat.participants` |
| Bouton « Envoyer un commentaire » dans le produit | Studio, site client, console (bouton dans le coin); application client (bouton flottant « Commentaire ») — participants seulement |
| File de tri | console › Tests du pilote › Commentaires; export CSV |
| Scénarios et formulaires d’approbation, par profil, en/fr | ce dossier (ci-dessous) |
| Approbation par participant et par scénario | console › Tests du pilote › Participants › Consigner l’approbation |
| Rapport de feu vert | console › Tests du pilote › Feu vert; CSV; `uat.api.UatReadiness` pour la liste de contrôle de mise en service (S-118) |

Accès à la console : l’écran **Tests du pilote** s’ouvre pour le **soutien**, le **responsable du soutien** et
l’**administrateur**; les trois peuvent trier, ajouter des participants et consigner des approbations (action `uat`).
Chaque changement figure au journal d’audit avec le rôle utilisé.

## Scénarios et formulaires

| profil | scénario | formulaire |
|---|---|---|
| Commerçant — prestataire de services | [merchant-provider](merchant-provider.md) | [formulaire](merchant-provider-signoff.md) |
| Commerçant — vendeur | [merchant-seller](merchant-seller.md) | [formulaire](merchant-seller-signoff.md) |
| Commerçant — cuisine | [merchant-kitchen](merchant-kitchen.md) | [formulaire](merchant-kitchen-signoff.md) |
| Client | [customer](customer.md) | [formulaire](customer-signoff.md) |
| Livreur | [courier](courier.md) | [formulaire](courier-signoff.md) |
| Personnel de la console | [console-staff](console-staff.md) | [formulaire](console-staff-signoff.md) |

Chaque scénario donne les étapes, le résultat attendu et une colonne réussi / échoué. Les étapes suivent les parcours
critiques de la suite de bout en bout (S-117) et les écrans de [SCREENS.md](../../SCREENS.md), du point de vue du
participant. La version du scénario est dans `uat.scripts`; une approbation garde la version suivie.

## Déroulement

1. **Former le groupe.** Les commerçants viennent de la cohorte pilote (intégration S-120). Ajoutez chacun dans Tests du
   pilote › Participants : une entreprise par son identifiant (le profil doit correspondre au type d’entreprise), une
   personne par le courriel ou le numéro de cellulaire de son compte Northline (client, livreur, personnel). Le nom de
   travail (« Cuisine pilote 3 ») n’est pas le nom de la personne. Au moins un participant par profil.
2. **Remettre le scénario et le formulaire** dans la langue du participant.
3. **Les participants suivent le scénario** dans l’environnement pilote et envoient leurs commentaires depuis le produit.
   Ils inscrivent le numéro (UAT-…) à côté d’une étape échouée.
4. **Trier chaque jour** (soutien; le responsable du soutien est maître de la file) :
   - `new` → `triaged` : lire, reproduire si possible, nommer un **responsable**.
   - `triaged` → `accepted`, en décidant **bloquant ou non** : bloquant = un participant de ce profil ne peut pas
     terminer une étape, l’argent ou des renseignements personnels sont erronés, ou les règles d’accessibilité ou de
     français ne sont pas respectées. Le reste n’est pas bloquant.
   - Lier le **billet de suivi** (toute adresse Web).
   - `duplicate` fusionne un élément dans celui qu’il répète; `wont_fix` avec une note. Les deux peuvent être rouverts.
   - `accepted` → `fixed` quand le correctif est déployé dans l’environnement pilote; `fixed` → `verified` après
     vérification (idéalement par la personne qui l’a signalé); un échec revient à `accepted`. `verified` → `closed`.
   - La gravité indiquée par le participant est un indice, pas la décision. Un signalement « bloquant » non trié
     empêche le feu vert jusqu’à son tri.
5. **Consigner les approbations** le jour où les formulaires reviennent : approuvé, approuvé avec commentaires, ou bloqué
   (avec les éléments UAT-… ou une description). La plus récente compte; les précédentes restent dans l’historique.
6. **Feu vert** quand : aucun élément bloquant ouvert (accepté et non corrigé) ni en attente de vérification (corrigé et
   non vérifié); aucun signalement bloquant en attente de tri; chaque profil a au moins un participant actif et chaque
   participant actif a approuvé (avec ou sans commentaires). Les raisons disent ce qui manque. Exportez le CSV pour la
   réunion de décision.

## Ce que contient un commentaire

- Ce que la personne a choisi et écrit, l’**écran** sous forme de chemin (sans hôte, sans paramètres de requête ni
  fragment; un segment qui ressemble à un jeton devient `:token`), la **version** de l’application, la **langue**, le
  **navigateur et le système** — rien d’autre de l’appareil.
- Le texte, l’écran et la ligne d’appareil passent par le masquage des journaux (S-112) avant d’être enregistrés.
- **Capture d’écran** (Web seulement, facultative) : la capture d’écran du navigateur ou une image jointe, PNG ou JPEG,
  5 Mo au plus, vérifiée comme le demande S-104. Visible seulement par le personnel de l’écran Tests du pilote.
- Accès, rectification et effacement (S-105) : le module `uat` exporte les commentaires et la participation de la
  personne; l’effacement retire ses mots, sa ligne d’appareil et ses captures, et remplace son identifiant.
