package ca.northline.console.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.oncall.export.*} (S-113, docs/runbooks/alerting.md § The on-call rota).
 *
 * @param token the shared token of {@code GET /api/v1/ops/oncall[.ics]} ({@code ONCALL_EXPORT_TOKEN}, a secret); empty
 *     = no export (404)
 * @param back how far back the export starts (a shift that began before still counts)
 * @param ahead how far ahead it reaches (the console plans a week ahead)
 */
@ConfigurationProperties("northline.oncall.export")
public record OncallExportProperties(
        @DefaultValue("") String token,
        @DefaultValue("12h") Duration back,
        @DefaultValue("8d") Duration ahead) {}
