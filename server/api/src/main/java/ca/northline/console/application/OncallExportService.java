package ca.northline.console.application;

import ca.northline.identity.api.OncallRota;
import ca.northline.identity.api.StaffDirectory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HashMap;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/** {@link OncallExport} over the rota and the staff directory (for the emails). */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(OncallExportProperties.class)
class OncallExportService implements OncallExport {

    private final OncallRota rota;
    private final StaffDirectory staff;
    private final OncallExportProperties props;
    private final Clock clock;

    @Override
    public boolean enabled() {
        return !props.token().isBlank();
    }

    @Override
    public boolean accepts(@Nullable String presented) {
        return enabled()
                && presented != null
                && MessageDigest.isEqual(
                        presented.getBytes(StandardCharsets.UTF_8),
                        props.token().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Export export() {
        var now = clock.instant();
        var shifts = rota.between(now.minus(props.back()), now.plus(props.ahead()));
        var emails = new HashMap<String, Optional<String>>();
        shifts.forEach(
                s -> emails.computeIfAbsent(s.userId(), id -> staff.member(id).map(StaffDirectory.Member::email)));
        var entries = shifts.stream()
                .map(s -> new Entry(
                        s.id(),
                        s.userId(),
                        s.name(),
                        emails.getOrDefault(s.userId(), Optional.empty()).orElse(null),
                        s.startsAt(),
                        s.endsAt(),
                        s.duty()))
                .toList();
        var current = entries.stream()
                .filter(e -> !e.startsAt().isAfter(now) && e.endsAt().isAfter(now))
                .toList();
        return new Export(now, current, entries);
    }
}
