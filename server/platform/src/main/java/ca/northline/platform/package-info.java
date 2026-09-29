/**
 * Cross-app platform wiring shared by api, auth, bff and worker: the start-up check for the environment variables a
 * deployed profile needs ({@link RequiredEnvironmentCheck}) and the provider settings that will pick the cloud
 * adapters ({@code northline.storage|kms|email|sms.provider}). See {@code docs/runbooks/README.md}.
 */
@NullMarked
package ca.northline.platform;

import org.jspecify.annotations.NullMarked;
