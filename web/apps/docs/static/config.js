// Runtime settings of the documentation site. In the container nginx answers /config.js itself, from NL_DOCS_SWAGGER
// (web/docker/docs.conf.template); this file is what `pnpm dev`, `docusaurus serve` and Pages builds get: the local
// services' viewers (make up SERVICES="auth api bff studio").
window.__NL_DOCS__ = {
  swagger: [
    'api (local)|http://localhost:8080/docs',
    'auth (local)|http://localhost:9000/docs',
    'studio-bff (local)|http://localhost:8082/bff/docs',
  ],
};
