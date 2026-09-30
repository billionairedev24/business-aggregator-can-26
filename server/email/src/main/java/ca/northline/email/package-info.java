/**
 * Transactional email (S-13), shared by the api and the worker: the {@link ca.northline.email.EmailSender} port and its
 * adapters (sub-packages {@code smtp}, {@code ses}, {@code sendgrid}, {@code azure}, chosen by {@code
 * northline.email.provider}), the en/fr templates ({@link ca.northline.email.EmailTemplates},
 * {@link ca.northline.email.EmailContent}) and {@link ca.northline.email.Mailer}, which renders, adds the CASL footer and
 * unsubscribe headers, and sends each (event, recipient) once. Only the types in this package are for callers.
 */
@NullMarked
package ca.northline.email;

import org.jspecify.annotations.NullMarked;
