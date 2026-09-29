package ca.northline.merchants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.TestJwt;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Outbox + externalization, end to end with a mocked Kafka: the publication row is written by the Modulith JDBC
 * registry, sent to topic {@code merchants.merchant} keyed by merchant id, then completed (archived).
 */
@TestPropertySource(properties = "spring.modulith.events.externalization.enabled=true")
class MerchantEventsExternalizationTest extends IntegrationTest {

    /** Replaces Boot's auto-configured {@code KafkaTemplate<?, ?>} (declared type must match for override). */
    @MockitoBean
    KafkaTemplate<?, ?> kafka;

    @Autowired
    JdbcClient jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void renameIsExternalizedToTheAggregateTopicKeyedByMerchantId() throws Exception {
        when(kafka.send(any(Message.class))).thenReturn(CompletableFuture.completedFuture(null));
        var biz = data.business(MerchantRole.OWNER);

        mvc.perform(patch("/api/v1/merchants/{id}", biz.merchantId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Prairie Wrench Mobile\"}")
                        .with(TestJwt.member(biz.userId())))
                .andExpect(status().isOk());

        ArgumentCaptor<Message<?>> sent = ArgumentCaptor.forClass(Message.class);
        verify(kafka, timeout(10_000)).send(sent.capture());
        assertThat(sent.getValue().getHeaders())
                .containsEntry(KafkaHeaders.TOPIC, "merchants.merchant")
                .containsEntry(KafkaHeaders.KEY, biz.merchantId());

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(jdbc.sql("""
                                select count(*) from events.event_publication_archive
                                 where event_type like '%MerchantRenamed' and serialized_event like :id
                                   and completion_date is not null
                                """)
                                .param("id", "%" + biz.merchantId() + "%")
                                .query(Integer.class)
                                .single())
                        .isEqualTo(1));
    }
}
