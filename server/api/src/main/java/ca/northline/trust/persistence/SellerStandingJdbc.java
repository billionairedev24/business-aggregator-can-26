package ca.northline.trust.persistence;

import ca.northline.trust.api.SellerStanding;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link SellerStanding} over {@code trust.quality_scores} and {@code trust.flags} (S-82). */
@Repository
@RequiredArgsConstructor
class SellerStandingJdbc implements SellerStanding {

    private final JdbcClient jdbc;

    @Override
    public Map<String, Standing> of(Collection<String> merchantIds) {
        if (merchantIds.isEmpty()) {
            return Map.of();
        }
        var ids = merchantIds.stream().distinct().toArray(String[]::new);
        var quality = new HashMap<String, Integer>();
        jdbc.sql("""
                        select distinct on (q.merchant_id) q.merchant_id, q.score from trust.quality_scores q
                         where q.merchant_id = any(:ids) order by q.merchant_id, q.date desc""")
                .param("ids", ids)
                .query((rs, _) -> quality.put(rs.getString("merchant_id"), rs.getObject("score", Integer.class)))
                .list();
        var flags = new HashMap<String, List<String>>();
        jdbc.sql("""
                        select coalesce(case when f.target_type = 'merchant' then f.target_id end, f.merchant_id) as merchant,
                               f.rule
                          from trust.flags f
                         where coalesce(f.state, 'open') = 'open'
                           and (f.merchant_id = any(:ids) or (f.target_type = 'merchant' and f.target_id = any(:ids)))
                         order by f.created_at, f.id""")
                .param("ids", ids)
                .query((rs, _) -> {
                    var merchant = rs.getString("merchant");
                    var rule = rs.getString("rule");
                    if (merchant != null && rule != null) {
                        var list = flags.computeIfAbsent(merchant, _ -> new ArrayList<>());
                        if (!list.contains(rule)) {
                            list.add(rule);
                        }
                    }
                    return merchant;
                })
                .list();
        var out = new HashMap<String, Standing>();
        for (var id : ids) {
            out.put(id, new Standing(quality.get(id), flags.getOrDefault(id, List.of())));
        }
        return Map.copyOf(out);
    }
}
