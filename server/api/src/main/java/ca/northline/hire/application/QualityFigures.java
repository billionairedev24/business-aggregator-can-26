package ca.northline.hire.application;

import ca.northline.trust.api.QualityQuery;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** One component of the latest nightly quality score ({@code on_time}, {@code disputes}, {@code rebook} …), if any. */
final class QualityFigures {
    private QualityFigures() {}

    static @Nullable Double of(Optional<QualityQuery.QualityScore> score, String key) {
        return score.flatMap(s ->
                        s.components().stream().filter(c -> c.key().equals(key)).findFirst())
                .map(QualityQuery.Component::value)
                .orElse(null);
    }
}
