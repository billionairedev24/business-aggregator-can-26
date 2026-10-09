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
    /** What counts towards a rating and shows publicly: everything trust &amp; safety hasn't hidden. */
    private static final String VISIBLE = OF_MERCHANT + " and hidden_at is null";

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
                        """.formatted(VISIBLE))
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
                         where target_type = 'merchant' and target_id = any(:ids) and hidden_at is null
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
                        """.formatted(VISIBLE))
                .param("m", merchantId)
                .param("limit", limit)
                .query((rs, _) -> new Praise(rs.getString("tag"), rs.getInt("percent")))
                .list();
    }

    @Override
    public List<Review> page(String merchantId, int limit, int offset, boolean withHidden) {
        return jdbc.sql("select * from trust.reviews where " + (withHidden ? OF_MERCHANT : VISIBLE)
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

    @Override
    public boolean insert(Authored r, String authorName, @Nullable String jobLabel) {
        return jdbc.sql("""
                        insert into trust.reviews (id, ref_type, ref_id, author_id, author_name, target_type, target_id,
                               rating, tags, text, lang, job_label, created_at, edit_until, screened)
                        values (:id, :refType, :refId, :author, :name, 'merchant', :merchant, :rating, :tags, :text,
                                :lang, :job, :at, :until, :screened)
                        on conflict (ref_id, author_id, target_type, target_id) do nothing
                        """)
                        .param("id", r.id())
                        .param("refType", r.refType())
                        .param("refId", r.refId())
                        .param("author", r.authorId())
                        .param("name", authorName)
                        .param("merchant", r.merchantId())
                        .param("rating", r.rating())
                        .param("tags", r.tags().toArray(String[]::new))
                        .param("text", r.text())
                        .param("lang", r.lang())
                        .param("job", jobLabel)
                        .param("at", ts(r.createdAt()))
                        .param("until", ts(r.editUntil()))
                        .param("screened", r.screened())
                        .update()
                == 1;
    }

    @Override
    public Optional<Authored> authored(String reviewId) {
        return jdbc.sql("select * from trust.reviews where id = :id and target_type = 'merchant'")
                .param("id", reviewId)
                .query((rs, _) -> authored(rs))
                .optional();
    }

    @Override
    public List<Authored> byAuthor(String authorId, Collection<String> refIds) {
        if (refIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select * from trust.reviews
                         where author_id = :a and target_type = 'merchant' and ref_id = any(:refs)
                         order by created_at""")
                .param("a", authorId)
                .param("refs", refIds.stream().distinct().toArray(String[]::new))
                .query((rs, _) -> authored(rs))
                .list();
    }

    @Override
    public boolean edit(
            String reviewId, int rating, List<String> tags, @Nullable String text, boolean screened, Instant at) {
        return jdbc.sql("""
                        update trust.reviews set rating = :rating, tags = :tags, text = :text, edited_at = :at,
                               screened = screened or :screened
                         where id = :id and reply is null and edit_until > :at
                        """)
                        .param("id", reviewId)
                        .param("rating", rating)
                        .param("tags", tags.toArray(String[]::new))
                        .param("text", text)
                        .param("screened", screened)
                        .param("at", ts(at))
                        .update()
                == 1;
    }

    @Override
    public boolean moderate(String reviewId, @Nullable String reason, String staffId, Instant at) {
        var hide = reason != null;
        return jdbc.sql("""
                        update trust.reviews
                           set hidden_at = :hiddenAt, hidden_by = :by, hidden_reason = :reason, moderated_at = :at
                         where id = :id and (hidden_at is null) = :hide
                        """)
                        .param("id", reviewId)
                        .param("hiddenAt", hide ? ts(at) : null)
                        .param("by", hide ? staffId : null)
                        .param("reason", reason)
                        .param("at", ts(at))
                        .param("hide", hide)
                        .update()
                == 1;
    }

    private static Authored authored(ResultSet rs) throws SQLException {
        return new Authored(
                rs.getString("id"),
                rs.getString("author_id"),
                rs.getString("target_id"),
                rs.getString("ref_type"),
                rs.getString("ref_id"),
                rs.getInt("rating"),
                tags(rs),
                rs.getString("text"),
                Objects.requireNonNullElse(rs.getString("lang"), "en"),
                rs.getBoolean("screened"),
                Objects.requireNonNull(instant(rs, "created_at")),
                instant(rs, "edit_until"),
                instant(rs, "edited_at"),
                rs.getString("reply"),
                instant(rs, "hidden_at"));
    }

    private static List<String> tags(ResultSet rs) throws SQLException {
        var tags = rs.getArray("tags");
        return tags == null
                ? List.of()
                : Arrays.stream((Object[]) tags.getArray()).map(String::valueOf).toList();
    }

    private static Review review(ResultSet rs) throws SQLException {
        return new Review(
                rs.getString("id"),
                rs.getString("target_id"),
                rs.getInt("rating"),
                rs.getString("author_name"),
                rs.getString("job_label"),
                Objects.requireNonNullElse(rs.getString("ref_type"), "booking"),
                rs.getString("text"),
                tags(rs),
                Objects.requireNonNull(instant(rs, "created_at")),
                rs.getString("reply"),
                instant(rs, "reply_at"),
                instant(rs, "reported_at"),
                CodedEnums.fromCode(rs.getString("report_reason"), ReportReason.class),
                instant(rs, "edited_at"),
                instant(rs, "hidden_at"));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
