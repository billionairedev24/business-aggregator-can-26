package ca.northline.trust.persistence;

import ca.northline.shared.CodedEnums;
import ca.northline.trust.application.BrowseReviews.Praise;
import ca.northline.trust.application.BrowseReviews.StarCount;
import ca.northline.trust.application.ReviewStore;
import ca.northline.trust.domain.ReportReason;
import ca.northline.trust.domain.Review;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reviews of businesses over {@code trust.reviews}. Writes touch only the reply and report columns. */
@Repository
@RequiredArgsConstructor
class TrustReviewAdapter implements ReviewStore {

    private static final String OF_MERCHANT = "target_type = 'merchant' and target_id = :m";

    private final JdbcClient jdbc;

    @Override
    public List<StarCount> distribution(String merchantId) {
        return jdbc.sql("""
                        select s.stars, coalesce(c.n, 0) as n,
                               case when total.n = 0 then 0
                                    else round(100.0 * coalesce(c.n, 0) / total.n)::int end as percent
                          from generate_series(5, 1, -1) s(stars)
                          left join (select rating, count(*) as n from trust.reviews where %1$s group by rating) c
                                 on c.rating = s.stars
                         cross join (select count(*) as n from trust.reviews where %1$s) total
                         order by s.stars desc
                        """.formatted(OF_MERCHANT))
                .param("m", merchantId)
                .query((rs, _) -> new StarCount(rs.getInt("stars"), rs.getInt("n"), rs.getInt("percent")))
                .list();
    }

    @Override
    public Map<String, StarTotal> totals(Collection<String> merchantIds) {
        if (merchantIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("""
                        select target_id, sum(rating)::int as stars, count(*)::int as n from trust.reviews
                         where target_type = 'merchant' and target_id = any(:ids)
                         group by target_id
                        """)
                .param("ids", merchantIds.stream().distinct().toArray(String[]::new))
                .query((rs, _) ->
                        Map.entry(rs.getString("target_id"), new StarTotal(rs.getInt("stars"), rs.getInt("n"))))
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public List<Praise> praise(String merchantId, int limit) {
        return jdbc.sql("""
                        select tag, round(100.0 * count(*) / max(total.n))::int as percent
                          from trust.reviews r
                         cross join lateral unnest(r.tags) tag
                         cross join (select count(*) as n from trust.reviews where %1$s) total
                         where r.%1$s
                         group by tag
                         order by count(*) desc, tag
                         limit :limit
                        """.formatted(OF_MERCHANT))
                .param("m", merchantId)
                .param("limit", limit)
                .query((rs, _) -> new Praise(rs.getString("tag"), rs.getInt("percent")))
                .list();
    }

    @Override
    public List<Review> page(String merchantId, int limit, int offset) {
        return jdbc.sql("select * from trust.reviews where " + OF_MERCHANT
                        + " order by created_at desc, id desc limit :limit offset :offset")
                .param("m", merchantId)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, _) -> review(rs))
                .list();
    }

    @Override
    public Optional<Review> find(String merchantId, String reviewId) {
        return jdbc.sql("select * from trust.reviews where " + OF_MERCHANT + " and id = :id")
                .param("m", merchantId)
                .param("id", reviewId)
                .query((rs, _) -> review(rs))
                .optional();
    }

    @Override
    public void saveReply(Review review, String actorId) {
        jdbc.sql("""
                        update trust.reviews set reply = :reply, reply_at = :at, reply_by = :by
                         where id = :id and reply is null
                        """)
                .param("id", review.id())
                .param("reply", review.reply())
                .param("at", ts(review.replyAt()))
                .param("by", actorId)
                .update();
    }

    @Override
    public void saveReport(Review review, @Nullable String note, String actorId) {
        jdbc.sql("""
                        update trust.reviews set reported_at = :at, report_reason = :reason, report_note = :note,
                               reported_by = :by
                         where id = :id and reported_at is null
                        """)
                .param("id", review.id())
                .param("at", ts(review.reportedAt()))
                .param("reason", CodedEnums.toCode(review.reportReason()))
                .param("note", note)
                .param("by", actorId)
                .update();
    }

    private static Review review(ResultSet rs) throws SQLException {
        var tags = rs.getArray("tags");
        return new Review(
                rs.getString("id"),
                rs.getString("target_id"),
                rs.getInt("rating"),
                rs.getString("author_name"),
                rs.getString("job_label"),
                Objects.requireNonNullElse(rs.getString("ref_type"), "booking"),
                rs.getString("text"),
                tags == null
                        ? List.of()
                        : Arrays.stream((Object[]) tags.getArray())
                                .map(String::valueOf)
                                .toList(),
                Objects.requireNonNull(instant(rs, "created_at")),
                rs.getString("reply"),
                instant(rs, "reply_at"),
                instant(rs, "reported_at"),
                CodedEnums.fromCode(rs.getString("report_reason"), ReportReason.class));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
