package ca.northline.studio.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.live.*} (S-68): the Studio's live stream.
 *
 * @param bus where signals travel between api replicas: {@code redis} (Valkey pub/sub; the cloud default, required in
 *     staging/prod) or {@code memory} (one instance: local, test) — {@code LIVE_BUS}
 * @param stream how long one stream stays open before the browser reconnects (the reconnect goes through the BFF again,
 *     so membership and the access token are checked again) — {@code LIVE_STREAM}
 * @param heartbeat the keep-alive comment interval that stops proxies from closing an idle stream
 */
@ConfigurationProperties("northline.live")
public record LiveProperties(
        @DefaultValue("memory") Bus bus,
        @DefaultValue("10m") Duration stream,
        @DefaultValue("25s") Duration heartbeat) {

    public enum Bus {
        MEMORY,
        REDIS
    }
}
