import Link from '@docusaurus/Link';
import Translate, { translate } from '@docusaurus/Translate';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';

/** Home: the guides, the API reference and, in the internal variant, the engineering documentation. */
export default function Home() {
  const { siteConfig } = useDocusaurusContext();
  const internal = siteConfig.customFields?.variant === 'internal';
  return (
    <Layout title={translate({ id: 'home.title', message: 'Documentation' })}>
      <main className="nl-home">
        <h1>
          <Translate id="home.heading">Northline documentation</Translate>
        </h1>
        <p className="nl-lead">
          <Translate id="home.lead">
            Guides and API references for integrations with the Northline marketplace.
          </Translate>
        </p>
        <div className="nl-cards">
          <Link className="nl-card" to="/guides/">
            <h2>
              <Translate id="home.guides">Guides</Translate>
            </h2>
            <p>
              <Translate id="home.guides.text">Get started, authenticate, receive webhooks.</Translate>
            </p>
          </Link>
          <Link className="nl-card" to="/api/">
            <h2>
              <Translate id="home.api">API reference</Translate>
            </h2>
            <p>
              <Translate id="home.api.text">Every OpenAPI 3.1 document, in Redoc and in Scalar.</Translate>
            </p>
          </Link>
          {internal && (
            <Link className="nl-card" to="/docs/ARCHITECTURE">
              <h2>
                <Translate id="home.engineering">Engineering</Translate>
              </h2>
              <p>
                <Translate id="home.engineering.text">
                  Architecture, data model, runbooks, decisions, security, backlog.
                </Translate>
              </p>
            </Link>
          )}
        </div>
      </main>
    </Layout>
  );
}
