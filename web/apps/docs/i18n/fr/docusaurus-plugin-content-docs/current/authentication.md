---
sidebar_position: 2
title: Authentification
---

# Authentification

Chaque jeton provient du serveur d’autorisation de Northline (OAuth 2.1 et OpenID Connect). Pour les points de
terminaison, voyez la [référence OAuth 2.1 / OpenID Connect](/api/auth-public/); la découverte se trouve à
`/.well-known/openid-configuration`.

## Partenaires : identifiants client avec `private_key_jwt`

Votre intégration est inscrite comme client `partner:<nom>`, avec vos clés publiques (une URL de JWK Set, ou des
clés EC P-256 / RSA ≥ 2048 enregistrées) et les entreprises pour lesquelles elle peut agir. Il n’y a pas de secret
client. Vous prouvez votre identité avec un JWT que vous signez :

```http
POST /oauth2/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
&client_id=partner:acme
&scope=api.read
&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
&client_assertion=<JWT>
```

L’assertion doit respecter toutes ces règles :

- Elle est signée en ES256, RS256 ou PS256 par une clé enregistrée pour vous.
- `iss` et `sub` sont votre identifiant client.
- `aud` est l’émetteur ou le point de jetons.
- `exp` et `iat` sont présents, et l’assertion est valide 5 minutes au plus.
- `jti` est unique : chaque assertion ne sert qu’une fois.

Le jeton dure 15 minutes. Il porte `api.read` ou `api.write` (ou les deux) et n’ouvre que les entreprises liées à
votre client :

- une entreprise hors de cette liste répond `403 not_bound`;
- un point de terminaison non ouvert aux partenaires répond `403 partner_not_allowed`.

Vous pouvez demander au plus 60 jetons par heure. Au-delà, le serveur répond `429` avec `Retry-After`. Réutilisez
chaque jeton jusqu’à son expiration.

## Applications : code d’autorisation avec PKCE, et DPoP sur mobile

Les applications de Northline connectent les utilisateurs par le flux de code d’autorisation avec PKCE (`S256`
seulement).

- **Applications Web** : elles ne détiennent jamais de jeton; c’est leur serveur intermédiaire (BFF) qui les garde.
- **Applications mobiles** : elles lient leurs jetons à une clé avec DPoP (RFC 9449). Elles envoient
  `Authorization: DPoP <jeton>` avec un en-tête de preuve `DPoP`. Un jeton lié par DPoP envoyé comme jeton Bearer
  est refusé.
- **Jetons d’actualisation** : ils changent à chaque utilisation.
