package ca.northline.studio.application;

import ca.northline.ai.api.AiCompletions;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.security.CurrentMember;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * S-130: the Studio assistant. The prompt carries only who the caller is (role, business, what the role may do); every
 * fact comes from tools that run as the caller ({@code AssistantTool} beans of the modules). Writes are proposed, never
 * run, until the person confirms them ({@link #confirm}).
 */
public interface StudioAssistant {

    /** {@code role}: {@code user} or {@code assistant}. */
    record Turn(String role, String content) {}

    /** @param screen the Studio screen the person has open ({@code orders}), or null */
    record Question(CurrentMember member, List<Turn> turns, @Nullable String screen, Locale locale) {}

    /**
     * @param usage tokens, cost and latency — shown to owners only
     * @param pending a write the person must confirm, or null
     * @param screen the Studio screen the answer's data comes from, for an "Open" link
     */
    record Answer(
            String content,
            List<AiCompletions.ToolRun> toolRuns,
            AiCompletions.@Nullable PendingAction pending,
            @Nullable String screen,
            String model,
            AiCompletions.@Nullable Usage usage) {}

    Answer ask(Question question, AiCompletions.@Nullable StreamSink sink);

    /** The screens with an insight. */
    enum InsightScreen implements CodedEnum {
        DASHBOARD,
        EARNINGS,
        LISTINGS
    }

    /** A short insight, always marked AI-generated in the Studio. */
    record Insight(String title, String body, List<String> bullets, String model) {}

    Insight insight(CurrentMember member, InsightScreen screen, Locale locale);

    /** The outcome of a confirmed write. */
    record ActionResult(String tool, String summary, @Nullable String screen) {}

    /** Runs a write the assistant proposed, now that the person confirmed it; audited. */
    ActionResult confirm(CurrentMember member, String tool, Map<String, Object> arguments, Locale locale);
}
