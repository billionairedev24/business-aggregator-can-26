/**
 * S-125: OpenAPI 3.1 documentation shared by the api, northline-auth and the BFF — the common info, error schemas
 * ({@link ApiDocs#PROBLEM}, {@link ApiDocs#VALIDATION_ERRORS}), security schemes, conventions ({@link ApiConventions}),
 * the {@code /docs} landing and Redoc pages ({@link DocsPagesController}) and the viewer CSP
 * ({@link DocsSecurityConfiguration}). Swagger UI and Scalar come from springdoc's starters. Everything here is off when
 * {@code northline.docs.enabled=false} (production). See {@code docs/runbooks/api-docs.md}.
 */
@NullMarked
package ca.northline.openapi;

import org.jspecify.annotations.NullMarked;
