/**
 * Messaging &amp; support: customer conversations (threads per booking / order / dispute, masked contact details,
 * attachments, quick replies, shared-inbox unread state), the help centre (topics, articles in en + fr, platform
 * status) and helpdesk cases with SLA by tier. Layout as in {@code merchants} (docs/BACKEND_CONVENTIONS.md); object
 * storage sits behind a port with a local fake in {@code messaging.adapters}.
 */
@ApplicationModule(displayName = "messaging")
@NullMarked
package ca.northline.messaging;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
