package ca.northline.availability.application;

import ca.northline.availability.domain.TimeOff;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Outbound port for {@code availability.time_off}. */
public interface TimeOffRepository {

    /** Entries ending on or after {@code from}, earliest first. */
    List<TimeOff> from(String merchantId, LocalDate from);

    void insert(String merchantId, TimeOff timeOff, String actorId, Instant at);

    /** @return false when there was no such entry for this business */
    boolean delete(String merchantId, String id);
}
