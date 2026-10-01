import BrowserOnly from '@docusaurus/BrowserOnly';
import Translate from '@docusaurus/Translate';
import { swaggerTargets } from '../runtimeConfig';

/**
 * Links to the services' own viewers (Swagger UI, Scalar, Redoc on /docs) in the non-production environments. The
 * list comes from /config.js, which nginx writes from NL_DOCS_SWAGGER at container start (empty in production, where
 * the services publish no viewer).
 */
export default function SwaggerLinks() {
  return (
    <BrowserOnly>
      {() => {
        const targets = swaggerTargets(window.__NL_DOCS__);
        if (targets.length === 0) return null;
        return (
          <section className="nl-swagger" aria-labelledby="nl-swagger-title">
            <h2 id="nl-swagger-title">
              <Translate id="api.swagger.title">Swagger UI of the running services</Translate>
            </h2>
            <ul>
              {targets.map((t) => (
                <li key={t.url}>
                  <a href={t.url}>{t.label}</a>
                </li>
              ))}
            </ul>
          </section>
        );
      }}
    </BrowserOnly>
  );
}
