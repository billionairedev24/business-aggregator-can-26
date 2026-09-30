package ca.northline.email.smtp;

import ca.northline.email.EmailAddress;
import ca.northline.email.EmailDeliveryFailed;
import ca.northline.email.EmailMessage;
import ca.northline.email.EmailSender;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.jspecify.annotations.Nullable;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * SMTP (Jakarta Mail): Mailpit locally ({@code EMAIL_PROVIDER=local}) or any relay ({@code smtp}: Amazon SES SMTP,
 * SendGrid SMTP, Azure Communication Services SMTP, Postfix…). multipart/alternative (text + HTML), UTF-8.
 *
 * <p>SMTP 5xx on the recipient or the message = rejected; 4xx, authentication and connection problems = unavailable.
 * With {@code lenient} (the {@code local} provider) an unreachable server only logs the plain-text body, so a laptop
 * without Mailpit still shows invitation links in the log, as before S-13.
 */
@Slf4j
public final class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mail;
    private final EmailAddress from;
    private final @Nullable EmailAddress replyTo;
    private final boolean lenient;

    public SmtpEmailSender(JavaMailSender mail, EmailAddress from, @Nullable EmailAddress replyTo, boolean lenient) {
        this.mail = mail;
        this.from = from;
        this.replyTo = replyTo;
        this.lenient = lenient;
    }

    /** A Jakarta Mail sender with time-outs (connect 5 s, read/write 10 s); STARTTLS required when asked for. */
    public static JavaMailSenderImpl client(
            String host, int port, @Nullable String username, @Nullable String password, boolean starttls) {
        var sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setDefaultEncoding("UTF-8");
        var props = new Properties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        if (username != null && !username.isBlank()) {
            sender.setUsername(username);
            sender.setPassword(password);
            props.put("mail.smtp.auth", "true");
        }
        if (starttls) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        sender.setJavaMailProperties(props);
        return sender;
    }

    @Override
    public void send(EmailMessage message) {
        try {
            var mime = mail.createMimeMessage();
            var helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(from.toInternetAddress());
            if (replyTo != null) {
                helper.setReplyTo(replyTo.toInternetAddress());
            }
            helper.setTo(message.to().toInternetAddress());
            helper.setSubject(message.subject());
            helper.setText(message.text(), message.html());
            for (var header : message.headers().entrySet()) {
                mime.setHeader(header.getKey(), header.getValue());
            }
            mime.setHeader("X-Northline-Template", message.tag());
            mail.send(mime);
        } catch (MessagingException e) {
            throw EmailDeliveryFailed.rejected("Invalid message: " + e.getMessage());
        } catch (MailException e) {
            var failure = classify(e);
            if (lenient && failure.getKind() == EmailDeliveryFailed.Kind.UNAVAILABLE) {
                log.warn("""
                        No SMTP server took the email (start Mailpit: docker compose --profile mail up -d). \
                        To {} — {}
                        {}""", message.to().address(), message.subject(), message.text());
                return;
            }
            throw failure;
        }
    }

    private static EmailDeliveryFailed classify(MailException e) {
        if (e instanceof MailAuthenticationException) {
            return EmailDeliveryFailed.unavailable("SMTP authentication failed", e);
        }
        if (e instanceof MailSendException send) {
            for (var cause : send.getFailedMessages().values()) {
                var code = smtpCode(cause);
                if (code >= 500) {
                    return new EmailDeliveryFailed(
                            EmailDeliveryFailed.Kind.REJECTED, "SMTP " + code + ": " + cause.getMessage(), cause);
                }
            }
        }
        return EmailDeliveryFailed.unavailable("SMTP send failed: " + e.getMessage(), e);
    }

    /** The SMTP reply code of the failure, or 0 when it wasn't an SMTP answer (connection, TLS…). */
    private static int smtpCode(@Nullable Throwable failure) {
        var t = failure;
        for (int depth = 0; t != null && depth < 10; depth++, t = nextOf(t)) {
            switch (t) {
                case SMTPAddressFailedException address -> {
                    return address.getReturnCode();
                }
                case SMTPSendFailedException send -> {
                    return send.getReturnCode();
                }
                default -> {}
            }
        }
        return 0;
    }

    private static @Nullable Throwable nextOf(Throwable t) {
        if (t instanceof SendFailedException sfe && sfe.getNextException() != null) {
            return sfe.getNextException();
        }
        return t.getCause();
    }
}
