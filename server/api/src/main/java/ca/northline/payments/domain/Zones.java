package ca.northline.payments.domain;

import java.time.ZoneId;

/** Business time: every date a merchant sees (payout days, report buckets, months) is America/Edmonton. */
public final class Zones {
    public static final ZoneId EDMONTON = ZoneId.of("America/Edmonton");

    private Zones() {}
}
