package ca.northline.payments.persistence;

import ca.northline.payments.domain.Escrow;
import ca.northline.payments.domain.Payout;
import ca.northline.payments.domain.PayoutAccount;
import ca.northline.payments.domain.Refund;
import ca.northline.shared.CodedEnums;
import org.mapstruct.Mapper;

/** Row ↔ aggregate for the payments aggregates stored with Spring Data JDBC. Enum columns via {@link CodedEnums}. */
@Mapper(uses = CodedEnums.class)
interface PaymentsRowMapper {

    Escrow toDomain(EscrowRow row);

    EscrowRow toRow(Escrow escrow);

    Payout toDomain(PayoutRow row);

    PayoutRow toRow(Payout payout);

    PayoutAccount toDomain(PayoutAccountRow row);

    PayoutAccountRow toRow(PayoutAccount account);

    Refund toDomain(RefundRow row);

    RefundRow toRow(Refund refund);
}
