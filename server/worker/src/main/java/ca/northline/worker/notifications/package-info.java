/**
 * The notifications consumer (S-27): money events from Kafka → SMS, push (stub) and the {@code payout.failed} email
 * to the business's team, per member's Settings › Notifications matrix and quiet hours. Who sends what (the api keeps
 * the S-13 emails): {@code docs/runbooks/notifications.md}.
 */
@NullMarked
package ca.northline.worker.notifications;

import org.jspecify.annotations.NullMarked;
