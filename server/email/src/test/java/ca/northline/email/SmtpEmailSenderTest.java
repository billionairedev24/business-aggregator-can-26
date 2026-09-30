package ca.northline.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ca.northline.platform.EmailProperties;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMultipart;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * {@code EMAIL_PROVIDER=smtp} and {@code local} against GreenMail (an in-process SMTP server), wired exactly as the
 * auto-configuration does.
 */
class SmtpEmailSenderTest {

    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP);

    @Test
    void sendsMultipartAlternative_withHeadersAndUtf8() throws Exception {
        var sender = TestEmails.sender(TestEmails.props(
                EmailProperties.Provider.SMTP, null, null, smtp(SMTP.getSmtp().getPort())));

        sender.send(TestEmails.message());

        var received = SMTP.getReceivedMessages();
        assertThat(received).hasSize(1);
        var mail = received[0];
        assertThat(mail.getSubject()).isEqualTo("Rejoignez Prairie Wrench sur Northline");
        assertThat(mail.getFrom()[0].toString()).isEqualTo("Northline <no-reply@mail.northline.test>");
        assertThat(mail.getReplyTo()[0].toString()).isEqualTo("support@northline.test");
        assertThat(mail.getAllRecipients()[0].toString()).contains("sam@example.com");
        assertThat(mail.getHeader(EmailMessage.LIST_UNSUBSCRIBE)[0])
                .isEqualTo("<http://localhost:8080/api/v1/email/unsubscribe?t=abc>");
        assertThat(mail.getHeader(EmailMessage.LIST_UNSUBSCRIBE_POST)[0]).isEqualTo("List-Unsubscribe=One-Click");
        assertThat(mail.getHeader("X-Northline-Template")[0]).isEqualTo("team-invitation");
        var body = GreenMailUtil.getBody(mail);
        assertThat(body).contains("multipart/alternative", "text/plain", "text/html");
        assertThat(((MimeMultipart) mail.getContent()).getCount()).isEqualTo(1); // mixed → one alternative part
    }

    @Test
    void noServer_isUnavailable_afterTheRetries() throws Exception {
        var sender = TestEmails.sender(TestEmails.props(EmailProperties.Provider.SMTP, null, null, smtp(freePort())));

        var failure = catchThrowableOfType(EmailDeliveryFailed.class, () -> sender.send(TestEmails.message()));

        assertThat(failure.getKind()).isEqualTo(EmailDeliveryFailed.Kind.UNAVAILABLE);
    }

    @Test
    void local_withoutMailpit_onlyLogs() throws Exception {
        var sender = TestEmails.sender(TestEmails.props(EmailProperties.Provider.LOCAL, null, null, smtp(freePort())));

        sender.send(TestEmails.message()); // no exception: the text body is in the log
    }

    @Test
    void local_deliversToMailpitWhenItRuns() {
        var sender = TestEmails.sender(TestEmails.props(
                EmailProperties.Provider.LOCAL, null, null, smtp(SMTP.getSmtp().getPort())));

        sender.send(TestEmails.message());

        assertThat(SMTP.getReceivedMessages()).hasSize(1);
    }

    private static EmailProperties.Smtp smtp(int port) {
        return new EmailProperties.Smtp("127.0.0.1", port, null, null, false);
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
