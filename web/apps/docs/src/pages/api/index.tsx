import Link from '@docusaurus/Link';
import Translate, { translate } from '@docusaurus/Translate';
import useBaseUrl from '@docusaurus/useBaseUrl';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';
import SwaggerLinks from '../../components/SwaggerLinks';
import { type Locale, redocRoute, scalarRoute, specsFor, type Variant } from '../../content';

/** Every OpenAPI document of this variant, each in Redoc and Scalar, plus the YAML it is generated from. */
export default function ApiReference() {
  const { siteConfig, i18n } = useDocusaurusContext();
  const variant = (siteConfig.customFields?.variant ?? 'public') as Variant;
  const locale = (i18n.currentLocale === 'fr' ? 'fr' : 'en') as Locale;
  const openapi = useBaseUrl('/openapi/');
  return (
    <Layout title={translate({ id: 'api.title', message: 'API reference' })}>
      <main className="nl-home">
        <h1>
          <Translate id="api.heading">API reference</Translate>
        </h1>
        <p className="nl-lead">
          <Translate id="api.lead">
            OpenAPI 3.1, generated from the code. Redoc to read, Scalar to try requests.
          </Translate>
        </p>
        <div className="nl-cards">
          {specsFor(variant).map((spec) => (
            <article key={spec.id} className="nl-card">
              <h2>{spec.title[locale]}</h2>
              <p>{spec.summary[locale]}</p>
              <p className="nl-links">
                <Link to={redocRoute(spec)}>Redoc</Link>
                <Link to={scalarRoute(spec)}>Scalar</Link>
                <a href={`${openapi}${spec.id}.yaml`} download>
                  OpenAPI (YAML)
                </a>
              </p>
              {spec.audience === 'internal' && (
                <p className="nl-badge">
                  <Translate id="api.internal">Internal — not published in production</Translate>
                </p>
              )}
            </article>
          ))}
        </div>
        <SwaggerLinks />
      </main>
    </Layout>
  );
}
