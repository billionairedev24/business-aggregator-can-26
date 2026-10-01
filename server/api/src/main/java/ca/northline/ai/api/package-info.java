/**
 * Public API of the AI platform: the {@link ca.northline.ai.api.LlmClient} port, {@link ca.northline.ai.api.AiCompletions}
 * (budgeted, metered completions and the bounded tool loop), {@link ca.northline.ai.api.AssistantTool} (tools other
 * modules contribute), {@link ca.northline.ai.api.Prompts} and the 503/429 errors.
 */
@NamedInterface("api")
@NullMarked
package ca.northline.ai.api;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
