package ca.northline.studio.application;

import ca.northline.ai.api.AiCompletions;
import ca.northline.ai.api.AiCompletions.Caller;
import ca.northline.ai.api.AiCompletions.Message;
import ca.northline.ai.api.AiCompletions.Request;
import ca.northline.ai.api.AiCompletions.StreamSink;
import ca.northline.ai.api.AiCompletions.ToolContext;
import ca.northline.ai.api.AiFeature;
import ca.northline.ai.api.AssistantTool;
import ca.northline.ai.api.Prompts;
import ca.northline.developer.api.AuditTrail;
import ca.northline.merchants.api.BusinessNames;
import ca.northline.merchants.api.MerchantDirectory;
import ca.northline.region.api.Markets;
import ca.northline.shared.NotFound;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.MerchantAccess;
import ca.northline.shared.security.MerchantAccessDenied;
import ca.northline.shared.security.MerchantPermission;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** {@link StudioAssistant} over {@link AiCompletions} and every module's {@link AssistantTool}. */
@Service
@RequiredArgsConstructor
class StudioAssistantService implements StudioAssistant {

    static final int MAX_TURNS = 20;

    private final AiCompletions ai;
    private final Prompts prompts;
    private final List<AssistantTool> tools;
    private final MerchantAccess access;
    private final MerchantDirectory directory;
    private final BusinessNames names;
    private final Markets markets;
    private final AuditTrail audit;
    private final JsonMapper json;
    private final Clock clock;

    /** Who is asking, in the words the prompts use. */
    private record Who(String business, String type, ZoneId zone, LocalDate today) {}

    private Who who(String merchantId) {
        var profile = directory.profile(merchantId).orElseThrow(() -> new NotFound("merchant", merchantId));
        var zone = markets.zone(profile.province());
        return new Who(
                names.displayName(merchantId).orElse("this business"),
                profile.type(),
                zone,
                LocalDate.now(clock.withZone(zone)));
    }

    private static String language(Locale locale) {
        return locale.getLanguage().equals("fr") ? "Canadian French" : "English";
    }

    private static String permissions(CurrentMember member) {
        return Arrays.stream(MerchantPermission.values())
                .filter(member::can)
                .map(p -> switch (p) {
                    case VIEW -> "see the business's work, listings, messages and reviews";
                    case OPERATE -> "do day-to-day work (pack orders, start jobs, reply)";
                    case EDIT -> "edit listings, availability and quotes";
                    case DELETE -> "delete records";
                    case FINANCE_READ -> "see earnings, payouts and reports";
                    case MANAGE -> "manage the business, team and payout settings";
                })
                .collect(Collectors.joining("; "));
    }

    private Map<String, Object> vars(CurrentMember member, Who who, Locale locale) {
        var vars = new LinkedHashMap<String, Object>();
        vars.put("business", who.business());
        vars.put("businessType", who.type());
        vars.put("role", member.role().code());
        vars.put("permissions", permissions(member));
        vars.put(
                "today",
                who.today().getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + " "
                        + who.today().format(DateTimeFormatter.ISO_LOCAL_DATE));
        vars.put("zone", who.zone().getId());
        vars.put("language", language(locale));
        return vars;
    }

    @Override
    public Answer ask(Question q, @Nullable StreamSink sink) {
        var member = q.member();
        var who = who(member.merchantId());
        var prompt = prompts.get("studio-assistant");
        var vars = vars(member, who, q.locale());
        vars.put("screen", q.screen() == null ? "" : "The person has the '" + q.screen() + "' screen open.");
        var messages = new ArrayList<Message>();
        messages.add(Message.system(prompt.render(vars)));
        q.turns().stream()
                .skip(Math.max(0, q.turns().size() - MAX_TURNS))
                .forEach(t ->
                        messages.add(new Message(t.role().equals("assistant") ? "assistant" : "user", t.content())));
        var request = new Request(
                AiFeature.ASSISTANT,
                Caller.member(member.userId(), member.merchantId()),
                prompt.id(),
                messages,
                false,
                900);
        var answer = ai.converse(
                request,
                tools,
                new ToolContext(member.merchantId(), member.userId(), member.role(), q.locale(), who.zone()),
                sink);
        return new Answer(
                answer.text(),
                answer.toolRuns(),
                answer.pending(),
                answer.screen(),
                answer.model(),
                member.can(MerchantPermission.MANAGE) ? answer.usage() : null);
    }

    /** The tools whose data each insight reads; a role sees what its permissions allow. */
    static List<String> insightTools(InsightScreen screen, String merchantType) {
        return switch (screen) {
            case DASHBOARD -> {
                var names = new ArrayList<String>();
                if (!merchantType.equals("seller")) {
                    names.add("list_jobs");
                }
                if (!merchantType.equals("provider")) {
                    names.add("list_orders");
                }
                names.add("list_threads");
                names.add("reviews_summary");
                yield names;
            }
            case EARNINGS -> List.of("earnings_overview", "payouts_overview");
            case LISTINGS -> List.of("list_listings");
        };
    }

    @Override
    public Insight insight(CurrentMember member, InsightScreen screen, Locale locale) {
        var who = who(member.merchantId());
        var byName = new LinkedHashMap<String, AssistantTool>();
        tools.forEach(t -> byName.putIfAbsent(t.name(), t));
        var data = new LinkedHashMap<String, Object>();
        for (var name : insightTools(screen, who.type())) {
            var tool = byName.get(name);
            if (tool == null || !member.can(tool.permission())) {
                continue;
            }
            access.require(member.merchantId(), tool.permission());
            var args = json.createObjectNode();
            if (name.equals("list_jobs")) {
                args.put("days", 2);
            }
            data.put(
                    name,
                    tool.run(new AssistantTool.Call(
                                    member.merchantId(), member.userId(), member.role(), args, locale, who.zone()))
                            .data());
        }
        if (data.isEmpty()) {
            throw new MerchantAccessDenied(
                    MerchantAccessDenied.Reason.INSUFFICIENT_ROLE,
                    "Your role (%s) can't see this screen's data."
                            .formatted(member.role().code()));
        }
        var prompt = prompts.get("studio-insight");
        var vars = vars(member, who, locale);
        vars.put("screen", screen.name().toLowerCase(Locale.ROOT));
        var answer = ai.complete(Request.of(
                        AiFeature.INSIGHT,
                        Caller.member(member.userId(), member.merchantId()),
                        prompt,
                        prompt.render(vars),
                        "Data: " + json.writeValueAsString(data))
                .asJson()
                .withMaxTokens(400));
        var parsed = answer.json();
        if (parsed.isEmpty()) {
            return new Insight(
                    "",
                    answer.text().length() > 400 ? answer.text().substring(0, 400) : answer.text(),
                    List.of(),
                    answer.model());
        }
        var node = parsed.get();
        var bullets = new ArrayList<String>();
        node.path("bullets").forEach(b -> {
            if (b.isString() && bullets.size() < 3) {
                bullets.add(b.asString());
            }
        });
        return new Insight(node.path("title").asString(""), node.path("body").asString(""), bullets, answer.model());
    }

    @Override
    @Transactional
    public ActionResult confirm(CurrentMember member, String toolName, Map<String, Object> arguments, Locale locale) {
        var tool = tools.stream()
                .filter(t -> t.name().equals(toolName) && t.write())
                .findFirst()
                .orElseThrow(() -> new NotFound("assistant action", toolName));
        access.require(member.merchantId(), tool.permission());
        var who = who(member.merchantId());
        var call = new AssistantTool.Call(
                member.merchantId(), member.userId(), member.role(), json.valueToTree(arguments), locale, who.zone());
        var result = tool.run(call);
        audit.record(AuditTrail.Entry.of(
                        member.merchantId(),
                        member.userId(),
                        member.role().code(),
                        "assistant.action_confirmed",
                        "assistant_action",
                        toolName)
                .withChange(null, Map.of("tool", toolName, "summary", result.summary())));
        return new ActionResult(toolName, result.summary(), tool.screen());
    }
}
