package ca.northline.studio.web;

import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.ai.api.AiCompletions.StreamSink;
import ca.northline.ai.api.AiCompletions.ToolRun;
import ca.northline.ai.api.AiRateLimited;
import ca.northline.ai.api.AiUnavailable;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import ca.northline.studio.application.StudioAssistant;
import ca.northline.studio.application.StudioAssistant.InsightScreen;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-130: the Studio assistant, {@code /api/v1/merchants/{merchantId}/assistant}:
 *
 * <ul>
 *   <li>{@code POST /chat} — one answer as JSON;
 *   <li>{@code POST /chat/stream} — the same as server-sent events: {@code tool} (a tool ran), {@code delta} (answer
 *       text), then {@code done} (the whole answer) or {@code error} (a ProblemDetail). Nothing is written before the
 *       first event, so a refusal up front (403, 422, 429, 503) is an ordinary response;
 *   <li>{@code GET /insights/{screen}} — a short insight for {@code dashboard}, {@code earnings} or {@code listings};
 *   <li>{@code POST /actions} — runs a write the assistant proposed, once the person confirmed it.
 * </ul>
 *
 * Every tool runs as the caller; the membership and {@code acr=mfa} are checked here and again before each tool run.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/assistant")
@RequiredArgsConstructor
class AssistantController {

    static final String TOO_MANY = "Ask at most 20 messages at a time.";
    static final String EMPTY = "Type a question.";
    static final String TOO_LONG = "Keep a message under 4,000 characters.";

    private final StudioAssistant assistant;
    private final JsonMapper json;

    record TurnBody(
            @NotNull @Pattern(regexp = "user|assistant", message = "Role is user or assistant.")
            String role,

            @NotBlank(message = EMPTY) @Size(max = 4000, message = TOO_LONG)
            String content) {}

    record ChatBody(
            @NotEmpty(message = EMPTY) @Size(max = 20, message = TOO_MANY)
            List<@Valid TurnBody> messages,

            @Nullable @Size(max = 40) String screen) {}

    record ActionBody(
            @NotBlank(message = "Choose an action.") @Size(max = 60)
            String tool,

            @Nullable Map<String, Object> arguments) {}

    private StudioAssistant.Question question(CurrentMember member, ChatBody body, Locale locale) {
        return new StudioAssistant.Question(
                member,
                body.messages().stream()
                        .map(t -> new StudioAssistant.Turn(t.role(), t.content()))
                        .toList(),
                body.screen(),
                locale);
    }

    @PostMapping("/chat")
    @RequiresMerchant(VIEW)
    StudioAssistant.Answer chat(
            @PathVariable String merchantId, @Valid @RequestBody ChatBody body, CurrentMember member, Locale locale) {
        return assistant.ask(question(member, body, locale), null);
    }

    @PostMapping(path = "/chat/stream", produces = "text/event-stream")
    @RequiresMerchant(VIEW)
    void stream(
            @PathVariable String merchantId,
            @Valid @RequestBody ChatBody body,
            CurrentMember member,
            Locale locale,
            HttpServletResponse response) {
        var frames = new Frames(response);
        StudioAssistant.Answer done;
        try {
            done = assistant.ask(question(member, body, locale), new StreamSink() {
                @Override
                public void tool(ToolRun run) {
                    frames.send("tool", run);
                }

                @Override
                public void delta(String text) {
                    frames.send("delta", Map.of("text", text));
                }
            });
        } catch (AiUnavailable | AiRateLimited e) {
            if (!frames.started) {
                throw e;
            }
            var problem = new LinkedHashMap<String, Object>();
            problem.put("status", e instanceof AiRateLimited ? 429 : 503);
            problem.put("code", e instanceof AiRateLimited ? AiRateLimited.CODE : AiUnavailable.CODE);
            problem.put("detail", e.getMessage());
            frames.send("error", problem);
            return;
        } catch (UncheckedIOException e) {
            log.debug("Assistant stream closed by the client: {}", e.getMessage());
            return;
        }
        frames.send("done", done);
    }

    @GetMapping("/insights/{screen}")
    @RequiresMerchant(VIEW)
    StudioAssistant.Insight insight(
            @PathVariable String merchantId, @PathVariable String screen, CurrentMember member, Locale locale) {
        var which = java.util.Arrays.stream(InsightScreen.values())
                .filter(v -> v.code().equals(screen))
                .findFirst()
                .orElseThrow(() -> new NotFound("insight", screen));
        return assistant.insight(member, which, locale);
    }

    @PostMapping("/actions")
    @RequiresMerchant(VIEW)
    StudioAssistant.ActionResult confirm(
            @PathVariable String merchantId, @Valid @RequestBody ActionBody body, CurrentMember member, Locale locale) {
        return assistant.confirm(member, body.tool(), body.arguments() == null ? Map.of() : body.arguments(), locale);
    }

    /** Writes SSE frames straight to the response, flushing each one (the BFF relays text/event-stream unbuffered). */
    private final class Frames {
        private final HttpServletResponse res;
        private @Nullable OutputStream out;
        boolean started;

        Frames(HttpServletResponse res) {
            this.res = res;
        }

        void send(String event, Object data) {
            try {
                if (out == null) {
                    started = true;
                    res.setStatus(HttpStatus.OK.value());
                    res.setContentType("text/event-stream");
                    res.setCharacterEncoding("UTF-8");
                    res.setHeader("Cache-Control", "no-cache, no-transform");
                    res.setHeader("X-Accel-Buffering", "no");
                    out = res.getOutputStream();
                }
                var frame = "event: " + event + "\ndata: " + json.writeValueAsString(data) + "\n\n";
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
