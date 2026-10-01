package ca.northline.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.messaging.domain.OutgoingText;
import ca.northline.messaging.domain.SupportCase;
import ca.northline.messaging.domain.SupportSla;
import ca.northline.messaging.domain.TicketPriority;
import ca.northline.messaging.domain.TicketState;
import ca.northline.shared.Conflict;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Plain domain rules: masking and off-platform detection, the support SLA in support hours, case replies. */
class MessagingDomainTest {

    /** Test data: support hours kept in a Mountain-time platform zone. */
    static final java.time.ZoneId ZONE = java.time.ZoneId.of("America/Edmonton");

    @Nested
    class Text {

        @ParameterizedTest(name = "[{index}] {0}")
        @CsvSource(
                delimiter = '|',
                value = {
                    "Call 403-555-0148 please           | Call •••-•••-•••• please           | true",
                    "Call +1 (403) 555 0148             | Call •••-•••-••••                  | true",
                    "or ravi@example.com                | or •••@•••                         | true",
                    "Can you take an e-transfer?        | Can you take an e-transfer?        | true",
                    "Cash only, thanks                  | Cash only, thanks                  | true",
                    "Payez par virement Interac         | Payez par virement Interac         | true",
                    "Stall 118, gate code 4410          | Stall 118, gate code 4410          | false",
                    "See you at 9. Order NL-48213 ready | See you at 9. Order NL-48213 ready | false",
                })
        void masksContactDetails_andFlagsOffPlatformPayment(String raw, String masked, boolean flagged) {
            var text = OutgoingText.of(raw);
            assertThat(text.text()).isEqualTo(masked);
            assertThat(text.offPlatform()).isEqualTo(flagged);
        }
    }

    @Nested
    class Sla {

        static Instant mt(String local) {
            return LocalDateTime.parse(local).atZone(ZONE).toInstant();
        }

        @Test
        void countsOnlySupportHours() {
            assertThat(SupportSla.dueAt(mt("2026-09-29T10:00"), TicketPriority.NORMAL, ZONE))
                    .isEqualTo(mt("2026-09-29T14:00"));
            assertThat(SupportSla.dueAt(mt("2026-09-29T21:30"), TicketPriority.NORMAL, ZONE))
                    .isEqualTo(mt("2026-09-30T09:30"));
            assertThat(SupportSla.dueAt(mt("2026-09-29T02:00"), TicketPriority.PRIORITY, ZONE))
                    .isEqualTo(mt("2026-09-29T08:00"));
            assertThat(SupportSla.dueAt(mt("2026-09-29T22:55"), TicketPriority.URGENT, ZONE))
                    .isEqualTo(mt("2026-09-30T07:10"));
            assertThat(SupportSla.dueAt(mt("2026-09-29T23:30"), TicketPriority.URGENT, ZONE))
                    .isEqualTo(mt("2026-09-30T07:15"));
        }

        @Test
        void priorityFromUrgencyAndTier() {
            assertThat(SupportSla.priority(true, false)).isEqualTo(TicketPriority.URGENT);
            assertThat(SupportSla.priority(false, true)).isEqualTo(TicketPriority.PRIORITY);
            assertThat(SupportSla.priority(false, false)).isEqualTo(TicketPriority.NORMAL);
        }
    }

    @Nested
    class Cases {

        final Instant now = Instant.parse("2026-09-29T16:00:00Z");

        @Test
        void replyToWaitingCaseReturnsItToNorthline() {
            var waiting = new SupportCase("c", 4471, TicketState.WAITING, TicketPriority.NORMAL, null);
            var replied = waiting.replied(now, ZONE);
            assertThat(replied.state()).isEqualTo(TicketState.IN_PROGRESS);
            assertThat(replied.slaDueAt()).isEqualTo(SupportSla.dueAt(now, TicketPriority.NORMAL, ZONE));
        }

        @Test
        void resolvedCaseStaysClosed() {
            var resolved = new SupportCase("c", 4402, TicketState.RESOLVED, TicketPriority.NORMAL, null);
            assertThatThrownBy(() -> resolved.replied(now, ZONE))
                    .isInstanceOf(Conflict.class)
                    .hasMessage("This case is resolved. Open a new case.");
        }

        @Test
        void code() {
            assertThat(SupportCase.code(4480)).isEqualTo("HD-4480");
        }
    }
}
