import { resolve } from 'node:path';
import type { Config } from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';
import type { PluginOptions as SearchOptions } from '@easyops-cn/docusaurus-search-local';
import { themes as prismThemes } from 'prism-react-renderer';
import { REPO_DOCS_EXCLUDE, REPO_DOCS_INCLUDE, redocRoute, scalarRoute, specsFor, variantFrom } from './src/content';

/*
 * Northline documentation site (S-126; docs/runbooks/docs-site.md).
 *   NORTHLINE_DOCS_VARIANT  public | internal (default internal)  — what the build contains (src/content.ts)
 *   DOCS_URL, DOCS_BASE_URL  the site's origin and base path (GitHub/GitLab Pages: /<project>/)
 * The repository's docs/ are rendered from where they are (docs plugin `path`), as CommonMark (`format: 'detect'`:
 * .md files are not MDX, so their `<` and `{` need no escaping).
 */
const variant = variantFrom(process.env.NORTHLINE_DOCS_VARIANT);
const internal = variant === 'internal';
// Docusaurus loads this file as CommonJS (jiti): __dirname, not import.meta.
// NORTHLINE_DOCS_DIR: the repository's docs/ elsewhere (the image build gets it as the named context repo-docs).
const repoDocs = resolve(process.env.NORTHLINE_DOCS_DIR ?? resolve(__dirname, '../../../docs'));
const specDir = resolve(repoDocs, 'api/openapi');
const baseUrl = process.env.DOCS_BASE_URL ?? '/';
const specs = specsFor(variant);

// Spruce & Honey (web/packages/tokens/tokens.json) for Redoc; the site itself uses the token CSS (src/css/custom.css).
const spruce = '#1E4D36';

const config: Config = {
  title: 'Northline',
  tagline: internal ? 'Engineering documentation' : 'Developer documentation',
  favicon: 'img/favicon.svg',
  url: process.env.DOCS_URL ?? 'http://localhost:3300',
  baseUrl,
  trailingSlash: true,
  // Links into the code (../../server/…) are fine on GitHub but not pages here: reported, not fatal.
  onBrokenLinks: internal ? 'warn' : 'throw',
  onBrokenAnchors: 'warn',
  markdown: { format: 'detect', hooks: { onBrokenMarkdownLinks: 'warn' } },
  noIndex: internal,
  // /config.js: runtime settings (Swagger UI links of the non-prod environments), written by nginx from NL_* at start.
  scripts: [{ src: `${baseUrl}config.js`, async: false }],
  customFields: { variant },
  i18n: {
    defaultLocale: 'en',
    locales: ['en', 'fr'],
    localeConfigs: { en: { label: 'English', htmlLang: 'en-CA' }, fr: { label: 'Français', htmlLang: 'fr-CA' } },
  },

  presets: [
    [
      'classic',
      {
        // The guides of this site (public in every variant): getting started, authentication, webhooks. The default
        // docs instance, so the theme and search always have one; fr pages in i18n/fr/docusaurus-plugin-content-docs.
        docs: { path: 'guides', routeBasePath: 'guides', sidebarPath: './sidebars-guides.ts' },
        blog: false,
        theme: { customCss: './src/css/custom.css' },
      } satisfies Preset.Options,
    ],
    [
      'redocusaurus',
      {
        specs: specs.map((spec) => ({ id: spec.id, spec: resolve(specDir, `${spec.id}.yaml`), route: redocRoute(spec) })),
        theme: { primaryColor: spruce, options: { requiredPropsFirst: true, expandResponses: '200,201' } },
      },
    ],
  ],

  plugins: [
    // The repository's docs/, rendered in place — internal variant only.
    ...(internal
      ? [
          [
            '@docusaurus/plugin-content-docs',
            {
              id: 'repo',
              path: repoDocs,
              routeBasePath: 'docs',
              include: [...REPO_DOCS_INCLUDE],
              exclude: [...REPO_DOCS_EXCLUDE],
              sidebarPath: './sidebars.ts',
              editUrl: 'https://github.com/billionairedev24/business-aggregator-can-26/edit/main/docs/',
            },
          ],
        ]
      : []),
    // Scalar, one page per spec, from the copies scripts/sync.mjs puts in static/openapi.
    ...specs.map((spec) => [
      '@scalar/docusaurus',
      {
        id: `scalar-${spec.id}`,
        label: spec.title.en,
        route: scalarRoute(spec),
        showNavLink: false,
        cdn: `${baseUrl}scalar/standalone.js`, // self-hosted, pinned (scripts/sync.mjs)
        configuration: {
          url: `${baseUrl}openapi/${spec.id}.yaml`,
          withDefaultFonts: false, // no fonts.scalar.com
          telemetry: false,
          agent: { disabled: true }, // no "Ask AI" (Scalar's hosted agent would receive the spec)
          showDeveloperTools: 'never', // no "Generate SDKs" (Scalar's hosted service)
          hideClientButton: false,
        },
      },
    ]),
  ],

  themes: [
    [
      '@easyops-cn/docusaurus-search-local',
      {
        hashed: true,
        language: ['en', 'fr'],
        indexBlog: false,
        docsRouteBasePath: internal ? ['guides', 'docs'] : ['guides'],
        docsDir: internal ? ['guides', repoDocs] : ['guides'],
        docsPluginIdForPreferredVersion: 'default',
        highlightSearchTermsOnTargetPage: true,
        explicitSearchResultPath: true,
      } satisfies SearchOptions,
    ],
  ],

  themeConfig: {
    colorMode: { respectPrefersColorScheme: true },
    navbar: {
      title: 'Northline',
      logo: { alt: 'Northline', src: 'img/favicon.svg' },
      items: [
        { to: '/guides/', label: 'Guides', position: 'left' },
        { to: '/api/', label: 'API reference', position: 'left' },
        ...(internal
          ? [
              { to: '/docs/ARCHITECTURE', label: 'Architecture', position: 'left' as const },
              { to: '/docs/runbooks/', label: 'Runbooks', position: 'left' as const },
              { to: '/docs/DECISIONS', label: 'Decisions', position: 'left' as const },
            ]
          : []),
        { type: 'localeDropdown', position: 'right' },
      ],
    },
    footer: {
      style: 'light',
      copyright: `© ${new Date().getFullYear()} Northline · ${internal ? 'internal — do not share' : 'northline.ca'}`,
    },
    prism: { theme: prismThemes.github, darkTheme: prismThemes.dracula },
  } satisfies Preset.ThemeConfig,
};

export default config;
