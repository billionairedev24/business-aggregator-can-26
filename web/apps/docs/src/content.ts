/**
 * What the documentation site contains, per variant (S-126, docs/runbooks/docs-site.md).
 *
 * - `public` — what anyone may read, also in production on docs.<zone>: the guides of this app (guides/) and the
 *   public, partner, webhook and OAuth references.
 * - `internal` — everything: the repository's docs/ (architecture, data model, runbooks, decisions, security review,
 *   backlog, plans and conventions) and every OpenAPI document, including the internal ones. Served only where the
 *   edge restricts it (an IP allowlist, see the chart's apps.docs-internal), never as the public site.
 *
 * The repository's docs/ are rendered in place (the docs plugin's `path`); nothing is copied but the specs, which
 * scripts/sync.mjs puts in static/openapi so Scalar and the download links can fetch them.
 */
export type Variant = 'public' | 'internal';
export type Locale = 'en' | 'fr';

export interface ApiSpec {
  /** File name without extension in docs/api/openapi, also the route segment: /api/<id>/. */
  id: string;
  service: 'api' | 'auth' | 'bff' | 'consumer-bff' | 'console-bff';
  audience: 'public' | 'internal';
  title: Record<Locale, string>;
  summary: Record<Locale, string>;
}

export const SPECS: readonly ApiSpec[] = [
  {
    id: 'api-public',
    service: 'api',
    audience: 'public',
    title: { en: 'Public & consumer API', fr: 'API publique et consommateurs' },
    summary: {
      en: 'Storefronts, catalogue and search without signing in; the customer’s cart, orders and profile.',
      fr: 'Vitrines, catalogue et recherche sans connexion; le panier, les commandes et le profil du client.',
    },
  },
  {
    id: 'api-partner',
    service: 'api',
    audience: 'public',
    title: { en: 'Partner API', fr: 'API partenaires' },
    summary: {
      en: 'For integrations: client credentials with private_key_jwt, the businesses bound to your partner client.',
      fr: 'Pour les intégrations : identifiants client avec private_key_jwt, pour les entreprises liées à votre client partenaire.',
    },
  },
  {
    id: 'api-webhooks',
    service: 'api',
    audience: 'public',
    title: { en: 'Webhooks', fr: 'Webhooks' },
    summary: {
      en: 'The signed events Northline sends to your endpoints, with every payload schema.',
      fr: 'Les événements signés que Northline envoie à vos points de terminaison, avec le schéma de chaque contenu.',
    },
  },
  {
    id: 'auth-public',
    service: 'auth',
    audience: 'public',
    title: { en: 'OAuth 2.1 / OpenID Connect', fr: 'OAuth 2.1 / OpenID Connect' },
    summary: {
      en: 'Discovery, keys, authorize and token endpoints: PKCE, DPoP for mobile apps, private_key_jwt for partners.',
      fr: 'Découverte, clés, points d’autorisation et de jetons : PKCE, DPoP pour les applis mobiles, private_key_jwt pour les partenaires.',
    },
  },
  {
    id: 'api-studio',
    service: 'api',
    audience: 'internal',
    title: { en: 'Studio API', fr: 'API Studio' },
    summary: { en: 'The business Studio through the studio-bff.', fr: 'Le Studio des entreprises, par le studio-bff.' },
  },
  {
    id: 'api-console',
    service: 'api',
    audience: 'internal',
    title: { en: 'Console API (staff)', fr: 'API Console (personnel)' },
    summary: { en: 'Platform staff, role STAFF with a second factor.', fr: 'Personnel de la plateforme, rôle STAFF avec second facteur.' },
  },
  {
    id: 'api-internal',
    service: 'api',
    audience: 'internal',
    title: { en: 'Internal callbacks', fr: 'Rappels internes' },
    summary: { en: 'Provider webhooks, OAuth callbacks, email links and local dev tools.', fr: 'Webhooks des fournisseurs, rappels OAuth, liens de courriel et outils locaux.' },
  },
  {
    id: 'auth-internal',
    service: 'auth',
    audience: 'internal',
    title: { en: 'Sign-in JSON API', fr: 'API JSON de connexion' },
    summary: { en: 'The first-party sign-in, registration and security pages.', fr: 'Les pages de connexion, d’inscription et de sécurité de Northline.' },
  },
  {
    id: 'bff-internal',
    service: 'bff',
    audience: 'internal',
    title: { en: 'studio-bff session API', fr: 'API de session du studio-bff' },
    summary: { en: 'GET /bff/session, login hand-off, logout.', fr: 'GET /bff/session, relais de connexion, déconnexion.' },
  },
  {
    id: 'bff-consumer-internal',
    service: 'consumer-bff',
    audience: 'internal',
    title: { en: 'consumer-bff session API', fr: 'API de session du consumer-bff' },
    summary: { en: 'The consumer web’s session, guests included.', fr: 'La session du site consommateurs, invités compris.' },
  },
  {
    id: 'bff-console-internal',
    service: 'console-bff',
    audience: 'internal',
    title: { en: 'console-bff session API', fr: 'API de session du console-bff' },
    summary: { en: 'The platform console’s session: staff with a second factor only.', fr: 'La session de la console de la plateforme : personnel avec deuxième facteur seulement.' },
  },
];

/** The specs a variant publishes: public ones everywhere, internal ones only in the internal variant. */
export function specsFor(variant: Variant): ApiSpec[] {
  return SPECS.filter((spec) => variant === 'internal' || spec.audience === 'public');
}

/**
 * The repository docs the internal variant renders (globs relative to docs/). spec/ (machine-readable JSON),
 * api/ (the YAML specs, rendered as the API reference instead), the backlog CSV and the agents' workstream brief stay
 * out. ai/ is the AI section (Spring AI + OpenRouter, a separate story): rendered as soon as it has pages.
 */
export const REPO_DOCS_INCLUDE: readonly string[] = [
  '*.md',
  'runbooks/**/*.md',
  'security/**/*.md',
  'compliance/**/*.md',
  'backlog/README.md',
  'ai/**/*.{md,mdx}',
  'a11y/**/*.md',
  'perf/**/*.md',
  'uat/**/*.md',
];
export const REPO_DOCS_EXCLUDE: readonly string[] = ['WORKSTREAM_BRIEF.md', 'spec/**', 'api/**'];

export function variantFrom(value: string | undefined): Variant {
  if (value === undefined || value === '') return 'internal';
  if (value === 'public' || value === 'internal') return value;
  throw new Error(`NORTHLINE_DOCS_VARIANT must be "public" or "internal" (got "${value}")`);
}

/** Route of a spec's Redoc page, and of its Scalar page. */
export const redocRoute = (spec: ApiSpec) => `/api/${spec.id}/`;
export const scalarRoute = (spec: ApiSpec) => `/api/${spec.id}/scalar`;
