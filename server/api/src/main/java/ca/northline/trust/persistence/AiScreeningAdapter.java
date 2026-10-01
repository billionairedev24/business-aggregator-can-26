package ca.northline.trust.persistence;

import ca.northline.shared.JdbcTimes;
import ca.northline.trust.application.AiScreeningStore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** S-133 screening records, read marks, the reviews screened, the anomaly scan's weekly counts and runs. */
@Repository
@RequiredArgsConstructor
class AiScreeningAdapter implements AiScreeningStore {

    private final JdbcClient jdbc;

    @Override
    public boolean lock(String source) {
        return Boolean.TRUE.equals(jdbc.sql("select pg_try_advisory_xact_lock(hashtext(:key))")
                .param("key", "trust.ai_screening." + source)
                .query(Boolean.class)
                .single());
    }

    @Override
    public Optional<Mark> mark(String source) {
        return jdbc.sql("select after_at, after_id from trust.ai_screening_marks where source = :s")
                .param("s", source)
                .query((rs, n) -> new Mark(JdbcTimes.requiredInstant(rs, "after_at"), rs.getString("after_id")))
                .optional();
    }

    @Override
    public void saveMark(String source, Mark mark) {
        jdbc.sql("""
                        insert into trust.ai_screening_marks (source, after_at, after_id, updated_at)
                        values (:s, :at, :id, now())
                        on conflict (source) do update set after_at = excluded.after_at, after_id = excluded.after_id,
                                                           updated_at = now()
                        """)
                .param("s", source)
                .param("at", mark.at().atOffset(ZoneOffset.UTC))
                .param("id", mark.id())
                .update();
    }

    @Override
    public void record(Screening s) {
        jdbc.sql("""
                        insert into trust.ai_screenings (id, target_type, target_id, merchant_id, flagged, categories,
                                                         explanation, model, prompt)
                        values (:id, :type, :target, :m, :flagged, :categories, :explanation, :model, :prompt)
                        """)
                .param("id", s.id())
                .param("type", s.targetType())
                .param("target", s.targetId())
                .param("m", s.merchantId())
                .param("flagged", s.flagged())
                .param("categories", s.categories().toArray(String[]::new))
                .param("explanation", s.explanation())
                .param("model", s.model())
                .param("prompt", s.prompt())
                .update();
    }

    @Override
    public List<ReviewText> reviewsAfter(Mark after, int limit) {
        return jdbc.sql("""
                        select id, target_id, rating, text, created_at
                          from trust.reviews
                         where target_type = 'merchant'
                           and coalesce(text, '') <> ''
                           and (created_at, id) > (:at, :id)
                         order by created_at, id
                         limit :limit
                        """)
                .param("at", after.at().atOffset(ZoneOffset.UTC))
                .param("id", after.id())
                .param("limit", limit)
                .query((rs, n) -> new ReviewText(
                        rs.getString("id"),
                        rs.getString("target_id"),
                        rs.getInt("rating"),
                        rs.getString("text"),
                        JdbcTimes.requiredInstant(rs, "created_at")))
                .list();
    }

    @Override
    public List<BusinessWeek> week(Instant from, Instant to) {
        return jdbc.sql("""
                        with wk as (
                          select target_id as merchant_id, count(*) as n, avg(rating)::float8 as avg
                            from trust.reviews
                           where target_type = 'merchant' and created_at >= :from and created_at < :to
                           group by target_id),
                        prior as (
                          select target_id as merchant_id, count(*) as n, avg(rating)::float8 as avg
                            from trust.reviews
                           where target_type = 'merchant' and created_at >= :prior and created_at < :from
                           group by target_id),
                        fl as (
                          select merchant_id,
                                 count(*) filter (where rule = 'off_platform_payment') as off_platform,
                                 count(*) filter (where rule = 'ai_screen') as ai_flags
                            from trust.flags
                           where merchant_id is not null and created_at >= :from and created_at < :to
                           group by merchant_id),
                        m as (select merchant_id from wk union select merchant_id from fl)
                        select m.merchant_id, coalesce(wk.n, 0) as reviews, wk.avg, coalesce(prior.n, 0) as prior_reviews,
                               prior.avg as prior_avg, coalesce(fl.off_platform, 0) as off_platform,
                               coalesce(fl.ai_flags, 0) as ai_flags
                          from m
                          left join wk on wk.merchant_id = m.merchant_id
                          left join prior on prior.merchant_id = m.merchant_id
                          left join fl on fl.merchant_id = m.merchant_id
                         order by m.merchant_id
                        """)
                .param("from", from.atOffset(ZoneOffset.UTC))
                .param("to", to.atOffset(ZoneOffset.UTC))
                .param("prior", from.minus(56, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC))
                .query((rs, n) -> new BusinessWeek(
                        rs.getString("merchant_id"),
                        rs.getInt("reviews"),
                        rs.getObject("avg", Double.class),
                        rs.getInt("prior_reviews"),
                        rs.getObject("prior_avg", Double.class),
                        rs.getInt("off_platform"),
                        rs.getInt("ai_flags")))
                .list();
    }

    @Override
    public boolean claimScan(String id, String market, LocalDate weekStart) {
        return jdbc.sql("""
                        insert into trust.anomaly_scans (id, market, week_start, merchants, flags_raised)
                        values (:id, :market, :week, 0, 0)
                        on conflict (market, week_start) do nothing
                        """)
                        .param("id", id)
                        .param("market", market)
                        .param("week", weekStart)
                        .update()
                > 0;
    }

    @Override
    public void completeScan(
            String id,
            int merchants,
            int flagsRaised,
            @Nullable String summary,
            @Nullable String model,
            @Nullable String prompt) {
        jdbc.sql("""
                        update trust.anomaly_scans
                           set merchants = :merchants, flags_raised = :flags, summary = :summary, model = :model,
                               prompt = :prompt, ran_at = now()
                         where id = :id
                        """)
                .param("merchants", merchants)
                .param("flags", flagsRaised)
                .param("summary", summary)
                .param("model", model)
                .param("prompt", prompt)
                .param("id", id)
                .update();
    }
}
