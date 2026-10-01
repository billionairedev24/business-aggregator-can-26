---
sidebar_position: 1
title: Pour commencer
slug: /
---

# Northline pour les développeurs

Northline est une place de marché canadienne de services, de produits et de repas. Ces guides et la
[référence de l’API](/api/) décrivent ce qu’une intégration peut utiliser :

- **API partenaires** : lire (et, lorsque c’est permis, modifier) les données des entreprises liées à votre
  intégration.
- **Webhooks** : des événements signés sur ces entreprises, comme une réservation terminée, un paiement libéré ou
  un remboursement émis.
- **OAuth 2.1 / OpenID Connect** : comment chaque client obtient un jeton.

Chaque référence s’ouvre dans deux visionneuses : **Redoc** pour la lecture et **Scalar** pour essayer des
requêtes. Vous pouvez aussi télécharger le fichier OpenAPI 3.1 dont elle est générée.

## Conventions

| Sujet | Règle |
| --- | --- |
| Chemin de base | `/api/v1` |
| Format | JSON, camelCase |
| Identifiants | [ULID](https://github.com/ulid/spec) : 26 caractères, triés par date |
| Montants | cents entiers de dollars canadiens (`priceCents: 12345` = 123,45 $) |
| Dates | instants ISO-8601 en UTC |
| Listes | `{ "items": [ … ] }` |
| Erreurs de validation | `422 { "errors": [ { "field", "rule", "message" } ] }`, une erreur par champ |
| Autres erreurs | `application/problem+json` (RFC 9457) avec un `code` stable, par exemple `not_bound` ou `partner_not_allowed` |
| Idempotence | les POST qui déplacent de l’argent acceptent un en-tête `Idempotency-Key`, conservé 24 heures |

## Ensuite

1. [Authentifiez-vous](authentication.md) avec des identifiants client et une assertion signée.
2. [Recevez les webhooks](webhooks.md) et vérifiez leur signature.
