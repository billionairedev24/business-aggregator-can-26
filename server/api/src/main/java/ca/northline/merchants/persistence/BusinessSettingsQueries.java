package ca.northline.merchants.persistence;

import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.merchants.application.BusinessSettingsStore;
import ca.northline.merchants.domain.BusinessSettings;
import ca.northline.merchants.domain.BusinessSettings.CancellationPolicy;
import ca.northline.merchants.domain.BusinessStructure;
import ca.northline.merchants.domain.DisplayName;
import ca.northline.merchants.domain.GstNumber;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.CodedEnums;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link BusinessSettingsStore}: Settings › Business columns of {@code merchants.merchants}. */
@Repository
@RequiredArgsConstructor
class BusinessSettingsQueries implements BusinessSettingsStore {

    private final JdbcClient jdbc;

    @Override
    public Optional<BusinessSettings> find(String merchantId) {
        return jdbc.sql("""
                        select m.id, m.type, m.structure, m.display_name, m.legal_name, m.gst_number,
                               m.profile ->> 'serviceArea' as service_area, m.cancellation_policy,
                               m.auto_accept_quote_cents, m.languages, s.slug
                          from merchants.merchants m
                          left join merchants.storefronts s on s.merchant_id = m.id
                         where m.id = :id
                        """)
                .param("id", merchantId)
                .query((rs, _) -> {
                    var gst = rs.getString("gst_number");
                    var policy = rs.getString("cancellation_policy");
                    return new BusinessSettings(
                            rs.getString("id"),
                            CodedEnum.fromCode(MerchantType.class, rs.getString("type")),
                            CodedEnums.fromCode(rs.getString("structure"), BusinessStructure.class),
                            new DisplayName(rs.getString("display_name")),
                            rs.getString("legal_name"),
                            gst != null && GstNumber.isValid(gst) ? new GstNumber(gst) : null,
                            rs.getString("service_area"),
                            policy == null ? CancellationPolicy.H12 : CancellationPolicy.parse(policy),
                            rs.getObject("auto_accept_quote_cents") == null
                                    ? null
                                    : rs.getLong("auto_accept_quote_cents"),
                            strings(rs.getArray("languages")),
                            rs.getString("slug"));
                })
                .optional();
    }

    @Override
    public void save(BusinessSettings s, Instant at) {
        var gst = s.gstNumber();
        jdbc.sql("""
                        update merchants.merchants
                           set legal_name = :legal, gst_number = :gst,
                               profile = case when cast(:area as text) is null then profile - 'serviceArea'
                                              else jsonb_set(profile, '{serviceArea}', to_jsonb(cast(:area as text))) end,
                               cancellation_policy = :policy, auto_accept_quote_cents = :auto,
                               languages = cast(:languages as text[]), updated_at = :at
                         where id = :id
                        """)
                .param("id", s.merchantId())
                .param("legal", s.legalName())
                .param("gst", gst == null ? null : gst.value())
                .param("area", s.serviceArea())
                .param("policy", s.cancellationPolicy().code())
                .param("auto", s.autoAcceptQuoteCents())
                .param("languages", s.languages().stream().collect(Collectors.joining(",", "{", "}")))
                .param("at", ts(at))
                .update();
    }

    @Override
    public void reopenCheck(String merchantId, String checkKey, Instant at) {
        jdbc.sql("""
                        update merchants.verifications set status = 'submitted', submitted_at = :at, updated_at = :at
                         where merchant_id = :m and check_key = :key and status = 'verified'
                        """)
                .param("m", merchantId)
                .param("key", checkKey)
                .param("at", ts(at))
                .update();
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        return array == null
                ? List.of()
                : Arrays.stream((Object[]) array.getArray())
                        .map(String::valueOf)
                        .toList();
    }
}
