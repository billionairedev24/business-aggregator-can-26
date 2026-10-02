package ca.northline.privacy.api;

import ca.northline.shared.DomainEvent;
import java.time.Instant;

/**
 * {@code privacy.personal_data_erased} — every module has erased (or pseudonymised) the person's data; holds that
 * remain are retried. In-process only (module listeners), ids only.
 *
 * @param aggregateId the privacy request ({@code privacy.requests.id})
 * @param subjectId the erased account ({@code identity.users.id}, now a pseudonym)
 * @param holds steps still held (an open order or dispute), 0 when nothing is left
 */
public record PersonalDataErased(String eventId, Instant occurredAt, String aggregateId, String subjectId, int holds)
        implements DomainEvent {}
