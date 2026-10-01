# syntax=docker/dockerfile:1.7
# Web images (S-14, docs/runbooks/deploy.md). Build context: web/. One Dockerfile, one target per app:
#
#   docker build --target studio   -t "$REGISTRY/studio:$IMAGE_TAG"   web
#   docker build --target consumer -t "$REGISTRY/consumer:$IMAGE_TAG" web
#   docker build --target console  -t "$REGISTRY/console:$IMAGE_TAG"  web
#   docker build --target docs          --build-context repo-docs=docs -t "$REGISTRY/docs:$IMAGE_TAG" web
#   docker build --target docs-internal --build-context repo-docs=docs -t "$REGISTRY/docs-internal:$IMAGE_TAG" web
#
# studio   = the Vite SPA as static files behind nginx-unprivileged (uid 101, port 8080), SPA fallback, security
#            headers, pre-compressed assets; NL_AUTH_ORIGIN is read at container start (/config.js), so one image
#            serves every environment.
# consumer = TanStack Start SSR (dist/server fetch handler + dist/client assets) on Node 22 distroless (uid 65532,
#            port 3000) through apps/consumer/server/node-server.mjs.
# docs, docs-internal = the Docusaurus site (S-126) as static files on nginx-unprivileged (port 8080): docs is the
#            public variant (guides, public/partner/webhook/OAuth references), docs-internal everything (docs/ of the
#            repository, every spec). The repository's docs/ comes in as the named build context `repo-docs`.
# console  = the platform console SPA (S-90), built and served exactly like the studio (same nginx template: SPA
#            fallback, security headers, /config.js from NL_AUTH_ORIGIN).

ARG NODE_IMAGE=node:22-bookworm-slim
ARG NGINX_IMAGE=nginxinc/nginx-unprivileged:1.29-alpine
ARG NODE_RUNTIME_IMAGE=gcr.io/distroless/nodejs22-debian13:nonroot

# ---- dependencies (cached on the lockfile) ------------------------------------------------------------------------
# Behind a TLS-intercepting proxy: pass HTTPS_PROXY as a build argument and the proxy's CA as the build secret
# `extra_ca` (docker build --secret id=extra_ca,src=ca.pem); neither ends up in an image.
# Build stages run on the builder's own platform (the output is platform-independent JavaScript), so multi-platform
# images (--platform linux/amd64,linux/arm64) need no emulation.
FROM --platform=$BUILDPLATFORM ${NODE_IMAGE} AS deps
ENV PNPM_HOME=/pnpm PATH=/pnpm:$PATH CI=true
RUN --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/extra_ca; fi \
 && npm install -g --no-fund --no-audit pnpm@10.17.0
WORKDIR /web
COPY pnpm-lock.yaml pnpm-workspace.yaml package.json ./
RUN --mount=type=cache,id=pnpm-store,target=/pnpm/store --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/extra_ca; fi \
 && pnpm config set store-dir /pnpm/store && pnpm fetch --frozen-lockfile
COPY . .
RUN --mount=type=cache,id=pnpm-store,target=/pnpm/store \
    pnpm config set store-dir /pnpm/store && pnpm install --frozen-lockfile --offline

# ---- studio build -----------------------------------------------------------------------------------------------------
FROM deps AS studio-build
# Optional build-time default; the runtime NL_AUTH_ORIGIN (config.js) wins when set.
ARG VITE_NL_AUTH_ORIGIN=
ENV VITE_NL_AUTH_ORIGIN=${VITE_NL_AUTH_ORIGIN}
RUN pnpm --filter @northline/studio build \
 && find apps/studio/dist -type f \( -name '*.js' -o -name '*.css' -o -name '*.html' -o -name '*.svg' -o -name '*.json' \) \
      -size +1k -exec gzip -9 -k -n {} \; \
 && rm -f apps/studio/dist/config.js

# ---- console build (S-90) -------------------------------------------------------------------------------------------
FROM deps AS console-build
ARG VITE_NL_AUTH_ORIGIN=
ENV VITE_NL_AUTH_ORIGIN=${VITE_NL_AUTH_ORIGIN}
RUN pnpm --filter @northline/console build \
 && find apps/console/dist -type f \( -name '*.js' -o -name '*.css' -o -name '*.html' -o -name '*.svg' -o -name '*.json' \) \
      -size +1k -exec gzip -9 -k -n {} \; \
 && rm -f apps/console/dist/config.js

# ---- consumer build -------------------------------------------------------------------------------------------------
FROM deps AS consumer-build
RUN --mount=type=cache,id=pnpm-store,target=/pnpm/store --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/extra_ca; fi \
 && pnpm --filter @northline/tokens build \
 && pnpm --filter @northline/consumer build \
 && pnpm --filter @northline/consumer deploy --prod --legacy /out \
 && cp -r apps/consumer/dist /out/dist \
 && cp -r apps/consumer/server /out/server

# ---- docs builds (S-126) --------------------------------------------------------------------------------------------
FROM deps AS docs-build
ARG DOCS_URL=
ARG DOCS_BASE_URL=/
COPY --from=repo-docs . /docs
ENV NORTHLINE_DOCS_DIR=/docs DOCS_URL=${DOCS_URL} DOCS_BASE_URL=${DOCS_BASE_URL} NODE_OPTIONS=--max-old-space-size=3072
# The site build test (scripts/check-build.mjs) runs in the image build too: no image without every page.
RUN NORTHLINE_DOCS_VARIANT=public pnpm --filter @northline/docs build \
 && NORTHLINE_DOCS_VARIANT=public pnpm --filter @northline/docs test:build \
 && mv apps/docs/build /out-public \
 && NORTHLINE_DOCS_VARIANT=internal pnpm --filter @northline/docs build \
 && NORTHLINE_DOCS_VARIANT=internal pnpm --filter @northline/docs test:build \
 && mv apps/docs/build /out-internal \
 && for d in /out-public /out-internal; do \
      rm -f "$d/config.js"; \
      find "$d" -type f \( -name '*.js' -o -name '*.css' -o -name '*.html' -o -name '*.svg' -o -name '*.json' -o -name '*.yaml' \) \
        -size +1k -exec gzip -9 -k -n {} \; ; \
    done

FROM ${NGINX_IMAGE} AS docs-runtime
ENV NGINX_ENVSUBST_FILTER=^NL_ NL_DOCS_SWAGGER= NL_DOCS_CONNECT=
USER root
RUN rm -f /etc/nginx/conf.d/default.conf
COPY docker/docs.conf.template docker/docs-security-headers.inc.template /etc/nginx/templates/

FROM docs-runtime AS docs
ARG GIT_SHA=unknown
ARG IMAGE_TAG=dev
LABEL org.opencontainers.image.title="northline-docs" \
      org.opencontainers.image.description="Northline documentation site, public variant (Docusaurus on nginx)" \
      org.opencontainers.image.source="https://github.com/billionairedev24/business-aggregator-can-26" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${IMAGE_TAG}" \
      org.opencontainers.image.vendor="Northline"
COPY --from=docs-build /out-public /usr/share/nginx/html
RUN chown -R 101:101 /etc/nginx/conf.d && chmod -R a+rX /usr/share/nginx/html
USER 101
EXPOSE 8080

FROM docs-runtime AS docs-internal
ARG GIT_SHA=unknown
ARG IMAGE_TAG=dev
LABEL org.opencontainers.image.title="northline-docs-internal" \
      org.opencontainers.image.description="Northline documentation site, internal variant (runbooks, decisions, every spec)" \
      org.opencontainers.image.source="https://github.com/billionairedev24/business-aggregator-can-26" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${IMAGE_TAG}" \
      org.opencontainers.image.vendor="Northline"
COPY --from=docs-build /out-internal /usr/share/nginx/html
RUN chown -R 101:101 /etc/nginx/conf.d && chmod -R a+rX /usr/share/nginx/html
USER 101
EXPOSE 8080

# ---- studio runtime ---------------------------------------------------------------------------------------------------
FROM ${NGINX_IMAGE} AS studio
ARG GIT_SHA=unknown
ARG IMAGE_TAG=dev
LABEL org.opencontainers.image.title="northline-studio" \
      org.opencontainers.image.description="Northline Studio (static SPA on nginx)" \
      org.opencontainers.image.source="https://github.com/billionairedev24/business-aggregator-can-26" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${IMAGE_TAG}" \
      org.opencontainers.image.vendor="Northline"
# Only NL_* variables are substituted into the templates; NL_AUTH_ORIGIN must be set (default: local auth server).
ENV NGINX_ENVSUBST_FILTER=^NL_ \
    NL_AUTH_ORIGIN=http://localhost:9000
USER root
RUN rm -f /etc/nginx/conf.d/default.conf
COPY docker/studio.conf.template docker/security-headers.inc.template /etc/nginx/templates/
COPY --from=studio-build /web/apps/studio/dist /usr/share/nginx/html
RUN chown -R 101:101 /etc/nginx/conf.d && chmod -R a+rX /usr/share/nginx/html
USER 101
EXPOSE 8080

# ---- console runtime (S-90) -------------------------------------------------------------------------------------------
FROM ${NGINX_IMAGE} AS console
ARG GIT_SHA=unknown
ARG IMAGE_TAG=dev
LABEL org.opencontainers.image.title="northline-console" \
      org.opencontainers.image.description="Northline platform console (static SPA on nginx)" \
      org.opencontainers.image.source="https://github.com/billionairedev24/business-aggregator-can-26" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${IMAGE_TAG}" \
      org.opencontainers.image.vendor="Northline"
ENV NGINX_ENVSUBST_FILTER=^NL_ \
    NL_AUTH_ORIGIN=http://localhost:9000
USER root
RUN rm -f /etc/nginx/conf.d/default.conf
COPY docker/studio.conf.template docker/security-headers.inc.template /etc/nginx/templates/
COPY --from=console-build /web/apps/console/dist /usr/share/nginx/html
RUN chown -R 101:101 /etc/nginx/conf.d && chmod -R a+rX /usr/share/nginx/html
USER 101
EXPOSE 8080

# ---- consumer runtime -------------------------------------------------------------------------------------------------
FROM ${NODE_RUNTIME_IMAGE} AS consumer
ARG GIT_SHA=unknown
ARG IMAGE_TAG=dev
LABEL org.opencontainers.image.title="northline-consumer" \
      org.opencontainers.image.description="Northline consumer web (TanStack Start SSR on Node)" \
      org.opencontainers.image.source="https://github.com/billionairedev24/business-aggregator-can-26" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${IMAGE_TAG}" \
      org.opencontainers.image.vendor="Northline"
ENV NODE_ENV=production PORT=3000
WORKDIR /app
COPY --from=consumer-build --chown=65532:65532 /out /app
USER 65532:65532
EXPOSE 3000
CMD ["server/node-server.mjs"]
