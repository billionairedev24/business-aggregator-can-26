package ca.northline.messaging.persistence;

import ca.northline.messaging.application.InboundSms;
import ca.northline.messaging.application.InboundSms.SmsWebhookLog;
import java.time.Instant;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code messaging.sms_inbound} and {@code messaging.sms_delivery_status} (V453, S-157). */
@Repository
@RequiredArgsConstructor
class SmsWebhookJdbc implements SmsWebhookLog {

    private final JdbcClient jdbc;

    @Override
    public boolean first(String provider, String messageId, String fromHash, String keyword, Instant at) {
        return jdbc.sql("""
                        insert into messaging.sms_inbound (provider, message_id, received_at, from_hash, keyword)
                        values (:provider, :id, :at, :from, :keyword)
                        on conflict do nothing""")
                        .param("provider", provider)
                        .param("id", messageId)
                        .param("at", at.atOffset(ZoneOffset.UTC))
                        .param("from", fromHash)
                        .param("keyword", keyword)
                        .update()
                == 1;
    }

    @Override
    public void applied(String provider, String messageId, int accounts) {
        jdbc.sql("update messaging.sms_inbound set accounts = :n where provider = :provider and message_id = :id")
                .param("n", accounts)
                .param("provider", provider)
                .param("id", messageId)
                .update();
    }

    /** Final statuses ({@code delivered, undelivered, failed, canceled, read}) are never replaced by earlier ones. */
    @Override
    public void status(InboundSms.Status s, Instant at) {
        jdbc.sql("""
                        insert into messaging.sms_delivery_status (provider, message_id, status, error_code, first_at,
                               updated_at)
                        values (:provider, :id, :status, :error, :at, :at)
                        on conflict (provider, message_id) do update
                           set status = excluded.status, error_code = excluded.error_code, updated_at = excluded.updated_at
                         where messaging.sms_delivery_status.status not in ('delivered', 'undelivered', 'failed',
                                                                             'canceled', 'read')
                            or excluded.status = 'read'""")
                .param("provider", s.provider())
                .param("id", s.messageId())
                .param("status", s.status())
                .param("error", s.errorCode())
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public int purgeBefore(Instant before) {
        var at = before.atOffset(ZoneOffset.UTC);
        return jdbc.sql("delete from messaging.sms_inbound where received_at < :at")
                        .param("at", at)
                        .update()
                + jdbc.sql("delete from messaging.sms_delivery_status where updated_at < :at")
                        .param("at", at)
                        .update();
    }
}
