/**
 * Push providers behind the notifications' {@code PushSender} port (S-102, docs/runbooks/push.md):
 * {@code northline.push.provider=native} sends to the device registry ({@code messaging.push_devices}, written by the
 * apps through the api) — APNs (HTTP/2, token-based auth with the {@code .p8} key) for iOS installations, FCM HTTP v1
 * (OAuth 2.0 with a service account) for Android ones — deletes devices the provider says are gone, and backs off when
 * a provider throttles or fails; {@code local} logs instead. Payloads carry the words, a deep link and ids only.
 */
@NullMarked
package ca.northline.worker.push;

import org.jspecify.annotations.NullMarked;
