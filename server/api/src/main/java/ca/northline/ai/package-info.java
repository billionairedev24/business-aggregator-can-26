/**
 * AI platform (S-129): the {@link ca.northline.ai.api.LlmClient} port and its adapters (OpenRouter, fake), chosen by
 * {@code northline.ai.provider}; per-person and per-merchant budgets in Valkey; metrics, traces and PII redaction on the
 * port; versioned prompt files; the bounded tool loop. It depends on no business module: AI features live in the module
 * that owns their data and use only {@code ai.api} (docs/runbooks/ai.md, DECISIONS.md S-129).
 */
@ApplicationModule(displayName = "ai")
@NullMarked
package ca.northline.ai;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
